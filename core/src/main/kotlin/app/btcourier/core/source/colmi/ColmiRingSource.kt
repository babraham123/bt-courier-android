package app.btcourier.core.source.colmi

import app.btcourier.core.lines.StoredLine
import app.btcourier.core.source.ClockEvent
import app.btcourier.core.source.Page
import app.btcourier.core.source.Source
import app.btcourier.core.transport.GattLink
import app.btcourier.core.transport.readDeviceRevisions
import kotlinx.coroutines.delay
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.IOException
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.format.ResolverStyle
import java.time.temporal.ChronoUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds

/**
 * [Source] over a Colmi R02-family smart ring's per-day logs, on a [GattLink] bound to
 * [ColmiGatt.RX] (writes) and [ColmiGatt.TX] (notifications). Protocol per [ColmiPacketCodec].
 *
 * A day's data: its heart-rate log (`0x15`), steps log (`0x43`) and HRV log (`0x39`) as
 * `colmi.hr` (bpm per 5-minute slot), `colmi.steps` (steps per quarter hour) and `colmi.hrv` (ms
 * per 30-minute slot), the slot-indexed samples, plus the big-data rows below, all in time order
 * after a `{"_file":"colmi/<day>"}` marker, so the store keeps each day as one file. Today (the
 * phone's date in [zone]) is pulled as it fills, a slice of closed slots per page.
 *
 * Cursor, days and times in [zone]:
 * - `YYYY-MM-DD`: that day is complete.
 * - `YYYY-MM-DD@HH:MM`: that day is pulled up to the cut `HH:MM`: every slot-indexed sample whose
 *   slot ended by the cut was emitted, and none other of that day.
 *
 * [pull] from a cursor:
 * - None, or a whole day before yesterday: the next whole day, `more:true` (another whole day or
 *   today's slice follows).
 * - Yesterday whole, or today with a cut: today's slice. The new cut is the phone's time floored
 *   to the heart-rate slot, so the slot in progress waits for the next pull; the page holds the
 *   samples whose slots ended after the previous cut (midnight for a whole-day cursor) and by the
 *   new one, its cursor is `<today>@<new cut>` and `more:false`. With no slot closed since the
 *   previous cut it is an empty page with the same cursor and `more:false`.
 *   No big-data rows: those come once the day is complete.
 * - An earlier day with a cut: that day's remaining samples (slots ending after the cut) and its
 *   big-data rows; the cursor becomes the whole day and `more:true`.
 *
 * A sample is emitted once its slot has ended (a quarter hour of steps in progress at the cut
 * waits for the next pull, though the cut is finer), so a slice never stores a slot that later
 * readings would change. The marker opens only a day's first page (one starting at midnight);
 * later pages of the day carry none, so they continue that day's file in the store instead of each
 * starting a batch (a batch the store cuts by age restates the marker). [nextDueAtMs] says, from the cursor alone, when a caller should next connect:
 * [intradayInterval] after the cut, a whole day's cut being the next midnight.
 *
 * The ring is assumed to keep today and the 7 days before it ([historyDays]). A null cursor
 * starts at the oldest day it holds (`today - (historyDays - 1)`), and an older cursor, whole or
 * cut, is clamped to that day: anything in between, a cut day's rest included, was overwritten on
 * the ring and is gone.
 *
 * HRV: the log reaches [ColmiPacketCodec.HRV_MAX_DAYS_AGO] days back, so the oldest day gets no
 * HRV request. Older firmware never answers `0x39` (Gadgetbridge ends its sync before the HRV
 * answer for that reason), and under the rule that a timeout retries the day such a ring would
 * hold the cursor forever. So each instance probes once: when its first `0x39` request meets
 * silence (no packet of the answer before the timeout), HRV counts as unsupported for the rest of
 * the instance, that day and later ones go without `colmi.hrv` rows, and nothing re-enables it.
 * The app opens one instance per connection, so the next sync probes again. Once the ring has
 * answered, a later silent `0x39`, like an answer cut short, is an ordinary timeout that retries
 * the day. A supporting ring whose first answer is lost entirely on air is mistaken for an old
 * one, and that sync stores its days without HRV, which is never fetched again.
 *
 * Clock: the first [pull] of an instance sets the ring to the phone's wall clock in [zone]
 * (`0x01`), as the vendor app and Gadgetbridge do, so the ring's days are the phone's days. It
 * first waits [settleDelay] after connecting, as Gadgetbridge does before its first command, then
 * waits for the ring's `0x01` echo: a lost set-time would silently shift every day offset, so a
 * missing echo fails the pull like any other timeout and the next pull sets the clock again.
 * There is no clock journal ([clockJournal] is empty) and no [deviceStatus]: rows carry the
 * ring's own stamps, which the normaliser rates `device`. A ring whose clock was lost (flat
 * battery) before this sync logged under a wrong clock that cannot be repaired afterwards.
 *
 * The first page of an instance also carries one `colmi.battery` row (percent), one
 * `colmi.hr_logging` row (the ring's own heart-rate logging setting, `0x16`, as
 * `{"enabled":<bool>,"interval_min":<minutes>}`) and one `colmi.device` row (the Device
 * Information Service's revision strings, as `{"firmware":<string|null>,"hardware":<string|null>}`,
 * null for one the ring does not expose), all stamped with the phone clock; a sync that is already
 * caught up stores no page and so none of these readings. The setting is reported rather than
 * used: each heart-rate log's header carries the interval it was recorded at, which places its
 * readings even when the setting changed since (there is no logger in core to flag a mismatch).
 *
 * Big-data channel ([ColmiBigData], the link's secondary channel): after the UART logs, the first
 * page of an instance that completes a day asks for the ring's SpO2, sleep and temperature history.
 * Each answer holds every day the ring keeps, so each type is asked for once per instance and
 * every page completing a day takes that day's part, by days before the phone's today as for steps:
 * - `colmi.spo2`: one row per hour with a reading, `ts` the hour's start,
 *   `"value":{"min":<percent>,"max":<percent>}`.
 * - `colmi.sleep`: one row per session that ended on the day, `ts` its start,
 *   `"value":{"start_ts":<ms>,"end_ts":<ms>,"stages":[{"stage":<name>,"minutes":<n>},...],
 *   "minutes":{"light":<n>,"deep":<n>,"rem":<n>,"awake":<n>}}`. Stages are in ring order, named
 *   `light`, `deep`, `rem`, `awake`, or `stage_<code>` for an unnamed code; `minutes` totals each
 *   stage, every named one included. A session that began the evening before has `ts` on the
 *   previous day.
 * - `colmi.temp`: one row per half hour with a reading, `ts` the slot's start,
 *   `"value":{"c":<degrees Celsius, one decimal>}`.
 *
 * A type the ring does not answer in time (a model without it) yields no rows for the rest of the
 * instance, and a link without the big-data channel yields none at all; neither fails the day,
 * while a dropped link does.
 *
 * Unverified on hardware (all from the public reverse engineering; to be checked against a ring):
 * - Ring timestamps are its wall clock counted as if UTC. `colmi_r02_client` sets the ring to UTC
 *   and requests UTC midnights, which is this rule with `zone = UTC`; with a local zone the ring's
 *   day boundaries follow local midnight. Readings are placed from the requested day's start,
 *   not the ring's echoed timestamp, as Gadgetbridge does.
 * - The steps request addresses days by offset from the ring's today, assumed equal to the phone's
 *   today right after set-time; each steps row is placed by the date the ring stamps on it.
 * - The HRV request addresses days the same way and its answer ends at sub-index 4 (see
 *   [HrvLogAssembler]); HRV rows are placed from the requested day's start.
 * - The `0x16` read answer says on with byte 2 = 1 (Gadgetbridge); what an off ring sends there
 *   is unknown, so anything else reads as off.
 * - Whether the ring has the Device Information Service and lets an unbonded link read its
 *   revision strings; without them the `colmi.device` values are null.
 * - Writes go with response when RX declares it, else without, as PulseLoopiOS picks
 *   (`colmi_r02_client` always writes without).
 * - Answers are not acknowledged, and the ring may send packets of other commands at any time;
 *   those are skipped, as are notifications that are not whole 16-byte packets or fail the checksum
 *   (a corrupted packet of the answer then surfaces as a timeout).
 * - [historyDays] = 8, as Gadgetbridge and PulseLoopiOS both ask for day offsets 0..7.
 * - Today's logs (heart rate by its day start, steps and HRV at offset 0) answer with the slots
 *   so far and zeros, or nothing, for those not yet reached, as a past day's do for slots without a
 *   reading; and a slot's reading is final once the slot has ended.
 * - Everything on the big-data channel (from PulseLoopiOS and Gadgetbridge): its UUIDs, which
 *   models have it, the request payloads, the write type, answers' `daysAgo` counting
 *   from the phone's today after set-time, sleep start/end as minutes of the day it ended, and
 *   the temperature scale.
 *
 * Not thread-safe: one pull at a time, as `SyncRunner` does.
 *
 * @param responseTimeout longest wait for each packet of an answer; exceeding it is an
 *   [IOException], like any other link failure, and the day is pulled again next sync. On the
 *   big-data channel it bounds each notification of an answer, and exceeding it marks the type
 *   unsupported instead.
 * @param settleDelay wait between the instance's first [pull] (right after connecting) and its
 *   first command.
 * @param intradayInterval how long after a cursor's cut the next pull is due ([nextDueAtMs]).
 */
