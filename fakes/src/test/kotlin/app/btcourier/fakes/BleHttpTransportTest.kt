package app.btcourier.fakes

import app.btcourier.core.transport.blehttp.BleHttpException
import app.btcourier.core.transport.blehttp.BleHttpTransport
import app.btcourier.core.transport.blehttp.BleHttpWire
import app.btcourier.core.transport.blehttp.Frame
import app.btcourier.core.transport.blehttp.FrameParser
import app.btcourier.core.transport.blehttp.FrameType
import app.btcourier.core.transport.blehttp.TunnelError
import app.btcourier.core.transport.blehttp.tunnelError
import app.btcourier.fakes.ScriptedPeripheral.Fault
import app.btcourier.fakes.ScriptedPeripheral.Reply
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** [BleHttpTransport] against [ScriptedPeripheral]; expected values come from BLE_HTTP_PROTOCOL.md. */
class BleHttpTransportTest {
    private val status = patterned(1093)
    private val file64k = patterned(65536)

    private fun TestScope.connect(
        routes: Map<String, Reply>,
        mtu: Int = 517,
        idleTimeout: Duration = BleHttpTransport.DEFAULT_RESPONSE_IDLE_TIMEOUT,
        writeDelay: Duration = Duration.ZERO,
    ): Pair<ScriptedPeripheral, BleHttpTransport> {
        val peripheral = ScriptedPeripheral({ routes[it.path] }, mtu, scope = backgroundScope, writeDelay = writeDelay)
        return peripheral to BleHttpTransport(peripheral.link, backgroundScope, idleTimeout)
    }

    /** The frames the app wrote, reassembled from its ATT writes. */
    private fun ScriptedPeripheral.appFrames(): List<Frame> {
        val parser = FrameParser()
        return link.writes.flatMap { parser.feed(it) }
    }

    /** Credits the app sent; the device ignores those arriving after it finished the response. */
    private fun ScriptedPeripheral.appCredits(): List<Long> =
        appFrames().filter { it.type == FrameType.CREDIT }.map { it.creditValue }

    @Test
    fun `GET status on notifications - section 6 frames and no credit owed`() =
        runTest {
            val (peripheral, transport) = connect(mapOf("/api/status" to Reply(200, status)))

            val response = transport.request("GET", "/api/status")

            assertEquals(200, response.status)
            assertEquals("application/json", response.headers["content-type"])
            assertEquals("1093", response.headers["content-length"])
            assertArrayEquals(status, response.body)
            val body = peripheral.delivered.filter { it.type == FrameType.RSP_BODY }
            assertEquals(listOf(482, 482, 129, 0), body.map { it.payload.size })
            assertEquals(listOf(0x00, 0x10, 0x20, 0x31), body.map { it.flags })
            // Only the REQ went out: 1093 B is below the 4 KB credit step.
            assertEquals(1, peripheral.link.writes.size)
            assertEquals(
                """{"m":"GET","p":"/api/status"}""",
                peripheral
                    .appFrames()
                    .single()
                    .payload
                    .decodeToString(),
            )
        }

    @Test
    fun `64 KB download on notifications - 136 frames, credits every 4 KB consumed, window held`() =
        runTest {
            val path = "/api/fs/download?path=/data/app/cfg.bin"
            val (peripheral, transport) = connect(mapOf(path to Reply(200, file64k, "application/octet-stream")))

            val response = transport.request("GET", path)

            assertArrayEquals(file64k, response.body)
            val body = peripheral.delivered.filter { it.type == FrameType.RSP_BODY }
            assertEquals(137, body.size, "136 RSP_BODY frames + LAST")
            assertEquals((0 until 137).map { it and 15 }, body.map { it.counter })
            assertEquals(0x81, body.last().flags)
            assertEquals((1..15).map { 4338L * it }, peripheral.appCredits())
            assertTrue(peripheral.maxUncredited <= BleHttpWire.DOWNLOAD_WINDOW)
            assertEquals(0, peripheral.creditTimeouts)
            // CREDIT goes out write-without-response (§4b), the REQ with response (§4).
            val modes = peripheral.link.writesWithResponse
            assertTrue(modes.first())
            assertTrue(modes.drop(1).none { it })
        }

