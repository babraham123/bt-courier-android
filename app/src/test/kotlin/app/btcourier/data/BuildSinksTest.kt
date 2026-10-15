package app.btcourier.data

import app.btcourier.core.auth.TokenSet
import app.btcourier.core.sink.Jsonl
import app.btcourier.core.sink.UploadResult
import app.btcourier.core.sink.formats.Csv
import app.btcourier.core.sink.formats.PrometheusText
import app.btcourier.core.sink.http.HttpPostUploadTransport
import app.btcourier.core.sink.http.HttpPutUploadTransport
import app.btcourier.core.sink.mqtt.MqttUploadTransport
import app.btcourier.core.sink.sftp.SftpUploadTransport
import app.btcourier.core.store.SealedBatch
import app.btcourier.data.saf.FOLDER_ACCESS_LOST
import app.btcourier.data.saf.FakeDocumentTree
import app.btcourier.data.saf.SafUploadTransport
import app.btcourier.work.InMemorySshKeys
import app.btcourier.work.MapSecretStore
import app.btcourier.work.marker
import app.btcourier.work.row
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.Closeable
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * [buildSinks] maps each enabled [SinkConfig] to the transport, format and credentials it names,
 * and turns configurations that can't work into sinks whose uploads fail with the reason.
 */
class BuildSinksTest {
    private lateinit var server: MockWebServer
    private val client = OkHttpClient.Builder().callTimeout(10, TimeUnit.SECONDS).build()
    private val secrets = MapSecretStore()
    private val sshKeys = InMemorySshKeys()
    private val folders = mutableMapOf<String, FakeDocumentTree>()
    private val env = SinkEnvironment(client, { uri -> folders.getOrPut(uri) { FakeDocumentTree() } }, sshKeys)

    private val batch = SealedBatch("b1", "wican", "1:100", listOf(marker("dl_1.jsonl"), row(1_000, 1)), sealedAtMs = 0)

    @BeforeEach
    fun start() {
        server = MockWebServer().apply { start() }
    }

    @AfterEach
    fun stop() = server.close()

    private suspend fun build(vararg sinks: SinkConfig) = buildSinks(AppSettings(sinks = sinks.toList()), secrets, env)

    private fun url(path: String = "/up/") = server.url(path).toString()

    private fun ok() = server.enqueue(MockResponse.Builder().code(201).build())

    private fun takeRequest(): RecordedRequest = checkNotNull(server.takeRequest(5, TimeUnit.SECONDS))

    private fun basic(user: String, password: String) = "Basic " + Base64.getEncoder().encodeToString("$user:$password".toByteArray())

    @Test
    fun `one sink per enabled config, with the transport and format each names`() = runTest {
        sshKeys.loadOrCreate(StoredNames.keyAlias("sftp"))
        val sinks = build(
            SinkConfig("put", "Put", transport = TransportConfig.HttpPut(url())),
            SinkConfig("off", "Off", enabled = false, transport = TransportConfig.HttpPut(url())),
            SinkConfig("post", "Post", transport = TransportConfig.HttpPost(url()), format = FormatKind.PROMETHEUS),
            SinkConfig("sftp", "Sftp", transport = TransportConfig.Sftp("sftp.invalid", 22, "u", "d", "SHA256:abc"), format = FormatKind.CSV),
            SinkConfig("mqtt", "Mqtt", transport = TransportConfig.Mqtt("mqtt.invalid", clientId = "c", baseTopic = "t")),
            SinkConfig("dir", "Dir", transport = TransportConfig.LocalFolder("content://tree/a")),
        )

        assertEquals(listOf("put", "post", "sftp", "mqtt", "dir"), sinks.map { it.id })
        assertInstanceOf(HttpPutUploadTransport::class.java, sinks[0].transport)
        assertInstanceOf(HttpPostUploadTransport::class.java, sinks[1].transport)
        assertInstanceOf(SftpUploadTransport::class.java, sinks[2].transport)
        assertInstanceOf(MqttUploadTransport::class.java, sinks[3].transport)
        assertInstanceOf(SafUploadTransport::class.java, sinks[4].transport)
        assertTrue(sinks[3].transport is Closeable, "the upload job closes MQTT after the round")
        assertEquals(listOf(Jsonl, PrometheusText, Csv, Jsonl, Jsonl), sinks.map { it.format })
    }

