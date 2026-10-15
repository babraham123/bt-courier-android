package app.btcourier.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.btcourier.core.auth.DeviceAuthorization
import app.btcourier.core.auth.OidcClient
import app.btcourier.core.auth.OidcDiscovery
import app.btcourier.core.auth.OidcException
import app.btcourier.core.auth.TokenSet
import app.btcourier.core.sink.sftp.SftpUploadTransport
import app.btcourier.data.AuthConfig
import app.btcourier.data.AuthKind
import app.btcourier.data.FormatKind
import app.btcourier.data.OidcClientAuth
import app.btcourier.data.PostEncoding
import app.btcourier.data.SecretStore
import app.btcourier.data.SettingsRepository
import app.btcourier.data.SinkConfig
import app.btcourier.data.SinkSecrets
import app.btcourier.data.SshKeys
import app.btcourier.data.TransportConfig
import app.btcourier.data.TransportKind
import app.btcourier.data.defaultMqttPort
import app.btcourier.data.deleteSink
import app.btcourier.data.deleteSinkCredentials
import app.btcourier.data.newMqttClientId
import app.btcourier.data.newSinkId
import app.btcourier.data.openSshPublicKey
import app.btcourier.data.sftpKeyAlias
import app.btcourier.ui.WorkControl
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.interfaces.ECPublicKey
import kotlin.coroutines.CoroutineContext

/**
 * Every field the editor can show, for all transports at once, so switching the transport back and
 * forth keeps what was typed. Secret inputs hold only what the user typed this visit: stored
 * secrets are never read back into the form.
 *
 * @property oidcSignedInAs issuer and client id the endpoints and tokens (stored, or staged by a
 *   sign-in this visit) were obtained with; editing either away from it means signing in again.
 */
data class SinkForm(
    val name: String = "",
    val enabled: Boolean = true,
    val kind: TransportKind = TransportKind.HTTP_PUT,
    val format: FormatKind = FormatKind.JSONL,
    val url: String = "",
    val compress: Boolean = true,
    val postEncoding: PostEncoding = PostEncoding.GZIP_FILE,
    val nameQuery: Boolean = false,
    val host: String = "",
    val sftpPort: String = "22",
    val sftpUser: String = "",
    val remoteDir: String = "",
    val hostKeyFingerprint: String? = null,
    val mqttTls: Boolean = true,
    val mqttPort: String = defaultMqttPort(true).toString(),
    val mqttClientId: String = "",
    val baseTopic: String = "btcourier",
    val treeUri: String? = null,
    val authKind: AuthKind = AuthKind.NONE,
    val basicUser: String = "",
    val secretInput: String = "",
    val oidcIssuer: String = "",
    val oidcClientId: String = "",
    val oidcClientAuth: OidcClientAuth = OidcClientAuth.NONE,
    val oidcClientSecretInput: String = "",
    val oidcTokenEndpoint: String? = null,
    val oidcDeviceEndpoint: String? = null,
    val oidcSignedInAs: Pair<String, String>? = null,
) {
    /** The auth kinds the editor offers for the current transport. */
    val allowedAuth: List<AuthKind> get() = kind.allowedAuth

    override fun toString() = "SinkForm(name=$name, kind=$kind, authKind=$authKind)"
}

/** Required fields left empty or unusable; drawn as errors on the matching inputs. */
enum class SinkField { NAME, HOST, PORT, USERNAME, MQTT_CLIENT_ID, BASE_TOPIC, FOLDER, BASIC_USER, OIDC_CLIENT_ID }

sealed interface SignInState {
    data object SignedOut : SignInState

    /** Discovery and the device authorization request are in flight. */
    data object Starting : SignInState

    /** Waiting for the user to approve [userCode] at [verificationUri]; polling meanwhile. */
    data class AwaitingUser(val userCode: String, val verificationUri: String, val openUri: String) : SignInState

    /** Tokens are stored, or staged by this visit's sign-in until the next save. */
    data object SignedIn : SignInState

    /** [message] names the OAuth error or network failure, never a token or secret. */
    data class Failed(val message: String) : SignInState
}

sealed interface HostKeyState {
    data object Idle : HostKeyState

    data object Fetching : HostKeyState

