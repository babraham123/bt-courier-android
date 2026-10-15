package app.btcourier.core.sink.http

import app.btcourier.core.auth.Auth
import app.btcourier.core.http.await
import app.btcourier.core.http.newCall
import app.btcourier.core.http.parseBaseUrl
import app.btcourier.core.sink.Batch
import app.btcourier.core.sink.UploadResult
import app.btcourier.core.sink.UploadTransport
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Uploads each batch with `PUT <baseUrl>/<batch.name>`. Works against WebDAV, S3-style
 * presigned prefixes behind a proxy, nginx `dav_methods PUT`, or a custom ingest endpoint.
 *
 * Batch names are content-addressed, so a repeated PUT overwrites an object with identical bytes
 * and the transport is idempotent without conditional headers. That also makes a retry after a
 * timeout safe, when the object may or may not exist.
 *
 * Result mapping is [sendUpload]'s (2xx Acked; 408/429/5xx/[java.io.IOException] Retry with the
 * server's `Retry-After`; redirects and other statuses Failed), plus a check for 409 and 412,
 * which servers that refuse to overwrite answer for an object that already exists. A HEAD on the
 * same URL decides:
 * - 2xx, not reached via a redirect, with a `Content-Length` equal to ours: [UploadResult.Acked].
 *   The name embeds a hash of the content, so same name and same length is the same object for
 *   any practical purpose, and the check costs one round trip without downloading anything.
 * - 404: [UploadResult.Failed]. Nothing is there, so the conflict is about the target itself
 *   (WebDAV answers 409 for a missing parent collection); retrying can't fix that.
 * - anything else, or a failed HEAD: [UploadResult.Retry], since a conflict can be transient
 *   (a concurrent writer, a lock) and declaring it permanent would strand the batch.
 *
 * @param compress true uploads the stored gzip bytes as `application/gzip` under the `.gz` name.
 *   false gunzips them and drops the `.gz` suffix, for receivers that can't decompress; the name
 *   stays content-addressed because its hash covers the uncompressed content. `Content-Encoding`
 *   is never set: receivers go by the object name.
 */
class HttpPutUploadTransport(
    baseUrl: String,
    private val client: OkHttpClient,
    private val auth: Auth = Auth.None,
    private val compress: Boolean = true,
) : UploadTransport {
    private val base = parseBaseUrl(baseUrl)

    override suspend fun upload(batch: Batch): UploadResult {
        val name = if (compress) batch.name else batch.name.removeSuffix(".gz")
        val bytes = if (compress) {
            batch.bytes
        } else {
            gunzipOrNull(batch.bytes) ?: return UploadResult.Failed("$name: stored batch is not valid gzip")
        }
        val url = base.newBuilder().addPathSegment(name).build()
        return client.sendUpload(auth, name, onConflict = { code -> resolveConflict(code, name, url, bytes.size.toLong()) }) {
            url(url).put(bytes.toRequestBody(contentTypeOf(name)))
        }
    }

    private suspend fun resolveConflict(code: Int, name: String, url: HttpUrl, length: Long): UploadResult =
        client.newCall(auth) { url(url).head() }.await().use { rsp ->
            when {
                rsp.isSuccessful && !rsp.redirected && rsp.header("Content-Length")?.toLongOrNull() == length -> UploadResult.Acked
                rsp.code == 404 -> UploadResult.Failed("HTTP $code for $name and no object exists there")
                else -> UploadResult.Retry("HTTP $code for $name and the existing object is not verifiably identical")
            }
        }
}