    @Test
    fun `a large body at a small MTU crosses many credit windows`() =
        runTest {
            val big = patterned(300_000)
            val (peripheral, transport) =
                connect(
                    mapOf("/api/big" to Reply(200, big, "application/octet-stream")),
                    mtu = 185,
                )

            val response = transport.request("GET", "/api/big")

            assertArrayEquals(big, response.body)
            assertTrue(peripheral.stalls > 0, "the device filled its window repeatedly")
            assertTrue(peripheral.maxUncredited <= BleHttpWire.DOWNLOAD_WINDOW)
            val frame = BleHttpWire.notifyPayloadMax(185)
            val credits = peripheral.appCredits()
            val gaps = credits.zipWithNext { a, b -> b - a }
            assertTrue(gaps.all { it in 4096L until 4096L + frame }, "credits every 4 KB: $gaps")
            assertTrue(credits.last() > big.size - 4096 - frame)
        }

    @Test
    fun `a slow consumer still credits in time because it pays per chunk consumed`() =
        runTest {
            val path = "/api/fs/download?path=/data/app/cfg.bin"
            val (peripheral, transport) = connect(mapOf(path to Reply(200, file64k, "application/octet-stream")))

            val response = transport.stream("GET", path)
            // 9 chunks per credit x 2 s = 18 s between credits, inside the 30 s budget.
            val chunks = mutableListOf<ByteArray>()
            response.body.collect {
                chunks += it
                delay(2.seconds)
            }

            assertArrayEquals(file64k, chunks.reduce(ByteArray::plus))
            assertEquals(0, peripheral.creditTimeouts)
        }

    @Test
    fun `a consumer stalled past 30 s gets the device's early LAST as a truncation`() =
        runTest {
            val path = "/api/fs/download?path=/data/app/cfg.bin"
            val (peripheral, transport) = connect(mapOf(path to Reply(200, file64k, "application/octet-stream")))

            val response = transport.stream("GET", path)
            var first = true
            val e =
                assertThrows<BleHttpException.Truncated> {
                    response.body.collect {
                        if (first) delay(31.seconds)
                        first = false
                    }
                }

            assertEquals(1, peripheral.creditTimeouts)
            assertEquals(65536L, e.expectedBytes)
            // 33 x 482 = 15906: one more frame would pass 16384 uncredited.
            assertEquals(15906L, e.receivedBytes)
        }

    @Test
    fun `a lost frame fails the request, aborts it on the device and frees the tunnel`() =
        runTest {
            val (peripheral, transport) =
                connect(
                    mapOf(
                        "/api/fs/download?path=/sd/a" to Reply(200, patterned(10_000)),
                        "/api/status" to Reply(200, status),
                    ),
                )
            peripheral.inject(Fault.DropFrame(5))

            val e = assertThrows<BleHttpException.FrameLost> { transport.request("GET", "/api/fs/download?path=/sd/a") }

            assertEquals(5, e.expectedCounter)
            assertEquals(6, e.actualCounter)
            assertEquals(5L * 482, e.bytesReceived)
            assertEquals(Frame.abort(1), peripheral.appFrames().last())
            // The next request is answered, not refused 429 busy.
            assertArrayEquals(status, transport.request("GET", "/api/status").body)
        }

    @Test
    fun `link loss mid-body is a typed error and the link stays lost`() =
        runTest {
            val (peripheral, transport) = connect(mapOf("/api/fs/download?path=/sd/a" to Reply(200, patterned(20_000))))
            peripheral.inject(Fault.LinkLossAfter(5000))

            val response = transport.stream("GET", "/api/fs/download?path=/sd/a")
            var received = 0
            assertThrows<BleHttpException.LinkLost> { response.body.collect { received += it.size } }

            // The transport may notice the drop on its next CREDIT write, before draining what was in flight.
            assertTrue(received in 1 until 20_000, "partial body before the drop: $received")
            assertTrue(peripheral.link.closed)
            assertThrows<BleHttpException.LinkLost> { transport.request("GET", "/api/status") }
        }

    @Test
    fun `busy is returned as 429 with the tunnel error, and the next request goes through`() =
        runTest {
            val (peripheral, transport) = connect(mapOf("/api/status" to Reply(200, status)))
            peripheral.inject(Fault.Busy)

            val busy = transport.request("GET", "/api/status")
            assertEquals(429, busy.status)
            assertEquals(TunnelError.BUSY, busy.tunnelError)
            assertEquals(0, busy.body.size)

            val ok = transport.request("GET", "/api/status")
            assertEquals(200, ok.status)
            assertNull(ok.tunnelError)
        }

