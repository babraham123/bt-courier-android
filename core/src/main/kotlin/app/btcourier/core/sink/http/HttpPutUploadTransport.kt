package app.btcourier.core.sink.http

import app.btcourier.core.auth.Auth
import app.btcourier.core.http.await
import app.btcourier.core.http.newCall
import app.btcourier.core.http.parseBaseUrl
import app.btcourier.core.sink.Batch
import app.btcourier.core.sink.UploadResult
import app.btcourier.core.sink.RetryBackoff
import app.btcourier.core.sink.UploadTransport
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException

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
 *   - 2xx, not reached via a redirect, with a `Content-Length` equal to ours: [UploadResult.Acked]. The name embeds a hash of
 *     the content, so same name and same length is the same object for any practical purpose,
 *     and the check costs one round trip without downloading anything.
 *   - 404: [UploadResult.Failed]. Nothing is there, so the conflict is about the target itself
 *     (WebDAV answers 409 for a missing parent collection); retrying can't fix that.
 *   - anything else, or a failed HEAD: [UploadResult.Retry], since a conflict can be transient
 *     (a concurrent writer, a lock) and declaring it permanent would strand the batch.
 * - 3xx, or any response the client reached by following a redirect: [UploadResult.Failed]. A
 *   redirect is a config problem (typically an auth proxy sending us to its login page), and
 *   OkHttp replays a redirected PUT as a bodyless GET whose 200 must not count as an upload. The
 *   transport checks this itself rather than trusting the client to have redirects disabled.
 * - Any other status (401, 403, 404, 413, ...): [UploadResult.Failed]; these need a config
 *   change, not another attempt.
 *
 * [UploadResult.Retry.afterMs] comes from [RetryBackoff]: `Retry-After` when usable, else an
 * exponential back-off over this instance's consecutive retries, reset by any non-retry result.
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
    nowMs: () -> Long = System::currentTimeMillis,
) : UploadTransport {
    private val base = parseBaseUrl(baseUrl)
    private val retries = RetryBackoff(nowMs)

    override suspend fun upload(batch: Batch): UploadResult {
        val result = try {
            put(batch)
        } catch (e: IOException) {
            retries.retry("network error for ${batch.name}: ${e.javaClass.simpleName}: ${e.message}", null)
        }
        if (result !is UploadResult.Retry) retries.reset()
        return result
    }

    private suspend fun put(batch: Batch): UploadResult {
        val name = if (compress) batch.name else batch.name.removeSuffix(".gz")
        val bytes = if (compress) {
            batch.bytes
        } else {
            gunzipOrNull(batch.bytes) ?: return UploadResult.Failed("$name: stored batch is not valid gzip")
        }
        val url = base.newBuilder().addPathSegment(name).build()
        val call = client.newCall(auth) { url(url).put(bytes.toRequestBody(contentTypeOf(name))) }
        return call.await().use { rsp ->
            val code = rsp.code
            when {
                code in 300..399 || rsp.redirected || rsp.request.method != "PUT" ->
                    UploadResult.Failed("HTTP $code for $name: redirects are not followed")
                code in 200..299 -> UploadResult.Acked
                code == 408 || code == 429 || code in 500..599 ->
                    retries.retry("HTTP $code for $name", retries.retryAfterMs(rsp.header("Retry-After")))
                code == 409 || code == 412 -> resolveConflict(code, name, url, bytes.size.toLong())
                else -> UploadResult.Failed("HTTP $code for $name")
            }
        }
    }

    private suspend fun resolveConflict(code: Int, name: String, url: HttpUrl, length: Long): UploadResult =
        client.newCall(auth) { url(url).head() }.await().use { rsp ->
            when {
                rsp.isSuccessful && !rsp.redirected && rsp.header("Content-Length")?.toLongOrNull() == length -> UploadResult.Acked
                rsp.code == 404 -> UploadResult.Failed("HTTP $code for $name and no object exists there")
                else -> retries.retry("HTTP $code for $name and the existing object is not verifiably identical", null)
            }
        }

    private val Response.redirected get() = priorResponse != null
}
