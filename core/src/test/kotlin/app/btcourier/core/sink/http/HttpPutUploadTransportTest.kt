package app.btcourier.core.sink.http

import app.btcourier.core.auth.Auth
import app.btcourier.core.http.MockServerTest
import app.btcourier.core.sink.Batch
import app.btcourier.core.sink.Gzip
import app.btcourier.core.sink.UploadResult
import app.btcourier.core.sink.http.HttpPutUploadTransport.Companion.INITIAL_BACKOFF_MS
import app.btcourier.core.sink.http.HttpPutUploadTransport.Companion.MAX_BACKOFF_MS
import app.btcourier.core.sink.http.HttpPutUploadTransport.Companion.MAX_RETRY_AFTER_MS
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.SocketEffect
import okhttp3.OkHttpClient
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

class HttpPutUploadTransportTest : MockServerTest() {
    private val content = "{\"_file\":\"1700000000.jsonl\"}\n{\"ts\":1}\n".encodeToByteArray()
    private val batch = Batch("7", Gzip.compress(content), "wican-1700000000_0-0123456789abcdef.jsonl.gz")

    private val now = Instant.parse("2026-10-14T12:00:00Z").toEpochMilli()

    private fun transport(auth: Auth = Auth.None, compress: Boolean = true, client: OkHttpClient = this.client) =
        HttpPutUploadTransport(baseUrl("/ingest/"), client, auth, compress, nowMs = { now })

    private fun retry(result: UploadResult) = assertInstanceOf(UploadResult.Retry::class.java, result)

    private fun httpDate(epochMs: Long) =
        DateTimeFormatter.RFC_1123_DATE_TIME.format(Instant.ofEpochMilli(epochMs).atOffset(ZoneOffset.UTC))

    @Test
    fun `PUTs the stored gzip bytes under the batch name with basic auth`() = runTest {
        respond(201)
        val result = transport(Auth.Basic("courier", "pässwörd")).upload(batch)

        val req = takeRequest()
        assertEquals(UploadResult.Acked, result)
        assertEquals("PUT", req.method)
        assertEquals("/ingest/${batch.name}", req.target)
        assertEquals("application/gzip", req.headers["Content-Type"])
        assertNull(req.headers["Content-Encoding"], "receivers go by the .gz name")
        assertEquals(basicHeader("courier", "pässwörd"), req.headers["Authorization"])
        assertArrayEquals(batch.bytes, req.body?.toByteArray())
    }

    @Test
    fun `bearer auth is sent as a bearer token`() = runTest {
        respond(204)
        transport(Auth.Bearer("upload-token")).upload(batch)
        assertEquals("Bearer upload-token", takeRequest().headers["Authorization"])
    }

    @Test
    fun `without compression the decompressed content goes under the name without gz`() = runTest {
        respond(200)
        assertEquals(UploadResult.Acked, transport(compress = false).upload(batch))

        val req = takeRequest()
        assertEquals("/ingest/wican-1700000000_0-0123456789abcdef.jsonl", req.target)
        assertEquals("application/x-ndjson", req.headers["Content-Type"])
        assertArrayEquals(content, req.body?.toByteArray())
    }

    @Test
    fun `without compression a corrupt stored batch fails instead of retrying forever`() = runTest {
        val result = transport(compress = false).upload(batch.copy(bytes = byteArrayOf(1, 2, 3)))
        assertInstanceOf(UploadResult.Failed::class.java, result)
        assertEquals(0, server.requestCount)
    }

    @ParameterizedTest
    @ValueSource(ints = [200, 201, 204])
    fun `2xx is acked`(code: Int) = runTest {
        respond(code)
        assertEquals(UploadResult.Acked, transport().upload(batch))
    }

    @ParameterizedTest
    @ValueSource(ints = [408, 429, 500, 502, 503, 504])
    fun `timeouts, throttling and server errors are retried`(code: Int) = runTest {
        respond(code)
        val result = retry(transport().upload(batch))
        assertEquals(INITIAL_BACKOFF_MS, result.afterMs)
    }

    @ParameterizedTest
    @ValueSource(ints = [400, 401, 403, 404, 405, 413])
    fun `other client errors fail permanently`(code: Int) = runTest {
        respond(code)
        assertInstanceOf(UploadResult.Failed::class.java, transport().upload(batch))
    }

    // An auth proxy (e.g. Authelia forward-auth) redirects to a login page that answers 200.
    private fun redirectToLogin(code: Int) {
        respond(code, "", "Location" to "/login")
        respond(200, "<html>login</html>")
    }

    @ParameterizedTest
    @ValueSource(ints = [301, 302, 303, 307, 308])
    fun `a redirect followed by the client fails instead of acking the login page`(code: Int) = runTest {
        redirectToLogin(code)

        val result = transport().upload(batch)

        assertInstanceOf(UploadResult.Failed::class.java, result)
        assertEquals("PUT", takeRequest().method)
        assertEquals("/login", takeRequest().target, "the default client followed the redirect")
    }

