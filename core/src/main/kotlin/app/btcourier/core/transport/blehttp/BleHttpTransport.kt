package app.btcourier.core.transport.blehttp

import app.btcourier.core.transport.GattLink
import app.btcourier.core.transport.Response
import app.btcourier.core.transport.StreamingResponse
import app.btcourier.core.transport.Transport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * [Transport] over the WiCAN Pro `ble_http` tunnel on a [GattLink] whose notifications are
 * subscribed in notify mode (CCCD `0x0001`). Indication mode also works: frames that span PDUs
 * are reassembled and the device ignores the credits.
 *
 * - One request in flight per link (§3). A concurrent call waits for the previous exchange to
 *   finish, in call order, rather than failing: the device would answer it `429 busy` anyway, and
 *   callers already serialise logically (pull loop). A [stream] holds the link until its body has
 *   been collected to the end or cancelled; a body nobody starts collecting within
 *   [responseIdleTimeout] is aborted and the link released, so a caller that drops a response
 *   (e.g. after reading an error status) cannot wedge the link.
 * - A device that goes silent mid-exchange (no frame for [responseIdleTimeout] while a head or body
 *   is owed) fails the call with [BleHttpException.ResponseTimeout] and is sent ABORT. The clock
 *   does not run while the request body is still going out: the device answers only once it has
 *   read it, and a slow upload is not a silent device.
 * - Request frames go out as write-with-response, which §4 lists as a safe upload mode needing
 *   no credit accounting; uploads in this app are small. CREDIT and ABORT go out
 *   write-without-response, so paying the device never waits on a connection event.
 * - Only `Content-Type` of [headers][Transport.request] is carried; the tunnel has no field for
 *   other request headers.
 * - Responses carry `content-type`, `content-length` (when known) and, on errors the tunnel
 *   generates itself (§5, e.g. `429 busy`), [TUNNEL_ERROR_HEADER].
 *
 * @param scope owns the coroutine that reads [GattLink.notifications] for the link's lifetime. It
 *   must outlive single calls because [stream] returns before the body arrives, and it subscribes
 *   before the first request so no head can arrive unobserved.
 * @param responseIdleTimeout longer than the device's own 30 s timeouts, so that when the device
 *   gives up first its early LAST arrives (and is reported as [BleHttpException.Truncated]) before
 *   this fires, and longer than the slowest route answers (`/api/rtc/sync` blocks up to ~32 s).
 */