    /** Shown for the user to compare with the server's own `ssh-keygen -lf`; not trusted yet. */
    data class Presented(val fingerprint: String) : HostKeyState

    data class Failed(val message: String) : HostKeyState
}

data class SinkEditorState(
    val loaded: Boolean = false,
    val id: String = "",
    /** Not in the settings yet: leaving without saving drops anything stored for its id. */
    val isNew: Boolean = true,
    val form: SinkForm = SinkForm(),
    val hasStoredSecret: Boolean = false,
    val hasStoredClientSecret: Boolean = false,
    /** OpenSSH `authorized_keys` line of this sink's SFTP key; null until the SFTP transport is picked. */
    val publicKey: String? = null,
    val keyError: String? = null,
    val hostKey: HostKeyState = HostKeyState.Idle,
    val signIn: SignInState = SignInState.SignedOut,
    val urlProblem: SinkUrlProblem? = null,
    val issuerProblem: SinkUrlProblem? = null,
    val invalid: Set<SinkField> = emptySet(),
    val saved: Boolean = false,
    /** The sink was deleted; the screen closes. */
    val deleted: Boolean = false,
)

/** Fetches an SSH server's host key fingerprint; a seam over [SftpUploadTransport.fetchHostKeyFingerprint]. */
fun interface HostKeyFetcher {
    suspend fun fetch(host: String, port: Int): String
}

/**
 * Edits one sink: [sinkId] for an existing one, null for a new one, which gets a fresh id at once
 * so the SFTP key can be created under it before the first save.
 *
 * Nothing but that key reaches storage before [save]: an OIDC sign-in only stages its tokens here,
 * since they belong to the issuer, client and sink URL in the form, which may differ from the saved
 * sink until the save commits them together.
 *
 * @param appScope runs cleanup that must finish after the screen has closed and this view model
 *   is cleared.
 * @param oidcSleep waits between device-flow polls; tests skip the interval.
 * @param io where blocking Keystore and network calls run; tests keep them on the test dispatcher.
 */
