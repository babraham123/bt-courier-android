package app.btcourier.core.sink.http

import app.btcourier.core.auth.Auth
import app.btcourier.core.http.MockServerTest
import app.btcourier.core.sink.Batch
import app.btcourier.core.sink.Gzip
import app.btcourier.core.sink.UploadResult
import app.btcourier.core.sink.http.HttpPostUploadTransport.BodyEncoding
import kotlinx.coroutines.test.runTest
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

class HttpPostUploadTransportTest : MockServerTest() {
    private val content =
        "autopid_SOC{file=\"dl_1700000000.jsonl\",ts_quality=\"journal\"} 81.5 1700000000123\n"
            .encodeToByteArray()
    private val batch = Batch("7", Gzip.compress(content), "wican-1700000000_0-0123456789abcdef.prom.gz")
    private val plainName = batch.name.removeSuffix(".gz")

    private val now = Instant.parse("2026-10-14T12:00:00Z").toEpochMilli()

    private fun transport(
        auth: Auth = Auth.None,
        encoding: BodyEncoding = BodyEncoding.GzipFile,
        nameQuery: Boolean = false,
        path: String = "/api/v1/import/prometheus",
        client: OkHttpClient = this.client,
    ) = HttpPostUploadTransport(baseUrl(path), client, auth, encoding, nameQuery)

    private fun retry(result: UploadResult) = assertInstanceOf(UploadResult.Retry::class.java, result)

    private fun httpDate(epochMs: Long) =
        DateTimeFormatter.RFC_1123_DATE_TIME.format(Instant.ofEpochMilli(epochMs).atOffset(ZoneOffset.UTC))

    @Test
    fun `POSTs the stored gzip bytes to the fixed url with the name in a header and basic auth`() =
        runTest {
            respond(204)
            val result = transport(Auth.Basic("courier", "pässwörd")).upload(batch)

            val req = takeRequest()
            assertEquals(UploadResult.Acked, result)
            assertEquals("POST", req.method)
            assertEquals("/api/v1/import/prometheus", req.target)
            assertEquals(batch.name, req.headers["X-Batch-Name"])
            assertEquals("application/gzip", req.headers["Content-Type"])
            assertNull(req.headers["Content-Encoding"])
            assertEquals(basicHeader("courier", "pässwörd"), req.headers["Authorization"])
            assertArrayEquals(batch.bytes, req.body?.toByteArray())
        }

    @Test
    fun `bearer auth is sent as a bearer token`() =
        runTest {
            respond(204)
            transport(Auth.Bearer("upload-token")).upload(batch)
            assertEquals("Bearer upload-token", takeRequest().headers["Authorization"])
        }

    @Test
    fun `content-encoding mode sends the gzip bytes labelled with the format's type`() =
        runTest {
            respond(204)
            assertEquals(UploadResult.Acked, transport(encoding = BodyEncoding.ContentEncodingGzip).upload(batch))

            val req = takeRequest()
            assertEquals("gzip", req.headers["Content-Encoding"])
            assertEquals("text/plain; version=0.0.4", req.headers["Content-Type"])
            assertEquals(plainName, req.headers["X-Batch-Name"])
            assertArrayEquals(batch.bytes, req.body?.toByteArray())
        }

    @Test
    fun `identity mode sends the decompressed content under the name without gz`() =
        runTest {
            respond(200)
            val csv =
                Batch("8", Gzip.compress("ts\n".encodeToByteArray()), "wican-1700000000_0-0123456789abcdef.csv.gz")
            assertEquals(UploadResult.Acked, transport(encoding = BodyEncoding.Identity).upload(csv))

            val req = takeRequest()
            assertNull(req.headers["Content-Encoding"])
            assertEquals("text/csv", req.headers["Content-Type"])
            assertEquals("wican-1700000000_0-0123456789abcdef.csv", req.headers["X-Batch-Name"])
            assertEquals("ts\n", req.body?.utf8())
        }

    @Test
    fun `identity mode fails on a corrupt stored batch instead of retrying forever`() =
        runTest {
            val result = transport(encoding = BodyEncoding.Identity).upload(batch.copy(bytes = byteArrayOf(1, 2, 3)))
            assertInstanceOf(UploadResult.Failed::class.java, result)
            assertEquals(0, server.requestCount)
        }

    @Test
    fun `the name query toggle adds name= and keeps the configured query`() =
        runTest {
            respond(204)
            transport(nameQuery = true, path = "/api/v1/import/prometheus?extra_label=car%3Dioniq").upload(batch)

            val url = takeRequest().url
            assertEquals("car=ioniq", url.queryParameter("extra_label"))
            assertEquals(batch.name, url.queryParameter("name"))
        }

    @ParameterizedTest
    @ValueSource(ints = [200, 201, 202, 204])
    fun `2xx is acked`(code: Int) =
        runTest {
            respond(code)
            assertEquals(UploadResult.Acked, transport().upload(batch))
        }

    @ParameterizedTest
    @ValueSource(ints = [408, 429, 500, 502, 503, 504])
    fun `timeouts, throttling and server errors are retried`(code: Int) =
        runTest {
            respond(code)
            retry(transport().upload(batch))
        }

    @ParameterizedTest
    @ValueSource(ints = [400, 401, 403, 404, 409, 412, 413])
    fun `other client errors fail permanently, conflicts included`(code: Int) =
        runTest {
            respond(code)
            assertInstanceOf(UploadResult.Failed::class.java, transport().upload(batch))
            assertEquals(1, server.requestCount, "no HEAD: a POST leaves no object to compare")
        }

    @ParameterizedTest
    @ValueSource(ints = [301, 302, 303, 307, 308])
    fun `a redirect followed by the client fails instead of acking the login page`(code: Int) =
        runTest {
            respond(code, "", "Location" to "/login")
            respond(200, "<html>login</html>")

            assertInstanceOf(UploadResult.Failed::class.java, transport().upload(batch))
            assertEquals("POST", takeRequest().method)
            assertEquals("/login", takeRequest().target, "the default client followed the redirect")
        }

    @Test
    fun `a redirect is a permanent failure when the client does not follow it`() =
        runTest {
            respond(302, "", "Location" to "/login")
            val noRedirects =
                client
                    .newBuilder()
                    .followRedirects(false)
                    .followSslRedirects(false)
                    .build()

            assertInstanceOf(UploadResult.Failed::class.java, transport(client = noRedirects).upload(batch))
            assertEquals(1, server.requestCount)
        }

    @Test
    fun `a connection failure is retried, not thrown`() =
        runTest {
            val t = transport()
            server.close()
            retry(t.upload(batch))
        }

    @Test
    fun `retry-after in seconds or as an HTTP date is passed on as the server's hint`() =
        runTest {
            respond(429, "", "Retry-After" to "120")
            respond(503, "", "Date" to httpDate(now), "Retry-After" to httpDate(now + 90_000))
            respond(503)
            val t = transport()
            assertEquals(listOf(120_000L, 90_000L, null), (1..3).map { retry(t.upload(batch)).retryAfterMs })
        }

    @Test
    fun `credentials never appear in toString or results`() =
        runTest {
            respond(403)
            val t = transport(Auth.Basic("courier", "s3cret-password"))
            for (s in listOf(t.toString(), t.upload(batch).toString())) assertFalse("s3cret" in s, s)
        }
}
