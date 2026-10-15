package app.btcourier.data.sinkkind

import app.btcourier.core.http.parseBaseUrl
import app.btcourier.core.sink.Batch
import app.btcourier.core.sink.UploadResult
import app.btcourier.core.sink.UploadTransport
import app.btcourier.core.sink.http.HttpPostUploadTransport
import app.btcourier.core.sink.http.HttpPutUploadTransport
import app.btcourier.core.sink.mqtt.MqttConfig
import app.btcourier.core.sink.mqtt.MqttUploadTransport
import app.btcourier.core.sink.sftp.SftpUploadTransport
import app.btcourier.data.AuthConfig
import app.btcourier.data.AuthKind
import app.btcourier.data.PostEncoding
import app.btcourier.data.SinkConfig
import app.btcourier.data.SinkEnvironment
import app.btcourier.data.StoredNames
import app.btcourier.data.TransportConfig
import app.btcourier.data.TransportKind
import app.btcourier.data.saf.FakeDocumentTree
import app.btcourier.data.saf.SafUploadTransport
import app.btcourier.data.testCredentials
import app.btcourier.work.InMemorySshKeys
import app.btcourier.work.MapSecretStore
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.Arguments.arguments
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.params.provider.ValueSource
import kotlin.reflect.KClass

/**
 * Every [SinkTransportKind] through its interface: the values the editor may save, the config
 * they become and back, the auth each allows, and what [SinkTransportKind.build] makes of a
 * config with and without stored credentials. Where core has its own checks (URL parsing,
 * [MqttConfig]) the editor must refuse exactly what core refuses.
 */
class SinkTransportKindsTest {
    private val secrets = MapSecretStore()
    private val sshKeys = InMemorySshKeys()
    private val credentials = testCredentials(secrets, sshKeys)
    private val env = SinkEnvironment(OkHttpClient(), { FakeDocumentTree() }, sshKeys)

    private fun SinkTransportKind.problems(vararg values: Pair<String, String>) =
        validate(values.fold(defaults()) { v, (key, value) -> v.with(key, value) })

    private fun urlProblem(kind: SinkTransportKind, url: String) = (kind.problems(HttpPutKind.URL_KEY to url)[HttpPutKind.URL_KEY] as FieldProblem.Url?)?.problem

    @Test
    fun `each transport kind has its own module`() {
        assertEquals(TransportKind.entries, TransportKind.entries.map { it.module.kind })
    }

    @Test
    fun `HTTP transports allow every auth, MQTT only basic, SFTP and folders none`() {
        assertEquals(AuthKind.entries, TransportKind.HTTP_PUT.module.allowedAuth)
        assertEquals(AuthKind.entries, TransportKind.HTTP_POST.module.allowedAuth)
        assertEquals(listOf(AuthKind.NONE, AuthKind.BASIC), TransportKind.MQTT.module.allowedAuth)
        assertEquals(listOf(AuthKind.NONE), TransportKind.SFTP.module.allowedAuth)
        assertEquals(listOf(AuthKind.NONE), TransportKind.LOCAL_FOLDER.module.allowedAuth)
    }

    @ParameterizedTest
    @MethodSource("everyVariant")
    fun `a saved config shows in the editor as values that validate and save back unchanged`(config: TransportConfig) {
        val kind = config.kind.module
        val values = kind.toValues(config)

        assertEquals(emptyMap<String, FieldProblem>(), kind.validate(values))
        assertEquals(config, kind.toConfig(values))
    }

    @ParameterizedTest
    @MethodSource("newSinks")
    fun `a new sink starts from the documented defaults and saves trimmed`(kind: SinkTransportKind, typed: Map<String, String>, expected: TransportConfig) {
        val values = typed.entries.fold(kind.defaults()) { v, (key, value) -> v.with(key, value) }

        assertEquals(emptyMap<String, FieldProblem>(), kind.validate(values))
        assertEquals(expected, kind.toConfig(values))
    }

