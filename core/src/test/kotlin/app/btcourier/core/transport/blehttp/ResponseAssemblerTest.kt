package app.btcourier.core.transport.blehttp

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class ResponseAssemblerTest {
    private fun started(
        length: Long,
        seq: Int = 5,
        step: Int = BleHttpWire.CLIENT_CREDIT_STEP,
    ) = ResponseAssembler(seq, step).also {
        it.accept(Frame.responseHead(seq, ResponseHead(200, "application/octet-stream", length)))
    }

    @Test
    fun `a counter gap is a lost frame`() {
        val a = started(2000)
        a.accept(Frame.responseBody(5, 0, last = false, ByteArray(482)))
        val e =
            assertThrows<BleHttpException.FrameLost> {
                a.accept(
                    Frame.responseBody(5, 2, last = false, ByteArray(482)),
                )
            }
        assertEquals(1, e.expectedCounter)
        assertEquals(2, e.actualCounter)
        assertEquals(482L, e.bytesReceived)
    }

    @Test
    fun `the counter covers the LAST frame too`() {
        val a = started(964)
        a.accept(Frame.responseBody(5, 0, last = false, ByteArray(482)))
        a.accept(Frame.responseBody(5, 1, last = false, ByteArray(482)))
        assertThrows<BleHttpException.FrameLost> { a.accept(Frame.responseBody(5, 3, last = true)) }
    }

    @Test
    fun `counters wrap modulo 16`() {
        val a = started(40L * 100)
        repeat(40) { n -> a.accept(Frame.responseBody(5, n and 15, last = false, ByteArray(100))) }
        assertTrue(a.accept(Frame.responseBody(5, 40 and 15, last = true)) is ResponseAssembler.Event.End)
    }

    @Test
    fun `LAST before len bytes is a truncation`() {
        val a = started(1000)
        a.accept(Frame.responseBody(5, 0, last = false, ByteArray(482)))
        val e = assertThrows<BleHttpException.Truncated> { a.accept(Frame.responseBody(5, 1, last = true)) }
        assertEquals(1000L, e.expectedBytes)
        assertEquals(482L, e.receivedBytes)
    }

    @Test
    fun `a chunked response ends at LAST whatever its size`() {
        val a = started(-1)
        a.accept(Frame.responseBody(5, 0, last = false, ByteArray(10)))
        assertTrue(a.accept(Frame.responseBody(5, 1, last = true)) is ResponseAssembler.Event.End)
    }

    @Test
    fun `a payload on the LAST frame is part of the body`() {
        val a = started(12)
        a.accept(Frame.responseBody(5, 0, last = false, patterned(8)))
        val end = a.accept(Frame.responseBody(5, 1, last = true, patterned(4))) as ResponseAssembler.Event.End
        assertArrayEquals(patterned(4), end.tail)
        assertEquals(12L, a.received)
    }

    @Test
    fun `frames of other exchanges, device credits and anything after LAST are ignored`() {
        val a = started(0)
        assertSame(ResponseAssembler.Event.Ignored, a.accept(Frame.responseBody(4, 0, last = false, ByteArray(10))))
        assertSame(ResponseAssembler.Event.Ignored, a.accept(Frame.credit(5, 8192)))
        a.accept(Frame.responseBody(5, 0, last = true))
        assertSame(ResponseAssembler.Event.Ignored, a.accept(Frame.responseBody(5, 1, last = true)))
    }

    @Test
    fun `a body before its head is a protocol error`() {
        val a = ResponseAssembler(seq = 5)
        assertThrows<BleHttpException.Protocol> { a.accept(Frame.responseBody(5, 0, last = true)) }
    }

    @Test
    fun `a tunnel error head is followed by an empty LAST and is not a truncation`() {
        val a = ResponseAssembler(seq = 5)
        val head = a.accept(Frame.responseHead(5, ResponseHead(429, error = "busy"))) as ResponseAssembler.Event.Head
        assertEquals(TunnelError.BUSY, head.head.tunnelError)
        assertTrue(a.accept(Frame.responseBody(5, 0, last = true)) is ResponseAssembler.Event.End)
    }

    @Test
    fun `credits are cumulative, at least every step, and never after LAST`() {
        val a = started(20_000, step = 8192)
        val credits = mutableListOf<Long>()
        var counter = 0
        var sent = 0
        while (sent + 482 <= 20_000) {
            a.accept(Frame.responseBody(5, counter++, last = false, ByteArray(482)))
            sent += 482
            a.creditDue()?.let(credits::add)
        }
        a.accept(Frame.responseBody(5, counter++, last = false, ByteArray(20_000 - sent)))
        a.accept(Frame.responseBody(5, counter, last = true))
        assertNull(a.creditDue())
        // 17 x 482 = 8194 is the first count past 8192.
        assertEquals(listOf(8194L, 16388L), credits)
    }

    @Test
    fun `a credit step above 8 KB breaks the device's rule and is refused`() {
        assertThrows<IllegalArgumentException> { ResponseAssembler(1, creditStep = 8193) }
    }
}
