package app.btcourier.fakes

import app.btcourier.core.transport.blehttp.BleHttpWire
import app.btcourier.core.transport.blehttp.Frame
import app.btcourier.core.transport.blehttp.FrameParser
import app.btcourier.core.transport.blehttp.FrameType
import app.btcourier.core.transport.blehttp.RequestHead
import app.btcourier.core.transport.blehttp.ResponseHead
import app.btcourier.core.transport.blehttp.TunnelError
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import kotlin.time.Duration

/**
 * The device side of the WiCAN Pro `ble_http` tunnel on a [FakeGattLink], answering requests
 * from a table. Mirrors the firmware's `ble_http_core.c` in notify mode: one frame per
 * notification of `min(490, MTU - 3) - 8` payload bytes, at most 16 KB of RSP_BODY uncredited
 * (checked against a full frame before each send, like the firmware), CREDITs every 8 KB of an
 * upload, `429 busy` for a second REQ, `400 hole` on a REQ_BODY counter gap, ABORT handling.
 *
 * Not thread-safe; drive it from one dispatcher (`runTest`).
 *
 * @param scope enables the 30 s credit timeout (a stalled response is closed early with LAST);
 *   pass the test scope so it runs on virtual time. Without it a stalled response waits forever.
 * @param writeDelay how long each app write takes to be accepted, to model a slow link.
 */
