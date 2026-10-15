package app.btcourier.core.wican

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/** Parses [line] as a JSON object, or null for anything else (garbage, arrays, a cut-off line). */
internal fun parseObjectOrNull(line: String): JsonObject? =
    try {
        Json.parseToJsonElement(line) as? JsonObject
    } catch (_: IllegalArgumentException) {
        // SerializationException extends IllegalArgumentException.
        null
    }

internal fun JsonObject.primitive(key: String): JsonPrimitive? = get(key) as? JsonPrimitive

/** A whole number however the device wrote it (`5`, `5.0`, `"5"`), or null. */
internal fun JsonObject.long(key: String): Long? =
    primitive(key)?.let { it.longOrNull ?: it.doubleOrNull?.toLong() }

internal fun JsonObject.string(key: String): String? = primitive(key)?.takeIf { it.isString }?.content

internal fun JsonObject.boolean(key: String): Boolean? = primitive(key)?.takeIf { !it.isString }?.booleanOrNull
