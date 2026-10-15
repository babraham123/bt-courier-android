package app.btcourier.core.sink.http

import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import java.io.IOException
import java.util.zip.GZIPInputStream

/** By the last extension, so a `.gz` body is labelled as gzip, not as the content inside it. */
fun contentTypeOf(name: String): MediaType = when (name.substringAfterLast('.')) {
    "gz" -> "application/gzip"
    "jsonl" -> "application/x-ndjson"
    "csv" -> "text/csv"
    "prom" -> "text/plain; version=0.0.4"
    else -> "application/octet-stream"
}.toMediaType()

// Corrupt stored bytes stay corrupt; reporting them as a network retry would loop forever, so
// callers turn null into a permanent failure.
fun gunzipOrNull(bytes: ByteArray): ByteArray? =
    try {
        GZIPInputStream(bytes.inputStream()).use { it.readBytes() }
    } catch (_: IOException) {
        null
    }
