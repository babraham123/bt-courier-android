package app.btcourier.core.wican

import app.btcourier.core.source.ClockEvent
import app.btcourier.core.source.DeviceStatus
import app.btcourier.core.source.Page
import app.btcourier.core.source.Source
import app.btcourier.core.source.SourceException
import app.btcourier.core.transport.Response
import app.btcourier.core.transport.Transport
import app.btcourier.core.transport.blehttp.BleHttpException
import java.time.Instant
import java.time.format.DateTimeParseException

/**
 * [Source] over the WiCAN Pro data logger's params stream (wican-fw `dev-v6` `HTTP_API.md` §6e6,
 * handler `data_logger_http.c`), on any [Transport].
 *
 * - [pull]: `GET /api/logger/export?format=jsonl&stream=params[&since=<cursor>]&limit=<limit>`.
 *   Without `since` the handler starts at `0:0`, i.e. the oldest file still on the card, so a null
 *   cursor simply omits it. The cursor goes out verbatim: the device does not URL-decode queries.
 * - [clockJournal]: `GET /api/fs/download?path=/sd/logs/clock.jsonl`; 404 (no clock event ever
 *   happened on this card) is an empty journal.
 * - [deviceStatus]: `GET /api/rtc`, the only route exposing the wall clock (`time`, whole seconds,
 *   UTC) and whether it is trusted (`valid`). `/api/status` has uptime but no wall time.
 *
 * A response cut short, whether the export lacks its cursor line or the `ble_http` transport saw
 * an early end, is [SourceException.Truncated]; other link failures stay the transport's
 * [java.io.IOException]s.
 *
 * @param limit lines per page; the device caps it at 1..2000 (and at 64 KB emitted per request),
 *   silently replacing anything else with 500.
 * @param phoneClock the phone's wall clock, paired with the device's in [deviceStatus].
 */
class WicanLoggerSource(
    private val transport: Transport,
    private val limit: Int = DEFAULT_LIMIT,
    private val phoneClock: () -> Long = System::currentTimeMillis,
) : Source {
    init {
        require(limit in 1..MAX_LIMIT) { "limit must be 1..$MAX_LIMIT, got $limit" }
    }

    override suspend fun pull(cursor: String?): Page {
        require(cursor == null || parseCursor(cursor) != null) { "not a WiCAN export cursor: $cursor" }
        val path = buildString {
            append("/api/logger/export?format=jsonl&stream=params")
            if (cursor != null) append("&since=").append(cursor)
            append("&limit=").append(limit)
        }
        val response = get(path)
        // The handler serves whatever engine the logger writes; a CSV engine answers text/plain
        // rows that would flow through as unparseable lines.
        if (response.headers["content-type"]?.startsWith("text/plain") == true) {
            throw SourceException.Unsupported("the logger writes CSV; set data_logger format to jsonl")
        }
        val page = try {
            ExportPageParser.parse(response.body.decodeToString())
        } catch (e: ExportPageException) {
            throw SourceException.Truncated(path, e)
        }
        return fixFinishedFileCursor(page).toPage()
    }

    override suspend fun clockJournal(): List<ClockEvent> {
        val response = request(JOURNAL_PATH)
        if (response.status == 404) return emptyList()
        checkStatus(JOURNAL_PATH, response)
        return ClockJournalParser.parse(response.body.decodeToString())
    }

    override suspend fun deviceStatus(): DeviceStatus {
        val sent = phoneClock()
        val response = get(RTC_PATH)
        val received = phoneClock()
        val o = parseObjectOrNull(response.body.decodeToString())
            ?: throw SourceException.Unsupported("$RTC_PATH did not answer a JSON object")
        val time = o.string("time") ?: throw SourceException.Unsupported("$RTC_PATH has no \"time\"")
        val seconds = try {
            Instant.parse(time).toEpochMilli()
        } catch (e: DateTimeParseException) {
            throw SourceException.Unsupported("$RTC_PATH time is not ISO-8601: $time")
        }
        return DeviceStatus(
            // The device truncates to whole seconds; half a second is the expected lost fraction,
            // and the round trip's midpoint is the best guess for when it read its clock.
            deviceEpochMs = seconds + 500,
            phoneEpochMs = sent + (received - sent) / 2,
            clockValid = o.boolean("valid") ?: false,
        )
    }

    private suspend fun get(path: String): Response = request(path).also { checkStatus(path, it) }

    /**
     * A `ble_http` body cut short is the same failure as an export page without its cursor line,
     * so callers see one [SourceException.Truncated] whatever the transport.
     */
    private suspend fun request(path: String): Response =
        try {
            transport.request("GET", path)
        } catch (e: BleHttpException.Truncated) {
            throw SourceException.Truncated(path, e)
        }

    private fun checkStatus(path: String, response: Response) {
        when (response.status) {
            in 200..299 -> Unit
            429 -> throw SourceException.Busy(path)
            else -> throw SourceException.DeviceError(response.status, path, response.body.decodeToString().take(200))
        }
    }

    /**
     * Works around the export handler (dev-v6, 2026-10-13): when a page budget runs out exactly
     * at the end of a file that has a newer sibling, the handler has already stepped to the next
     * file and reset the offset, but reports the finished file's epoch at offset 0 with
     * `more:false`. Fed back, that cursor re-sends the whole file, and since the same walk ends
     * the same way, pulling never gets past it. Offset 0 of a file whose rows this page carries
     * cannot come from anywhere else (reading any row advances the offset), so the cursor is
     * moved to `<epoch + 1>:0`, which the handler resumes at the next newer file.
     */
    private fun fixFinishedFileCursor(page: ExportPage): ExportPage {
        val (epoch, offset) = parseCursor(page.cursor) ?: return page
        if (offset != 0L) return page
        val at = page.files.indexOfLast { epochOf(it.name) == epoch }
        if (at < 0) return page
        val end = page.files.getOrNull(at + 1)?.index ?: page.lines.size
        val hasRows = page.lines.subList(page.files[at].index + 1, end).any { it.isNotBlank() }
        return if (hasRows) page.copy(cursor = "${epoch + 1}:0", more = true) else page
    }

    companion object {
        const val DEFAULT_LIMIT: Int = 500
        const val MAX_LIMIT: Int = 2000
        const val JOURNAL_PATH: String = "/api/fs/download?path=/sd/logs/clock.jsonl"
        const val RTC_PATH: String = "/api/rtc"

        private val cursorShape = Regex("""(\d+):(\d+)""")
        private val fileEpoch = Regex("""^dl_(\d+)\.""")

        /** `<file epoch>:<byte offset>`, or null for anything else. */
        private fun parseCursor(cursor: String): Pair<Long, Long>? =
            cursorShape.matchEntire(cursor)?.destructured?.let { (e, o) ->
                e.toLongOrNull()?.let { epoch -> o.toLongOrNull()?.let { epoch to it } }
            }

        private fun epochOf(fileName: String): Long? = fileEpoch.find(fileName)?.groupValues?.get(1)?.toLongOrNull()
    }
}
