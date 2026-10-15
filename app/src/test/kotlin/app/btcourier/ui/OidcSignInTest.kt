package app.btcourier.ui

import app.btcourier.core.auth.TokenSet
import app.btcourier.data.AppSettings
import app.btcourier.data.AuthConfig
import app.btcourier.data.AuthKind
import app.btcourier.data.OidcClientAuth
import app.btcourier.data.SinkConfig
import app.btcourier.data.StoredNames
import app.btcourier.data.TransportConfig
import app.btcourier.data.sinkkind.HttpPutKind
import app.btcourier.data.sinkkind.SinkUrlProblem
import app.btcourier.data.testCredentials
import app.btcourier.ui.settings.SignInState
import app.btcourier.ui.settings.SinkForm
import app.btcourier.ui.settings.SinkEditorViewModel
import app.btcourier.work.MapSecretStore
import app.btcourier.work.RecordingWorkControl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.InetAddress
import java.net.URLDecoder
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The editor's OIDC sign-in (discovery, RFC 8628 device flow, token storage) against an
 * Authelia-shaped provider served over https by [MockWebServer]. The view model runs on a real
 * main thread here, since the flow waits on real network replies.
 *
 * A sign-in only stages its tokens: they belong to the issuer, client and sink URL in the form,
 * so nothing is stored or uploaded until the sink is saved.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OidcSignInTest {
    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient
    private lateinit var main: ExecutorCoroutineDispatcher

    private val sink = SinkConfig("o1", "Oidc", transport = TransportConfig.HttpPut("https://sink.invalid/"), auth = AuthConfig.Oidc("", ""))
    private val settings = FakeSettings(AppSettings(sinks = listOf(sink)))
    private val secrets = MapSecretStore()
    private val work = RecordingWorkControl()

    /** Each poll waits for one element, so the test decides when the provider is asked again. */
    private val polls = Channel<Unit>()

    private val issuer get() = server.url("/auth").toString()

    @BeforeEach
    fun setUp() {
        main = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        Dispatchers.setMain(main)
        val localhost = HeldCertificate.Builder()
            .addSubjectAlternativeName("localhost")
            .addSubjectAlternativeName(InetAddress.getByName("localhost").canonicalHostName)
            .build()
        server = MockWebServer().apply {
            useHttps(HandshakeCertificates.Builder().heldCertificate(localhost).build().sslSocketFactory())
            start()
        }
        val trust = HandshakeCertificates.Builder().addTrustedCertificate(localhost.certificate).build()
        client = OkHttpClient.Builder()
            .sslSocketFactory(trust.sslSocketFactory(), trust.trustManager)
            .callTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    @AfterEach
    fun tearDown() {
        server.close()
        Dispatchers.resetMain()
        main.close()
    }

    private fun vm() = SinkEditorViewModel(
        sinkId = "o1",
        settings = settings,
        credentials = testCredentials(secrets),
        work = work,
        http = client,
        appScope = CoroutineScope(main),
        oidcSleep = { polls.receive() },
    )

    private fun respondJson(code: Int, json: String) =
        server.enqueue(MockResponse.Builder().code(code).setHeader("Content-Type", "application/json").body(json).build())

    private fun respondDiscovery() = respondJson(
        200,
        """{"issuer":"$issuer","token_endpoint":"$issuer/api/oidc/token",
            "device_authorization_endpoint":"$issuer/api/oidc/device-authorization"}""",
    )

    private fun respondDeviceCode() = respondJson(
        200,
        """{"device_code":"dc-123","user_code":"WDJB-MJHT","verification_uri":"https://auth.invalid/device",
            "verification_uri_complete":"https://auth.invalid/device?user_code=WDJB-MJHT","expires_in":600,"interval":5}""",
    )

    private fun takeRequest(): RecordedRequest = checkNotNull(server.takeRequest(5, TimeUnit.SECONDS)) { "no request" }

    private fun RecordedRequest.form(): Map<String, String> = body!!.utf8().split('&').associate {
        val (k, v) = it.split('=', limit = 2)
        URLDecoder.decode(k, Charsets.UTF_8) to URLDecoder.decode(v, Charsets.UTF_8)
    }

    private fun <T> await(vm: SinkEditorViewModel, what: String, pick: (SignInState) -> T?): T = runBlocking {
        withTimeout(10_000) { vm.state.first { pick(it.signIn) != null } }.signIn.let(pick) ?: error(what)
    }

    private fun SinkEditorViewModel.startSignIn(extra: (SinkForm) -> SinkForm = { it }) = runBlocking(main) {
        edit { extra(it.copy(authKind = AuthKind.OIDC, oidcIssuer = issuer, oidcClientId = "bt-courier")) }
        signIn()
    }

    /** Runs the whole device flow up to signed in, the user approving at the first poll. */
    private fun signedIn(extra: (SinkForm) -> SinkForm = { it }): SinkEditorViewModel {
        respondDiscovery()
        respondDeviceCode()
        respondJson(200, """{"access_token":"at-1","token_type":"Bearer","refresh_token":"rt-1","expires_in":3600}""")
        val vm = vm()
        await(vm, "loaded") { it.takeIf { vm.state.value.loaded } }
        vm.startSignIn(extra)
        await(vm, "awaiting user") { it as? SignInState.AwaitingUser }
        runBlocking { polls.send(Unit) }
        await(vm, "signed in") { it as? SignInState.SignedIn }
        return vm
    }

    private fun assertNothingStored() {
        assertEquals(emptySet<String>(), secrets.names)
        assertEquals(sink, runBlocking { settings.current() }.sinks.single())
        assertEquals(emptyList<String>(), work.calls)
    }

    @Test
    fun `sign-in shows the user code while pending and stages the tokens, which saving stores with the endpoints`() {
        respondDiscovery()
        respondDeviceCode()
        respondJson(400, """{"error":"authorization_pending"}""")
        respondJson(200, """{"access_token":"at-1","token_type":"Bearer","refresh_token":"rt-1","expires_in":3600}""")
        val vm = vm()
        await(vm, "loaded") { it.takeIf { vm.state.value.loaded } }

        vm.startSignIn { it.copy(oidcClientAuth = OidcClientAuth.SECRET_POST, oidcClientSecretInput = "cs-1") }

        val awaiting = await(vm, "awaiting user") { it as? SignInState.AwaitingUser }
        assertEquals(SignInState.AwaitingUser("WDJB-MJHT", "https://auth.invalid/device", "https://auth.invalid/device?user_code=WDJB-MJHT"), awaiting)
        assertNull(runBlocking { secrets.get(StoredNames.tokens("o1")) })

        runBlocking {
            polls.send(Unit)
            polls.send(Unit)
        }
        await(vm, "signed in") { it as? SignInState.SignedIn }

        val discovery = takeRequest()
        assertEquals("/auth/.well-known/openid-configuration", discovery.target)
        val device = takeRequest()
        assertEquals("/auth/api/oidc/device-authorization", device.target)
        assertEquals("bt-courier", device.form()["client_id"])
        repeat(2) {
            val poll = takeRequest()
            assertEquals("/auth/api/oidc/token", poll.target)
            assertEquals("urn:ietf:params:oauth:grant-type:device_code", poll.form()["grant_type"])
            assertEquals("dc-123", poll.form()["device_code"])
            assertEquals("cs-1", poll.form()["client_secret"])
        }
        assertNothingStored()
        assertFalse(vm.state.value.toString().contains("at-1"))

        runBlocking(main) { vm.save() }
        runBlocking { withTimeout(10_000) { vm.state.first { it.saved } } }

        val tokens = TokenSet.decode(checkNotNull(runBlocking { secrets.get(StoredNames.tokens("o1")) }))
        assertEquals("at-1", tokens.accessToken)
        assertEquals("rt-1", tokens.refreshToken)
        assertEquals("cs-1", runBlocking { secrets.get(StoredNames.clientSecret("o1")) })
        val expected = AuthConfig.Oidc(issuer, "bt-courier", OidcClientAuth.SECRET_POST, "$issuer/api/oidc/token", "$issuer/api/oidc/device-authorization")
        assertEquals(expected, runBlocking { settings.current() }.sinks.single().auth, "the sink keeps the endpoints its tokens belong to")
        assertEquals(listOf(false to true), work.uploads)
        assertEquals(SignInState.SignedIn, vm.state.value.signIn)
    }

    @Test
    fun `signing in for another server and leaving without saving stores nothing`() {
        val vm = signedIn { it.withValues(it.values.with(HttpPutKind.URL_KEY, "https://other-sink.invalid/")) }

        runBlocking(main) { vm.discard() }

        assertNothingStored()
    }

    @Test
    fun `tokens staged for one issuer are not stored when the issuer is changed before saving`() {
        val vm = signedIn()

        runBlocking(main) {
            vm.edit { it.copy(oidcIssuer = "https://other-auth.invalid") }
            vm.save()
        }
        runBlocking { withTimeout(10_000) { vm.state.first { it.saved } } }

        assertEquals(emptySet<String>(), secrets.names)
        assertEquals(AuthConfig.Oidc("https://other-auth.invalid", "bt-courier"), runBlocking { settings.current() }.sinks.single().auth)
        assertEquals(SignInState.SignedOut, vm.state.value.signIn)
    }

    @Test
    fun `a denied request ends in an error and stores nothing`() {
        respondDiscovery()
        respondDeviceCode()
        respondJson(400, """{"error":"access_denied"}""")
        val vm = vm()
        await(vm, "loaded") { it.takeIf { vm.state.value.loaded } }

        vm.startSignIn()
        await(vm, "awaiting user") { it as? SignInState.AwaitingUser }
        runBlocking { polls.send(Unit) }

        val failed = await(vm, "failed") { it as? SignInState.Failed }
        assertTrue(failed.message.contains("denied"), failed.message)
        assertNothingStored()
    }

    @Test
    fun `cancelling while pending stops polling, and signing out cancels the upload before deleting the tokens`() {
        respondDiscovery()
        respondDeviceCode()
        val vm = vm()
        await(vm, "loaded") { it.takeIf { vm.state.value.loaded } }

        vm.startSignIn()
        await(vm, "awaiting user") { it as? SignInState.AwaitingUser }
        runBlocking(main) { vm.cancelSignIn() }

        assertEquals(SignInState.SignedOut, vm.state.value.signIn)
        assertEquals(2, server.requestCount, "no token request after cancelling")

        runBlocking { secrets.put(StoredNames.tokens("o1"), TokenSet("a", "r", 0).encode()) }
        var storedAtCancel = emptySet<String>()
        work.onCancelUpload = { storedAtCancel = secrets.names }
        runBlocking(main) { vm.signOut() }
        // The fakes never suspend, so anything signing out queued on main has run after this.
        runBlocking(main) {}
        assertNull(runBlocking { secrets.get(StoredNames.tokens("o1")) })
        assertEquals(setOf(StoredNames.tokens("o1")), storedAtCancel)
        assertEquals(listOf("cancel", "upload"), work.calls)
        assertEquals(listOf(false to true), work.uploads)
        assertEquals(SignInState.SignedOut, vm.state.value.signIn)
    }

    @Test
    fun `an http issuer is refused before any request`() {
        val vm = vm()
        await(vm, "loaded") { it.takeIf { vm.state.value.loaded } }

        runBlocking(main) {
            vm.edit { it.copy(authKind = AuthKind.OIDC, oidcIssuer = "http://auth.invalid", oidcClientId = "c") }
            vm.signIn()
        }

        assertEquals(SinkUrlProblem.CLEARTEXT, vm.state.value.issuerProblem)
        assertEquals(SignInState.SignedOut, vm.state.value.signIn)
        assertEquals(0, server.requestCount)
    }
}
