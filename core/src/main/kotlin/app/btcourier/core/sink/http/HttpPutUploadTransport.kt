package app.btcourier.core.sink.http

import app.btcourier.core.auth.Auth
import app.btcourier.core.http.await
import app.btcourier.core.http.newCall
import app.btcourier.core.http.parseBaseUrl
import app.btcourier.core.sink.Batch
import app.btcourier.core.sink.UploadResult
import app.btcourier.core.sink.UploadTransport
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.GZIPInputStream

/**
 * Uploads each batch with `PUT <baseUrl>/<batch.name>`. Works against WebDAV, S3-style
 * presigned prefixes behind a proxy, nginx `dav_methods PUT`, or a custom ingest endpoint.
 *
 * Batch names are content-addressed, so a repeated PUT overwrites an object with identical bytes
 * and the transport is idempotent without conditional headers.
 *
 * Result mapping:
 * - 2xx: [UploadResult.Acked].
 * - 408, 429, 5xx, any [IOException] (including a timeout after the body was sent, when the
 *   object may or may not exist): [UploadResult.Retry]. A retry is safe because PUT is
 *   idempotent here.
 * - 409, 412: servers that refuse to overwrite answer these for an object that already exists.
 *   A HEAD on the same URL decides:
 *   - 2xx with a `Content-Length` equal to ours: [UploadResult.Acked]. The name embeds a hash of
 *     the content, so same name and same length is the same object for any practical purpose,
 *     and the check costs one round trip without downloading anything.
 *   - 404: [UploadResult.Failed]. Nothing is there, so the conflict is about the target itself
 *     (WebDAV answers 409 for a missing parent collection); retrying can't fix that.
 *   - anything else, or a failed HEAD: [UploadResult.Retry], since a conflict can be transient
 *     (a concurrent writer, a lock) and declaring it permanent would strand the batch.
 * - Any other status (401, 403, 404, 413, unfollowed 3xx, ...): [UploadResult.Failed]; these
 *   need a config change, not another attempt.
 *
 * [UploadResult.Retry.afterMs] honours `Retry-After` (seconds or HTTP-date, capped at a day);
 * without it, it is an exponential back-off over this instance's consecutive retries, starting at
 * [INITIAL_BACKOFF_MS], doubling, capped at [MAX_BACKOFF_MS], and reset by any non-retry result.
 *
 * @param compress true uploads the stored gzip bytes as `application/gzip` under the `.gz` name.
 *   false gunzips them and drops the `.gz` suffix, for receivers that can't decompress; the name
 *   stays content-addressed because its hash covers the uncompressed content. `Content-Encoding`
 *   is never set: receivers go by the object name.
 * @param nowMs wall clock, for `Retry-After` dates.
 */
class HttpPutUploadTransport(
    baseUrl: String,
    private val client: OkHttpClient,
    private val auth: Auth = Auth.None,
    private val compress: Boolean = true,
    private val nowMs: () -> Long = System::currentTimeMillis,
) : UploadTransport {
    private val base = parseBaseUrl(baseUrl)
    private val consecutiveRetries = AtomicInteger(0)

    override suspend fun upload(batch: Batch): UploadResult {
        val result = try {
            put(batch)
        } catch (e: IOException) {
            retry("network error for ${batch.name}: ${e.javaClass.simpleName}: ${e.message}", null)
        }
        if (result !is UploadResult.Retry) consecutiveRetries.set(0)
        return result
    }

    private suspend fun put(batch: Batch): UploadResult {
        val name = if (compress) batch.name else batch.name.removeSuffix(".gz")
        val bytes = if (compress) {
            batch.bytes
        } else {
            gunzip(batch.bytes) ?: return UploadResult.Failed("$name: stored batch is not valid gzip")
        }
        val url = base.newBuilder().addPathSegment(name).build()
        val call = client.newCall(auth) { url(url).put(bytes.toRequestBody(contentTypeOf(name))) }
        return call.await().use { rsp ->
            val code = rsp.code
            when {
                code in 200..299 -> UploadResult.Acked
                code == 408 || code == 429 || code in 500..599 ->
                    retry("HTTP $code for $name", retryAfterMs(rsp.header("Retry-After")))
                code == 409 || code == 412 -> resolveConflict(code, name, url, bytes.size.toLong())
                else -> UploadResult.Failed("HTTP $code for $name")
            }
        }
    }

    private suspend fun resolveConflict(code: Int, name: String, url: HttpUrl, length: Long): UploadResult =
        client.newCall(auth) { url(url).head() }.await().use { rsp ->
            when {
                rsp.isSuccessful && rsp.header("Content-Length")?.toLongOrNull() == length -> UploadResult.Acked
                rsp.code == 404 -> UploadResult.Failed("HTTP $code for $name and no object exists there")
                else -> retry("HTTP $code for $name and the existing object is not verifiably identical", null)
            }
        }

    // Corrupt stored bytes stay corrupt; reporting them as a network retry would loop forever.
    private fun gunzip(bytes: ByteArray): ByteArray? =
        try {
            GZIPInputStream(bytes.inputStream()).use { it.readBytes() }
        } catch (_: IOException) {
            null
        }

    private fun retry(reason: String, retryAfterMs: Long?): UploadResult.Retry {
        val attempt = consecutiveRetries.getAndIncrement()
        val backoff = (INITIAL_BACKOFF_MS shl attempt.coerceAtMost(MAX_DOUBLINGS)).coerceAtMost(MAX_BACKOFF_MS)
        return UploadResult.Retry(reason, retryAfterMs ?: backoff)
    }

    /** RFC 9110 §10.2.3: delay-seconds or an HTTP-date; null if absent or unparseable. */
    private fun retryAfterMs(header: String?): Long? {
        val value = header?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val ms = value.toLongOrNull()?.let { it * 1000 }
            ?: try {
                ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() - nowMs()
            } catch (_: DateTimeParseException) {
                return null
            }
        // A date in the past means "now"; a far-future one is more likely a broken server than a
        // real request to wait for days, and would stall delivery silently.
        return ms.coerceIn(0, MAX_RETRY_AFTER_MS)
    }

    private fun contentTypeOf(name: String) = when {
        name.endsWith(".gz") -> "application/gzip"
        name.endsWith(".jsonl") -> "application/x-ndjson"
        else -> "application/octet-stream"
    }.toMediaType()

    companion object {
        const val INITIAL_BACKOFF_MS = 30_000L
        const val MAX_BACKOFF_MS = 60 * 60_000L
        const val MAX_RETRY_AFTER_MS = 24 * 60 * 60_000L

        // Enough to pass MAX_BACKOFF_MS; bounds the shift so it can't overflow.
        private const val MAX_DOUBLINGS = 16
    }
}