class ColmiRingSource(
    private val link: GattLink,
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val phoneClock: () -> Long = System::currentTimeMillis,
    private val historyDays: Int = DEFAULT_HISTORY_DAYS,
    private val responseTimeout: Duration = 5.seconds,
    private val settleDelay: Duration = DEFAULT_SETTLE_DELAY,
    private val intradayInterval: Duration = DEFAULT_INTRADAY_INTERVAL,
) : Source {
    init {
        require(historyDays >= 2) { "the ring must hold at least one whole day before today, got $historyDays" }
    }

    private var settled = false
    private var clockSet = false
    private var deviceRowsReported = false
    private val bigData = ColmiBigDataLogs(link, responseTimeout)

    // Unknown until the first 0x39 request of this instance.
    private var hrvSupported: Boolean? = null

    override suspend fun pull(cursor: String?): Page {
        val last = cursor?.let { parseCursor(it) ?: throw IllegalArgumentException("not a Colmi cursor: $it") }
        setClockOnce()
        val now = LocalDateTime.ofInstant(Instant.ofEpochMilli(phoneClock()), zone)
        val today = now.toLocalDate()
        val oldest = today.minusDays(historyDays - 1L)
        // A cut day still on the ring is resumed; one older than the ring's history is gone.
        if (cursor != null && last?.cut != null && !last.day.isBefore(oldest)) {
            return when {
                last.day.isBefore(today) -> dayPage(last.day, last.cut, today)
                last.day == today -> todayPage(cursor, last.cut, now)
                else -> Page(emptyList(), cursor, more = false)
            }
        }
        // A null cursor always has a whole day to pull, since oldest < today.
        val day = maxOf(last?.day?.plusDays(1) ?: oldest, oldest)
        return when {
            day.isBefore(today) -> dayPage(day, LocalTime.MIDNIGHT, today)
            day == today -> todayPage(checkNotNull(cursor), LocalTime.MIDNIGHT, now)
            else -> Page(emptyList(), checkNotNull(cursor), more = false)
        }
    }

    /** The rest of [day], a day before [today], from [from] on, with its big-data rows. */
    private suspend fun dayPage(
        day: LocalDate,
        from: LocalTime,
        today: LocalDate,
    ): Page {
        val window = SlotWindow(day, from, null)
        val lines = pageLines(day, from, uartRows(day, today, window) + bigDataRows(day, today))
        return Page(lines, day.toString(), more = true)
    }

    /** Today's slots that ended after [from] and by [now] floored to a heart-rate slot. */
    private suspend fun todayPage(
        cursor: String,
        from: LocalTime,
        now: LocalDateTime,
    ): Page {
        val slotMinutes = HeartRateLog.DEFAULT_INTERVAL_MINUTES
        val cut = LocalTime.of(now.hour, now.minute / slotMinutes * slotMinutes)
        if (!cut.isAfter(from)) return Page(emptyList(), cursor, more = false)
        val today = now.toLocalDate()
        val lines = pageLines(today, from, uartRows(today, today, SlotWindow(today, from, cut)))
        return Page(lines, ColmiCursor(today, cut).toString(), more = false)
    }

    /**
     * [rows] in time order, after [day]'s marker when the page starts the day at midnight, then the
     * per-connection rows on the instance's first page.
     */
    private suspend fun pageLines(
        day: LocalDate,
        from: LocalTime,
        rows: List<Row>,
    ): List<String> =
        buildList {
            if (from == LocalTime.MIDNIGHT) add(StoredLine.marker("$FILE_PREFIX$day"))
            rows.sortedBy { it.ts }.forEach { add(it.json()) }
            if (!deviceRowsReported) {
                val battery =
                    link.colmiRequest(ColmiPacketCodec.batteryRequest(), responseTimeout) {
                        ColmiPacketCodec.parseBattery(it)
                    }
                add(Row(phoneClock(), PARAM_BATTERY, battery.level).json())
                add(hrLoggingRow().json())
                add(deviceRow().json())
                deviceRowsReported = true
            }
        }

    private suspend fun uartRows(
        day: LocalDate,
        today: LocalDate,
        window: SlotWindow,
    ): List<Row> = heartRateRows(day, window) + stepsRows(day, today, window) + hrvRows(day, today, window)

    /** [nextDueAtMs] in this instance's [zone] with its [intradayInterval]. */
    fun nextDueAtMs(cursor: String?): Long? = nextDueAtMs(cursor, zone, intradayInterval)

    override suspend fun clockJournal(): List<ClockEvent> = emptyList()

    private suspend fun hrLoggingRow(): Row {
        val setting = link.readColmiAutoHeartRate(responseTimeout)
        val value = """{"enabled":${setting.enabled},"interval_min":${setting.intervalMinutes}}"""
        return Row(phoneClock(), PARAM_HR_LOGGING, value)
    }

    private suspend fun deviceRow(): Row {
        val revisions = link.readDeviceRevisions()
        val value =
            buildJsonObject {
                put("firmware", revisions.firmware)
                put("hardware", revisions.hardware)
            }
        return Row(phoneClock(), PARAM_DEVICE, value.toString())
    }

    private suspend fun setClockOnce() {
        if (clockSet) return
        if (!settled) {
            delay(settleDelay)
            settled = true
        }
        val wall = LocalDateTime.ofInstant(Instant.ofEpochMilli(phoneClock()), zone)
        link.colmiRequest(ColmiPacketCodec.setTime(wall), responseTimeout) { }
        clockSet = true
    }

    private suspend fun heartRateRows(
        day: LocalDate,
        window: SlotWindow,
    ): List<Row> {
        val start = day.atStartOfDay()
        val log =
            link.colmiRequest(
                ColmiPacketCodec.heartRateLogRequest(ringEpochSeconds(start)),
                responseTimeout,
                HeartRateLogAssembler(),
            )
        return slotRows(start, log.intervalMinutes, log.values, PARAM_HR, window)
    }

    /** See the class KDoc for when the ring is not asked and how a silent ring is told apart. */
    private suspend fun hrvRows(
        day: LocalDate,
        today: LocalDate,
        window: SlotWindow,
    ): List<Row> {
        val daysAgo = ChronoUnit.DAYS.between(day, today).toInt()
        if (hrvSupported == false || daysAgo > ColmiPacketCodec.HRV_MAX_DAYS_AGO) return emptyList()
        val assembler = HrvLogAssembler()
        val values =
            try {
                link.colmiRequest(ColmiPacketCodec.hrvLogRequest(daysAgo), responseTimeout, assembler)
            } catch (e: ColmiTimeoutException) {
                if (hrvSupported == true || assembler.started) throw e
                hrvSupported = false
                return emptyList()
            }
        hrvSupported = true
        return slotRows(day.atStartOfDay(), HrvLogAssembler.SLOT_MINUTES, values, PARAM_HRV, window)
    }

    /**
     * One row per non-zero reading in [window], the reading at index i placed i * [minutes] after
     * [start].
     */
    private fun slotRows(
        start: LocalDateTime,
        minutes: Int,
        values: List<Int>,
        param: String,
        window: SlotWindow,
    ): List<Row> =
        values.mapIndexedNotNull { slot, value ->
            val slotStart = start.plusMinutes(slot.toLong() * minutes)
            if (value == 0 || !window.holds(slotStart, minutes)) null else Row(epochMs(slotStart), param, value)
        }

    private suspend fun stepsRows(
        day: LocalDate,
        today: LocalDate,
        window: SlotWindow,
    ): List<Row> {
        val offset = ChronoUnit.DAYS.between(day, today).toInt()
        val quarters = link.colmiRequest(ColmiPacketCodec.stepsLogRequest(offset), responseTimeout, StepsLogAssembler())
        return quarters.mapNotNull {
            val start = it.date.atStartOfDay().plusMinutes(it.quarter * 15L)
            if (window.holds(start, 15)) Row(epochMs(start), PARAM_STEPS, it.steps) else null
        }
    }

    /**
     * The slots of [day] a page takes: those that ended after [from] and, when [to] is set, by it.
     * A page from midnight takes every slot the ring files under the day, so a whole day keeps
     * steps stamped with another date as the ring sends them.
     */
    private class SlotWindow(
        day: LocalDate,
        from: LocalTime,
        to: LocalTime?,
    ) {
        private val after = day.atTime(from).takeIf { from != LocalTime.MIDNIGHT }
        private val until = to?.let(day::atTime)

        fun holds(
            start: LocalDateTime,
            minutes: Int,
        ): Boolean {
            val end = start.plusMinutes(minutes.toLong())
            return (after == null || end.isAfter(after)) && (until == null || !end.isAfter(until))
        }
    }

    /** The ring's frame: its wall clock counted as if UTC. */
    private fun ringEpochSeconds(wall: LocalDateTime): Long = wall.toEpochSecond(ZoneOffset.UTC)

    private fun epochMs(wall: LocalDateTime): Long = wall.atZone(zone).toInstant().toEpochMilli()

    /** A parsed cursor: [day] complete when [cut] is null, else pulled up to [cut]. */
    private data class ColmiCursor(
        val day: LocalDate,
        val cut: LocalTime?,
    ) {
        override fun toString() = if (cut == null) day.toString() else "$day$CUT_SEPARATOR${CUT_FORMAT.format(cut)}"
    }

    /** @property valueJson the `value` as JSON text. */
    private class Row(
        val ts: Long,
        val param: String,
        val valueJson: String,
    ) {
        constructor(ts: Long, param: String, value: Int) : this(ts, param, value.toString())

        fun json() = """{"ts":$ts,"param":"$param","value":$valueJson}"""
    }

    /** The cursor day's rows from the big-data channel's answers, in no particular order. */
    private suspend fun bigDataRows(
        day: LocalDate,
        today: LocalDate,
    ): List<Row> {
        val daysAgo = ChronoUnit.DAYS.between(day, today).toInt()
        val start = day.atStartOfDay()
        val spo2 =
            ColmiBigData.parseSpo2(bigData.payload(ColmiBigData.TYPE_SPO2)).filter { it.daysAgo == daysAgo }.map {
                val value =
                    buildJsonObject {
                        put("min", it.min)
                        put("max", it.max)
                    }
                Row(epochMs(start.plusHours(it.hour.toLong())), PARAM_SPO2, value.toString())
            }
        val sleep =
            ColmiBigData.parseSleep(bigData.payload(ColmiBigData.TYPE_SLEEP)).filter { it.daysAgo == daysAgo }.map {
                val startTs = epochMs(start.plusMinutes(it.startOffsetMinutes.toLong()))
                Row(startTs, PARAM_SLEEP, sleepValue(it, startTs, epochMs(start.plusMinutes(it.endMinute.toLong()))))
            }
        val temperature =
            ColmiBigData
                .parseTemperature(bigData.payload(ColmiBigData.TYPE_TEMPERATURE))
                .filter { it.daysAgo == daysAgo }
                .map {
                    val value = buildJsonObject { put("c", BigDecimal.valueOf(it.deciCelsius.toLong(), 1)) }
                    Row(epochMs(start.plusMinutes(it.slot * 30L)), PARAM_TEMP, value.toString())
                }
        return spo2 + sleep + temperature
    }

    /** `{"start_ts","end_ts","stages":[{"stage","minutes"}],"minutes":{<stage>:<total>}}`. */
    private fun sleepValue(
        session: ColmiSleepSession,
        startTs: Long,
        endTs: Long,
    ): String {
        // Every named stage appears in the totals, so receivers need not treat a missing key as 0.
        val totals = ColmiSleepStage.STAGE_LABELS.values.associateWithTo(LinkedHashMap()) { 0 }
        session.stages.forEach { totals.merge(it.label, it.minutes, Int::plus) }
        return buildJsonObject {
            put("start_ts", startTs)
            put("end_ts", endTs)
            putJsonArray("stages") {
                session.stages.forEach {
                    addJsonObject {
                        put("stage", it.label)
                        put("minutes", it.minutes)
                    }
                }
            }
            putJsonObject("minutes") { totals.forEach { (stage, minutes) -> put(stage, minutes) } }
        }.toString()
    }

    companion object {
        /**
         * The phone time, epoch ms, from which an automatic sync from [cursor] is due:
         * [intradayInterval] after the cursor's cut in [zone], the cut of a whole day being the
         * start of the next day. Earlier, [pull] would find at most a few slots closed since the
         * last connection, not worth another. Null when any time is: no cursor yet (the ring's
         * history is waiting), or a cursor that [pull] would reject, which then fails the sync as
         * it always did.
         *
         * Midnight that a DST change skips counts from the first instant of the day, as [pull]'s
         * "today" does; a cut in a DST gap counts as shifted later by the gap's length.
         */
        fun nextDueAtMs(
            cursor: String?,
            zone: ZoneId,
            intradayInterval: Duration = DEFAULT_INTRADAY_INTERVAL,
        ): Long? {
            val last = parseCursor(cursor ?: return null) ?: return null
            val cut =
                if (last.cut == null) {
                    last.day.plusDays(1).atStartOfDay(zone)
                } else {
                    last.day.atTime(last.cut).atZone(zone)
                }
            return cut.toInstant().toEpochMilli() + intradayInterval.inWholeMilliseconds
        }

        /** `YYYY-MM-DD` or `YYYY-MM-DD@HH:MM` (see the class KDoc); null for anything else. */
        private fun parseCursor(cursor: String): ColmiCursor? =
            try {
                val day = LocalDate.parse(cursor.substringBefore(CUT_SEPARATOR))
                val cut = cursor.substringAfter(CUT_SEPARATOR, "").takeIf { CUT_SEPARATOR in cursor }
                ColmiCursor(day, cut?.let { LocalTime.parse(it, CUT_FORMAT) })
            } catch (_: DateTimeParseException) {
                null
            }

        private const val CUT_SEPARATOR = '@'
        private val CUT_FORMAT = DateTimeFormatter.ofPattern("HH:mm").withResolverStyle(ResolverStyle.STRICT)

        /**
         * Each pull opens a BLE connection and costs ring battery, while the heart-rate log fills
         * one 5-minute slot at a time: an hour of latency buys 12 readings per connection. With
         * the 15-minute periodic trigger the effective cadence is 60 to 75 minutes.
         */
        val DEFAULT_INTRADAY_INTERVAL: Duration = 1.hours

        const val DEFAULT_HISTORY_DAYS: Int = 8
        const val FILE_PREFIX: String = "colmi/"
        const val PARAM_HR: String = "colmi.hr"
        const val PARAM_STEPS: String = "colmi.steps"
        const val PARAM_HRV: String = "colmi.hrv"
        const val PARAM_BATTERY: String = "colmi.battery"
        const val PARAM_HR_LOGGING: String = "colmi.hr_logging"
        const val PARAM_DEVICE: String = "colmi.device"
        const val PARAM_SPO2: String = "colmi.spo2"
        const val PARAM_SLEEP: String = "colmi.sleep"
        const val PARAM_TEMP: String = "colmi.temp"

        /** Gadgetbridge's wait after connecting, "to give the ring time to settle". */
        val DEFAULT_SETTLE_DELAY: Duration = 2.seconds
    }
}