class SinkEditorViewModel(
    sinkId: String?,
    private val settings: SettingsRepository,
    private val secrets: SecretStore,
    private val work: WorkControl,
    private val sshKeys: SshKeys,
    private val http: OkHttpClient,
    private val appScope: CoroutineScope,
    private val hostKeys: HostKeyFetcher = HostKeyFetcher { host, port -> SftpUploadTransport.fetchHostKeyFingerprint(host, port) },
    private val oidcSleep: suspend (Long) -> Unit = { delay(it) },
    private val io: CoroutineContext = Dispatchers.IO,
) : ViewModel() {
    private val id = sinkId ?: newSinkId()
    private val mutableState = MutableStateFlow(SinkEditorState(id = id))
    val state: StateFlow<SinkEditorState> = mutableState.asStateFlow()

    private var signInJob: Job? = null

    /** Tokens from this visit's sign-in; stored by [save], dropped when the editor is left unsaved. */
    private var stagedTokens: TokenSet? = null

    init {
        viewModelScope.launch {
            val stored = settings.current().sinks.firstOrNull { it.id == id }
            val form = stored?.toForm() ?: SinkForm(mqttClientId = newMqttClientId())
            val tokens = secrets.get(SinkSecrets.oidcTokens(id))
            val signedIn = tokens != null && (stored?.auth as? AuthConfig.Oidc)?.signedInEndpoints == true
            mutableState.value = SinkEditorState(
                loaded = true,
                id = id,
                isNew = stored == null,
                form = form,
                hasStoredSecret = secrets.get(SinkSecrets.secret(id)) != null,
                hasStoredClientSecret = secrets.get(SinkSecrets.oidcClientSecret(id)) != null,
                signIn = if (signedIn) SignInState.SignedIn else SignInState.SignedOut,
            )
            if (form.kind == TransportKind.SFTP) ensureKey()
        }
    }

    fun edit(transform: (SinkForm) -> SinkForm) {
        mutableState.update { it.copy(form = transform(it.form), saved = false) }
    }

    /**
     * Switches transport. Auth falls back to None where the old kind is not allowed; picking SFTP
     * creates the sink's key so its public half can be shown.
     */
    fun setTransport(kind: TransportKind) {
        edit { f -> f.copy(kind = kind, authKind = if (f.authKind in kind.allowedAuth) f.authKind else AuthKind.NONE) }
        if (kind == TransportKind.SFTP) viewModelScope.launch { ensureKey() }
    }

    /** Ignored for kinds the transport does not allow, so the UI can't store an impossible pair. */
    fun setAuth(kind: AuthKind) {
        if (kind in mutableState.value.form.allowedAuth) edit { it.copy(authKind = kind) }
    }

    /** Keeps the port on the protocol default when it was on the other one. */
    fun setMqttTls(tls: Boolean) = edit { f ->
        val port = if (f.mqttPort == defaultMqttPort(!tls).toString()) defaultMqttPort(tls).toString() else f.mqttPort
        f.copy(mqttTls = tls, mqttPort = port)
    }

    fun clearSecret() {
        viewModelScope.launch {
            secrets.put(SinkSecrets.secret(id), null)
            mutableState.update { it.copy(hasStoredSecret = false) }
            replaceUpload()
        }
    }

    fun fetchHostKey() {
        val form = mutableState.value.form
        val port = form.sftpPort.trim().toIntOrNull()
        val missing = setOfNotNull(SinkField.HOST.takeIf { form.host.isBlank() }, SinkField.PORT.takeIf { port !in 1..65535 })
        if (missing.isNotEmpty() || port == null) {
            mutableState.update { it.copy(invalid = it.invalid + missing) }
            return
        }
        mutableState.update { it.copy(hostKey = HostKeyState.Fetching) }
        viewModelScope.launch {
            val next = try {
                HostKeyState.Presented(hostKeys.fetch(form.host.trim(), port))
            } catch (e: IOException) {
                HostKeyState.Failed(e.message ?: e.javaClass.simpleName)
            }
            mutableState.update { it.copy(hostKey = next) }
        }
    }

    /** Pins the presented fingerprint; stored with the next save. */
    fun trustHostKey() {
        val presented = mutableState.value.hostKey as? HostKeyState.Presented ?: return
        mutableState.update { it.copy(form = it.form.copy(hostKeyFingerprint = presented.fingerprint), hostKey = HostKeyState.Idle, saved = false) }
    }

    fun forgetHostKey() {
        mutableState.update { it.copy(form = it.form.copy(hostKeyFingerprint = null), hostKey = HostKeyState.Idle, saved = false) }
    }

    /**
     * Runs OIDC discovery and the device flow (RFC 8628) for the issuer and client in the form. On
     * success the tokens are staged and the discovered endpoints go into the form; [save] stores
     * them with the typed client secret and the rest of the sink.
     */
    fun signIn() {
        val form = mutableState.value.form
        val issuerProblem = validateSinkUrl(form.oidcIssuer) ?: SinkUrlProblem.INVALID.takeIf { form.oidcIssuer.isBlank() }
        val clientIdMissing = form.oidcClientId.isBlank()
        mutableState.update {
            it.copy(issuerProblem = issuerProblem, invalid = if (clientIdMissing) it.invalid + SinkField.OIDC_CLIENT_ID else it.invalid - SinkField.OIDC_CLIENT_ID)
        }
        if (issuerProblem != null || clientIdMissing) return

        signInJob?.cancel()
        signInJob = viewModelScope.launch {
            mutableState.update { it.copy(signIn = SignInState.Starting) }
            val next = try {
                runSignIn(form)
                SignInState.SignedIn
            } catch (e: CancellationException) {
                throw e
            } catch (e: OidcException) {
                SignInState.Failed(e.message.orEmpty())
            } catch (e: IOException) {
                SignInState.Failed("network error: ${e.message ?: e.javaClass.simpleName}")
            } catch (e: IllegalArgumentException) {
                SignInState.Failed(e.message.orEmpty())
            }
            mutableState.update { it.copy(signIn = next) }
        }
    }

    private suspend fun runSignIn(form: SinkForm) {
        val issuer = form.oidcIssuer.trim()
        val clientId = form.oidcClientId.trim()
        val clientSecret = when {
            form.oidcClientAuth == OidcClientAuth.NONE -> null
            form.oidcClientSecretInput.isNotEmpty() -> form.oidcClientSecretInput
            else -> secrets.get(SinkSecrets.oidcClientSecret(id))
                ?: throw IllegalArgumentException("enter the client secret, or choose a public client")
        }
        // OkHttp reads a response body on the calling thread, so every exchange runs on io.
        val provider = withContext(io) { OidcDiscovery(http).discover(issuer) }
        val flow = DeviceAuthorization(provider, OidcClient(clientId, clientSecret, form.oidcClientAuth.core), http, sleep = oidcSleep)
        val grant = withContext(io) { flow.start() }
        mutableState.update {
            it.copy(signIn = SignInState.AwaitingUser(grant.userCode, grant.verificationUri, grant.verificationUriComplete ?: grant.verificationUri))
        }
        stagedTokens = withContext(io) { flow.poll(grant) }
        mutableState.update {
            it.copy(
                form = it.form.copy(
                    oidcTokenEndpoint = provider.tokenEndpoint.toString(),
                    oidcDeviceEndpoint = provider.deviceAuthorizationEndpoint.toString(),
                    oidcSignedInAs = issuer to clientId,
                ),
            )
        }
    }

    /** Stops polling; called when the screen leaves. */
    fun cancelSignIn() {
        signInJob?.cancel()
        signInJob = null
        mutableState.update { if (it.signIn is SignInState.SignedIn) it else it.copy(signIn = SignInState.SignedOut) }
    }

    /**
     * Drops a staged sign-in and, at once, the saved sink's stored tokens. A running upload holds
     * those tokens in memory and could refresh them back into the store, so it is cancelled before
     * they are deleted; the replacing upload lets the other sinks carry on.
     */
    fun signOut() {
        signInJob?.cancel()
        stagedTokens = null
        viewModelScope.launch {
            val tokens = SinkSecrets.oidcTokens(id)
            if (secrets.get(tokens) != null) {
                work.cancelUpload()
                secrets.put(tokens, null)
                replaceUpload()
            }
            mutableState.update { it.copy(signIn = SignInState.SignedOut) }
        }
    }

    fun save() {
        val current = mutableState.value
        val form = current.form
        val urlProblem = when (form.kind) {
            TransportKind.HTTP_PUT, TransportKind.HTTP_POST ->
                validateSinkUrl(form.url, allowQuery = form.kind == TransportKind.HTTP_POST)
                    ?: SinkUrlProblem.INVALID.takeIf { form.url.isBlank() }
            else -> null
        }
        val issuerProblem = if (form.authKind == AuthKind.OIDC) {
            validateSinkUrl(form.oidcIssuer) ?: SinkUrlProblem.INVALID.takeIf { form.oidcIssuer.isBlank() }
        } else {
            null
        }
        val invalid = invalidFields(form)
        mutableState.update { it.copy(urlProblem = urlProblem, issuerProblem = issuerProblem, invalid = invalid) }
        if (urlProblem != null || issuerProblem != null || invalid.isNotEmpty()) return

        val config = form.toConfig()
        val staged = stagedTokens
        viewModelScope.launch {
            val previous = settings.current().sinks.firstOrNull { it.id == id }
            // Secrets first, so an upload that builds the saved sink finds its credentials.
            storeSecrets(form, staged)
            settings.update { s ->
                val sinks = if (s.sinks.any { it.id == id }) s.sinks.map { if (it.id == id) config else it } else s.sinks + config
                s.copy(sinks = sinks)
            }
            if (previous?.transport is TransportConfig.Sftp && config.transport !is TransportConfig.Sftp) {
                withContext(io) { sshKeys.delete(sftpKeyAlias(id)) }
            }
            if (previous != config || form.secretInput.isNotEmpty() || form.oidcClientSecretInput.isNotEmpty() || staged != null) replaceUpload()
            if (stagedTokens === staged) stagedTokens = null
            mutableState.update {
                it.copy(
                    isNew = false,
                    form = it.form.copy(secretInput = "", oidcClientSecretInput = ""),
                    hasStoredSecret = config.auth.usesSecret && (it.hasStoredSecret || form.secretInput.isNotEmpty()),
                    hasStoredClientSecret = config.auth.usesClientSecret && (it.hasStoredClientSecret || form.oidcClientSecretInput.isNotEmpty()),
                    signIn = if ((config.auth as? AuthConfig.Oidc)?.signedInEndpoints == true) it.signIn else SignInState.SignedOut,
                    saved = true,
                )
            }
        }
    }

    fun delete() {
        signInJob?.cancel()
        viewModelScope.launch {
            deleteSink(id, settings, secrets, sshKeys, io)
            replaceUpload()
            mutableState.update { it.copy(deleted = true) }
        }
    }

    /**
     * Leaving without saving drops a staged sign-in; for a never-saved sink also whatever was
     * stored for its id meanwhile (the SFTP key). The deletion runs in [appScope] because the
     * screen closes, and clears this view model, right away.
     */
    fun discard() {
        signInJob?.cancel()
        stagedTokens = null
        if (!mutableState.value.isNew) return
        appScope.launch { deleteSinkCredentials(id, secrets, sshKeys, io) }
    }

    /** Typed secrets and staged tokens must not stay in memory once the visit is over. */
    override fun onCleared() {
        stagedTokens = null
        mutableState.update { it.copy(form = it.form.copy(secretInput = "", oidcClientSecretInput = "")) }
    }

    /**
     * Writes typed secrets and [staged] tokens, and deletes the ones the saved auth no longer uses,
     * so switching a sink from Basic to None does not leave its password behind.
     */
    private suspend fun storeSecrets(form: SinkForm, staged: TokenSet?) {
        val auth = form.toAuth()
        val secretName = SinkSecrets.secret(id)
        when {
            !auth.usesSecret -> secrets.put(secretName, null)
            form.secretInput.isNotEmpty() -> secrets.put(secretName, form.secretInput)
        }
        val clientSecretName = SinkSecrets.oidcClientSecret(id)
        when {
            !auth.usesClientSecret -> secrets.put(clientSecretName, null)
            form.oidcClientSecretInput.isNotEmpty() -> secrets.put(clientSecretName, form.oidcClientSecretInput)
        }
        val tokensName = SinkSecrets.oidcTokens(id)
        when {
            // Tokens from another issuer or client must not be offered to this one.
            auth !is AuthConfig.Oidc || !auth.signedInEndpoints -> secrets.put(tokensName, null)
            staged != null -> secrets.put(tokensName, staged.encode())
        }
    }

    private suspend fun ensureKey() {
        if (mutableState.value.publicKey != null) return
        val line = try {
            val pair = withContext(io) { sshKeys.loadOrCreate(sftpKeyAlias(id)) }
            val public = pair.public as? ECPublicKey ?: throw GeneralSecurityException("not an EC key")
            openSshPublicKey(public, comment = "btcourier-$id")
        } catch (e: GeneralSecurityException) {
            mutableState.update { it.copy(keyError = e.message ?: e.javaClass.simpleName) }
            return
        }
        mutableState.update { it.copy(publicKey = line, keyError = null) }
    }

    private suspend fun replaceUpload() = work.uploadNow(settings.current().uploadOverCellular, replace = true)

    private fun invalidFields(f: SinkForm): Set<SinkField> = buildSet {
        if (f.name.isBlank()) add(SinkField.NAME)
        when (f.kind) {
            TransportKind.HTTP_PUT, TransportKind.HTTP_POST -> Unit
            TransportKind.SFTP -> {
                if (f.host.isBlank()) add(SinkField.HOST)
                if (f.sftpPort.trim().toIntOrNull() !in 1..65535) add(SinkField.PORT)
                if (f.sftpUser.isBlank()) add(SinkField.USERNAME)
            }
            TransportKind.MQTT -> {
                if (f.host.isBlank()) add(SinkField.HOST)
                if (f.mqttPort.trim().toIntOrNull() !in 1..65535) add(SinkField.PORT)
                if (f.mqttClientId.isBlank()) add(SinkField.MQTT_CLIENT_ID)
                if (f.baseTopic.any { it == '+' || it == '#' } || f.baseTopic.trim().startsWith("$")) add(SinkField.BASE_TOPIC)
            }
            TransportKind.LOCAL_FOLDER -> if (f.treeUri == null) add(SinkField.FOLDER)
        }
        if (f.authKind == AuthKind.BASIC && f.basicUser.isBlank()) add(SinkField.BASIC_USER)
        if (f.authKind == AuthKind.OIDC && f.oidcClientId.isBlank()) add(SinkField.OIDC_CLIENT_ID)
    }

    private fun SinkForm.toConfig() = SinkConfig(
        id = id,
        name = name.trim(),
        enabled = enabled,
        transport = when (kind) {
            TransportKind.HTTP_PUT -> TransportConfig.HttpPut(url.trim(), compress)
            TransportKind.HTTP_POST -> TransportConfig.HttpPost(url.trim(), postEncoding, nameQuery)
            TransportKind.SFTP -> TransportConfig.Sftp(
                host = host.trim(),
                port = sftpPort.trim().toInt(),
                username = sftpUser.trim(),
                remoteDir = remoteDir.trim(),
                hostKeyFingerprint = hostKeyFingerprint,
                keyAlias = sftpKeyAlias(id),
            )
            TransportKind.MQTT -> TransportConfig.Mqtt(host.trim(), mqttPort.trim().toInt(), mqttTls, mqttClientId.trim(), baseTopic.trim())
            TransportKind.LOCAL_FOLDER -> TransportConfig.LocalFolder(checkNotNull(treeUri))
        },
        format = format,
        auth = toAuth(),
    )
}