    @Test
    fun `configured sink ids are the enabled ones`() {
        val settings = AppSettings(
            sinks = listOf(
                SinkConfig("a", "A", transport = TransportConfig.HttpPut("https://sink.invalid/")),
                SinkConfig("b", "B", enabled = false, transport = TransportConfig.HttpPut("https://sink.invalid/")),
            ),
        )
        assertEquals(setOf("a"), configuredSinkIds(settings))
        assertEquals(emptySet<String>(), configuredSinkIds(AppSettings()))
    }

    @Test
    fun `HTTP PUT with Basic auth sends the user and the sink's own secret`() = runTest {
        secrets.put(StoredNames.secret("put"), "s3cret")
        secrets.put(StoredNames.secret("other"), "wrong")
        ok()

        val sink = build(SinkConfig("put", "Put", transport = TransportConfig.HttpPut(url("/ingest/"), compress = false), auth = AuthConfig.Basic("courier"))).single()

        assertEquals(UploadResult.Acked, sink.upload(batch))
        val request = takeRequest()
        assertEquals("PUT", request.method)
        assertEquals(basic("courier", "s3cret"), request.headers["Authorization"])
        assertTrue(request.target.startsWith("/ingest/wican-") && request.target.endsWith(".jsonl"), "uncompressed: no .gz")
    }

    @Test
    fun `HTTP POST with a bearer token and CSV goes to the fixed URL with the name in a header and the query`() = runTest {
        secrets.put(StoredNames.secret("post"), "tok")
        ok()

        val sink = build(
            SinkConfig("post", "Post", transport = TransportConfig.HttpPost(url("/import"), PostEncoding.CONTENT_ENCODING_GZIP, nameQuery = true), format = FormatKind.CSV, auth = AuthConfig.Bearer),
        ).single()

        assertEquals(UploadResult.Acked, sink.upload(batch))
        val request = takeRequest()
        assertEquals("POST", request.method)
        assertEquals("Bearer tok", request.headers["Authorization"])
        assertEquals("gzip", request.headers["Content-Encoding"])
        val name = checkNotNull(request.headers["X-Batch-Name"])
        assertTrue(name.endsWith(".csv"))
        assertEquals("/import?name=$name", request.target)
    }

    @Test
    fun `an OIDC sink sends the stored access token and refreshes it into the sink's token secret`() = runTest {
        val tokenName = StoredNames.tokens("o")
        // Expired, so the first upload refreshes before sending.
        secrets.put(tokenName, TokenSet("old-access", "refresh-1", expiresAtMs = 0).encode())
        server.enqueue(
            MockResponse.Builder().code(200).setHeader("Content-Type", "application/json")
                .body("""{"access_token":"new-access","token_type":"Bearer","refresh_token":"refresh-2","expires_in":3600}""").build(),
        )
        ok()
        val auth = AuthConfig.Oidc(url("/auth"), "bt-courier", OidcClientAuth.NONE, url("/auth/token"), url("/auth/device"))

        val sink = build(SinkConfig("o", "Oidc", transport = TransportConfig.HttpPut(url("/up/")), auth = auth)).single()

        assertEquals(UploadResult.Acked, sink.upload(batch))
        val refresh = takeRequest()
        assertEquals("/auth/token", refresh.target)
        assertTrue(refresh.body!!.utf8().contains("refresh_token=refresh-1"))
        assertEquals("Bearer new-access", takeRequest().headers["Authorization"])
        val stored = TokenSet.decode(checkNotNull(secrets.get(tokenName)))
        assertEquals("new-access", stored.accessToken)
        assertEquals("refresh-2", stored.refreshToken, "the rotated refresh token is saved before use")
    }

