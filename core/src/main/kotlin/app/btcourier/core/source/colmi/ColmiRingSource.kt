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
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * [Source] over a Colmi R02-family smart ring's per-day logs, on a [GattLink] bound to
 * [ColmiGatt.RX] (writes) and [ColmiGatt.TX] (notifications). Protocol per [ColmiPacketCodec].
 *
 * Cursor: the last whole day pulled, `YYYY-MM-DD` in [zone]. [pull] fetches the day after it:
 * its heart-rate log (`0x15`), steps log (`0x43`) and HRV log (`0x39`), as
 * `{"_file":"colmi/<day>"}` followed by `colmi.hr` (bpm per 5-minute slot), `colmi.steps` (steps
 * per quarter hour), `colmi.hrv` (ms per 30-minute slot) and the big-data rows below, in time order,
 * so the store seals one batch per day. Only days before today are pulled, because a partial today
 * stored under its cursor could never be topped up; `more` stays true while the day after the one
 * pulled is still before today. Once caught up, [pull] answers an empty page with the same cursor
 * and `more:false`, until local midnight makes another day whole: [nextDueAtMs] says when, from the
 * cursor alone, so a caller can skip connecting before then.
 *
 * The ring is assumed to keep today and the 7 days before it ([historyDays]). A null cursor
 * starts at the oldest day it holds (`today - (historyDays - 1)`), and an older cursor is clamped
 * to that day: anything in between was overwritten on the ring and is gone.
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
 * Big-data channel ([ColmiBigData], the link's secondary channel): after the cursor day's UART
 * logs, the first page of an instance asks for the ring's SpO2, sleep and temperature history.
 * Each answer holds every day the ring keeps, so each type is asked for once per instance and
 * every page takes the cursor day's part, by days before the phone's today as for steps:
 * - `colmi.spo2`: one row per hour with a reading, `ts` the hour's start,
 *   `"value":{"min":<percent>,"max":<percent>}`.
 * - `colmi.sleep`: one row per session that ended on the cursor day, `ts` its start,
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
 */
class ColmiRingSource(
    private val link: GattLink,
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val phoneClock: () -> Long = System::currentTimeMillis,
    private val historyDays: Int = DEFAULT_HISTORY_DAYS,
    private val responseTimeout: Duration = 5.seconds,
    private val settleDelay: Duration = DEFAULT_SETTLE_DELAY,
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
        val last = cursor?.let(::parseCursor)
        setClockOnce()
        val today = Instant.ofEpochMilli(phoneClock()).atZone(zone).toLocalDate()
        val oldest = today.minusDays(historyDays - 1L)
        val day = maxOf(last?.plusDays(1) ?: oldest, oldest)
        // A null cursor always has a day to pull, since oldest < today.
        if (!day.isBefore(today)) return Page(emptyList(), checkNotNull(cursor), more = false)

        val rows =
            (
                heartRateRows(
                    day,
                ) + stepsRows(day, today) + hrvRows(day, today) + bigDataRows(day, today)
            ).sortedBy { it.ts }
        val lines =
            buildList {
                add(StoredLine.marker("$FILE_PREFIX$day"))
                rows.forEach { add(it.json()) }
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
        return Page(lines, day.toString(), more = day.plusDays(1).isBefore(today))
    }

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

    private suspend fun heartRateRows(day: LocalDate): List<Row> {
        val start = day.atStartOfDay()
        val log =
            link.colmiRequest(
                ColmiPacketCodec.heartRateLogRequest(ringEpochSeconds(start)),
                responseTimeout,
                HeartRateLogAssembler(),
            )
        return slotRows(start, log.intervalMinutes, log.values, PARAM_HR)
    }

    /** See the class KDoc for when the ring is not asked and how a silent ring is told apart. */
    private suspend fun hrvRows(
        day: LocalDate,
        today: LocalDate,
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
        return slotRows(day.atStartOfDay(), HrvLogAssembler.SLOT_MINUTES, values, PARAM_HRV)
    }

    /** One row per non-zero reading, the reading at index i placed i * [minutes] after [start]. */
    private fun slotRows(
        start: LocalDateTime,
        minutes: Int,
        values: List<Int>,
        param: String,
    ): List<Row> =
        values.mapIndexedNotNull { slot, value ->
            if (value == 0) null else Row(epochMs(start.plusMinutes(slot.toLong() * minutes)), param, value)
        }

    private suspend fun stepsRows(
        day: LocalDate,
        today: LocalDate,
    ): List<Row> {
        val offset = ChronoUnit.DAYS.between(day, today).toInt()
        return link.colmiRequest(ColmiPacketCodec.stepsLogRequest(offset), responseTimeout, StepsLogAssembler()).map {
            Row(epochMs(it.date.atStartOfDay().plusMinutes(it.quarter * 15L)), PARAM_STEPS, it.steps)
        }
    }

    /** The ring's frame: its wall clock counted as if UTC. */
    private fun ringEpochSeconds(wall: LocalDateTime): Long = wall.toEpochSecond(ZoneOffset.UTC)

    private fun epochMs(wall: LocalDateTime): Long = wall.atZone(zone).toInstant().toEpochMilli()

    private fun parseCursor(cursor: String): LocalDate =
        try {
            LocalDate.parse(cursor)
        } catch (e: DateTimeParseException) {
            throw IllegalArgumentException("not a Colmi cursor (YYYY-MM-DD): $cursor", e)
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
         * The earliest phone time, epoch ms, at which [pull] from [cursor] can return a day: the
         * start, in [zone], of the second day after the cursor day, because the day after it is
         * pulled only once it is whole. Before then every [pull] answers the empty caught-up page.
         * Null when any time can: no cursor yet (the ring's history is waiting), or a cursor that
         * [pull] would reject, which then fails the sync as it always did.
         *
         * Midnight that a DST change skips counts from the first instant of the day, as [pull]'s
         * "today" does.
         */
        fun nextDueAtMs(
            cursor: String?,
            zone: ZoneId,
        ): Long? {
            val last =
                try {
                    LocalDate.parse(cursor ?: return null)
                } catch (_: DateTimeParseException) {
                    return null
                }
            return last
                .plusDays(2)
                .atStartOfDay(zone)
                .toInstant()
                .toEpochMilli()
        }

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
