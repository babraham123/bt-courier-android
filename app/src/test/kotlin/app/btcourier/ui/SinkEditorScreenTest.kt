package app.btcourier.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import app.btcourier.R
import app.btcourier.core.auth.TokenSet
import app.btcourier.data.AppSettings
import app.btcourier.data.AuthConfig
import app.btcourier.data.OidcClientAuth
import app.btcourier.data.SinkConfig
import app.btcourier.data.StoredNames
import app.btcourier.data.TransportConfig
import app.btcourier.data.testCredentials
import app.btcourier.ui.settings.HostKeyFetcher
import app.btcourier.ui.settings.SinkEditorScreen
import app.btcourier.ui.settings.SinkEditorViewModel
import app.btcourier.work.MapSecretStore
import app.btcourier.work.RecordingWorkControl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SinkEditorScreenTest : ScreenTest() {
    private val bearer =
        SinkConfig(
            "b",
            "Token sink",
            transport = TransportConfig.HttpPut("https://sink.invalid/"),
            auth = AuthConfig.Bearer,
        )
    private val settings = FakeSettings(AppSettings(sinks = listOf(bearer)))
    private val secrets = MapSecretStore(StoredNames.secret(bearer.id) to STORED_SECRET)
    private val credentials = testCredentials(secrets)
    private var done = 0

    private fun show(id: String? = null) {
        val vm =
            SinkEditorViewModel(
                sinkId = id,
                settings = settings,
                credentials = credentials,
                work = RecordingWorkControl(),
                http = OkHttpClient(),
                appScope = CoroutineScope(Dispatchers.Unconfined),
                hostKeys = HostKeyFetcher { _, _ -> error("no host key fetch expected") },
                io = Dispatchers.Unconfined,
            )
        setScreen { SinkEditorScreen(vm, onDone = { done++ }) }
    }

    private fun sinks() = runBlocking { settings.current().sinks }

    /** Opens the auth dropdown and checks it offers exactly [offered] of the four auth kinds. */
    private fun assertAuthOptions(vararg offered: Int) {
        field(R.string.settings_auth).tap()
        for (kind in AUTH_KINDS) {
            val item = compose.onNode(inOpenMenu(string(kind)))
            if (kind in offered) item.assertExists() else item.assertDoesNotExist()
        }
        menuItem(offered.first()).performClick()
    }

    @Test
    fun `a new HTTP PUT sink asks for its URL and offers every auth kind`() {
        show()

        onText(R.string.sink_add_title).assertIsDisplayed()
        field(R.string.sink_transport).assertTextContains(string(R.string.transport_http_put))
        field(R.string.settings_sink_url).assertExists()
        assertAuthOptions(*AUTH_KINDS.toIntArray())
    }

    @Test
    fun `switching to SFTP shows its server fields, public key and host key, and no auth choice`() {
        show()

        pick(R.string.sink_transport, R.string.transport_sftp)

        field(R.string.sink_host).assertExists()
        field(R.string.sink_port).assertTextContains("22")
        field(R.string.settings_username).assertExists()
        field(R.string.sink_remote_dir).assertExists()
        onText(R.string.sink_public_key_help).assertExists()
        onText(R.string.sink_copy).assertExists()
        onText(R.string.sink_host_key_fetch).assertExists()
        compose.onNodeWithText(string(R.string.settings_sink_url)).assertDoesNotExist()
        compose.onNodeWithText(string(R.string.settings_auth)).assertDoesNotExist()
    }

    @Test
    fun `MQTT offers only None and Basic, and Basic asks for a username and a password`() {
        show()
        pick(R.string.sink_transport, R.string.transport_mqtt)

        field(R.string.sink_base_topic).assertExists()
        assertAuthOptions(R.string.settings_auth_none, R.string.settings_auth_basic)
        pick(R.string.settings_auth, R.string.settings_auth_basic)

        field(R.string.settings_username).assertExists()
        field(R.string.settings_password).assertIsPassword()
    }

    @Test
    fun `a typed token is masked and never shown as text`() {
        show()
        pick(R.string.settings_auth, R.string.settings_auth_bearer)

        field(R.string.settings_token).assertIsPassword().performTextInput(TYPED_SECRET)

        assertNoText(TYPED_SECRET, substring = true)
    }

    @Test
    fun `a stored secret is never shown, only that one is saved, and it can be removed`() {
        show(bearer.id)

        onText(R.string.sink_edit_title).assertIsDisplayed()
        field(R.string.settings_token).assertIsPassword()
        assertNoText(STORED_SECRET, substring = true)

        onText(R.string.settings_secret_clear).tap()

        assertNoText(R.string.settings_secret_clear)
        assertFalse(runBlocking { credentials.stored(bearer.id).secret })
    }

    @Test
    fun `saving with problems marks them and stays open`() {
        show()

        field(R.string.settings_sink_url).performTextInput("http://sink.invalid/up/")
        onText(R.string.settings_save).tap()

        field(R.string.sink_name).assertIsInError()
        onText(R.string.settings_url_cleartext).assertExists()
        assertEquals(listOf(bearer), sinks())
        assertEquals(0, done)
    }

    @Test
    fun `a valid new sink is saved and the editor closes`() {
        show()

        field(R.string.sink_name).performTextInput("Archive")
        field(R.string.settings_sink_url).performTextInput("https://sink.invalid/up/")
        onText(R.string.settings_save).tap()

        compose.waitForIdle()
        assertEquals(listOf("Token sink", "Archive"), sinks().map { it.name })
        assertEquals(1, done)
    }

    @Test
    fun `delete asks first, then removes the sink and closes`() {
        show(bearer.id)

        onText(R.string.sink_delete).tap()
        onText(R.string.sink_delete_confirm).assertIsDisplayed()
        dialogButton(R.string.sink_delete).performClick()

        compose.waitForIdle()
        assertEquals(emptyList<SinkConfig>(), sinks())
        assertEquals(1, done)
    }

    @Test
    fun `a signed-in OIDC sink signs out`() {
        val oidc =
            SinkConfig(
                "o",
                "OIDC sink",
                transport = TransportConfig.HttpPut("https://sink.invalid/"),
                auth =
                    AuthConfig.Oidc(
                        "https://auth.invalid",
                        "c",
                        OidcClientAuth.NONE,
                        "https://auth.invalid/t",
                        "https://auth.invalid/d",
                    ),
            )
        settings.settings.value = AppSettings(sinks = listOf(oidc))
        runBlocking { secrets.put(StoredNames.tokens(oidc.id), TokenSet("a", "r", 0).encode()) }
        show(oidc.id)

        onText(R.string.sink_oidc_signed_in).assertExists()
        onText(R.string.sink_oidc_sign_out).tap()

        onText(R.string.sink_oidc_sign_in).assertExists()
        assertFalse(runBlocking { credentials.stored(oidc.id).tokens })
    }

    @Test
    fun `back leaves without saving`() {
        show()
        field(R.string.sink_name).performTextReplacement("Unsaved")

        compose.onNodeWithContentDescription(string(R.string.nav_back)).performClick()

        assertEquals(1, done)
        assertTrue(sinks().none { it.name == "Unsaved" })
    }

    private companion object {
        const val STORED_SECRET = "stored-token-value"
        const val TYPED_SECRET = "typed-token-value"
        val AUTH_KINDS =
            listOf(
                R.string.settings_auth_none,
                R.string.settings_auth_basic,
                R.string.settings_auth_bearer,
                R.string.settings_auth_oidc,
            )
    }
}
