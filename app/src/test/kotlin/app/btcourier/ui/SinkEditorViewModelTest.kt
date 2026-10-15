package app.btcourier.ui

import app.btcourier.core.auth.TokenSet
import app.btcourier.data.AppSettings
import app.btcourier.data.AuthConfig
import app.btcourier.data.AuthKind
import app.btcourier.data.FormatKind
import app.btcourier.data.OidcClientAuth
import app.btcourier.data.PostEncoding
import app.btcourier.data.RecordingFolderGrants
import app.btcourier.data.SinkConfig
import app.btcourier.data.StoredNames
import app.btcourier.data.TransportConfig
import app.btcourier.data.TransportKind
import app.btcourier.data.testCredentials
import app.btcourier.ui.settings.HostKeyFetcher
import app.btcourier.ui.settings.HostKeyState
import app.btcourier.ui.settings.SinkEditorViewModel
import app.btcourier.ui.settings.SinkField
import app.btcourier.ui.settings.SignInState
import app.btcourier.ui.settings.SinkUrlProblem
import app.btcourier.work.InMemorySshKeys
import app.btcourier.work.MapSecretStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import java.io.IOException

@ExtendWith(MainDispatcherExtension::class)
class SinkEditorViewModelTest {
    private val other = SinkConfig("other", "Other", transport = TransportConfig.HttpPut("https://other.invalid/"), auth = AuthConfig.Bearer)
    private val settings = FakeSettings(AppSettings(sinks = listOf(other)))
    private val secrets = MapSecretStore(StoredNames.secret("other") to "other-token")
    private val work = RecordingWorkControl()
    private val folders = RecordingFolderGrants()
    private val credentials = testCredentials(secrets, InMemorySshKeys(), folders)
    private val fetched = mutableListOf<Pair<String, Int>>()
    private var hostKeyResult: () -> String = { "SHA256:presented" }

    private fun vm(id: String? = null) = SinkEditorViewModel(
        sinkId = id,
        settings = settings,
        credentials = credentials,
        work = work,
        http = OkHttpClient(),
        appScope = CoroutineScope(Dispatchers.Unconfined),
        hostKeys = HostKeyFetcher { host, port -> fetched += host to port; hostKeyResult() },
        io = Dispatchers.Unconfined,
    )

    private suspend fun stored(id: String) = settings.current().sinks.single { it.id == id }

    @Test
    fun `a new sink gets a fresh id, is appended, and its password goes to its own secret`() = runTest {
        val vm = vm()
        val id = vm.state.value.id
        assertTrue(vm.state.value.isNew)
        assertTrue(vm.state.value.form.mqttClientId.startsWith("btcourier-"), "MQTT client id is prefilled")

        vm.edit {
            it.copy(name = " Archive ", url = " https://sink.invalid/up/ ", format = FormatKind.CSV, authKind = AuthKind.BASIC, basicUser = "courier", secretInput = "s3cret")
        }
        vm.save()

        assertEquals(
            listOf(other, SinkConfig(id, "Archive", true, TransportConfig.HttpPut("https://sink.invalid/up/"), FormatKind.CSV, AuthConfig.Basic("courier"))),
            settings.current().sinks,
        )
        assertEquals("s3cret", secrets.get(StoredNames.secret(id)))
        assertEquals(listOf(false to true), work.uploads)
        val state = vm.state.value
        assertTrue(state.saved)
        assertFalse(state.isNew)
        assertTrue(state.hasStoredSecret)
        assertEquals("", state.form.secretInput)
    }

    @Test
    fun `editing keeps the id and position, and the stored secret is never read back`() = runTest {
        val first = SinkConfig("s1", "One", transport = TransportConfig.HttpPost("https://a.invalid/", PostEncoding.IDENTITY), auth = AuthConfig.Basic("u"))
        settings.update { it.copy(sinks = listOf(first, other)) }
        secrets.put(StoredNames.secret("s1"), "hunter22")
        val vm = vm("s1")

        val loaded = vm.state.value
        assertFalse(loaded.isNew)
        assertEquals(TransportKind.HTTP_POST, loaded.form.kind)
        assertEquals(PostEncoding.IDENTITY, loaded.form.postEncoding)
        assertTrue(loaded.hasStoredSecret)
        assertEquals("", loaded.form.secretInput)
        assertFalse(loaded.toString().contains("hunter22"))

        vm.edit { it.copy(name = "Renamed", url = "https://b.invalid/import?extra_label=car") }
        vm.save()

        assertEquals(listOf("s1", "other"), settings.current().sinks.map { it.id })
        assertEquals(first.copy(name = "Renamed", transport = TransportConfig.HttpPost("https://b.invalid/import?extra_label=car", PostEncoding.IDENTITY)), stored("s1"))
        assertEquals("hunter22", secrets.get(StoredNames.secret("s1")), "empty input keeps the stored secret")
    }

