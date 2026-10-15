package app.btcourier.data

import app.btcourier.core.auth.OidcException
import app.btcourier.core.auth.TokenSet
import app.btcourier.ui.FakeSettings
import app.btcourier.work.InMemorySshKeys
import app.btcourier.work.MapSecretStore
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.concurrent.TimeUnit

/**
 * The per-sink credential lifecycle of [SinkCredentials]: where each credential lives, which ones
 * a saved config keeps, and that deleting or discarding a sink leaves nothing behind.
 */
class SinkCredentialsTest {
    private val secrets = MapSecretStore(StoredNames.secret("other") to "other-token")
    private val sshKeys = InMemorySshKeys()
    private val folders = RecordingFolderGrants()
    private val credentials = testCredentials(secrets, sshKeys, folders)

    private val other =
        SinkConfig(
            "other",
            "Other",
            transport = TransportConfig.HttpPut("https://other.invalid/"),
            auth = AuthConfig.Bearer,
        )
    private val settings = FakeSettings(AppSettings(sinks = listOf(other)))

    private val tokens = TokenSet("access-1", "refresh-1", expiresAtMs = 0)

    private fun sink(
        id: String,
        auth: AuthConfig = AuthConfig.None,
        transport: TransportConfig = TransportConfig.HttpPut("https://sink.invalid/"),
    ) = SinkConfig(id, id.uppercase(), transport = transport, auth = auth)

    private fun oidc(
        method: OidcClientAuth = OidcClientAuth.SECRET_BASIC,
        signedIn: Boolean = true,
    ) = AuthConfig.Oidc(
        "https://auth.invalid",
        "c",
        method,
        "https://auth.invalid/t".takeIf { signedIn },
        "https://auth.invalid/d".takeIf { signedIn },
    )

    private fun folder(tree: String) = TransportConfig.LocalFolder(tree)

    private suspend fun stored(id: String) = settings.current().sinks.firstOrNull { it.id == id }

    @Test
    fun `saving keeps exactly the credentials the saved auth uses, under the sink's names`() =
        runTest {
            assertNull(
                credentials.save(sink("a", AuthConfig.Basic("u")), CredentialEdits(secret = "pw"), settings),
                "a new sink",
            )
            assertEquals(
                mapOf(
                    StoredNames.secret("a") to "pw",
                    StoredNames.secret("other") to "other-token",
                ),
                secrets.names.associateWith {
                    secrets.get(it)
                },
            )
            assertEquals(listOf(other, sink("a", AuthConfig.Basic("u"))), settings.current().sinks, "appended")

            val previous =
                credentials.save(
                    sink("a", oidc()),
                    CredentialEdits(clientSecret = "cs", tokens = tokens),
                    settings,
                )
            assertEquals(sink("a", AuthConfig.Basic("u")), previous)
            assertEquals(
                setOf(StoredNames.clientSecret("a"), StoredNames.tokens("a"), StoredNames.secret("other")),
                secrets.names,
                "the password went with Basic",
            )
            assertEquals(tokens, TokenSet.decode(checkNotNull(secrets.get(StoredNames.tokens("a")))))
            assertEquals(StoredCredentials(secret = false, clientSecret = true, tokens = true), credentials.stored("a"))
            assertEquals("cs", credentials.clientSecret("a"))

            credentials.save(sink("a", oidc()), CredentialEdits(), settings)
            assertEquals(
                StoredCredentials(secret = false, clientSecret = true, tokens = true),
                credentials.stored("a"),
                "no edits keep what is stored",
            )

            credentials.save(sink("a", oidc(OidcClientAuth.NONE)), CredentialEdits(), settings)
            assertEquals(
                StoredCredentials(secret = false, clientSecret = false, tokens = true),
                credentials.stored("a"),
                "a public client has no secret",
            )

            credentials.save(sink("a"), CredentialEdits(), settings)
            assertEquals(setOf(StoredNames.secret("other")), secrets.names)
            assertEquals(listOf(other, sink("a")), settings.current().sinks, "replaced in place")
        }

    @Test
    fun `tokens never outlive the issuer and client they were obtained for`() =
        runTest {
            credentials.save(sink("o", oidc()), CredentialEdits(clientSecret = "cs", tokens = tokens), settings)

            // The editor drops the endpoints when the issuer or client changes.
            credentials.save(sink("o", oidc(signedIn = false)), CredentialEdits(tokens = tokens), settings)

            assertEquals(
                StoredCredentials(secret = false, clientSecret = true, tokens = false),
                credentials.stored("o"),
            )
        }

    @Test
    fun `an SFTP sink's key lives under its alias until the sink stops being SFTP`() =
        runTest {
            val line = credentials.sftpPublicKey("s")
            assertTrue(
                line.startsWith("ecdsa-sha2-nistp256 AAAAE2VjZHNhLXNoYTItbmlzdHAyNTY") && line.endsWith(" btcourier-s"),
                line,
            )
            assertEquals(line, credentials.sftpPublicKey("s"), "created once")
            assertSame(sshKeys.load(StoredNames.keyAlias("s")), credentials.sftpKey("s"))
            assertNull(credentials.sftpKey("other"))

            credentials.save(
                sink("s", transport = TransportConfig.Sftp("h.invalid", 22, "u", "", "SHA256:x")),
                CredentialEdits(),
                settings,
            )
            assertNotNull(credentials.sftpKey("s"))

            credentials.save(sink("s"), CredentialEdits(), settings)
            assertNull(credentials.sftpKey("s"))
            assertEquals(emptyMap<String, Any>(), sshKeys.keys)
        }

