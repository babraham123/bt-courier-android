package app.btcourier.core.testing

import app.btcourier.core.source.ClockEvent
import app.btcourier.core.wican.ClockJournalParser

/**
 * Builders for synthetic device output in the shapes wican-fw `dev-v6` writes: export rows
 * `{"ts","param","value"}`, `_file` / `_cursor` lines, and clock journal lines. Values are made up.
 */
object WicanLines {
    fun row(ts: Long, param: String = "autopid.TEST", value: String = "1") =
        """{"ts":$ts,"param":"$param","value":$value}"""

    fun fileMarker(name: String) = """{"_file":"$name"}"""

    fun meta(cursor: String, more: Boolean) = """{"_cursor":"$cursor","more":$more}"""

    fun boot(boot: Int, ts: Long, upMs: Long, valid: Boolean, source: String) =
        """{"ev":"boot","boot":$boot,"ts":$ts,"up_ms":$upMs,"valid":$valid,"source":"$source"}"""

    /** A `valid` or `step` line; `delta_ms` is how far the clock moved, `ts_after - ts_before`. */
    fun clockSet(ev: String, boot: Int, tsBefore: Long, tsAfter: Long, source: String, vararg files: String) =
        """{"ev":"$ev","boot":$boot,"ts_before":$tsBefore,"ts_after":$tsAfter,""" +
            """"delta_ms":${tsAfter - tsBefore},"source":"$source",""" +
            """"files":[${files.joinToString(",") { "\"$it\"" }}]}"""

    fun rotate(boot: Int, ts: Long, from: String, to: String, why: String = "clock") =
        """{"ev":"rotate","boot":$boot,"ts":$ts,"stream":"params","from":"$from","to":"$to","why":"$why"}"""

    fun journal(vararg lines: String): List<ClockEvent> = ClockJournalParser.parse(lines.joinToString("\n"))
}