    @Test
    fun `HTTP URLs must be https, non-blank, and query-free for PUT`() = runTest {
        val vm = vm()
        vm.edit { it.copy(name = "N", url = "http://sink.invalid/") }
        vm.save()
        assertEquals(SinkUrlProblem.CLEARTEXT, vm.state.value.urlProblem)

        vm.edit { it.copy(url = " ") }
        vm.save()
        assertEquals(SinkUrlProblem.INVALID, vm.state.value.urlProblem)

        vm.edit { it.copy(url = "https://sink.invalid/?a=1") }
        vm.save()
        assertEquals(SinkUrlProblem.QUERY_OR_FRAGMENT, vm.state.value.urlProblem)

        assertEquals(listOf(other), settings.current().sinks, "nothing was saved")
        assertTrue(work.uploads.isEmpty())

        vm.setTransport(TransportKind.HTTP_POST)
        vm.save()
        assertNull(vm.state.value.urlProblem, "POST keeps its query")
    }

    @Test
    fun `the auth choice is limited to what the transport supports`() = runTest {
        val vm = vm()
        vm.setAuth(AuthKind.BEARER)
        assertEquals(AuthKind.BEARER, vm.state.value.form.authKind)

        vm.setTransport(TransportKind.MQTT)
        assertEquals(AuthKind.NONE, vm.state.value.form.authKind, "MQTT has no bearer auth")
        assertEquals(listOf(AuthKind.NONE, AuthKind.BASIC), vm.state.value.form.allowedAuth)
        vm.setAuth(AuthKind.OIDC)
        assertEquals(AuthKind.NONE, vm.state.value.form.authKind)
        vm.setAuth(AuthKind.BASIC)
        assertEquals(AuthKind.BASIC, vm.state.value.form.authKind)

        vm.setTransport(TransportKind.SFTP)
        assertEquals(AuthKind.NONE, vm.state.value.form.authKind)
        vm.setAuth(AuthKind.BASIC)
        assertEquals(AuthKind.NONE, vm.state.value.form.authKind)

        vm.setTransport(TransportKind.LOCAL_FOLDER)
        assertEquals(listOf(AuthKind.NONE), vm.state.value.form.allowedAuth)
    }

    @Test
    fun `an MQTT sink stores its broker password and follows the TLS port default`() = runTest {
        val vm = vm()
        val id = vm.state.value.id
        vm.setTransport(TransportKind.MQTT)
        assertEquals("8883", vm.state.value.form.mqttPort)
        vm.setMqttTls(false)
        assertEquals("1883", vm.state.value.form.mqttPort)
        vm.edit { it.copy(mqttPort = "11883") }
        vm.setMqttTls(true)
        assertEquals("11883", vm.state.value.form.mqttPort, "a custom port is kept")

        vm.edit { it.copy(name = "Broker", host = "mqtt.invalid", baseTopic = "car/obd", mqttClientId = "btcourier-1") }
        vm.setAuth(AuthKind.BASIC)
        vm.edit { it.copy(basicUser = "broker", secretInput = "pw") }
        vm.save()

        assertEquals(
            SinkConfig(id, "Broker", transport = TransportConfig.Mqtt("mqtt.invalid", 11883, true, "btcourier-1", "car/obd"), auth = AuthConfig.Basic("broker")),
            stored(id),
        )
        assertTrue(credentials.stored(id).secret)
    }

    @Test
    fun `required fields are reported per transport`() = runTest {
        val vm = vm()
        vm.setTransport(TransportKind.SFTP)
        vm.edit { it.copy(sftpPort = "70000") }
        vm.save()
        assertEquals(setOf(SinkField.NAME, SinkField.HOST, SinkField.PORT, SinkField.USERNAME), vm.state.value.invalid)

        vm.setTransport(TransportKind.LOCAL_FOLDER)
        vm.edit { it.copy(name = "Dir") }
        vm.save()
        assertEquals(setOf(SinkField.FOLDER), vm.state.value.invalid)

        vm.setTransport(TransportKind.MQTT)
        vm.edit { it.copy(host = "m.invalid", baseTopic = "car/#") }
        vm.save()
        assertEquals(setOf(SinkField.BASE_TOPIC), vm.state.value.invalid)
        assertEquals(listOf(other), settings.current().sinks)
    }

    @Test
    fun `SFTP shows the sink's public key and pins a host key only once the user trusts it`() = runTest {
        val vm = vm()
        val id = vm.state.value.id
        vm.setTransport(TransportKind.SFTP)

        assertEquals(credentials.sftpPublicKey(id), vm.state.value.publicKey)

        vm.edit { it.copy(name = "Box", host = " sftp.invalid ", sftpPort = "2222", sftpUser = "courier", remoteDir = "uploads") }
        vm.fetchHostKey()
        assertEquals(listOf("sftp.invalid" to 2222), fetched)
        assertEquals(HostKeyState.Presented("SHA256:presented"), vm.state.value.hostKey)
        assertNull(vm.state.value.form.hostKeyFingerprint, "presented is not trusted")

        vm.trustHostKey()
        vm.save()
        assertEquals(
            TransportConfig.Sftp("sftp.invalid", 2222, "courier", "uploads", "SHA256:presented"),
            stored(id).transport,
        )

        hostKeyResult = { throw IOException("connection refused") }
        vm.forgetHostKey()
        vm.fetchHostKey()
        assertEquals(HostKeyState.Failed("connection refused"), vm.state.value.hostKey)
        vm.save()
        assertNull((stored(id).transport as TransportConfig.Sftp).hostKeyFingerprint)
    }