    @Test
    fun `signing out deletes the tokens but keeps the client secret, stopping uploads first`() =
        runTest {
            credentials.save(sink("o", oidc()), CredentialEdits(clientSecret = "cs", tokens = tokens), settings)
            val atStop = mutableListOf<StoredCredentials>()

            assertTrue(credentials.signOut("o") { atStop += runBlocking { credentials.stored("o") } })

            assertEquals(
                listOf(StoredCredentials(secret = false, clientSecret = true, tokens = true)),
                atStop,
                "uploads stop while the tokens still exist",
            )
            assertEquals(
                StoredCredentials(secret = false, clientSecret = true, tokens = false),
                credentials.stored("o"),
            )
            assertFalse(credentials.signOut("o") { error("nothing to sign out of, so uploads carry on") })
        }

    @Test
    fun `a refresh that lands after sign-out does not bring the tokens back`() =
        runTest {
            val config = sink("o", oidc(OidcClientAuth.NONE))
            credentials.save(config, CredentialEdits(tokens = tokens), settings)
            MockWebServer().use { server ->
                // The provider answers the refresh only after the user has signed out meanwhile.
                server.dispatcher =
                    object : Dispatcher() {
                        override fun dispatch(request: RecordedRequest): MockResponse {
                            runBlocking { credentials.signOut("o") {} }
                            return MockResponse
                                .Builder()
                                .code(200)
                                .setHeader("Content-Type", "application/json")
                                .body(
                                    """{"access_token":"access-2","token_type":"Bearer","refresh_token":"refresh-2","expires_in":3600}""",
                                ).build()
                        }
                    }
                server.start()
                val auth =
                    AuthConfig.Oidc(
                        server.url("/").toString(),
                        "c",
                        OidcClientAuth.NONE,
                        server.url("/t").toString(),
                        server.url("/d").toString(),
                    )
                val http = OkHttpClient.Builder().callTimeout(10, TimeUnit.SECONDS).build()

                credentials.httpAuth(config.copy(auth = auth), http).applyTo(Request.Builder())

                assertEquals(1, server.requestCount)
            }
            assertFalse(credentials.stored("o").tokens)
        }

    @Test
    fun `a stored token set that no longer parses reads as signed out`() =
        runTest {
            val config = sink("o", oidc(OidcClientAuth.NONE))
            credentials.save(config, CredentialEdits(), settings)
            secrets.put(StoredNames.tokens("o"), "{not json")

            val auth = credentials.httpAuth(config, OkHttpClient())

            assertThrows<OidcException.ReauthorizationRequired> { auth.applyTo(Request.Builder()) }
            assertFalse(auth.toString().contains("not json"))
        }

    @Test
    fun `an MQTT sink's broker credentials are its Basic user and its own secret`() =
        runTest {
            val mqtt = TransportConfig.Mqtt("mqtt.invalid", clientId = "c", baseTopic = "t")
            credentials.save(sink("m", AuthConfig.Basic("broker"), mqtt), CredentialEdits(secret = "pw"), settings)

            val broker = checkNotNull(credentials.mqttCredentials(checkNotNull(stored("m"))))

            assertEquals("broker" to "pw", broker.username to broker.password)
            assertFalse(broker.toString().contains("pw"))
            assertNull(credentials.mqttCredentials(sink("anon", transport = mqtt)))
        }

    @Test
    fun `deleting a sink removes it, then forgets every credential and releases its folder grant`() =
        runTest {
            settings.update { it.copy(sinks = it.sinks + sink("t", AuthConfig.Basic("u"), folder("content://tree/t"))) }
            StoredNames.all("t").forEach { secrets.put(it, "x") }
            credentials.sftpPublicKey("t")

            credentials.delete("t", settings)

            assertEquals(listOf(other), settings.current().sinks)
            assertEquals(setOf(StoredNames.secret("other")), secrets.names)
            assertNull(credentials.sftpKey("t"))
            assertEquals(listOf("content://tree/t"), folders.released)
        }

    @Test
    fun `a folder grant is released once no sink points at the tree any more`() =
        runTest {
            settings.update {
                it.copy(
                    sinks =
                        listOf(
                            sink("a", transport = folder("content://tree/shared")),
                            sink("b", transport = folder("content://tree/shared")),
                        ),
                )
            }

            credentials.save(sink("a", transport = folder("content://tree/new")), CredentialEdits(), settings)
            assertEquals(emptyList<String>(), folders.released, "b still writes to the shared tree")

            credentials.delete("b", settings)
            assertEquals(listOf("content://tree/shared"), folders.released)

            credentials.save(sink("a"), CredentialEdits(), settings)
            assertEquals(
                listOf("content://tree/shared", "content://tree/new"),
                folders.released,
                "leaving the folder transport",
            )
        }

    @Test
    fun `discarding a never-saved sink forgets what its visit stored, a saved one keeps everything`() =
        runTest {
            credentials.sftpPublicKey("fresh")
            secrets.put(StoredNames.tokens("fresh"), "{}")

            credentials.discard("fresh", settings, pickedTree = "content://tree/picked")

            assertNull(credentials.sftpKey("fresh"))
            assertEquals(setOf(StoredNames.secret("other")), secrets.names)
            assertEquals(listOf("content://tree/picked"), folders.released)

            settings.update { it.copy(sinks = it.sinks + sink("dir", transport = folder("content://tree/kept"))) }
            credentials.discard("other", settings, pickedTree = null)
            credentials.discard("dir", settings, pickedTree = "content://tree/kept")
            assertEquals(
                StoredCredentials(secret = true, clientSecret = false, tokens = false),
                credentials.stored("other"),
            )
            assertEquals(listOf("content://tree/picked"), folders.released, "the saved folder keeps its grant")
        }
}
