package app.btcourier.fakes

import app.btcourier.fakes.ScriptedPeripheral.Reply
import app.btcourier.fakes.ScriptedPeripheral.Request
import java.time.Instant
import java.util.TreeMap

/**
 * The WiCAN Pro routes a sync uses, over an in-memory SD card, as a [ScriptedPeripheral] handler:
 *
 * - `GET /api/logger/export`: a port of wican-fw `dev-v6` `export_get_handler`
 *   (`data_logger_http.c`) for the jsonl engine: files `dl_<epoch>.jsonl` in epoch order, cursor
 *   `<epoch>:<byte offset>`, `since` absent = `0:0`, a `{"_file"}` line before each file's rows
 *   (also when resuming mid-file), at most `limit` lines (1..2000, else 500) and 64 KB per
 *   response, a budget never cutting a line, `{"_cursor","more"}` last. Faithful to the
 *   handler's quirk too: a budget running out exactly at the end of a file with a newer sibling
 *   reports `<that file>:0` and `more:false`.
 * - `GET /api/fs/download?path=/sd/logs/clock.jsonl`: [journal], or 404 when null.
 * - `GET /api/rtc`: `{"time","valid"}` from [rtcEpochSeconds] and [clockValid].
 *
 * Unknown routes return null, which [ScriptedPeripheral] answers 404.
 */
class FakeWicanLogger {
    private val files = TreeMap<Long, StringBuilder>()

    /** `/sd/logs/clock.jsonl` content; null = no journal on the card. */
    var journal: String? = null

    var rtcEpochSeconds: Long = 0
    var clockValid: Boolean = true

    /** Export requests answered so far. */
    var exports: Int = 0
        private set

    /** Appends whole lines to `dl_<epoch>.jsonl`, creating it if needed. */
    fun append(epoch: Long, lines: List<String>) {
        val file = files.getOrPut(epoch) { StringBuilder() }
        lines.forEach { file.append(it).append('\n') }
    }

    fun handle(request: Request): Reply? {
        val (route, query) = split(request.path)
        if (request.method != "GET") return null
        return when (route) {
            "/api/logger/export" -> export(query)
            "/api/fs/download" -> if (query["path"] == JOURNAL) download(journal) else null
            "/api/rtc" -> Reply(
                200,
                """{"time":"${Instant.ofEpochSecond(rtcEpochSeconds)}","valid":$clockValid}""",
            )
            else -> null
        }
    }

    private fun download(content: String?): Reply =
        if (content == null) {
            Reply(404, "file not found", "text/plain")
        } else {
            Reply(200, content.encodeToByteArray(), "application/octet-stream")
        }

    private fun export(query: Map<String, String>): Reply {
        exports++
        if (query["stream"].let { it != null && it != "params" }) return Reply(400, "only stream=params", "text/plain")
        // sscanf("%lld:%ld") semantics: whatever parses, the rest stays 0.
        val since = query["since"].orEmpty().split(':')
        val sinceEpoch = since.getOrNull(0)?.toLongOrNull() ?: 0
        var off = since.getOrNull(1)?.toLongOrNull()?.toInt() ?: 0
        val limit = query["limit"]?.toIntOrNull()?.takeIf { it in 1..LINE_CAP } ?: LINE_DEFAULT

        val epochs = files.keys.toList()
        var idx = epochs.indexOfFirst { it >= sinceEpoch }.let { if (it < 0) epochs.size else it }
        if (idx < epochs.size && epochs[idx] != sinceEpoch) off = 0

        val out = StringBuilder()
        var linesLeft = limit
        var bytesLeft = BYTE_CAP
        var more = false
        var curEpoch = if (idx < epochs.size) epochs[idx] else sinceEpoch
        while (idx < epochs.size && linesLeft > 0 && bytesLeft > 0) {
            curEpoch = epochs[idx]
            val content = files.getValue(curEpoch).toString().encodeToByteArray()
            val marker = """{"_file":"dl_$curEpoch.jsonl"}""" + "\n"
            out.append(marker)
            bytesLeft -= marker.length
            while (linesLeft > 0 && bytesLeft > 0 && off < content.size) {
                val nl = (off until content.size).firstOrNull { content[it] == '\n'.code.toByte() } ?: break
                val len = nl + 1 - off
                if (len > bytesLeft && linesLeft < limit) break
                out.append(String(content, off, len, Charsets.UTF_8))
                bytesLeft -= len
                linesLeft--
                off += len
            }
            if (off < content.size) {
                more = true
                break
            }
            if (idx + 1 < epochs.size) {
                idx++
                off = 0
            } else {
                break
            }
        }
        out.append("""{"_cursor":"$curEpoch:$off","more":$more}""").append('\n')
        return Reply(200, out.toString().encodeToByteArray(), "application/x-ndjson", chunked = true)
    }

    private fun split(path: String): Pair<String, Map<String, String>> {
        val route = path.substringBefore('?')
        // The device does not URL-decode; neither does this.
        val query = path.substringAfter('?', "").split('&').filter { it.isNotEmpty() }
            .associate { it.substringBefore('=') to it.substringAfter('=', "") }
        return route to query
    }

    companion object {
        const val JOURNAL: String = "/sd/logs/clock.jsonl"
        private const val LINE_DEFAULT = 500
        private const val LINE_CAP = 2000
        private const val BYTE_CAP = 65536
    }
}