    @Test
    fun `after saving, the editor shows which secrets the saved auth keeps`() = runTest {
        val oidc = SinkConfig(
            "o",
            "O",
            transport = TransportConfig.HttpPut("https://sink.invalid/"),
            auth = AuthConfig.Oidc("https://auth.invalid", "c", OidcClientAuth.SECRET_BASIC, "https://auth.invalid/t", "https://auth.invalid/d"),
        )
        settings.update { it.copy(sinks = listOf(oidc, other)) }
        secrets.put(StoredNames.clientSecret("o"), "client-secret")
        val vm = vm("o")
        assertTrue(vm.state.value.hasStoredClientSecret)

        vm.setAuth(AuthKind.BEARER)
        vm.edit { it.copy(secretInput = "tok") }
        vm.save()
        assertTrue(vm.state.value.hasStoredSecret)
        assertFalse(vm.state.value.hasStoredClientSecret)

        vm.setAuth(AuthKind.NONE)
        vm.save()
        assertFalse(vm.state.value.hasStoredSecret)
        assertEquals(AuthConfig.None, stored("o").auth)
    }

    @Test
    fun `changing the OIDC issuer drops the endpoints and tokens of the old one`() = runTest {
        val oidc = SinkConfig(
            "o",
            "O",
            transport = TransportConfig.HttpPut("https://sink.invalid/"),
            auth = AuthConfig.Oidc("https://auth.invalid", "c", OidcClientAuth.NONE, "https://auth.invalid/t", "https://auth.invalid/d"),
        )
        settings.update { it.copy(sinks = listOf(oidc)) }
        secrets.put(StoredNames.tokens("o"), TokenSet("a", "r", 0).encode())
        val vm = vm("o")
        assertEquals(SignInState.SignedIn, vm.state.value.signIn)

        vm.edit { it.copy(oidcIssuer = "https://other-auth.invalid") }
        vm.save()

        assertEquals(AuthConfig.Oidc("https://other-auth.invalid", "c"), stored("o").auth)
        assertFalse(credentials.stored("o").tokens)
        assertEquals(SignInState.SignedOut, vm.state.value.signIn)
    }

    @Test
    fun `deleting removes the sink and its credentials, then replaces the upload`() = runTest {
        val target = SinkConfig("t", "T", transport = TransportConfig.HttpPut("https://sink.invalid/"), auth = AuthConfig.Basic("u"))
        settings.update { it.copy(sinks = listOf(target, other)) }
        secrets.put(StoredNames.secret("t"), "x")
        val vm = vm("t")

        vm.delete()

        assertEquals(listOf(other), settings.current().sinks)
        assertFalse(credentials.stored("t").secret)
        assertTrue(vm.state.value.deleted)
        assertEquals(listOf(false to true), work.uploads)
    }

    @Test
    fun `discarding a never-saved sink drops its key and picked folder, a saved one keeps everything`() = runTest {
        val fresh = vm()
        val id = fresh.state.value.id
        fresh.setTransport(TransportKind.SFTP)
        assertNotNull(credentials.sftpKey(id))
        fresh.setTransport(TransportKind.LOCAL_FOLDER)
        fresh.edit { it.copy(treeUri = "content://tree/picked") }

        fresh.discard()

        assertNull(credentials.sftpKey(id))
        assertEquals(listOf("content://tree/picked"), folders.released)

        vm("other").discard()
        assertTrue(credentials.stored("other").secret)
        assertEquals(listOf(other), settings.current().sinks)
    }

    @Test
    fun `leaving the visit clears typed secrets from memory and still finishes a discard`() = runTest {
        val stores = VisitStores()
        val vm = stores.viewModel("v1") { vm() }
        val id = vm.state.value.id
        vm.setTransport(TransportKind.SFTP)
        vm.edit { it.copy(secretInput = "typed-password", oidcClientSecretInput = "typed-client-secret") }

        vm.discard()
        stores.keepOnly(null)

        assertEquals("", vm.state.value.form.secretInput)
        assertEquals("", vm.state.value.form.oidcClientSecretInput)
        assertNull(credentials.sftpKey(id))
        assertEquals(listOf(other), settings.current().sinks)
    }

    @Test
    fun `signing out with no stored tokens leaves the upload alone`() = runTest {
        val vm = vm("other")

        vm.signOut()

        assertEquals(emptyList<String>(), work.calls)
        assertTrue(credentials.stored("other").secret)
    }

    @Test
    fun `clearing the secret deletes it and replaces the upload`() = runTest {
        val vm = vm("other")
        assertTrue(vm.state.value.hasStoredSecret)

        vm.clearSecret()

        assertFalse(credentials.stored("other").secret)
        assertFalse(vm.state.value.hasStoredSecret)
        assertEquals(listOf(false to true), work.uploads)
    }
}