class ScriptedPeripheral(
    private val handler: (Request) -> Reply?,
    private val mtu: Int = 517,
    private val scope: CoroutineScope? = null,
    private val writeDelay: Duration = Duration.ZERO,
) {
    /** Answers by exact path (with query); unknown paths get 404. */
    constructor(routes: Map<String, Reply>, mtu: Int = 517, scope: CoroutineScope? = null) :
        this({ routes[it.path] }, mtu, scope)

    class Request(val method: String, val path: String, val contentType: String, val body: ByteArray)

    class Reply(
        val status: Int,
        val body: ByteArray = ByteArray(0),
        val contentType: String = "application/json",
        /** Announce `len` -1, as a route streaming a chunked response does. */
        val chunked: Boolean = false,
    ) {
        constructor(status: Int, body: String, contentType: String = "application/json") :
            this(status, body.encodeToByteArray(), contentType)
    }

    /** A failure applied to the next request. */
    sealed interface Fault {
        /** The [index]th RSP_BODY frame (0-based) is counted as sent but never delivered. */
        data class DropFrame(val index: Int) : Fault

        /** The link drops once at least [bytes] body bytes have been delivered. */
        data class LinkLossAfter(val bytes: Int) : Fault

        /** Answer `{"s":429,"err":"busy"}` as if another request were still in flight. */
        data object Busy : Fault

        /** The route never answers (no head), as a handler stuck behind the device's busy web server; ABORT ends it. */
        data object Hang : Fault

        /** The route stops producing body after at least [bytes] bytes without closing; ABORT ends it. */
        data class StallAfter(val bytes: Int) : Fault
    }

    val link: FakeGattLink = FakeGattLink(mtu) { bytes, _ ->
        if (writeDelay > Duration.ZERO) delay(writeDelay)
        receive(bytes)
    }

    private val frameMax = BleHttpWire.notifyPayloadMax(mtu)
    private val parser = FrameParser()
    private var nextFault: Fault? = null
    private var state: State = State.Idle

    private val _requests = mutableListOf<Request>()
    private val _credits = mutableListOf<Long>()
    private val _frames = mutableListOf<Frame>()

    /** Requests answered by the table, in order. */
    val requests: List<Request> get() = _requests.toList()

    /** Credit values accepted from the app, in order. */
    val credits: List<Long> get() = _credits.toList()

    /** Every frame delivered to the app, in order. */
    val delivered: List<Frame> get() = _frames.toList()

    /** Most RSP_BODY bytes ever uncredited at once. */
    var maxUncredited: Int = 0
        private set

    /** Times a response paused on a full window. */
    var stalls: Int = 0
        private set

    /** Responses closed early by the 30 s credit timeout. */
    var creditTimeouts: Int = 0
        private set

    fun inject(fault: Fault) {
        nextFault = fault
    }

    private sealed interface State {
        data object Idle : State

        class Uploading(val seq: Int, val head: RequestHead, val fault: Fault?) : State {
            val body = ByteArrayOutputStream()
            var counter = 0
            var lastCredit = 0
        }

        class Responding(val seq: Int, val body: ByteArray, val fault: Fault?) : State {
            var sent = 0
            var acked = 0
            var counter = 0
            var frameIndex = 0
            var watchdog: Job? = null
        }
    }

    private fun receive(bytes: ByteArray) = parser.feed(bytes).forEach(::onFrame)

    private fun onFrame(frame: Frame) {
        when (frame.type) {
            FrameType.REQ -> onRequest(frame)
            FrameType.REQ_BODY -> onRequestBody(frame)
            FrameType.CREDIT -> onCredit(frame)
            FrameType.ABORT -> onAbort(frame)
            FrameType.RSP, FrameType.RSP_BODY -> Unit
        }
    }

    private fun onRequest(frame: Frame) {
        if (state != State.Idle) return sendError(frame.seq, TunnelError.BUSY)
        val fault = nextFault.also { nextFault = null }
        if (fault == Fault.Busy) return sendError(frame.seq, TunnelError.BUSY)
        val head = RequestHead.decode(frame.payload) ?: return sendError(frame.seq, TunnelError.BAD_REQUEST)
        if (head.length > BleHttpWire.MAX_REQUEST_BODY) return sendError(frame.seq, TunnelError.LEN)
        if (head.length > 0) {
            state = State.Uploading(frame.seq, head, fault)
        } else {
            respond(frame.seq, Request(head.method, head.path, head.contentType, ByteArray(0)), fault)
        }
    }

    private fun onRequestBody(frame: Frame) {
        val up = state as? State.Uploading ?: return
        if (frame.seq != up.seq) return
        if (frame.counter != up.counter) return failUpload(up, TunnelError.HOLE)
        up.counter = (up.counter + 1) and 0x0F
        if (up.body.size() + frame.payload.size > up.head.length) return failUpload(up, TunnelError.LEN)
        up.body.write(frame.payload)
        val received = up.body.size()
        val complete = received.toLong() == up.head.length
        if (frame.isLast && !complete) return failUpload(up, TunnelError.SHORT)
        if (complete) {
            respond(up.seq, Request(up.head.method, up.head.path, up.head.contentType, up.body.toByteArray()), up.fault)
        } else if (received - up.lastCredit >= BleHttpWire.DEVICE_CREDIT_STEP) {
            up.lastCredit = received
            deliver(Frame.credit(up.seq, received.toLong()))
        }
    }

    private fun failUpload(up: State.Uploading, error: TunnelError) {
        state = State.Idle
        sendError(up.seq, error)
    }

    private fun onCredit(frame: Frame) {
        val r = state as? State.Responding ?: return
        if (frame.seq != r.seq || frame.payload.size != 4) return
        val v = frame.creditValue
        // Cumulative, never beyond what went out: anything else is ignored (§4b).
        if (v <= r.acked || v > r.sent) return
        r.acked = v.toInt()
        _credits += v
        r.watchdog?.cancel()
        r.watchdog = null
        pump(r)
    }

    private fun onAbort(frame: Frame) {
        when (val s = state) {
            is State.Uploading -> if (frame.seq == s.seq) failUpload(s, TunnelError.ABORTED)
            is State.Responding -> if (frame.seq == s.seq) finish(s)
            State.Idle -> Unit
        }
    }

    private fun respond(seq: Int, request: Request, fault: Fault?) {
        _requests += request
        if (fault == Fault.Hang) {
            state = State.Responding(seq, ByteArray(0), fault)
            return
        }
        val reply = handler(request) ?: Reply(404, """{"error":"not found"}""")
        val length = if (reply.chunked) -1L else reply.body.size.toLong()
        deliver(Frame.responseHead(seq, ResponseHead(reply.status, reply.contentType, length)))
        val r = State.Responding(seq, reply.body, fault)
        state = r
        pump(r)
    }

    private fun pump(r: State.Responding) {
        while (state === r) {
            // The firmware checks the window against a whole frame before it knows how many bytes
            // the next read yields, so even the final LAST waits for room.
            if (r.sent - r.acked + frameMax > BleHttpWire.DOWNLOAD_WINDOW) return stall(r)
            if (r.sent == r.body.size) return finish(r)
            // Silent until ABORT; no credit timeout either, since the window is not what stops it.
            if (r.fault is Fault.StallAfter && r.sent >= r.fault.bytes) return

            val n = minOf(frameMax, r.body.size - r.sent)
            val frame = Frame.responseBody(r.seq, r.counter, last = false, r.body.copyOfRange(r.sent, r.sent + n))
            r.counter = (r.counter + 1) and 0x0F
            r.sent += n
            maxUncredited = maxOf(maxUncredited, r.sent - r.acked)
            val dropped = (r.fault as? Fault.DropFrame)?.index == r.frameIndex++
            if (!dropped) deliver(frame)

            val lossAfter = (r.fault as? Fault.LinkLossAfter)?.bytes
            if (lossAfter != null && r.sent >= lossAfter) {
                state = State.Idle
                link.drop()
            }
        }
    }

    private fun stall(r: State.Responding) {
        stalls++
        if (scope == null || r.watchdog != null) return
        r.watchdog = scope.launch {
            delay(BleHttpWire.CREDIT_TIMEOUT)
            if (state === r) {
                creditTimeouts++
                finish(r)
            }
        }
    }

    /** Closes the response with its LAST frame, short of the body when timed out or aborted. */
    private fun finish(r: State.Responding) {
        r.watchdog?.cancel()
        state = State.Idle
        deliver(Frame.responseBody(r.seq, r.counter, last = true))
    }

    private fun sendError(seq: Int, error: TunnelError) {
        deliver(Frame.responseHead(seq, ResponseHead(error.status, error = error.code)))
        deliver(Frame.responseBody(seq, 0, last = true))
    }

    /** Body frames fit one notification by construction; heads may span several (§2). */
    private fun deliver(frame: Frame) {
        _frames += frame
        BleHttpWire.attUnits(frame.encode(), mtu).forEach(link::notify)
    }
}
