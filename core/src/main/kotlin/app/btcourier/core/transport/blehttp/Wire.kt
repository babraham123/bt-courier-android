package app.btcourier.core.transport.blehttp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/*
 * Wire format of the WiCAN Pro `ble_http` tunnel, protocol version 2.
 *
 * Contract: `components/ble_http/BLE_HTTP_PROTOCOL.md` in meatpi-components (the doc wican-fw's
 * `components/HTTP_API.md` points to), with the constants of `ble_http_core.h`. Section numbers in
 * the comments below refer to that doc. Every byte-level detail of the protocol lives in this file
 * so a protocol revision touches one place.
 */

/** Protocol constants (§2, §4, §4b, §8). */
object BleHttpWire {
    const val MAGIC: Int = 0x57
    const val VERSION: Int = 0x02
    const val HEADER_SIZE: Int = 8

    /** Largest payload of one frame in either direction (§2). */
    const val MAX_FRAME_PAYLOAD: Int = 4096

    /** ATT units are capped at `min(490, MTU - 3)` (BLE_API.md §4.3). */
    const val MAX_ATT_UNIT: Int = 490

    /** Request bodies above this are refused by the device with `400 len` (§3). */
    const val MAX_REQUEST_BODY: Long = 8L * 1024 * 1024

    /** Notify mode: the device stops sending once this many RSP_BODY bytes are uncredited (§4b). */
    const val DOWNLOAD_WINDOW: Int = 16 * 1024

    /**
     * The client credits whenever this many bytes arrived since its last credit. §4b requires at
     * least every 8 KB; 4 KB (what §4b and §7 recommend) keeps the device's 16 KB window from
     * ever running dry, so it never stalls and never reaches its credit timeout.
     */
    const val CLIENT_CREDIT_STEP: Int = 4096

    /** The device credits uploads every 8 KB it accepted (§4). */
    const val DEVICE_CREDIT_STEP: Int = 8192

    /** Notify mode: 30 s without a CREDIT while the window is full ends the response early (§8). */
    val CREDIT_TIMEOUT: Duration = 30.seconds

    const val FLAG_LAST: Int = 0x01
    private const val COUNTER_SHIFT = 4
    private const val COUNTER_MASK = 0x0F

    /** Bytes per ATT write or notification at this MTU. */
    fun attUnit(mtu: Int): Int {
        require(mtu >= 23) { "ATT MTU is at least 23, got $mtu" }
        return minOf(MAX_ATT_UNIT, mtu - 3)
    }

    /**
     * RSP_BODY payload per frame in notify mode, where each frame is exactly one notification
     * (§4b): `min(490, MTU - 3) - 8`.
     */
    fun notifyPayloadMax(mtu: Int): Int = attUnit(mtu) - HEADER_SIZE

    /** Flags of a REQ_BODY / RSP_BODY frame: counter in bits 7..4 (mod 16), LAST in bit 0 (§2). */
    fun bodyFlags(
        counter: Int,
        last: Boolean,
    ): Int = ((counter and COUNTER_MASK) shl COUNTER_SHIFT) or (if (last) FLAG_LAST else 0)

    internal fun counterOf(flags: Int): Int = (flags ushr COUNTER_SHIFT) and COUNTER_MASK

    /** Splits an encoded frame into ATT writes; a frame may span several units (§2, §7). */
    fun attUnits(
        encodedFrame: ByteArray,
        mtu: Int,
    ): List<ByteArray> {
        val unit = attUnit(mtu)
        return (encodedFrame.indices step unit).map {
            encodedFrame.copyOfRange(
                it,
                minOf(it + unit, encodedFrame.size),
            )
        }
    }

    /**
     * The frames of one request (§3 steps 1-2): the REQ head, then the body in REQ_BODY frames of
     * up to 4096 bytes with counters 0, 1, 2, ... (mod 16) and LAST on the final one.
     */
    fun requestFrames(
        seq: Int,
        method: String,
        path: String,
        body: ByteArray,
        contentType: String = "",
    ): List<Frame> {
        val head = Frame.requestHead(seq, RequestHead(method, path, contentType, body.size.toLong()))
        val bodyFrames =
            (body.indices step MAX_FRAME_PAYLOAD).mapIndexed { n, offset ->
                val end = minOf(offset + MAX_FRAME_PAYLOAD, body.size)
                Frame.requestBody(seq, n, last = end == body.size, body.copyOfRange(offset, end))
            }
        return listOf(head) + bodyFrames
    }
}

/** Frame types (§2). */
enum class FrameType(
    val code: Int,
) {
    /** app → device: JSON request head. */
    REQ(1),

    /** app → device: request body bytes. */
    REQ_BODY(2),

    /** device → app: JSON response head. */
    RSP(3),

    /** device → app: response body bytes; the one with LAST ends the response. */
    RSP_BODY(4),

    /** app → device: cancel the active request, during its body or its response. */
    ABORT(5),

    /** both ways: `u32` LE cumulative body bytes accepted (device) or received (app). */
    CREDIT(6),
    ;

    companion object {
        fun of(code: Int): FrameType? = entries.firstOrNull { it.code == code }
    }
}

