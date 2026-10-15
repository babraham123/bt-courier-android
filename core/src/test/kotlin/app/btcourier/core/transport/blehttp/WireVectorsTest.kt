package app.btcourier.core.transport.blehttp

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The byte-exact worked examples of BLE_HTTP_PROTOCOL.md §6, copied verbatim.
 *
 * Erratum: §6 prints the REQ and RSP head headers with `len` bytes `1C 00` (28) and `2E 00` (46),
 * but the JSON shown next to them is 29 and 44 bytes. The firmware sets `len` from the payload it
 * sends (`bleh_hdr_build` with the `snprintf` length of `bleh_rsp_head`), so these tests take the
 * doc's first six header bytes and payloads verbatim and expect `len` to be the payload size.
 */
class WireVectorsTest {
    // §6 "GET /api/status (seq 1, FFF3 on indications: 4096 B frames)"
    private val docReqHeader = hex("57 02 01 00 01 00 1C 00")
    private val docReqPayload = """{"m":"GET","p":"/api/status"}"""
    private val docRspHeader = hex("57 02 03 00 01 00 2E 00")
    private val docRspPayload = """{"s":200,"ct":"application/json","len":1093}"""
    private val docBodyHeader = hex("57 02 04 00 01 00 45 04")
    private val docLastHeader = hex("57 02 04 11 01 00 00 00")
    private val statusBody = patterned(1093)

    @Test
    fun `GET status REQ encodes to the section 6 bytes`() {
        val encoded = Frame.requestHead(1, RequestHead("GET", "/api/status")).encode()

        assertArrayEquals(docReqHeader.copyOfRange(0, 6), encoded.copyOfRange(0, 6))
        assertArrayEquals(le16(docReqPayload.length), encoded.copyOfRange(6, 8))
        assertEquals(docReqPayload, encoded.copyOfRange(8, encoded.size).decodeToString())
    }

    @Test
    fun `GET status RSP head decodes from the section 6 bytes and encodes back`() {
        val wire = docRspHeader.copyOfRange(0, 6) + le16(docRspPayload.length) + docRspPayload.encodeToByteArray()

        val frame = FrameParser().feed(wire).single()
        assertEquals(FrameType.RSP, frame.type)
        assertEquals(0, frame.flags)
        assertEquals(1, frame.seq)
        assertEquals(ResponseHead(200, "application/json", 1093), ResponseHead.decode(frame.payload))
        assertArrayEquals(wire, Frame.responseHead(1, ResponseHead(200, "application/json", 1093)).encode())
    }

    @Test
    fun `indication body frame and empty LAST match the section 6 headers both ways`() {
        assertArrayEquals(docBodyHeader, Frame.responseBody(1, 0, last = false, statusBody).encode().copyOfRange(0, 8))
        assertArrayEquals(docLastHeader, Frame.responseBody(1, 1, last = true).encode())

        val frames = FrameParser().feed(docBodyHeader + statusBody + docLastHeader)
        assertEquals(2, frames.size)
        with(frames[0]) {
            assertEquals(FrameType.RSP_BODY, type)
            assertEquals(1, seq)
            assertEquals(0, counter)
            assertFalse(isLast)
            assertArrayEquals(statusBody, payload)
        }
        with(frames[1]) {
            assertEquals(FrameType.RSP_BODY, type)
            assertEquals(1, counter)
            assertTrue(isLast)
            assertEquals(0, payload.size)
        }
    }

    @Test
    fun `GET status on indications reassembles from 490-byte PDUs without credits`() {
        val rsp = docRspHeader.copyOfRange(0, 6) + le16(docRspPayload.length) + docRspPayload.encodeToByteArray()
        val stream = rsp + docBodyHeader + statusBody + docLastHeader
        val parser = FrameParser()
        val assembler = ResponseAssembler(seq = 1)
        val body = mutableListOf<Byte>()
        var ended = false

        for (pdu in BleHttpWire.attUnits(stream, mtu = 517)) {
            assertTrue(pdu.size <= 490)
            for (frame in parser.feed(pdu)) {
                when (val e = assembler.accept(frame)) {
                    is ResponseAssembler.Event.Body -> body += e.bytes.toList()
                    is ResponseAssembler.Event.End -> ended = true
                    else -> Unit
                }
                assertNull(assembler.creditDue())
            }
        }
        assertTrue(ended)
        assertArrayEquals(statusBody, body.toByteArray())
    }

    @Test
    fun `GET status on notifications at MTU 517 is three frames 482 482 129 and owes no credit`() {
        assertEquals(482, BleHttpWire.notifyPayloadMax(517))
        val sizes = listOf(482, 482, 129)
        val docFlags = listOf(0x00, 0x10, 0x20)
        val assembler = ResponseAssembler(seq = 1)
        assembler.accept(Frame.responseHead(1, ResponseHead(200, "application/json", 1093)))

        var offset = 0
        sizes.forEachIndexed { n, size ->
            val frame = Frame.responseBody(1, n, last = false, statusBody.copyOfRange(offset, offset + size))
            assertEquals(docFlags[n], frame.flags)
            assertTrue(frame.encode().size <= BleHttpWire.attUnit(517))
            assembler.accept(frame)
            assertNull(assembler.creditDue(), "1093 B < the 4 KB credit step: no CREDIT goes back")
            offset += size
        }
        val last = Frame.responseBody(1, 3, last = true)
        assertEquals(0x31, last.flags)
        assertTrue(assembler.accept(last) is ResponseAssembler.Event.End)
        assertEquals(1093L, assembler.received)
    }

