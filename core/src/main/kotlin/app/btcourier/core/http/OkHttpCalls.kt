package app.btcourier.core.http

import app.btcourier.core.auth.Auth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resumeWithException

/**
 * Parses a transport base URL. Credentials in the URL are rejected so they can only come through
 * [Auth], which keeps them out of `toString()` and error messages that quote the URL.
 */
internal fun parseBaseUrl(baseUrl: String): HttpUrl {
    val url = baseUrl.toHttpUrl()
    require(url.username.isEmpty() && url.password.isEmpty()) { "put credentials in Auth, not the base URL" }
    return url
}

/**
 * Appends [path] (absolute, may carry a query) to [base], keeping any base path prefix so a
 * device or bucket behind a reverse proxy at `/prefix/` still works. `HttpUrl.resolve` would
 * drop that prefix for an absolute path.
 */
internal fun HttpUrl.append(path: String): HttpUrl {
    require(path.startsWith("/")) { "path must be absolute: $path" }
    return (toString().trimEnd('/') + path).toHttpUrl()
}

/** Header names lower-cased; repeated headers joined with `, ` (RFC 9110 §5.3). */
internal fun Headers.toLowerCaseMap(): Map<String, String> =
    names().associate { name -> name.lowercase() to values(name).joinToString(", ") }

/**
 * Builds a call carrying [auth]. Building runs on [Dispatchers.IO] because a refreshing token
 * source may block.
 */
internal suspend fun OkHttpClient.newCall(auth: Auth, build: Request.Builder.() -> Unit): Call {
    val request = withContext(Dispatchers.IO) {
        Request.Builder().apply(build).also(auth::applyTo).build()
    }
    return newCall(request)
}

/** Executes the call; cancelling the coroutine cancels it. */
internal suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    cont.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onResponse(call: Call, response: Response) {
            // A response that lands after cancellation would otherwise leak its connection.
            cont.resume(response) { _, value, _ -> value.close() }
        }

        override fun onFailure(call: Call, e: IOException) {
            cont.resumeWithException(e)
        }
    })
}
