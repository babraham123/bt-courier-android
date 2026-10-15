package app.btcourier.core.transport.wifi

import app.btcourier.core.auth.Auth
import app.btcourier.core.auth.TokenSource
import app.btcourier.core.http.MockServerTest
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockResponseBody
import okio.BufferedSink
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class WifiHttpTransportTest : MockServerTest() {
    private fun transport(auth: Auth = Auth.None, prefix: String = "") = WifiHttpTransport(baseUrl(prefix), client, auth)

    @Test
    fun `request sends method, path, query, headers and body, and returns the response`() = runTest {
        respond(201, "created", "Content-Type" to "application/json", "X-Device" to "ok")
        val rsp = transport().request(
            "POST",
            "/api/config?section=can",
            "{\"a\":1}".encodeToByteArray(),
            mapOf("Content-Type" to "application/json", "X-Trace" to "1"),
        )

        val req = takeRequest()
        assertEquals("POST", req.method)
        assertEquals("/api/config?section=can", req.target)
        assertEquals("application/json", req.headers["Content-Type"])
        assertEquals("1", req.headers["X-Trace"])
        assertEquals("{\"a\":1}", req.body?.utf8())
        assertNull(req.headers["Authorization"])

        assertEquals(201, rsp.status)
        assertEquals("application/json", rsp.headers["content-type"])
        assertEquals("ok", rsp.headers["x-device"], "header names are lower-cased")
        assertEquals("created", rsp.body.decodeToString())
    }

    @Test
    fun `a base URL path prefix is kept in front of the request path`() = runTest {
        respond(200)
        transport(prefix = "/wican/").request("GET", "/api/status")
        assertEquals("/wican/api/status", takeRequest().target)
    }

    @Test
    fun `basic auth sends RFC 7617 credentials encoded as UTF-8`() = runTest {
        respond(200)
        transport(Auth.Basic("courier", "pässwörd")).request("GET", "/api/status")
        assertEquals(basicHeader("courier", "pässwörd"), takeRequest().headers["Authorization"])
    }

    @Test
    fun `bearer auth reads the token source on every request`() = runTest {
        val issued = AtomicInteger(0)
        val auth = Auth.Bearer(TokenSource { "token-${issued.incrementAndGet()}" })
        respond(200)
        respond(200)
        val t = transport(auth)
        t.request("GET", "/api/status")
        t.request("GET", "/api/status")
        assertEquals("Bearer token-1", takeRequest().headers["Authorization"])
        assertEquals("Bearer token-2", takeRequest().headers["Authorization"])
    }

    @Test
    fun `a 429 from the device comes back as a response, not an exception`() = runTest {
        respond(429, "busy", "Retry-After" to "2")
        respond(429, "busy")
        val t = transport()

        val rsp = t.request("GET", "/api/logger/export")
        assertEquals(429, rsp.status)
        assertEquals("2", rsp.headers["retry-after"])
        assertEquals("busy", rsp.body.decodeToString())

        val streamed = t.stream("GET", "/api/logger/export")
        assertEquals(429, streamed.status)
        assertEquals("busy", streamed.body.toList().reduce(ByteArray::plus).decodeToString())
    }

    @Test
    fun `a network failure throws IOException`() = runTest {
        val t = transport()
        server.close()
        assertThrows<IOException> { t.request("GET", "/api/status") }
        assertThrows<IOException> { t.stream("GET", "/api/status") }
    }

    @Test
    fun `stream delivers each chunk before the device has sent the next`() = runTest {
        val chunks = listOf("first-chunk\n", "second-chunk\n", "third-chunk\n").map { it.encodeToByteArray() }
        // The server sends a chunk only after the client has seen the previous one, so a transport
        // that buffered the whole body would never complete.
        val clientSaw = Semaphore(0)
        server.enqueue(
            MockResponse.Builder().code(200).body(object : MockResponseBody {
                override val contentLength = chunks.sumOf { it.size }.toLong()

                override fun writeTo(sink: BufferedSink) {
                    chunks.forEachIndexed { i, chunk ->
                        if (i > 0) check(clientSaw.tryAcquire(5, TimeUnit.SECONDS)) { "client did not see chunk $i" }
                        sink.write(chunk)
                        sink.flush()
                    }
                }
            }).build(),
        )

        val rsp = transport().stream("GET", "/api/fs/download?path=/sd/logs/x.jsonl")
        assertEquals(200, rsp.status)

        val received = ByteArrayOutputStream()
        var boundary = 0
        var emissions = 0
        rsp.body.collect { bytes ->
            emissions++
            received.write(bytes)
            while (boundary < chunks.size && received.size() >= chunks.take(boundary + 1).sumOf { it.size }) {
                boundary++
                clientSaw.release()
            }
        }
        assertArrayEquals(chunks.reduce(ByteArray::plus), received.toByteArray())
        assertTrue(emissions >= 3, "one emission per chunk at least, got $emissions")
    }

    @Test
    fun `credentials never appear in toString`() {
        val basic = Auth.Basic("courier", "s3cret-password")
        val bearer = Auth.Bearer("s3cret-token")
        for (s in listOf(basic.toString(), bearer.toString())) {
            assertFalse("courier" in s || "s3cret" in s, s)
        }
        val t = WifiHttpTransport("http://wican.example.invalid", client, basic)
        assertFalse("s3cret" in t.toString())
    }

    @Test
    fun `credentials in the base URL are rejected`() {
        assertThrows<IllegalArgumentException> {
            WifiHttpTransport("http://courier:s3cret@wican.example.invalid", client)
        }
    }
}
