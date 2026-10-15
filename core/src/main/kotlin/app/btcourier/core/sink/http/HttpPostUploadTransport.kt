package app.btcourier.core.sink.http

import app.btcourier.core.auth.Auth
import app.btcourier.core.http.await
import app.btcourier.core.http.newCall
import app.btcourier.core.http.parseBaseUrl
import app.btcourier.core.sink.Batch
import app.btcourier.core.sink.UploadResult
import app.btcourier.core.sink.RetryBackoff
import app.btcourier.core.sink.UploadTransport
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/**
 * Uploads each batch with `POST <url>`, for ingest endpoints that take a body rather than an object
 * path: VictoriaMetrics `/api/v1/import/prometheus` or `/api/v1/import/csv`, a webhook, a Loki or
 * Vector HTTP source. The URL is fixed (its query, e.g. VictoriaMetrics `extra_label=`, is kept);
 * the object name travels in an `X-Batch-Name` header and, with [nameQuery], a `name=` parameter.
 *
 * POST is not idempotent by itself. Batch names are content-addressed, so a receiver that must
 * not double-count dedupes on `X-Batch-Name`; a time-series database that receives the same
 * samples twice overwrites or dedupes them (VictoriaMetrics with `-dedup.minScrapeInterval`).
 *
 * Result mapping is [HttpPutUploadTransport]'s minus the 409/412 HEAD check, which needs an object
 * to compare and POST has none: 2xx Acked; 408/429/5xx/[IOException] Retry; 3xx or any response
 * reached through a redirect Failed (OkHttp replays a 301-303 POST as a bodyless GET); anything
 * else Failed. Retry delays honour `Retry-After`, else back off exponentially.
 *
 * @param encoding how the stored gzip bytes go on the wire; see [BodyEncoding].
 * @param nameQuery true also sends the batch name as `?name=`, for receivers that can't read
 *   custom headers (form handlers, some serverless ingress).
 * @param nowMs wall clock, for `Retry-After` dates.
 */
class HttpPostUploadTransport(
    url: String,
    private val client: OkHttpClient,
    private val auth: Auth = Auth.None,
    private val encoding: BodyEncoding = BodyEncoding.GzipFile,
    private val nameQuery: Boolean = false,
    nowMs: () -> Long = System::currentTimeMillis,
) : UploadTransport {
    private val endpoint = parseBaseUrl(url)
    private val retries = RetryBackoff(nowMs)

    enum class BodyEncoding {
        /** The stored gzip bytes as `application/gzip`, named `*.gz`. */
        GzipFile,

        /**
         * The stored gzip bytes with `Content-Encoding: gzip` and the format's own `Content-Type`,
         * named without `.gz`: what VictoriaMetrics' import endpoints and most HTTP servers decode
         * transparently.
         */
        ContentEncodingGzip,

        /** Decompressed, with the format's `Content-Type`, named without `.gz`. */
        Identity,
    }

    override suspend fun upload(batch: Batch): UploadResult {
        val result = try {
            send(batch)
        } catch (e: IOException) {
            retries.retry("network error for ${batch.name}: ${e.javaClass.simpleName}: ${e.message}", null)
        }
        if (result !is UploadResult.Retry) retries.reset()
        return result
    }

    private suspend fun send(batch: Batch): UploadResult {
        val name = if (encoding == BodyEncoding.GzipFile) batch.name else batch.name.removeSuffix(".gz")
        val bytes = if (encoding == BodyEncoding.Identity) {
            gunzipOrNull(batch.bytes) ?: return UploadResult.Failed("$name: stored batch is not valid gzip")
        } else {
            batch.bytes
        }
        val target = if (nameQuery) endpoint.newBuilder().addQueryParameter("name", name).build() else endpoint
        val call = client.newCall(auth) {
            url(target)
            header("X-Batch-Name", name)
            if (encoding == BodyEncoding.ContentEncodingGzip) header("Content-Encoding", "gzip")
            post(bytes.toRequestBody(contentTypeOf(name)))
        }
        return call.await().use { rsp ->
            val code = rsp.code
            when {
                code in 300..399 || rsp.priorResponse != null || rsp.request.method != "POST" ->
                    UploadResult.Failed("HTTP $code for $name: redirects are not followed")
                code in 200..299 -> UploadResult.Acked
                code == 408 || code == 429 || code in 500..599 ->
                    retries.retry("HTTP $code for $name", retries.retryAfterMs(rsp.header("Retry-After")))
                else -> UploadResult.Failed("HTTP $code for $name")
            }
        }
    }
}
