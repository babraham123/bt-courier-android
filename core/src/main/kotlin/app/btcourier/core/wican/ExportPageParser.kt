package app.btcourier.core.wican

import app.btcourier.core.source.Page

/**
 * One response of `GET /api/logger/export?format=jsonl&stream=params`.
 *
 * @property lines every line before the trailing meta line, verbatim: data rows and `_file`
 *   markers. Rows are not validated; the normalizer decides what to do with odd ones.
 * @property files the `_file` markers in order, each with its index in [lines]. Rows after a
 *   marker (up to the next one) belong to that file.
 * @property cursor the trailing `_cursor` (`<file epoch>:<byte offset>`), fed back as `since`.
 * @property more whether the device has more data after [cursor].
 */
data class ExportPage(
    val lines: List<String>,
    val files: List<FileMarker>,
    val cursor: String,
    val more: Boolean,
) {
    fun toPage(): Page = Page(lines = lines, nextCursor = cursor, more = more)
}

/** A `{"_file":"dl_<epoch>.jsonl"}` line at [index] of [ExportPage.lines]. */
data class FileMarker(val index: Int, val name: String)

/** Why an export response was rejected; the caller retries from the same cursor. */
sealed class ExportPageException(message: String) : Exception(message) {
    /**
     * The response does not end with `{"_cursor":..,"more":..}`: the transfer was cut short, so
     * the rows cannot be trusted to be complete and there is no cursor to advance to.
     */
    class Truncated(lastLine: String?) :
        ExportPageException("export page has no trailing cursor line (last line: ${lastLine?.take(80)})")

    /** A meta line appeared before the end: two responses were spliced or the stream is corrupt. */
    class MisplacedMeta(index: Int) : ExportPageException("export cursor line at $index is not the last line")
}

object ExportPageParser {
    /** Parses a raw response body. */
    fun parse(body: String): ExportPage = parse(body.split('\n'))

    /**
     * Parses response lines. Blank lines are ignored and a trailing `\r` is stripped; every other
     * line is kept byte-for-byte.
     *
     * @throws ExportPageException.Truncated if the last line is not a complete meta line.
     * @throws ExportPageException.MisplacedMeta if a meta line appears before the last line.
     */
    fun parse(rawLines: List<String>): ExportPage {
        val lines = rawLines.map { it.removeSuffix("\r") }.filter { it.isNotBlank() }
        val last = lines.lastOrNull()
        val meta = last?.let(::metaOrNull) ?: throw ExportPageException.Truncated(last)
        val body = lines.dropLast(1)
        val files = body.mapIndexedNotNull { index, line ->
            if (metaOrNull(line) != null) throw ExportPageException.MisplacedMeta(index)
            fileMarkerName(line)?.let { FileMarker(index, it) }
        }
        return ExportPage(lines = body, files = files, cursor = meta.first, more = meta.second)
    }

    private fun metaOrNull(line: String): Pair<String, Boolean>? {
        if (!line.contains("\"_cursor\"")) return null
        val obj = parseObjectOrNull(line) ?: return null
        val cursor = obj.string("_cursor") ?: return null
        val more = obj.boolean("more") ?: return null
        return cursor to more
    }
}