/**
 * One frame: 8-byte little-endian header (magic, version, type, flags, seq, len) and payload (§2).
 */
class Frame(
    val type: FrameType,
    val flags: Int,
    /** Request id chosen by the app and echoed in every frame of that exchange. */
    val seq: Int,
    val payload: ByteArray = EMPTY,
) {
    init {
        require(flags in 0..0xFF) { "flags out of range: $flags" }
        require(seq in 0..0xFFFF) { "seq out of range: $seq" }
        require(payload.size <= BleHttpWire.MAX_FRAME_PAYLOAD) { "payload of ${payload.size} bytes exceeds 4096" }
    }

    val isLast: Boolean get() = flags and BleHttpWire.FLAG_LAST != 0

    /** Body frame counter; meaningful on REQ_BODY / RSP_BODY only. */
    val counter: Int get() = BleHttpWire.counterOf(flags)

    /** The cumulative byte count of a CREDIT frame. */
    val creditValue: Long
        get() {
            check(type == FrameType.CREDIT && payload.size == 4) { "not a CREDIT frame: $this" }
            return (payload[0].u() or (payload[1].u() shl 8) or (payload[2].u() shl 16)).toLong() or
                (payload[3].u().toLong() shl 24)
        }

    fun encode(): ByteArray {
        val out = ByteArray(BleHttpWire.HEADER_SIZE + payload.size)
        out[0] = BleHttpWire.MAGIC.toByte()
        out[1] = BleHttpWire.VERSION.toByte()
        out[2] = type.code.toByte()
        out[3] = flags.toByte()
        out[4] = seq.toByte()
        out[5] = (seq ushr 8).toByte()
        out[6] = payload.size.toByte()
        out[7] = (payload.size ushr 8).toByte()
        payload.copyInto(out, BleHttpWire.HEADER_SIZE)
        return out
    }

    override fun equals(other: Any?): Boolean =
        other is Frame && type == other.type && flags == other.flags && seq == other.seq &&
            payload.contentEquals(other.payload)

    override fun hashCode(): Int = ((type.hashCode() * 31 + flags) * 31 + seq) * 31 + payload.contentHashCode()

    override fun toString(): String = "Frame($type, flags=0x%02x, seq=$seq, len=${payload.size})".format(flags)

    companion object {
        private val EMPTY = ByteArray(0)

        fun requestHead(
            seq: Int,
            head: RequestHead,
        ) = Frame(FrameType.REQ, 0, seq, head.encode())

        fun requestBody(
            seq: Int,
            counter: Int,
            last: Boolean,
            bytes: ByteArray,
        ) = Frame(FrameType.REQ_BODY, BleHttpWire.bodyFlags(counter, last), seq, bytes)

        fun responseHead(
            seq: Int,
            head: ResponseHead,
        ) = Frame(FrameType.RSP, 0, seq, head.encode())

        fun responseBody(
            seq: Int,
            counter: Int,
            last: Boolean,
            bytes: ByteArray = EMPTY,
        ) = Frame(FrameType.RSP_BODY, BleHttpWire.bodyFlags(counter, last), seq, bytes)

        fun abort(seq: Int) = Frame(FrameType.ABORT, 0, seq)

        fun credit(
            seq: Int,
            cumulativeBytes: Long,
        ): Frame {
            require(cumulativeBytes in 0..0xFFFF_FFFFL) { "credit is a u32: $cumulativeBytes" }
            val v = cumulativeBytes
            val payload = byteArrayOf(v.toByte(), (v ushr 8).toByte(), (v ushr 16).toByte(), (v ushr 24).toByte())
            return Frame(FrameType.CREDIT, 0, seq, payload)
        }
    }
}

private fun Byte.u(): Int = toInt() and 0xFF

/**
 * Turns a byte stream (notifications or writes, cut anywhere) back into frames, per §7: reassemble
 * by `len`, never by unit boundaries, and skip forward byte by byte past anything that is not a
 * valid header. Like the device (`bleh_hdr_parse`), an unknown type or `len` > 4096 also counts
 * as "not a header", so a stray `0x57` inside garbage cannot swallow the next real frame.
 */
class FrameParser {
    private var buf = ByteArray(BleHttpWire.HEADER_SIZE + BleHttpWire.MAX_FRAME_PAYLOAD)
    private var size = 0

    /** Bytes skipped while looking for a header. */
    var resyncedBytes: Long = 0
        private set

