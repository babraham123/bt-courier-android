package app.btcourier.core.lines

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * One JSONL line as pull sources emit it, the normaliser rewrites it, the store keeps it and the
 * sink formats read it. Every module that asks "is this a file marker, and for which file" asks
 * here, so a line means the same thing at every step.
 *
 * Marker spec, as wican-fw's export handler and `ColmiRingSource` write it: a JSON object whose
 * only key is `_file`, with a string value, e.g. `{"_file":"dl_1784000060.jsonl"}`. Whitespace
 * around tokens is allowed; the key must be spelled `_file` literally (no `\u` escapes). Markers
 * split batches, and the rows after one (up to the next) belong to its file.
 *
 * A row that carries `_file` next to other keys is a [Row], not a marker: the firmware never
 * writes one, and treating it as data keeps its values in every output instead of silently
 * dropping them as a marker would.
 */
sealed interface StoredLine {
    /** A `{"_file":"<name>"}` line opening [file]. */
    data class Marker(val file: String) : StoredLine

    /** Any other JSON object; data rows are `{"ts","param","value",...}`. */
    data class Row(val fields: JsonObject) : StoredLine

    /** Not a JSON object: blank, garbage, a cut-off line, an array or a bare value. */
    data object Other : StoredLine

    companion object {
        private const val KEY = "_file"
        private const val QUOTED_KEY = "\"_file\""

        /** The marker line for [file], escaped as JSON requires. */
        fun marker(file: String): String = buildJsonObject { put(KEY, file) }.toString()

        fun classify(line: String): StoredLine {
            val obj = parseObjectOrNull(line) ?: return Other
            val file = (obj[KEY] as? JsonPrimitive)?.takeIf { it.isString && obj.size == 1 }?.content
            return if (file != null) Marker(file) else Row(obj)
        }

        /** The file [line] opens if it is a marker, else null; skips parsing lines that cannot be one. */
        fun markerFile(line: String): String? =
            if (!line.contains(QUOTED_KEY)) null else (classify(line) as? Marker)?.file

        fun isMarker(line: String): Boolean = markerFile(line) != null

        private fun parseObjectOrNull(line: String): JsonObject? =
            try {
                Json.parseToJsonElement(line) as? JsonObject
            } catch (_: IllegalArgumentException) {
                // SerializationException extends IllegalArgumentException.
                null
            }
    }
}

/**
 * Classifies [lines] in order and calls [onLine] with the file each belongs to: a marker belongs to
 * the file it opens, any other line to the latest marker before it, or to [openFile] before the
 * first marker (a page that starts mid-file).
 *
 * @return the file open after the last line, to pass as [openFile] for the lines that follow.
 */
inline fun walkFiles(
    lines: Iterable<String>,
    openFile: String? = null,
    onLine: (line: String, kind: StoredLine, file: String?) -> Unit,
): String? {
    var file = openFile
    for (line in lines) {
        val kind = StoredLine.classify(line)
        if (kind is StoredLine.Marker) file = kind.file
        onLine(line, kind, file)
    }
    return file
}