    @Test
    fun `64 KB raw upload is the section 6 head and 16 REQ_BODY frames of 4096`() {
        val frames = BleHttpWire.requestFrames(
            seq = 7,
            method = "POST",
            path = "/api/fs/upload?path=/data/app/cfg.bin",
            body = patterned(65536),
            contentType = "application/octet-stream",
        )

        assertEquals(
            """{"m":"POST","p":"/api/fs/upload?path=/data/app/cfg.bin","ct":"application/octet-stream","len":65536}""",
            frames[0].payload.decodeToString(),
        )
        val body = frames.drop(1)
        assertEquals(16, body.size)
        body.forEachIndexed { n, f ->
            assertEquals(FrameType.REQ_BODY, f.type)
            assertEquals(7, f.seq)
            assertEquals(4096, f.payload.size)
            assertEquals(n and 15, f.counter)
            assertEquals(n == 15, f.isLast)
        }
    }

    @Test
    fun `device upload credits 8192 to 57344 are u32 LE CREDIT frames`() {
        val credit = Frame.credit(7, 8192)
        assertArrayEquals(hex("57 02 06 00 07 00 04 00 00 20 00 00"), credit.encode())
        val parsed = FrameParser().feed((8192L..57344L step 8192).flatMap { Frame.credit(7, it).encode().toList() }.toByteArray())
        assertEquals((8192L..57344L step 8192).toList(), parsed.map { it.creditValue })
    }

    @Test
    fun `64 KB notify download is 136 frames with counters cycling and credits per section 7`() {
        val size = 65536
        val frameMax = BleHttpWire.notifyPayloadMax(517)
        val assembler = ResponseAssembler(seq = 2)
        assembler.accept(Frame.responseHead(2, ResponseHead(200, "application/octet-stream", size.toLong())))

        val credits = mutableListOf<Long>()
        var frames = 0
        var offset = 0
        while (offset < size) {
            val n = minOf(frameMax, size - offset)
            assembler.accept(Frame.responseBody(2, frames, last = false, ByteArray(n)))
            assembler.creditDue()?.let(credits::add)
            offset += n
            frames++
        }
        assertEquals(136, frames)
        val last = Frame.responseBody(2, frames, last = true)
        assertEquals(0x81, last.flags)
        assertTrue(assembler.accept(last) is ResponseAssembler.Event.End)

        // §7 pays `len(out)` whenever 4096 B arrived since the last payment: every 9th 482-byte
        // frame (9 x 482 = 4338), 15 times within 136 frames.
        assertEquals((1..15).map { 4338L * it }, credits)
    }

    @Test
    fun `other section 6 request heads encode verbatim`() {
        val cases = listOf(
            RequestHead("GET", "/api/fs/list?path=/data") to """{"m":"GET","p":"/api/fs/list?path=/data"}""",
            RequestHead("GET", "/api/fs/info?path=/sd") to """{"m":"GET","p":"/api/fs/info?path=/sd"}""",
            RequestHead("POST", "/api/fs/mkdir?path=/data/app") to """{"m":"POST","p":"/api/fs/mkdir?path=/data/app"}""",
            RequestHead("GET", "/api/fs/download?path=/data/app/cfg.bin") to
                """{"m":"GET","p":"/api/fs/download?path=/data/app/cfg.bin"}""",
            RequestHead("DELETE", "/api/fs/file?path=/data/app/cfg.bin") to
                """{"m":"DELETE","p":"/api/fs/file?path=/data/app/cfg.bin"}""",
            RequestHead("GET", "/api/settings/wifi_manager") to """{"m":"GET","p":"/api/settings/wifi_manager"}""",
            RequestHead("POST", "/api/settings/submit") to """{"m":"POST","p":"/api/settings/submit"}""",
            RequestHead("GET", "/api/uds") to """{"m":"GET","p":"/api/uds"}""",
            RequestHead("POST", "/api/uds/session", "application/json", """{"action":"end"}""".length.toLong()) to
                """{"m":"POST","p":"/api/uds/session","ct":"application/json","len":16}""",
        )
        for ((head, expected) in cases) {
            assertEquals(expected, head.encode().decodeToString())
            assertEquals(head, RequestHead.decode(expected.encodeToByteArray()))
        }
    }

    @Test
    fun `section 3 and 5 response heads decode`() {
        assertEquals(
            ResponseHead(200, "application/json", 142),
            ResponseHead.decode("""{"s":200,"ct":"application/json","len":142}""".encodeToByteArray()),
        )
        val busy = ResponseHead.decode("""{"s":429,"err":"busy"}""".encodeToByteArray())
        assertEquals(429, busy.status)
        assertEquals(TunnelError.BUSY, busy.tunnelError)
        assertEquals(0L, busy.length)
        assertEquals("""{"s":429,"err":"busy"}""", ResponseHead(429, error = "busy").encode().decodeToString())
    }

    @Test
    fun `frame sizes follow the MTU`() {
        assertEquals(490, BleHttpWire.attUnit(517))
        assertEquals(182, BleHttpWire.attUnit(185))
        assertEquals(20, BleHttpWire.attUnit(23))
        assertEquals(482, BleHttpWire.notifyPayloadMax(517))
        assertEquals(482, BleHttpWire.notifyPayloadMax(600))
        assertEquals(174, BleHttpWire.notifyPayloadMax(185))
        assertEquals(12, BleHttpWire.notifyPayloadMax(23))
    }
}