    @Test
    fun `a redirect is a permanent failure when the client does not follow it`() = runTest {
        redirectToLogin(302)
        val noRedirects = client.newBuilder().followRedirects(false).followSslRedirects(false).build()

        assertInstanceOf(UploadResult.Failed::class.java, transport(client = noRedirects).upload(batch))
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `a conflict whose HEAD is redirected is not acked`() = runTest {
        respond(409)
        respond(302, "", "Location" to "/login")
        respond(200, "", "Content-Length" to batch.bytes.size.toString())

        val result = transport().upload(batch)

        assertFalse(result == UploadResult.Acked, result.toString())
        assertEquals(3, server.requestCount)
    }

    @Test
    fun `a connection failure is retried, not thrown`() = runTest {
        val t = transport()
        server.close()
        retry(t.upload(batch))
    }

    @Test
    fun `a timeout after the body was sent is retried, because the object may exist`() = runTest {
        server.enqueue(MockResponse.Builder().code(201).headersDelay(5, TimeUnit.SECONDS).build())
        val impatient = client.newBuilder().readTimeout(300, TimeUnit.MILLISECONDS).build()

        val result = transport(client = impatient).upload(batch)

        retry(result)
        assertArrayEquals(batch.bytes, takeRequest().body?.toByteArray(), "server received the whole body")
    }

    @Test
    fun `retry-after in seconds is honoured`() = runTest {
        respond(429, "", "Retry-After" to "120")
        assertEquals(120_000, retry(transport().upload(batch)).afterMs)
    }

    @Test
    fun `retry-after as an HTTP date is relative to now`() = runTest {
        respond(503, "", "Retry-After" to httpDate(now + 90_000))
        assertEquals(90_000, retry(transport().upload(batch)).afterMs)
    }

    @Test
    fun `retry-after in the past means retry now, and a far-future one is capped`() = runTest {
        respond(503, "", "Retry-After" to httpDate(now - 60_000))
        respond(503, "", "Retry-After" to "${7 * 24 * 3600}")
        val t = transport()
        assertEquals(0, retry(t.upload(batch)).afterMs)
        assertEquals(MAX_RETRY_AFTER_MS, retry(t.upload(batch)).afterMs)
    }

    @Test
    fun `unparseable retry-after falls back to back-off`() = runTest {
        respond(503, "", "Retry-After" to "soon")
        assertEquals(INITIAL_BACKOFF_MS, retry(transport().upload(batch)).afterMs)
    }

    @Test
    fun `back-off doubles over consecutive retries, is capped, and resets after an ack`() = runTest {
        val t = transport()
        val delays = (1..10).map {
            respond(503)
            retry(t.upload(batch)).afterMs
        }
        val expected = (0 until 10).map { (INITIAL_BACKOFF_MS shl it).coerceAtMost(MAX_BACKOFF_MS) }
        assertEquals(expected, delays)
        assertEquals(MAX_BACKOFF_MS, delays.last())

        respond(201)
        assertEquals(UploadResult.Acked, t.upload(batch))
        respond(503)
        assertEquals(INITIAL_BACKOFF_MS, retry(t.upload(batch)).afterMs)
    }

    @ParameterizedTest
    @ValueSource(ints = [409, 412])
    fun `a conflict on an existing object of the same length is acked`(code: Int) = runTest {
        respond(code)
        respond(200, "", "Content-Length" to batch.bytes.size.toString())

        assertEquals(UploadResult.Acked, transport(Auth.Bearer("upload-token")).upload(batch))

        takeRequest()
        val head = takeRequest()
        assertEquals("HEAD", head.method)
        assertEquals("/ingest/${batch.name}", head.target)
        assertEquals("Bearer upload-token", head.headers["Authorization"])
    }

    @Test
    fun `a conflict on an existing object of a different length is retried`() = runTest {
        respond(409)
        respond(200, "", "Content-Length" to (batch.bytes.size + 1).toString())
        retry(transport().upload(batch))
    }

    @Test
    fun `a conflict whose object cannot be found fails, since the target itself is wrong`() = runTest {
        respond(412)
        respond(404)
        assertInstanceOf(UploadResult.Failed::class.java, transport().upload(batch))
    }

    @Test
    fun `a conflict whose HEAD fails is retried`() = runTest {
        respond(409)
        server.enqueue(MockResponse.Builder().onRequestStart(SocketEffect.ShutdownConnection).build())
        retry(transport(client = client.newBuilder().retryOnConnectionFailure(false).build()).upload(batch))
    }

    @Test
    fun `credentials never appear in toString or results`() = runTest {
        respond(403)
        val t = transport(Auth.Basic("courier", "s3cret-password"))
        val result = t.upload(batch)
        for (s in listOf(t.toString(), result.toString())) {
            assertFalse("s3cret" in s, s)
        }
        val bearer = HttpPutUploadTransport("https://upload.example.invalid/ingest/", client, Auth.Bearer("s3cret"))
        assertFalse("s3cret" in bearer.toString())
    }
}