    @Test
    fun `an OIDC sink that never signed in, or lost its client secret, fails asking to sign in`() = runTest {
        val unsigned = SinkConfig("u", "U", transport = TransportConfig.HttpPut(url()), auth = AuthConfig.Oidc(url("/auth"), "c"))
        val noSecret = SinkConfig(
            "s",
            "S",
            transport = TransportConfig.HttpPut(url()),
            auth = AuthConfig.Oidc(url("/auth"), "c", OidcClientAuth.SECRET_BASIC, url("/auth/token"), url("/auth/device")),
        )
        secrets.put(StoredNames.tokens("s"), TokenSet("a", "r", Long.MAX_VALUE).encode())

        val results = build(unsigned, noSecret).map { it.upload(batch) }

        assertTrue(results.all { it is UploadResult.Failed && it.reason.contains("sign in again") }, "$results")
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `a missing Basic secret still builds the sink, so the server's refusal surfaces`() = runTest {
        server.enqueue(MockResponse.Builder().code(401).build())

        val sink = build(SinkConfig("put", "Put", transport = TransportConfig.HttpPut(url()), auth = AuthConfig.Basic("courier"))).single()

        assertEquals(UploadResult.Failed("HTTP 401 for ${sink.encode(batch).name}"), sink.upload(batch))
        assertEquals(basic("courier", ""), takeRequest().headers["Authorization"])
    }

    @Test
    fun `SFTP without a confirmed host key or without its key fails instead of connecting`() = runTest {
        sshKeys.loadOrCreate(StoredNames.keyAlias("unpinned"))
        val unpinned = SinkConfig("unpinned", "U", transport = TransportConfig.Sftp("sftp.invalid", 22, "u", "d", null))
        val keyless = SinkConfig("keyless", "K", transport = TransportConfig.Sftp("sftp.invalid", 22, "u", "d", "SHA256:abc"))

        val (a, b) = build(unpinned, keyless).map { it.upload(batch) }

        assertTrue(a is UploadResult.Failed && a.reason.contains("host key not confirmed"), "$a")
        assertTrue(b is UploadResult.Failed && b.reason.contains("key missing"), "$b")
    }

    @Test
    fun `a local folder sink writes into its tree, and a revoked grant fails`() = runTest {
        val sink = build(SinkConfig("dir", "Dir", transport = TransportConfig.LocalFolder("content://tree/a"))).single()

        assertEquals(UploadResult.Acked, sink.upload(batch))
        assertEquals(listOf(sink.encode(batch).name), folders.getValue("content://tree/a").files.keys.toList())

        folders.getValue("content://tree/a").revoked = true
        assertEquals(UploadResult.Failed(FOLDER_ACCESS_LOST), sink.upload(batch))
    }

    @Test
    fun `an unparseable URL or an auth the transport cannot use fails that sink only`() = runTest {
        ok()
        val sinks = build(
            SinkConfig("bad", "Bad", transport = TransportConfig.HttpPut("not a url")),
            SinkConfig("sftpbasic", "S", transport = TransportConfig.Sftp("h", 22, "u", "d", "SHA256:abc"), auth = AuthConfig.Basic("x")),
            SinkConfig("dirbearer", "D", transport = TransportConfig.LocalFolder("content://tree/b"), auth = AuthConfig.Bearer),
            SinkConfig("good", "Good", transport = TransportConfig.HttpPut(url())),
        )

        val results = sinks.map { it.upload(batch) }

        val (bad, sftp, dir, good) = results
        assertTrue(bad is UploadResult.Failed && bad.reason.startsWith("invalid sink configuration"), "$bad")
        assertTrue(sftp is UploadResult.Failed && sftp.reason.contains("SFTP does not support BASIC"), "$sftp")
        assertTrue(dir is UploadResult.Failed && dir.reason.contains("LOCAL_FOLDER does not support BEARER"), "$dir")
        assertSame(UploadResult.Acked, good)
    }
}