class BleHttpTransport(
    private val link: GattLink,
    private val scope: CoroutineScope,
    private val responseIdleTimeout: Duration = DEFAULT_RESPONSE_IDLE_TIMEOUT,
) : Transport {
    // Unbounded is safe: the device never has more than one 16 KB credit window plus a head in
    // flight, and a consumer that stops reading stops crediting.
    private val frames = Channel<Frame>(Channel.UNLIMITED)

    @Volatile
    private var linkFailure: Throwable? = null

    private val inFlight = Mutex()
    private var nextSeq = 1

    init {
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            val parser = FrameParser()
            try {
                link.notifications.collect { pdu -> parser.feed(pdu).forEach { frames.send(it) } }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                linkFailure = e
            } finally {
                frames.close()
            }
        }
    }

    override suspend fun request(
        method: String,
        path: String,
        body: ByteArray?,
        headers: Map<String, String>,
    ): Response = inFlight.withLock {
        val exchange = start(method, path, body, headers)
        val out = ByteArrayOutputStream()
        guarded(exchange) { readBody(exchange) { out.write(it) } }
        val head = exchange.head!!
        Response(head.status, headersOf(head), out.toByteArray())
    }

    override suspend fun stream(
        method: String,
        path: String,
        body: ByteArray?,
        headers: Map<String, String>,
    ): StreamingResponse {
        inFlight.lock()
        val exchange = try {
            start(method, path, body, headers)
        } catch (e: Throwable) {
            inFlight.unlock()
            throw e
        }
        val head = exchange.head!!
        if (head.length == 0L) {
            // Nothing but LAST follows (tunnel errors such as 429 busy, empty routes): finish now
            // so a caller that only looks at the status never holds the link.
            try {
                guarded(exchange) { readBody(exchange) {} }
            } finally {
                inFlight.unlock()
            }
            val collected = AtomicBoolean(false)
            return StreamingResponse(
                head.status,
                headersOf(head),
                flow { check(collected.compareAndSet(false, true)) { "a streamed body can be collected once" } },
            )
        }

        val state = AtomicInteger(BODY_UNCLAIMED)
        val watchdog = scope.launch {
            delay(responseIdleTimeout)
            if (state.compareAndSet(BODY_UNCLAIMED, BODY_ABANDONED)) {
                abortQuietly(exchange.seq)
                inFlight.unlock()
            }
        }
        val bodyFlow = flow {
            if (!state.compareAndSet(BODY_UNCLAIMED, BODY_CLAIMED)) {
                if (state.get() == BODY_ABANDONED) throw BleHttpException.ResponseTimeout(responseIdleTimeout, 0)
                error("a streamed body can be collected once")
            }
            watchdog.cancel()
            try {
                guarded(exchange) { readBody(exchange) { emit(it) } }
            } finally {
                inFlight.unlock()
            }
        }
        return StreamingResponse(head.status, headersOf(head), bodyFlow)
    }

    /** Sends the request and waits for the RSP head. */
    private suspend fun start(
        method: String,
        path: String,
        body: ByteArray?,
        headers: Map<String, String>,
    ): ResponseAssembler {
        val seq = nextSeq
        nextSeq = if (seq == 0xFFFF) 1 else seq + 1
        val exchange = ResponseAssembler(seq)
        val contentType = headers.entries.firstOrNull { it.key.equals("content-type", ignoreCase = true) }?.value ?: ""
        val requestFrames = BleHttpWire.requestFrames(seq, method, path, body ?: ByteArray(0), contentType)

        guarded(exchange) {
            coroutineScope {
                val answered = AtomicBoolean(false)
                // Concurrent with waiting for the head: the device may refuse the request (403
                // path, 400 hole, ...) mid-upload, and the rest of the body would be wasted.
                val upload = launch {
                    for (frame in requestFrames) {
                        // Only between frames: a frame cut short would make the device parse the
                        // next request's bytes as the rest of it.
                        if (answered.get()) break
                        writeFrame(frame, withResponse = true)
                    }
                }
                while (exchange.head == null) {
                    val frame = if (upload.isActive) {
                        // No idle clock yet: the device owes nothing until it has the whole body.
                        select<Frame?> {
                            frames.onReceiveCatching { it.getOrNull() ?: throw BleHttpException.LinkLost(linkFailure) }
                            upload.onJoin { null }
                        }
                    } else {
                        receive(exchange)
                    }
                    frame?.let(exchange::accept)
                }
                answered.set(true)
            }
        }
        return exchange
    }

    private suspend inline fun readBody(exchange: ResponseAssembler, sink: (ByteArray) -> Unit) {
        while (true) {
            when (val event = exchange.accept(receive(exchange))) {
                is ResponseAssembler.Event.Body -> {
                    sink(event.bytes)
                    exchange.creditDue()?.let { writeFrame(Frame.credit(exchange.seq, it), withResponse = false) }
                }
                is ResponseAssembler.Event.End -> {
                    if (event.tail.isNotEmpty()) sink(event.tail)
                    return
                }
                is ResponseAssembler.Event.Head, ResponseAssembler.Event.Ignored -> Unit
            }
        }
    }

    /**
     * Runs [block]; if it fails while the device may still be working on the exchange (counter
     * gap, cancellation, consumer error), sends ABORT so the device frees the tunnel instead of
     * answering the next request `429 busy` until its 30 s credit timeout.
     */
    private suspend inline fun <T> guarded(exchange: ResponseAssembler, block: () -> T): T =
        try {
            block()
        } catch (e: Throwable) {
            if (e !is BleHttpException.LinkLost && !exchange.finished) abortQuietly(exchange.seq)
            throw e
        }

    private suspend fun abortQuietly(seq: Int) {
        try {
            writeFrame(Frame.abort(seq), withResponse = false)
        } catch (_: IOException) {
            // The link is gone; the device drops the request by itself.
        }
    }

    /**
     * Writes all ATT units of a frame. Not cancellable: abandoning a frame halfway would leave the
     * device's parser waiting for bytes the next frame would then supply.
     */
    private suspend fun writeFrame(frame: Frame, withResponse: Boolean) = withContext(NonCancellable) {
        for (unit in BleHttpWire.attUnits(frame.encode(), link.mtu)) {
            try {
                link.write(unit, withResponse)
            } catch (e: IOException) {
                throw BleHttpException.LinkLost(e)
            }
        }
    }

    /** The next frame, failing once the device has been silent for [responseIdleTimeout]. */
    private suspend fun receive(exchange: ResponseAssembler): Frame {
        val result = withTimeoutOrNull(responseIdleTimeout) { frames.receiveCatching() }
            ?: throw BleHttpException.ResponseTimeout(responseIdleTimeout, exchange.received)
        return result.getOrNull() ?: throw BleHttpException.LinkLost(linkFailure)
    }

    private fun headersOf(head: ResponseHead): Map<String, String> = buildMap {
        if (head.contentType.isNotEmpty()) put("content-type", head.contentType)
        if (head.error == null && head.length >= 0) put("content-length", head.length.toString())
        head.error?.let { put(TUNNEL_ERROR_HEADER, it) }
    }

    companion object {
        /** Response header carrying the `err` code of a tunnel-generated error (§5). */
        const val TUNNEL_ERROR_HEADER: String = "x-blehttp-error"

        val DEFAULT_RESPONSE_IDLE_TIMEOUT: Duration = 40.seconds

        private const val BODY_UNCLAIMED = 0
        private const val BODY_CLAIMED = 1
        private const val BODY_ABANDONED = 2
    }
}

/** The tunnel-generated error of a response from [BleHttpTransport], e.g. [TunnelError.BUSY]; null for route responses. */
val Response.tunnelError: TunnelError?
    get() = headers[BleHttpTransport.TUNNEL_ERROR_HEADER]?.let(TunnelError::of)

/** As [Response.tunnelError]. */
val StreamingResponse.tunnelError: TunnelError?
    get() = headers[BleHttpTransport.TUNNEL_ERROR_HEADER]?.let(TunnelError::of)