    @Test
    fun `route errors pass through unchanged`() =
        runTest {
            val (_, transport) = connect(emptyMap())

            val response = transport.request("GET", "/api/nope")

            assertEquals(404, response.status)
            assertNull(response.tunnelError)
            assertEquals("""{"error":"not found"}""", response.body.decodeToString())
        }

    @Test
    fun `concurrent calls wait their turn instead of drawing 429`() =
        runTest {
            val (peripheral, transport) =
                connect(
                    mapOf(
                        "/api/a" to Reply(200, patterned(30_000)),
                        "/api/b" to Reply(200, status),
                        "/api/c" to Reply(200, "{}"),
                    ),
                )

            val responses =
                listOf(
                    "/api/a",
                    "/api/b",
                    "/api/c",
                ).map { path -> async { transport.request("GET", path) } }.awaitAll()

            assertEquals(listOf(200, 200, 200), responses.map { it.status })
            assertEquals(listOf("/api/a", "/api/b", "/api/c"), peripheral.requests.map { it.path })
            assertTrue(
                peripheral.delivered.none { it.type == FrameType.RSP && it.payload.decodeToString().contains("busy") },
            )
        }

    @Test
    fun `64 KB raw upload - section 6 head, 16 body frames, device credits every 8 KB`() =
        runTest {
            val path = "/api/fs/upload?path=/data/app/cfg.bin"
            val (peripheral, transport) =
                connect(
                    mapOf(path to Reply(200, """{"ok":true,"size":65536,"path":"/data/app/cfg.bin"}""")),
                )

            val response = transport.request("POST", path, file64k, mapOf("Content-Type" to "application/octet-stream"))

            assertEquals(200, response.status)
            val received = peripheral.requests.single()
            assertEquals("POST", received.method)
            assertEquals("application/octet-stream", received.contentType)
            assertArrayEquals(file64k, received.body)
            val frames = peripheral.appFrames()
            assertEquals(
                """{"m":"POST","p":"/api/fs/upload?path=/data/app/cfg.bin","ct":"application/octet-stream","len":65536}""",
                frames.first().payload.decodeToString(),
            )
            val body = frames.filter { it.type == FrameType.REQ_BODY }
            assertEquals(16, body.size)
            assertEquals((0 until 16).map { (it shl 4) or (if (it == 15) 1 else 0) }, body.map { it.flags })
            assertTrue(peripheral.link.writes.all { it.size <= 490 })
            assertEquals(
                (8192L..57344L step 8192).toList(),
                peripheral.delivered.filter { it.type == FrameType.CREDIT }.map { it.creditValue },
            )
        }

    @Test
    fun `a request refused at its head stops the upload at a frame boundary`() =
        runTest {
            val (peripheral, transport) = connect(emptyMap())
            val tooBig = ByteArray((BleHttpWire.MAX_REQUEST_BODY + 1).toInt())

            val response = transport.request("PUT", "/api/ota/upload", tooBig)

            assertEquals(400, response.status)
            assertEquals(TunnelError.LEN, response.tunnelError)
            val sentFrames = peripheral.appFrames().count { it.type == FrameType.REQ_BODY }
            assertTrue(sentFrames < 3, "upload kept going after the refusal: $sentFrames frames")
        }

    @Test
    fun `cancelling a streamed body aborts the response and frees the tunnel`() =
        runTest {
            val path = "/api/fs/download?path=/data/app/cfg.bin"
            val (peripheral, transport) =
                connect(
                    mapOf(path to Reply(200, file64k), "/api/status" to Reply(200, status)),
                )

            val head =
                transport
                    .stream("GET", path)
                    .body
                    .take(2)
                    .toList()

            assertEquals(2, head.size)
            assertEquals(Frame.abort(1), peripheral.appFrames().last())
            assertArrayEquals(status, transport.request("GET", "/api/status").body)
        }

    @Test
    fun `a chunked streamed response ends at LAST`() =
        runTest {
            val logs = patterned(10_000)
            val (_, transport) =
                connect(
                    mapOf("/api/logger/export" to Reply(200, logs, "application/x-ndjson", chunked = true)),
                )

            val response = transport.stream("GET", "/api/logger/export")

            assertFalse(response.headers.containsKey("content-length"))
            assertArrayEquals(logs, response.body.toList().reduce(ByteArray::plus))
        }

    @Test
    fun `a streamed body can be collected once`() =
        runTest {
            val (_, transport) = connect(mapOf("/api/status" to Reply(200, status)))
            val response = transport.stream("GET", "/api/status")
            response.body.toList()
            assertThrows<IllegalStateException> { response.body.toList() }
        }