    fun feed(chunk: ByteArray): List<Frame> {
        if (size + chunk.size > buf.size) buf = buf.copyOf(maxOf(buf.size * 2, size + chunk.size))
        chunk.copyInto(buf, size)
        size += chunk.size

        val frames = mutableListOf<Frame>()
        var pos = 0
        while (size - pos >= BleHttpWire.HEADER_SIZE) {
            val type = FrameType.of(buf[pos + 2].u())
            val len = buf[pos + 6].u() or (buf[pos + 7].u() shl 8)
            val validHeader =
                buf[pos].u() == BleHttpWire.MAGIC && buf[pos + 1].u() == BleHttpWire.VERSION &&
                    type != null && len <= BleHttpWire.MAX_FRAME_PAYLOAD
            if (!validHeader) {
                pos++
                resyncedBytes++
                continue
            }
            if (size - pos < BleHttpWire.HEADER_SIZE + len) break
            val seq = buf[pos + 4].u() or (buf[pos + 5].u() shl 8)
            val payloadStart = pos + BleHttpWire.HEADER_SIZE
            frames += Frame(type, buf[pos + 3].u(), seq, buf.copyOfRange(payloadStart, payloadStart + len))
            pos = payloadStart + len
        }
        buf.copyInto(buf, 0, pos, size)
        size -= pos
        return frames
    }
}

private val json = Json

/**
 * The REQ head (§3): `{"m":"GET","p":"/api/...","ct":"...","len":N}`. `ct` and `len` are optional
 * on the wire and omitted when empty / zero, which is what §6's `GET /api/status` example shows.
 */
data class RequestHead(
    val method: String,
    /** Path and query, starting with `/api/`. */
    val path: String,
    val contentType: String = "",
    val length: Long = 0,
) {
    fun encode(): ByteArray =
        json
            .encodeToString(
                JsonObject.serializer(),
                buildJsonObject {
                    put("m", method)
                    put("p", path)
                    if (contentType.isNotEmpty()) put("ct", contentType)
                    if (length > 0) put("len", length)
                },
            ).encodeToByteArray()

    companion object {
        /** Parses a REQ head; null when it is not what §3 describes (the device answers 400). */
        fun decode(bytes: ByteArray): RequestHead? =
            runCatching {
                val o = json.parseToJsonElement(bytes.decodeToString()).jsonObject
                RequestHead(
                    method = (o["m"] as JsonPrimitive).content,
                    path = (o["p"] as JsonPrimitive).content,
                    contentType = (o["ct"] as? JsonPrimitive)?.contentOrNull ?: "",
                    length = (o["len"] as? JsonPrimitive)?.longOrNull ?: 0,
                )
            }.getOrNull()
    }
}

/**
 * The RSP head (§3, §5): `{"s":200,"ct":"application/json","len":142}`, or for errors the tunnel
 * itself generates `{"s":429,"err":"busy"}`. Route errors (404, 409, ...) come with `ct`/`len`
 * like any response.
 */
data class ResponseHead(
    val status: Int,
    val contentType: String = "",
    /** Upstream Content-Length; -1 when the route streams a chunked response. Advisory (§3). */
    val length: Long = -1,
    /** Set only on tunnel-generated errors (§5). */
    val error: String? = null,
) {
    val tunnelError: TunnelError? get() = error?.let(TunnelError::of)

    /** Same key order as the firmware's `bleh_rsp_head`. */
    fun encode(): ByteArray =
        json
            .encodeToString(
                JsonObject.serializer(),
                buildJsonObject {
                    put("s", status)
                    if (error != null) {
                        put("err", error)
                    } else {
                        put("ct", contentType)
                        put("len", length)
                    }
                },
            ).encodeToByteArray()

    companion object {
        fun decode(bytes: ByteArray): ResponseHead {
            val o =
                runCatching { json.parseToJsonElement(bytes.decodeToString()).jsonObject }
                    .getOrElse { throw BleHttpException.Protocol("RSP head is not a JSON object", it) }
            val status =
                (o["s"] as? JsonPrimitive)?.let { runCatching { it.int }.getOrNull() }
                    ?: throw BleHttpException.Protocol("RSP head has no numeric \"s\"")
            val error = (o["err"] as? JsonPrimitive)?.contentOrNull
            return ResponseHead(
                status = status,
                contentType = (o["ct"] as? JsonPrimitive)?.contentOrNull ?: "",
                // An error head has no body, so its absent len means "nothing follows", not "unknown".
                length = (o["len"] as? JsonPrimitive)?.longOrNull ?: if (error != null) 0 else -1,
                error = error,
            )
        }
    }
}

/** The `err` codes of responses the tunnel generates itself (§5). */
enum class TunnelError(
    val code: String,
    val status: Int,
) {
    BAD_REQUEST("bad_request", 400),
    METHOD("method", 400),
    PATH("path", 403),
    LEN("len", 400),
    SHORT("short", 400),

    /** A REQ_BODY counter gap: an upload PDU never reached the device. Repeat the request. */
    HOLE("hole", 400),

    /** Another request is still in flight on the device (uploading or streaming). Back off. */
    BUSY("busy", 429),
    ABORTED("aborted", 499),
    UPSTREAM("upstream", 502),

    /** No REQ_BODY frame for 30 s during an upload. */
    TIMEOUT("timeout", 504),
    ;

    companion object {
        fun of(code: String): TunnelError? = entries.firstOrNull { it.code == code }
    }
}