/** The form's auth, constrained to what its transport allows. */
internal fun SinkForm.toAuth(): AuthConfig {
    val kind = if (authKind in allowedAuth) authKind else AuthKind.NONE
    return when (kind) {
        AuthKind.NONE -> AuthConfig.None
        AuthKind.BASIC -> AuthConfig.Basic(basicUser.trim())
        AuthKind.BEARER -> AuthConfig.Bearer
        AuthKind.OIDC -> {
            val issuer = oidcIssuer.trim()
            val clientId = oidcClientId.trim()
            // Endpoints only count for the issuer and client they were discovered and signed in with.
            val current = oidcSignedInAs == issuer to clientId
            AuthConfig.Oidc(
                issuer = issuer,
                clientId = clientId,
                clientAuthMethod = oidcClientAuth,
                tokenEndpoint = oidcTokenEndpoint.takeIf { current },
                deviceAuthorizationEndpoint = oidcDeviceEndpoint.takeIf { current },
            )
        }
    }
}

private val AuthConfig.usesSecret get() = this is AuthConfig.Basic || this is AuthConfig.Bearer

private val AuthConfig.usesClientSecret get() = this is AuthConfig.Oidc && clientAuthMethod != OidcClientAuth.NONE

internal fun SinkConfig.toForm(): SinkForm {
    val base = SinkForm(name = name, enabled = enabled, kind = transport.kind, format = format, mqttClientId = newMqttClientId())
    val withTransport = when (val t = transport) {
        is TransportConfig.HttpPut -> base.copy(url = t.url, compress = t.compress)
        is TransportConfig.HttpPost -> base.copy(url = t.url, postEncoding = t.encoding, nameQuery = t.nameQuery)
        is TransportConfig.Sftp -> base.copy(
            host = t.host,
            sftpPort = t.port.toString(),
            sftpUser = t.username,
            remoteDir = t.remoteDir,
            hostKeyFingerprint = t.hostKeyFingerprint,
        )
        is TransportConfig.Mqtt -> base.copy(host = t.host, mqttPort = t.port.toString(), mqttTls = t.tls, mqttClientId = t.clientId, baseTopic = t.baseTopic)
        is TransportConfig.LocalFolder -> base.copy(treeUri = t.treeUri)
    }
    return when (val a = auth) {
        AuthConfig.None -> withTransport.copy(authKind = AuthKind.NONE)
        is AuthConfig.Basic -> withTransport.copy(authKind = AuthKind.BASIC, basicUser = a.user)
        AuthConfig.Bearer -> withTransport.copy(authKind = AuthKind.BEARER)
        is AuthConfig.Oidc -> withTransport.copy(
            authKind = AuthKind.OIDC,
            oidcIssuer = a.issuer,
            oidcClientId = a.clientId,
            oidcClientAuth = a.clientAuthMethod,
            oidcTokenEndpoint = a.tokenEndpoint,
            oidcDeviceEndpoint = a.deviceAuthorizationEndpoint,
            oidcSignedInAs = if (a.signedInEndpoints) a.issuer to a.clientId else null,
        )
    }
}