    @Test
    fun `a route that never answers times out after the idle limit, is aborted and frees the tunnel`() =
        runTest {
            val (peripheral, transport) = connect(mapOf("/api/status" to Reply(200, status)), idleTimeout = 10.seconds)
            peripheral.inject(Fault.Hang)

            val e = assertThrows<BleHttpException.ResponseTimeout> { transport.request("GET", "/api/status") }

            assertEquals(10_000, currentTime)
            assertEquals(0L, e.bytesReceived)
            assertEquals(Frame.abort(1), peripheral.appFrames().last())
            assertArrayEquals(status, transport.request("GET", "/api/status").body)
        }

    @Test
    fun `a body that stops mid-way times out once the device is silent for the idle limit`() =
        runTest {
            val path = "/api/fs/download?path=/sd/a"
            val (peripheral, transport) =
                connect(
                    mapOf(
                        path to Reply(200, patterned(20_000)),
                        "/api/status" to Reply(200, status),
                    ),
                    idleTimeout = 10.seconds,
                )
            peripheral.inject(Fault.StallAfter(3000))

            val e = assertThrows<BleHttpException.ResponseTimeout> { transport.request("GET", path) }

            assertEquals(10_000, currentTime)
            // The route stops after the first frame past 3000 bytes: 7 frames of 482.
            assertEquals(7L * 482, e.bytesReceived)
            assertEquals(Frame.abort(1), peripheral.appFrames().last())
            assertArrayEquals(status, transport.request("GET", "/api/status").body)
        }

    @Test
    fun `the idle clock does not run while the request body is still going out`() =
        runTest {
            // 4000 B at MTU 23: about 200 writes of 20 B at 100 ms each, 20 s with nothing owed by
            // the device (below the 8 KB upload credit step), twice the idle limit.
            val path = "/api/fs/upload?path=/data/app/cfg.bin"
            val body = patterned(4000)
            val (peripheral, transport) =
                connect(
                    mapOf(path to Reply(200, """{"ok":true}""")),
                    mtu = 23,
                    idleTimeout = 10.seconds,
                    writeDelay = 100.milliseconds,
                )

            val response = transport.request("POST", path, body, mapOf("Content-Type" to "application/octet-stream"))

            assertEquals(200, response.status)
            assertArrayEquals(body, peripheral.requests.single().body)
            assertTrue(currentTime > 15_000, "the upload outlasted the idle limit: $currentTime ms")
        }

    @Test
    fun `a streamed body nobody collects is aborted after the idle limit and the link released`() =
        runTest {
            val path = "/api/fs/download?path=/data/app/cfg.bin"
            val (peripheral, transport) =
                connect(
                    mapOf(path to Reply(200, file64k), "/api/status" to Reply(200, status)),
                    idleTimeout = 10.seconds,
                )

            val dropped = transport.stream("GET", path)
            // Waits for the link, which only the abandoned-body watchdog can release.
            val next = transport.request("GET", "/api/status")

            assertArrayEquals(status, next.body)
            assertEquals(10_000, currentTime)
            assertTrue(peripheral.appFrames().contains(Frame.abort(1)))
            assertThrows<BleHttpException.ResponseTimeout> { dropped.body.toList() }
        }

    @Test
    fun `a collector cancelled mid-body aborts the response and releases the link`() =
        runTest {
            val path = "/api/fs/download?path=/data/app/cfg.bin"
            val (peripheral, transport) =
                connect(
                    mapOf(path to Reply(200, file64k), "/api/status" to Reply(200, status)),
                )
            val response = transport.stream("GET", path)
            val firstChunk = CompletableDeferred<Unit>()

            val consumer =
                launch {
                    response.body.collect {
                        firstChunk.complete(Unit)
                        delay(1.seconds)
                    }
                }
            firstChunk.await()
            consumer.cancel()
            consumer.join()

            assertEquals(Frame.abort(1), peripheral.appFrames().last())
            assertArrayEquals(status, transport.request("GET", "/api/status").body)
        }

    @Test
    fun `a streamed tunnel error needs no collecting to free the link`() =
        runTest {
            val (peripheral, transport) = connect(mapOf("/api/status" to Reply(200, status)))
            peripheral.inject(Fault.Busy)

            val busy = transport.stream("GET", "/api/status")

            assertEquals(429, busy.status)
            assertEquals(TunnelError.BUSY, busy.tunnelError)
            assertArrayEquals(status, transport.request("GET", "/api/status").body)
            assertEquals(0, currentTime)
        }
}
