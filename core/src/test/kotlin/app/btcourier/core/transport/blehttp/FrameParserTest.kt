package app.btcourier.core.transport.blehttp

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** §2 / §7: reassemble by `len`, never by unit boundaries; resync byte by byte on garbage. */
class FrameParserTest {
    private val a = Frame.responseBody(3, 0, last = false, patterned(600))
    private val b = Frame.responseBody(3, 1, last = true)
    private val c = Frame.credit(3, 4096)

    @Test
    fun `a frame fed one byte at a time comes out once`() {
        val parser = FrameParser()
        val out = a.encode().flatMap { parser.feed(byteArrayOf(it)) }
        assertEquals(listOf(a), out)
    }

    @Test
    fun `one unit may hold the tail of one frame and the head of the next`() {
        val stream = a.encode() + b.encode() + c.encode()
        val parser = FrameParser()
        val cut1 = a.encode().size - 5
        val cut2 = a.encode().size + b.encode().size + 3
        val out = parser.feed(stream.copyOfRange(0, cut1)) +
            parser.feed(stream.copyOfRange(cut1, cut2)) +
            parser.feed(stream.copyOfRange(cut2, stream.size))
        assertEquals(listOf(a, b, c), out)
    }

    @Test
    fun `garbage before a frame is skipped byte by byte`() {
        val parser = FrameParser()
        // A stray magic + version with an unknown type, and one announcing len > 4096.
        val garbage = hex("00 FF 57 02 09 00 00 00") + hex("57 02 04 00 00 00 01 10")
        val out = parser.feed(garbage + b.encode())
        assertEquals(listOf(b), out)
        assertEquals(garbage.size.toLong(), parser.resyncedBytes)
    }

    @Test
    fun `a wrong protocol version is not a header`() {
        val parser = FrameParser()
        val v1 = b.encode().also { it[1] = 0x01 }
        assertEquals(listOf(b), parser.feed(v1 + b.encode()))
    }
}
