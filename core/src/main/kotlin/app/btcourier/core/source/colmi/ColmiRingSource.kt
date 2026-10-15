package app.btcourier.core.source.colmi

import app.btcourier.core.source.ClockEvent
import app.btcourier.core.source.Page
import app.btcourier.core.source.Source
import app.btcourier.core.transport.GattLink
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.timeout
import kotlinx.coroutines.flow.transform
import java.io.IOException
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
 * its heart-rate log (`0x15`) and steps log (`0x43`), as `{"_file":"colmi/<day>"}` followed by
 * `colmi.hr` (bpm per 5-minute slot) and `colmi.steps` (steps per quarter hour) rows in time order,
 * so the store seals one batch per day. Only days before today are pulled, because a partial
 * today stored under its cursor could never be topped up; `more` stays true while the day after
 * the one pulled is still before today. Once caught up, [pull] answers an empty page with the
 * same cursor and `more:false`.
 *
 * The ring keeps about a week. A null cursor starts at the oldest day it is assumed to hold
 * (`today - (historyDays - 1)`), and an older cursor is clamped to that day: anything in between
 * was overwritten on the ring and is gone.
 *
 * Clock: the first [pull] of an instance sets the ring to the phone's wall clock in [zone]
 * (`0x01`), as the vendor app and Gadgetbridge do, so the ring's days are the phone's days.
 * There is no clock journal ([clockJournal] is empty) and no [deviceStatus]: rows carry the
 * ring's own stamps, which the normaliser rates `device`. A ring whose clock was lost (flat
 * battery) before this sync logged under a wrong clock that cannot be repaired afterwards.
 *
 * The first page of an instance also carries one `colmi.battery` row (percent, stamped with the
 * phone clock); a sync that is already caught up stores no page and so no battery reading.
 *
 * Unverified on hardware (all from the public reverse engineering; to be checked against a ring):
 * - Ring timestamps are its wall clock counted as if UTC. `colmi_r02_client` sets the ring to UTC
 *   and requests UTC midnights, which is this rule with `zone = UTC`; with a local zone the ring's
 *   day boundaries follow local midnight. Readings are placed from the requested day's start,
 *   not the ring's echoed timestamp, as Gadgetbridge does.
 * - The steps request addresses days by offset from the ring's today, assumed equal to the phone's
 *   today right after set-time; each steps row is placed by the date the ring stamps on it.
 * - Writes go without response, as `colmi_r02_client` does.
 * - Answers are not acknowledged, and the ring may send packets of other commands at any time;
 *   those are skipped, as are notifications that are not whole 16-byte packets or fail the checksum
 *   (a corrupted packet of the answer then surfaces as a timeout).
 * - [historyDays] = 7.
 *
 * Not thread-safe: one pull at a time, as `SyncRunner` does.
 *
 * @param responseTimeout longest wait for each packet of an answer; exceeding it is an
 *   [IOException], like any other link failure, and the day is pulled again next sync.
 */
class ColmiRingSource(
    private val link: GattLink,
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val phoneClock: () -> Long = System::currentTimeMillis,
    private val historyDays: Int = DEFAULT_HISTORY_DAYS,
    private val responseTimeout: Duration = 5.seconds,
) : Source {
    init {
        require(historyDays >= 2) { "the ring must hold at least one whole day before today, got $historyDays" }
    }

    private var clockSet = false
    private var batteryReported = false

    override suspend fun pull(cursor: String?): Page {
        val last = cursor?.let(::parseCursor)
        if (!clockSet) {
            link.write(ColmiPacketCodec.setTime(LocalDateTime.ofInstant(Instant.ofEpochMilli(phoneClock()), zone)), WITHOUT_RESPONSE)
            clockSet = true
        }
        val today = Instant.ofEpochMilli(phoneClock()).atZone(zone).toLocalDate()
        val oldest = today.minusDays(historyDays - 1L)
        val day = maxOf(last?.plusDays(1) ?: oldest, oldest)
        // A null cursor always has a day to pull, since oldest < today.
        if (!day.isBefore(today)) return Page(emptyList(), checkNotNull(cursor), more = false)

        val rows = (heartRateRows(day) + stepsRows(day, today)).sortedBy { it.ts }
        val lines = buildList {
            add("""{"_file":"$FILE_PREFIX$day"}""")
            rows.forEach { add(it.json()) }
            if (!batteryReported) {
                val battery = request(ColmiPacketCodec.batteryRequest()) { ColmiPacketCodec.parseBattery(it) }
                add(Row(phoneClock(), PARAM_BATTERY, battery.level).json())
                batteryReported = true
            }
        }
        return Page(lines, day.toString(), more = day.plusDays(1).isBefore(today))
    }

    override suspend fun clockJournal(): List<ClockEvent> = emptyList()

    private suspend fun heartRateRows(day: LocalDate): List<Row> {
        val start = day.atStartOfDay()
        val log = request(ColmiPacketCodec.heartRateLogRequest(ringEpochSeconds(start)), HeartRateLogAssembler())
        return log.values.mapIndexedNotNull { slot, bpm ->
            if (bpm == 0) null else Row(epochMs(start.plusMinutes(slot.toLong() * log.intervalMinutes)), PARAM_HR, bpm)
        }
    }

    private suspend fun stepsRows(day: LocalDate, today: LocalDate): List<Row> {
        val offset = ChronoUnit.DAYS.between(day, today).toInt()
        return request(ColmiPacketCodec.stepsLogRequest(offset), StepsLogAssembler()).map {
            Row(epochMs(it.date.atStartOfDay().plusMinutes(it.quarter * 15L)), PARAM_STEPS, it.steps)
        }
    }

    /**
     * Writes [packet] and assembles the answer from notifications carrying its command. A
     * notification that holds several packets is consumed whole, so packets after the one that
     * completes the answer are dropped.
     */
    @OptIn(FlowPreview::class)
    private suspend fun <T : Any> request(packet: ByteArray, assembler: ColmiAssembler<T>): T {
        val command = packet[0]
        link.write(packet, WITHOUT_RESPONSE)
        val result = try {
            link.notifications
                .timeout(responseTimeout)
                .transform { pdu -> ColmiPacketCodec.packets(pdu).forEach { emit(it) } }
                .filter { ColmiPacketCodec.isValid(it) && it[0] == command }
                .mapNotNull { assembler.feed(it) }
                .firstOrNull()
        } catch (e: TimeoutCancellationException) {
            throw IOException("no complete answer to command 0x%02x within %s".format(command, responseTimeout), e)
        }
        return result ?: throw IOException("link dropped during command 0x%02x".format(command))
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

    private class Row(val ts: Long, val param: String, val value: Int) {
        fun json() = """{"ts":$ts,"param":"$param","value":$value}"""
    }

    companion object {
        const val DEFAULT_HISTORY_DAYS: Int = 7
        const val FILE_PREFIX: String = "colmi/"
        const val PARAM_HR: String = "colmi.hr"
        const val PARAM_STEPS: String = "colmi.steps"
        const val PARAM_BATTERY: String = "colmi.battery"

        private const val WITHOUT_RESPONSE = false
    }
}
