package app.btcourier.core.transport.wifi

import app.btcourier.core.auth.Auth
import app.btcourier.core.http.append
import app.btcourier.core.http.await
import app.btcourier.core.http.newCall
import app.btcourier.core.http.parseBaseUrl
import app.btcourier.core.http.toLowerCaseMap
import app.btcourier.core.transport.Response
import app.btcourier.core.transport.StreamingResponse
import app.btcourier.core.transport.Transport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import okio.Buffer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * [Transport] over plain HTTP to the device's own web server, on the LAN or through a VPN.
 *
 * Same contract as the BLE transport: every status (429 included) comes back as a [Response];
 * only network failures throw [java.io.IOException]. Unlike the BLE tunnel, all request
 * [headers][Transport.request] are sent.
 *
 * @param baseUrl scheme, host, port and optional path prefix; request paths are appended to it.
 * @param client shared client; timeouts, TLS pinning and proxies are configured there.
 */
class WifiHttpTransport(
    baseUrl: String,
    private val client: OkHttpClient,
    private val auth: Auth = Auth.None,
) : Transport {
    private val base = parseBaseUrl(baseUrl)

    override suspend fun request(
        method: String,
        path: String,
        body: ByteArray?,
        headers: Map<String, String>,
    ): Response = call(method, path, body, headers).await().use { rsp ->
        val bytes = withContext(Dispatchers.IO) { rsp.body.bytes() }
        Response(rsp.code, rsp.headers.toLowerCaseMap(), bytes)
    }

    /**
     * The body flow must be collected or cancelled, as with the BLE transport: until then the
     * response holds a pooled connection open.
     */
    override suspend fun stream(
        method: String,
        path: String,
        body: ByteArray?,
        headers: Map<String, String>,
    ): StreamingResponse {
        val call = call(method, path, body, headers)
        val rsp = call.await()
        val collected = AtomicBoolean(false)
        val bodyFlow = flow {
            check(collected.compareAndSet(false, true)) { "a streamed body can be collected once" }
            rsp.use {
                coroutineScope {
                    // A blocking socket read ignores coroutine cancellation; cancelling the call
                    // closes the socket, which is what makes cancelling collection abort it.
                    val canceller = launch {
                        try {
                            awaitCancellation()
                        } finally {
                            call.cancel()
                        }
                    }
                    val source = rsp.body.source()
                    val chunk = Buffer()
                    while (withContext(Dispatchers.IO) { source.read(chunk, CHUNK_BYTES) } != -1L) {
                        emit(chunk.readByteArray())
                    }
                    canceller.cancel()
                }
            }
        }
        return StreamingResponse(rsp.code, rsp.headers.toLowerCaseMap(), bodyFlow)
    }

    private suspend fun call(
        method: String,
        path: String,
        body: ByteArray?,
        headers: Map<String, String>,
    ): Call {
        val contentType = headers.entries.firstOrNull { it.key.equals("content-type", ignoreCase = true) }?.value
        val requestBody = when {
            body != null -> body.toRequestBody(contentType?.toMediaTypeOrNull())
            // OkHttp rejects a body-less POST/PUT; the device treats an empty body the same.
            method.uppercase() in BODY_REQUIRED -> ByteArray(0).toRequestBody(contentType?.toMediaTypeOrNull())
            else -> null
        }
        return client.newCall(auth) {
            url(base.append(path))
            headers.forEach { (name, value) -> header(name, value) }
            method(method, requestBody)
        }
    }

    private companion object {
        const val CHUNK_BYTES = 8L * 1024
        val BODY_REQUIRED = setOf("POST", "PUT", "PATCH")
    }
}
