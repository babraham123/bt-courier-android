package app.btcourier.fakes

import app.btcourier.core.transport.blehttp.BleHttpWire
import app.btcourier.core.transport.blehttp.Frame
import app.btcourier.core.transport.blehttp.FrameType
import app.btcourier.core.transport.blehttp.RequestHead
import app.btcourier.core.transport.blehttp.ResponseHead
import app.btcourier.core.transport.blehttp.TunnelError
import app.btcourier.fakes.ScriptedPeripheral.Reply
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.seconds

/** The fake device itself, driven with raw frames, against BLE_HTTP_PROTOCOL.md §3-§5 and §8. */
class ScriptedPeripheralTest {
    private suspend fun ScriptedPeripheral.send(frame: Frame) =
        BleHttpWire.attUnits(frame.encode(), link.mtu).forEach { link.write(it, withResponse = false) }

    private fun ScriptedPeripheral.bodyFrames(seq: Int) =
        delivered.filter { it.type == FrameType.RSP_BODY && it.seq == seq }

    @Test
    fun `without credits it stops at the 16 KB window and closes with LAST after 30 s`() = runTest {
        val peripheral = ScriptedPeripheral(mapOf("/api/f" to Reply(200, patterned(65536))), scope = backgroundScope)

        peripheral.send(Frame.requestHead(1, RequestHead("GET", "/api/f")))

        // 33 x 482 = 15906; a 34th frame would leave 16388 > 16384 uncredited.
        assertEquals(33, peripheral.bodyFrames(1).size)
        delay(29.seconds)
        assertEquals(33, peripheral.bodyFrames(1).size)
        delay(2.seconds)
        val last = peripheral.bodyFrames(1).last()
        assertTrue(last.isLast)
        assertEquals(0x11, last.flags, "counter 33 mod 16 = 1, LAST")
        assertEquals(1, peripheral.creditTimeouts)
    }

    @Test
    fun `a credit reopens the window by the bytes it acknowledges`() = runTest {
        val peripheral = ScriptedPeripheral(mapOf("/api/f" to Reply(200, patterned(65536))))
        peripheral.send(Frame.requestHead(1, RequestHead("GET", "/api/f")))

        peripheral.send(Frame.credit(1, 4338))

        assertEquals(33 + 9, peripheral.bodyFrames(1).size)
        // A credit beyond what was sent is ignored (§4b).
        peripheral.send(Frame.credit(1, 1_000_000))
        assertEquals(33 + 9, peripheral.bodyFrames(1).size)
        assertEquals(listOf(4338L), peripheral.credits)
    }

    @Test
    fun `a second REQ while one is in flight is answered 429 busy and the first continues`() = runTest {
        val peripheral = ScriptedPeripheral(mapOf("/api/f" to Reply(200, patterned(65536))))
        peripheral.send(Frame.requestHead(1, RequestHead("GET", "/api/f")))

        peripheral.send(Frame.requestHead(2, RequestHead("GET", "/api/status")))

        val head = peripheral.delivered.single { it.type == FrameType.RSP && it.seq == 2 }
        assertEquals(429 to TunnelError.BUSY, ResponseHead.decode(head.payload).let { it.status to it.tunnelError })
        assertEquals(listOf(Frame.responseBody(2, 0, last = true)), peripheral.bodyFrames(2))
        peripheral.send(Frame.credit(1, 4338))
        assertEquals(33 + 9, peripheral.bodyFrames(1).size)
    }

    @Test
    fun `a REQ_BODY counter gap is answered 400 hole`() = runTest {
        val peripheral = ScriptedPeripheral(mapOf("/api/up" to Reply(200, "{}")))
        peripheral.send(Frame.requestHead(1, RequestHead("POST", "/api/up", "application/octet-stream", 8192)))

        peripheral.send(Frame.requestBody(1, 0, last = false, ByteArray(4096)))
        peripheral.send(Frame.requestBody(1, 2, last = true, ByteArray(4096)))

        val head = peripheral.delivered.single { it.type == FrameType.RSP }
        assertEquals(400 to TunnelError.HOLE, ResponseHead.decode(head.payload).let { it.status to it.tunnelError })
        assertTrue(peripheral.requests.isEmpty())
    }

    @Test
    fun `heads longer than one ATT unit are split across notifications`() = runTest {
        val peripheral = ScriptedPeripheral(mapOf("/api/s" to Reply(200, "{}", "application/json")), mtu = 23)

        peripheral.send(Frame.requestHead(1, RequestHead("GET", "/api/s")))
        peripheral.link.drop()
        val pdus = peripheral.link.notifications.toList()

        assertTrue(pdus.all { it.size <= 20 })
        assertTrue(pdus.size > peripheral.delivered.size, "the head took several PDUs")
        assertArrayEquals(
            peripheral.delivered.map { it.encode() }.reduce(ByteArray::plus),
            pdus.reduce(ByteArray::plus),
        )
    }
}
