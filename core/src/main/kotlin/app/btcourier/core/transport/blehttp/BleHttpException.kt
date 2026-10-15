package app.btcourier.core.transport.blehttp

import java.io.IOException
import kotlin.time.Duration

/**
 * Failures of a `ble_http` exchange that leave no usable response. All are [IOException]s, which
 * is what [app.btcourier.core.transport.Transport] promises for link failures; in every case the
 * caller repeats the whole request (the protocol has no resume).
 *
 * Statuses the device answers with, including the tunnel's own `429 busy`, are not exceptions:
 * they come back as responses (see [TunnelError]).
 */
sealed class BleHttpException(
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause) {
    /**
     * The link dropped (notifications completed or a write failed). The device drops the active
     * request and never retries it (§8).
     */
    class LinkLost(
        cause: Throwable? = null,
    ) : BleHttpException("BLE link lost; the device dropped the request", cause)

    /**
     * An RSP_BODY counter gap: a stack dropped a notification it had accepted, so the response is
     * torn (§2). The transport has already sent ABORT so the device frees the tunnel.
     */
    class FrameLost(
        val expectedCounter: Int,
        val actualCounter: Int,
        val bytesReceived: Long,
    ) : BleHttpException(
            "response frame lost: counter $actualCounter where $expectedCounter was expected " +
                "after $bytesReceived bytes",
        )

    /**
     * LAST arrived before the `len` bytes the head announced: the device gave up (30 s without a
     * credit, the route failed mid-body, or an ABORT) and closed the response early (§5).
     * Undetectable for chunked responses (`len` = -1).
     */
    class Truncated(
        val expectedBytes: Long,
        val receivedBytes: Long,
    ) : BleHttpException("response truncated at $receivedBytes of $expectedBytes bytes")

    /**
     * No frame arrived for [timeout] while the device owed one (the head after the request went
     * out, or the rest of a body), or a streamed body was left uncollected that long. The link
     * is still up but the exchange is dead; ABORT has been sent so the device frees the tunnel.
     */
    class ResponseTimeout(
        val timeout: Duration,
        val bytesReceived: Long,
    ) : BleHttpException("no response frame for $timeout after $bytesReceived body bytes")

    /** The device sent something §2/§3 does not allow, e.g. a body before its head. */
    class Protocol(
        message: String,
        cause: Throwable? = null,
    ) : BleHttpException(message, cause)
}
