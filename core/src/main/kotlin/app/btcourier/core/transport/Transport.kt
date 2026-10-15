package app.btcourier.core.transport

import kotlinx.coroutines.flow.Flow

/**
 * HTTP-shaped request/response channel to a device, independent of the physical link.
 *
 * Implementations: `BleHttp` (wican-fw `ble_http` over a [GattLink]) and `WifiHttp` (plain HTTP
 * over LAN/VPN). Callers see the same semantics either way, so sources never know which link
 * carried the bytes.
 */
interface Transport {
    /**
     * Sends one request and buffers the whole response body.
     *
     * Non-2xx statuses are returned, not thrown, so callers can act on e.g. 429. Link failures
     * throw [java.io.IOException].
     *
     * @param path absolute path including query string, e.g. `/api/logger/export?since=...`.
     */
    suspend fun request(
        method: String,
        path: String,
        body: ByteArray? = null,
        headers: Map<String, String> = emptyMap(),
    ): Response

    /**
     * Like [request], but exposes the body as chunks as they arrive, for responses too large to
     * buffer (log exports, file downloads). The body flow can be collected once; cancelling
     * collection aborts the transfer.
     */
    suspend fun stream(
        method: String,
        path: String,
        body: ByteArray? = null,
        headers: Map<String, String> = emptyMap(),
    ): StreamingResponse
}

/** A fully buffered response. Header names are lower-cased. */
data class Response(
    val status: Int,
    val headers: Map<String, String>,
    val body: ByteArray,
) {
    // Arrays compare by identity by default; responses are compared by content in tests.
    override fun equals(other: Any?): Boolean =
        other is Response && status == other.status && headers == other.headers && body.contentEquals(other.body)

    override fun hashCode(): Int = 31 * (31 * status + headers.hashCode()) + body.contentHashCode()
}

/** Status and headers are known before the body; [body] emits it in arrival order. */
class StreamingResponse(
    val status: Int,
    val headers: Map<String, String>,
    val body: Flow<ByteArray>,
)
