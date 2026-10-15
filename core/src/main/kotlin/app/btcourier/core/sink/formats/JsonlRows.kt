package app.btcourier.core.sink.formats

import app.btcourier.core.wican.parseObjectOrNull
import app.btcourier.core.wican.string
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Walks stored JSONL [lines] for the tabular formats. `{"_file":...}` markers are consumed and set
 * the file every later line is attributed to (null before the first marker); every other line is
 * passed on with its parsed object, or null when it is not a JSON object.
 */
internal inline fun forEachRow(lines: List<String>, onRow: (file: String?, row: JsonObject?, line: String) -> Unit) {
    var file: String? = null
    for (line in lines) {
        val row = parseObjectOrNull(line)
        val marker = row?.string("_file")
        if (marker != null) file = marker else onRow(file, row, line)
    }
}

/**
 * [key]'s value as text: a primitive's literal as the device wrote it (so `81.50` stays `81.50`
 * and the encoding never depends on float formatting), "" for absent or JSON null, and compact
 * JSON for an object or array.
 */
internal fun JsonObject.text(key: String): String = when (val v: JsonElement? = get(key)) {
    null, JsonNull -> ""
    is JsonPrimitive -> v.content
    else -> v.toString()
}
