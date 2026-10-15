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
import app.btcourier.data.sinkkind.FieldProblem
import app.btcourier.data.sinkkind.HttpPostKind
import app.btcourier.data.sinkkind.HttpPutKind
import app.btcourier.data.sinkkind.LocalFolderKind
import app.btcourier.data.sinkkind.MqttKind
import app.btcourier.data.sinkkind.SftpKind
import app.btcourier.data.sinkkind.SinkUrlProblem
import app.btcourier.data.testCredentials
import app.btcourier.ui.settings.HostKeyFetcher
import app.btcourier.ui.settings.HostKeyState
import app.btcourier.ui.settings.SignInState
import app.btcourier.ui.settings.SinkEditorViewModel
import app.btcourier.ui.settings.SinkField
import app.btcourier.work.InMemorySshKeys
import app.btcourier.work.MapSecretStore
import app.btcourier.work.RecordingWorkControl
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

/**
 * The editor's own behaviour over any transport kind: loading and saving through
 * [app.btcourier.data.SinkCredentials], reporting the kind's validation, switching kinds, the
 * kind capabilities it drives, and forgetting the sink's upload back-off on save and delete. What
 * each kind accepts is [app.btcourier.data.sinkkind.SinkTransportKindsTest]'s business.
 */
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

    private fun SinkEditorViewModel.setFields(vararg values: Pair<String, String>) = values.forEach { (key, value) -> setField(key, value) }

    @Test
    fun `a new sink gets a fresh id, is appended, its password goes to its own secret, and its back-off is forgotten`() = runTest {
        val vm = vm()
        val id = vm.state.value.id
        assertTrue(vm.state.value.isNew)

        vm.setField(HttpPutKind.URL_KEY, " https://sink.invalid/up/ ")
        vm.edit { it.copy(name = " Archive ", format = FormatKind.CSV, authKind = AuthKind.BASIC, basicUser = "courier", secretInput = "s3cret") }
        vm.save()

        assertEquals(
            listOf(other, SinkConfig(id, "Archive", true, TransportConfig.HttpPut("https://sink.invalid/up/"), FormatKind.CSV, AuthConfig.Basic("courier"))),
            settings.current().sinks,
        )
        assertEquals("s3cret", secrets.get(StoredNames.secret(id)))
        assertEquals(listOf("forget $id", "upload"), work.calls, "the back-off goes before the replacing upload")
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
        assertEquals(PostEncoding.IDENTITY.name, loaded.form.values[HttpPostKind.ENCODING])
        assertTrue(loaded.hasStoredSecret)
        assertEquals("", loaded.form.secretInput)
        assertFalse(loaded.toString().contains("hunter22"))

        vm.edit { it.copy(name = "Renamed") }
        vm.setField(HttpPostKind.URL_KEY, "https://b.invalid/import?extra_label=car")
        vm.save()

        assertEquals(listOf("s1", "other"), settings.current().sinks.map { it.id })
        assertEquals(first.copy(name = "Renamed", transport = TransportConfig.HttpPost("https://b.invalid/import?extra_label=car", PostEncoding.IDENTITY)), stored("s1"))
        assertEquals("hunter22", secrets.get(StoredNames.secret("s1")), "empty input keeps the stored secret")
        assertEquals(listOf("forget s1", "upload"), work.calls)
    }

    @Test
    fun `the kind's problems are reported by field and block saving, together with a blank name`() = runTest {
        val vm = vm()
        vm.setField(HttpPutKind.URL_KEY, "http://sink.invalid/")
        vm.save()
        assertEquals(mapOf(HttpPutKind.URL_KEY to FieldProblem.Url(SinkUrlProblem.CLEARTEXT)), vm.state.value.transportProblems)
        assertEquals(setOf(SinkField.NAME), vm.state.value.invalid)

        vm.setTransport(TransportKind.SFTP)
        vm.edit { it.copy(name = "Box") }
        vm.setField(SftpKind.PORT, "70000")
        vm.save()
        assertEquals(setOf(SftpKind.HOST, SftpKind.PORT, SftpKind.USERNAME), vm.state.value.transportProblems.keys)
        assertEquals(emptySet<SinkField>(), vm.state.value.invalid)

        vm.setTransport(TransportKind.LOCAL_FOLDER)
        vm.save()
        assertEquals(setOf(LocalFolderKind.TREE_URI), vm.state.value.transportProblems.keys)

        assertEquals(listOf(other), settings.current().sinks, "nothing was saved")
        assertTrue(work.calls.isEmpty())
    }

    @Test
    fun `switching kinds keeps each kind's values and carries a shared text field into an empty one`() = runTest {
        val vm = vm()
        vm.edit { it.copy(name = "N") }
        vm.setField(HttpPutKind.URL_KEY, "https://sink.invalid/import?a=1")

        vm.setTransport(TransportKind.HTTP_POST)
        assertEquals("https://sink.invalid/import?a=1", vm.state.value.form.values[HttpPostKind.URL_KEY])
        vm.setTransport(TransportKind.MQTT)
        vm.setField(MqttKind.HOST, "broker.invalid")
        vm.setTransport(TransportKind.SFTP)
        assertEquals("broker.invalid", vm.state.value.form.values[SftpKind.HOST], "host carried over")
        assertEquals("22", vm.state.value.form.values[SftpKind.PORT], "SFTP keeps its own port")
        vm.setTransport(TransportKind.HTTP_POST)
        vm.save()

        assertEquals(emptyMap<String, FieldProblem>(), vm.state.value.transportProblems, "POST keeps its query")
        assertEquals(TransportConfig.HttpPost("https://sink.invalid/import?a=1"), stored(vm.state.value.id).transport)
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
    fun `an MQTT sink stores its broker password, and the kind adjusts the port as TLS is flipped`() = runTest {
        val vm = vm()
        val id = vm.state.value.id
        vm.setTransport(TransportKind.MQTT)
        vm.setField(MqttKind.TLS, "false")
        assertEquals("1883", vm.state.value.form.values[MqttKind.PORT])

        vm.edit { it.copy(name = "Broker") }
        vm.setFields(MqttKind.HOST to "mqtt.invalid", MqttKind.BASE_TOPIC to "car/obd", MqttKind.CLIENT_ID to "btcourier-1")
        vm.setAuth(AuthKind.BASIC)
        vm.edit { it.copy(basicUser = "broker", secretInput = "pw") }
        vm.save()

        assertEquals(
            SinkConfig(id, "Broker", transport = TransportConfig.Mqtt("mqtt.invalid", 1883, false, "btcourier-1", "car/obd"), auth = AuthConfig.Basic("broker")),
            stored(id),
        )
        assertTrue(credentials.stored(id).secret)
    }

    @Test
    fun `SFTP shows the sink's public key and pins a host key only once the user trusts it`() = runTest {
        val vm = vm()
        val id = vm.state.value.id
        vm.setTransport(TransportKind.SFTP)

        assertEquals(credentials.sftpPublicKey(id), vm.state.value.publicKey)

        vm.fetchHostKey()
        assertEquals(setOf(SftpKind.HOST), vm.state.value.transportProblems.keys, "no fetch without a host")
        assertEquals(emptyList<Pair<String, Int>>(), fetched)

        vm.edit { it.copy(name = "Box") }
        vm.setFields(SftpKind.HOST to " sftp.invalid ", SftpKind.PORT to "2222", SftpKind.USERNAME to "courier", SftpKind.REMOTE_DIR to "uploads")
        vm.fetchHostKey()
        assertEquals(listOf("sftp.invalid" to 2222), fetched)
        assertEquals(HostKeyState.Presented("SHA256:presented"), vm.state.value.hostKey)
        assertEquals("", vm.state.value.form.values[SftpKind.FINGERPRINT], "presented is not trusted")

        vm.trustHostKey()
        vm.save()
        assertEquals(TransportConfig.Sftp("sftp.invalid", 2222, "courier", "uploads", "SHA256:presented"), stored(id).transport)

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
    fun `deleting removes the sink, its credentials and its back-off, then replaces the upload`() = runTest {
        val target = SinkConfig("t", "T", transport = TransportConfig.HttpPut("https://sink.invalid/"), auth = AuthConfig.Basic("u"))
        settings.update { it.copy(sinks = listOf(target, other)) }
        secrets.put(StoredNames.secret("t"), "x")
        val vm = vm("t")

        vm.delete()

        assertEquals(listOf(other), settings.current().sinks)
        assertFalse(credentials.stored("t").secret)
        assertTrue(vm.state.value.deleted)
        assertEquals(listOf("forget t", "upload"), work.calls)
        assertEquals(listOf(false to true), work.uploads)
    }

    @Test
    fun `discarding a never-saved sink drops its key and picked folder, a saved one keeps everything`() = runTest {
        val fresh = vm()
        val id = fresh.state.value.id
        fresh.setTransport(TransportKind.SFTP)
        assertNotNull(credentials.sftpKey(id))
        fresh.setTransport(TransportKind.LOCAL_FOLDER)
        fresh.setField(LocalFolderKind.TREE_URI, "content://tree/picked")

        fresh.discard()

        assertNull(credentials.sftpKey(id))
        assertEquals(listOf("content://tree/picked"), folders.released)

        vm("other").discard()
        assertTrue(credentials.stored("other").secret)
        assertEquals(listOf(other), settings.current().sinks)
        assertTrue(work.calls.isEmpty(), "discarding keeps the back-off")
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
