package app.btcourier.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class SinkConfigTest {
    /** One sink per transport variant and per auth variant, every field off its default. */
    private val everyVariant = listOf(
        SinkConfig("put1", "Put", enabled = false, TransportConfig.HttpPut("https://sink.invalid/up/", compress = false), FormatKind.CSV, AuthConfig.Basic("courier")),
        SinkConfig("post1", "Post", transport = TransportConfig.HttpPost("https://sink.invalid/import?x=1", PostEncoding.CONTENT_ENCODING_GZIP, nameQuery = true), format = FormatKind.PROMETHEUS, auth = AuthConfig.Bearer),
        SinkConfig(
            "oidc1",
            "Oidc",
            transport = TransportConfig.HttpPost("https://sink.invalid/", PostEncoding.IDENTITY),
            auth = AuthConfig.Oidc(
                issuer = "https://auth.invalid",
                clientId = "bt-courier",
                clientAuthMethod = OidcClientAuth.SECRET_POST,
                tokenEndpoint = "https://auth.invalid/token",
                deviceAuthorizationEndpoint = "https://auth.invalid/device",
            ),
        ),
        SinkConfig("oidc2", "Oidc unsigned", transport = TransportConfig.HttpPut("https://sink.invalid/"), auth = AuthConfig.Oidc("https://auth.invalid", "c")),
        SinkConfig("sftp1", "Sftp", transport = TransportConfig.Sftp("sftp.invalid", 2222, "courier", "uploads", "SHA256:abc", sftpKeyAlias("sftp1"))),
        SinkConfig("sftp2", "Sftp unpinned", transport = TransportConfig.Sftp("sftp.invalid", username = "u", remoteDir = "", keyAlias = sftpKeyAlias("sftp2"))),
        SinkConfig("mqtt1", "Mqtt", transport = TransportConfig.Mqtt("mqtt.invalid", 1883, tls = false, clientId = "btcourier-x", baseTopic = "car/obd"), auth = AuthConfig.Basic("broker")),
        SinkConfig("dir1", "Folder", transport = TransportConfig.LocalFolder("content://com.android.externalstorage.documents/tree/primary%3ABT")),
    )

    @Test
    fun `every transport and auth variant survives a JSON round trip`() {
        assertEquals(everyVariant, decodeSinks(encodeSinks(everyVariant)))
    }

    @Test
    fun `variants are tagged with stable type names, not class names`() {
        val array = Json.parseToJsonElement(encodeSinks(everyVariant)).jsonArray.map { it.jsonObject }
        fun JsonObject.type(key: String) = getValue(key).jsonObject.getValue("type").jsonPrimitive.content

        assertEquals(
            listOf("http_put", "http_post", "http_post", "http_put", "sftp", "sftp", "mqtt", "local_folder"),
            array.map { it.type("transport") },
        )
        assertEquals(listOf("basic", "bearer", "oidc", "oidc", "none", "none", "basic", "none"), array.map { it.type("auth") })
    }

    @Test
    fun `defaults are written out and missing optional fields read as their defaults`() {
        val stored = """[{"id":"a","name":"A","transport":{"type":"mqtt","host":"m.invalid","clientId":"c","baseTopic":"t"}}]"""

        val sink = decodeSinks(stored).single()

        assertEquals(SinkConfig("a", "A", true, TransportConfig.Mqtt("m.invalid", 8883, true, "c", "t"), FormatKind.JSONL, AuthConfig.None), sink)
        assertTrue(encodeSinks(listOf(sink)).contains("\"port\":8883"))
    }

    @Test
    fun `unknown fields from a newer version are ignored, unknown transports are an error`() {
        val withExtra = """[{"id":"a","name":"A","future":1,"transport":{"type":"local_folder","treeUri":"content://x","future":2}}]"""
        assertEquals(TransportConfig.LocalFolder("content://x"), decodeSinks(withExtra).single().transport)

        assertThrows<IllegalArgumentException> { decodeSinks("""[{"id":"a","name":"A","transport":{"type":"carrier_pigeon"}}]""") }
    }

    @Test
    fun `HTTP transports allow every auth, MQTT only basic, SFTP and folders none`() {
        assertEquals(AuthKind.entries, TransportKind.HTTP_PUT.allowedAuth)
        assertEquals(AuthKind.entries, TransportKind.HTTP_POST.allowedAuth)
        assertEquals(listOf(AuthKind.NONE, AuthKind.BASIC), TransportKind.MQTT.allowedAuth)
        assertEquals(listOf(AuthKind.NONE), TransportKind.SFTP.allowedAuth)
        assertEquals(listOf(AuthKind.NONE), TransportKind.LOCAL_FOLDER.allowedAuth)
    }

    @Test
    fun `sink ids are short, distinct and valid secret names`() {
        val ids = List(1000) { newSinkId() }

        assertEquals(1000, ids.toSet().size)
        assertTrue(ids.all { it.length == 10 })
        assertTrue(ids.flatMap(SinkSecrets::all).all { it.matches(Regex("[A-Za-z0-9_-]+")) })
        assertEquals(listOf("sink_ab_secret", "sink_ab_oidc_client_secret", "sink_ab_oidc_tokens"), SinkSecrets.all("ab"))
        assertEquals("btcourier.sftp.ab", sftpKeyAlias("ab"))
    }
}
