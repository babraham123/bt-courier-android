package app.btcourier.core.sink.http

import app.btcourier.core.auth.Auth
import app.btcourier.core.http.await
import app.btcourier.core.http.newCall
import app.btcourier.core.sink.UploadResult
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException

/**
 * Sends one upload request for the object [name] and classifies what happened, the mapping every
 * HTTP transport shares:
 * - 3xx, or any response the client reached by following a redirect, or one whose method is not
 *   the one sent: [UploadResult.Failed]. A redirect is a config problem (typically an auth proxy
 *   sending us to its login page), and OkHttp replays a redirected upload as a bodyless GET whose
 *   200 must not count as delivery. Checked here rather than trusting the client to have
 *   redirects disabled.
 * - 2xx: [UploadResult.Acked].
 * - 408, 429, 5xx: [UploadResult.Retry] carrying the server's `Retry-After` ([retryAfterMs]).
 * - 409, 412: [onConflict] when given, else [UploadResult.Failed].
 * - Any other status: [UploadResult.Failed]; it needs a config change, not another attempt.
 * - Any [IOException], from building the call (a token refresh), sending it, or [onConflict]:
 *   [UploadResult.Retry] without a hint. The object may or may not exist then, so this is only
 *   safe because uploads are idempotent or deduped by name.
 *
 * Classification only: when to try again is the delivery module's decision.
 *
 * @param onConflict runs while the conflicting response is still open, with its status code.
 */
internal suspend fun OkHttpClient.sendUpload(
    auth: Auth,
    name: String,
    onConflict: (suspend (code: Int) -> UploadResult)? = null,
    build: Request.Builder.() -> Unit,
): UploadResult =
    try {
        val call = newCall(auth, build)
        val method = call.request().method
        call.await().use { rsp ->
            val code = rsp.code
            when {
                code in 300..399 || rsp.redirected || rsp.request.method != method -> {
                    UploadResult.Failed("HTTP $code for $name: redirects are not followed")
                }

                code in 200..299 -> {
                    UploadResult.Acked
                }

                code == 408 || code == 429 || code in 500..599 -> {
                    UploadResult.Retry("HTTP $code for $name", rsp.retryAfterMs())
                }

                (code == 409 || code == 412) && onConflict != null -> {
                    onConflict(code)
                }

                else -> {
                    UploadResult.Failed("HTTP $code for $name")
                }
            }
        }
    } catch (e: IOException) {
        UploadResult.Retry("network error for $name: ${e.javaClass.simpleName}: ${e.message}")
    }

internal val Response.redirected get() = priorResponse != null

/**
 * RFC 9110 §10.2.3 `Retry-After` as a delay from this response: delay-seconds, or an HTTP-date
 * measured against the response's own `Date` so a skewed phone clock cancels out (against the
 * arrival time when the server sent no `Date`). A date in the past is 0. Null when absent or
 * unparseable. Not capped: bounding a hint is retry policy.
 */
internal fun Response.retryAfterMs(): Long? {
    val value = header("Retry-After")?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    if (value.all(Char::isDigit)) {
        return value.toLongOrNull()?.coerceAtMost(Long.MAX_VALUE / 1000)?.times(1000) ?: Long.MAX_VALUE
    }
    val at = headers.getDate("Retry-After")?.time ?: return null
    val reference = headers.getDate("Date")?.time ?: receivedResponseAtMillis
    return (at - reference).coerceAtLeast(0)
}