    @Test
    fun `a new MQTT sink gets a random client id, so two phones never share one`() {
        val ids = List(20) { MqttKind.defaults()[MqttKind.CLIENT_ID] }

        assertTrue(ids.all { it.startsWith("btcourier-") }, "$ids")
        assertEquals(20, ids.toSet().size)
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "https://sink.example.org/ingest/",
            " https://sink.example.org/ingest/ ",
            "https://203.0.113.5:8443/",
            "https://localhost:8443/",
            "https://192.168.1.20/dav/",
            "https://[fd12:3456::1]/",
        ],
    )
    fun `an https URL to any host is accepted by both HTTP kinds`(url: String) {
        assertEquals(null, urlProblem(HttpPutKind, url))
        assertEquals(null, urlProblem(HttpPostKind, url))
    }

    @ParameterizedTest
    @MethodSource("refusedUrls")
    fun `an unusable URL is refused with the reason`(url: String, put: SinkUrlProblem, post: SinkUrlProblem?) {
        assertEquals(put, urlProblem(HttpPutKind, url))
        assertEquals(post, urlProblem(HttpPostKind, url))
    }

    @ParameterizedTest
    @MethodSource("allUrls")
    fun `a URL the editor accepts is one core's transport accepts, and one core refuses the editor refuses`(url: String) {
        val accepted = urlProblem(HttpPutKind, url) == null
        val coreAccepts = runCatching { parseBaseUrl(url.trim()) }.isSuccess

        if (accepted) HttpPutUploadTransport(url.trim(), OkHttpClient())
        if (!coreAccepts) assertTrue(!accepted, url)
    }

    @Test
    fun `SFTP needs a host, a port in range and a username`() {
        assertEquals(setOf(SftpKind.HOST, SftpKind.USERNAME), SftpKind.problems().keys)
        for (port in listOf("0", "65536", "x", "")) {
            assertEquals(setOf(SftpKind.PORT), SftpKind.problems(SftpKind.HOST to "h", SftpKind.USERNAME to "u", SftpKind.PORT to port).keys, port)
        }
        assertEquals(emptySet<String>(), SftpKind.problems(SftpKind.HOST to "h", SftpKind.USERNAME to "u", SftpKind.PORT to " 65535 ").keys)
    }

    @Test
    fun `MQTT needs a host, a port in range and a client id`() {
        assertEquals(setOf(MqttKind.HOST), MqttKind.problems(MqttKind.HOST to " ").keys)
        for (port in listOf("0", "65536", "x")) {
            assertEquals(setOf(MqttKind.PORT), MqttKind.problems(MqttKind.HOST to "h", MqttKind.PORT to port).keys, port)
        }
        assertEquals(setOf(MqttKind.CLIENT_ID), MqttKind.problems(MqttKind.HOST to "h", MqttKind.CLIENT_ID to " ").keys)
    }

    @ParameterizedTest
    @MethodSource("baseTopics")
    fun `an MQTT base topic is refused exactly when MqttConfig refuses it`(topic: String, valid: Boolean) {
        val refused = MqttKind.BASE_TOPIC in MqttKind.problems(MqttKind.HOST to "h", MqttKind.BASE_TOPIC to topic)
        val coreRefuses = runCatching { MqttConfig("h", "c", topic.trim()) }.isFailure

        assertEquals(!valid, refused, topic)
        assertEquals(coreRefuses, refused, topic)
    }

    @Test
    fun `the MQTT port follows the TLS default unless the user set another`() {
        val tls = MqttKind.defaults()
        assertEquals("8883", tls[MqttKind.PORT])

        val plain = MqttKind.adjust(tls, tls.with(MqttKind.TLS, "false"))
        assertEquals("1883", plain[MqttKind.PORT])
        assertEquals("8883", MqttKind.adjust(plain, plain.with(MqttKind.TLS, "true"))[MqttKind.PORT])

        val custom = plain.with(MqttKind.PORT, "11883")
        assertEquals("11883", MqttKind.adjust(custom, custom.with(MqttKind.TLS, "true"))[MqttKind.PORT])
        assertEquals("1883", MqttKind.adjust(plain, plain.with(MqttKind.HOST, "h"))[MqttKind.PORT], "other edits leave the port alone")
    }

    @Test
    fun `a local folder needs a picked tree`() {
        assertEquals(setOf(LocalFolderKind.TREE_URI), LocalFolderKind.problems().keys)
        assertEquals(LocalFolderKind.TREE_URI, LocalFolderKind.capability<TransportCapability.FolderPick>()?.treeUriKey)
    }

    @Test
    fun `only SFTP has a client key and a host key pin, which its own values hold`() {
        val pin = SftpKind.capability<TransportCapability.HostKeyPin>()
        assertEquals(Triple(SftpKind.HOST, SftpKind.PORT, SftpKind.FINGERPRINT), pin?.let { Triple(it.hostKey, it.portKey, it.fingerprintKey) })
        assertEquals(listOf(TransportKind.SFTP), TransportKind.entries.filter { it.module.capability<TransportCapability.ClientKey>() != null })
    }

    @ParameterizedTest
    @MethodSource("builtWithoutCredentials")
    fun `a sink with nothing stored still builds, so its uploads report why`(config: SinkConfig, expected: KClass<out UploadTransport>) = runTest {
        val transport = config.transport.kind.module.build(config, credentials, env)

        assertInstanceOf(expected.java, transport)
    }

    @Test
    fun `SFTP builds its transport only with its key and a confirmed host key`() = runTest {
        val pinned = SinkConfig("s", "S", transport = TransportConfig.Sftp("sftp.invalid", 22, "u", "d", "SHA256:abc"))
        val unpinned = pinned.copy(transport = TransportConfig.Sftp("sftp.invalid", 22, "u", "d", null))

        assertTrue(failure(SftpKind.build(pinned, credentials, env)).contains("key missing"))
        sshKeys.loadOrCreate(StoredNames.keyAlias("s"))
        assertTrue(failure(SftpKind.build(unpinned, credentials, env)).contains("host key not confirmed"))
        assertInstanceOf(SftpUploadTransport::class.java, SftpKind.build(pinned, credentials, env))
    }

    private suspend fun failure(transport: UploadTransport): String =
        assertInstanceOf(UploadResult.Failed::class.java, transport.upload(Batch("b", ByteArray(0), "b.jsonl.gz"))).reason

    companion object {
        /** One config per transport variant, every field off its default. */
        @JvmStatic
        fun everyVariant() = listOf(
            TransportConfig.HttpPut("https://sink.invalid/up/", compress = false),
            TransportConfig.HttpPost("https://sink.invalid/import?x=1", PostEncoding.CONTENT_ENCODING_GZIP, nameQuery = true),
            TransportConfig.HttpPost("https://sink.invalid/", PostEncoding.IDENTITY),
            TransportConfig.Sftp("sftp.invalid", 2222, "courier", "uploads", "SHA256:abc"),
            TransportConfig.Sftp("sftp.invalid", username = "u", remoteDir = ""),
            TransportConfig.Mqtt("mqtt.invalid", 1883, tls = false, clientId = "btcourier-x", baseTopic = "car/obd"),
            TransportConfig.Mqtt("mqtt.invalid", 8884, tls = true, clientId = "c", baseTopic = ""),
            TransportConfig.LocalFolder("content://com.android.externalstorage.documents/tree/primary%3ABT"),
        )

        @JvmStatic
        fun newSinks(): List<Arguments> {
            val mqttDefaults = MqttKind.defaults()
            return listOf(
                arguments(HttpPutKind, mapOf(HttpPutKind.URL_KEY to " https://s.invalid/up/ "), TransportConfig.HttpPut("https://s.invalid/up/", compress = true)),
                arguments(
                    HttpPostKind,
                    mapOf(HttpPostKind.URL_KEY to "https://s.invalid/import"),
                    TransportConfig.HttpPost("https://s.invalid/import", PostEncoding.GZIP_FILE, nameQuery = false),
                ),
                arguments(
                    SftpKind,
                    mapOf(SftpKind.HOST to " sftp.invalid ", SftpKind.USERNAME to " courier ", SftpKind.REMOTE_DIR to " uploads "),
                    TransportConfig.Sftp("sftp.invalid", 22, "courier", "uploads", null),
                ),
                arguments(
                    MqttKind,
                    mapOf(MqttKind.HOST to " mqtt.invalid ", MqttKind.CLIENT_ID to mqttDefaults[MqttKind.CLIENT_ID]),
                    TransportConfig.Mqtt("mqtt.invalid", 8883, tls = true, clientId = mqttDefaults[MqttKind.CLIENT_ID], baseTopic = "btcourier"),
                ),
                arguments(LocalFolderKind, mapOf(LocalFolderKind.TREE_URI to "content://tree/a"), TransportConfig.LocalFolder("content://tree/a")),
            )
        }

        /** URL, PUT's problem, POST's problem: POST keeps a query, PUT appends to the path. */
        @JvmStatic
        fun refusedUrls() = listOf(
            arguments("", SinkUrlProblem.INVALID, SinkUrlProblem.INVALID),
            arguments("   ", SinkUrlProblem.INVALID, SinkUrlProblem.INVALID),
            arguments("sink.example.org", SinkUrlProblem.INVALID, SinkUrlProblem.INVALID),
            arguments("ftp://sink.example.org/", SinkUrlProblem.INVALID, SinkUrlProblem.INVALID),
            arguments("https://", SinkUrlProblem.INVALID, SinkUrlProblem.INVALID),
            arguments("not a url", SinkUrlProblem.INVALID, SinkUrlProblem.INVALID),
            arguments("http://sink.example.org/", SinkUrlProblem.CLEARTEXT, SinkUrlProblem.CLEARTEXT),
            arguments("http://203.0.113.5/", SinkUrlProblem.CLEARTEXT, SinkUrlProblem.CLEARTEXT),
            arguments("http://localhost:8080/", SinkUrlProblem.CLEARTEXT, SinkUrlProblem.CLEARTEXT),
            arguments("http://127.0.0.1/", SinkUrlProblem.CLEARTEXT, SinkUrlProblem.CLEARTEXT),
            arguments("http://10.1.2.3/up/", SinkUrlProblem.CLEARTEXT, SinkUrlProblem.CLEARTEXT),
            arguments("http://192.168.1.20:8080/dav/", SinkUrlProblem.CLEARTEXT, SinkUrlProblem.CLEARTEXT),
            arguments("http://169.254.10.10/", SinkUrlProblem.CLEARTEXT, SinkUrlProblem.CLEARTEXT),
            arguments("http://[::1]/", SinkUrlProblem.CLEARTEXT, SinkUrlProblem.CLEARTEXT),
            arguments("http://[fd12:3456::1]/", SinkUrlProblem.CLEARTEXT, SinkUrlProblem.CLEARTEXT),
            arguments("http://nas.local/", SinkUrlProblem.CLEARTEXT, SinkUrlProblem.CLEARTEXT),
            arguments("https://user:pass@sink.example.org/", SinkUrlProblem.CREDENTIALS_IN_URL, SinkUrlProblem.CREDENTIALS_IN_URL),
            arguments("https://user@sink.example.org/", SinkUrlProblem.CREDENTIALS_IN_URL, SinkUrlProblem.CREDENTIALS_IN_URL),
            arguments("http://user:pass@192.168.1.20/", SinkUrlProblem.CREDENTIALS_IN_URL, SinkUrlProblem.CREDENTIALS_IN_URL),
            arguments("https://sink.example.org/up?token=x", SinkUrlProblem.QUERY_OR_FRAGMENT, null),
            arguments("https://sink.example.org/up#frag", SinkUrlProblem.QUERY_OR_FRAGMENT, SinkUrlProblem.QUERY_OR_FRAGMENT),
        )

        @JvmStatic
        fun allUrls() = refusedUrls().map { it.get()[0] } + listOf("https://sink.example.org/ingest/", "https://[fd12:3456::1]/")

        @JvmStatic
        fun baseTopics() = listOf(
            arguments("", true),
            arguments("car/obd", true),
            arguments("car/obd/", true),
            arguments(" car ", true),
            arguments("car/#", false),
            arguments("car/+/obd", false),
            arguments("\$SYS/x", false),
            arguments(" \$SYS", false),
            arguments("car\u0000obd", false),
        )

        @JvmStatic
        fun builtWithoutCredentials() = listOf(
            arguments(SinkConfig("put", "P", transport = TransportConfig.HttpPut("https://s.invalid/"), auth = AuthConfig.Basic("u")), HttpPutUploadTransport::class),
            arguments(SinkConfig("post", "P", transport = TransportConfig.HttpPost("https://s.invalid/"), auth = AuthConfig.Bearer), HttpPostUploadTransport::class),
            arguments(
                SinkConfig("oidc", "O", transport = TransportConfig.HttpPut("https://s.invalid/"), auth = AuthConfig.Oidc("https://auth.invalid", "c")),
                HttpPutUploadTransport::class,
            ),
            arguments(
                SinkConfig("mqtt", "M", transport = TransportConfig.Mqtt("mqtt.invalid", clientId = "c", baseTopic = "t"), auth = AuthConfig.Basic("u")),
                MqttUploadTransport::class,
            ),
            arguments(SinkConfig("dir", "D", transport = TransportConfig.LocalFolder("content://tree/a")), SafUploadTransport::class),
        )
    }
}
