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
import app.btcourier.data.CredentialEdits
import app.btcourier.data.FormatKind
import app.btcourier.data.OidcClientAuth
import app.btcourier.data.SettingsRepository
import app.btcourier.data.SinkConfig
import app.btcourier.data.SinkCredentials
import app.btcourier.data.TransportKind
import app.btcourier.data.newSinkId
import app.btcourier.data.sinkkind.FieldProblem
import app.btcourier.data.sinkkind.FieldValues
import app.btcourier.data.sinkkind.SinkTransportKind
import app.btcourier.data.sinkkind.SinkUrlProblem
import app.btcourier.data.sinkkind.TransportCapability
import app.btcourier.data.sinkkind.TransportField
import app.btcourier.data.sinkkind.capability
import app.btcourier.data.sinkkind.module
import app.btcourier.data.sinkkind.validateSinkUrl
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
import kotlin.coroutines.CoroutineContext

/**
 * What the editor holds: the fields every sink has, the auth fields, and each transport kind's own
 * values, so switching the transport back and forth keeps what was typed. Secret inputs hold only
 * what the user typed this visit: stored secrets are never read back into the form.
 *
 * @property transports each kind's values, as its [SinkTransportKind] defines them.
 * @property oidcSignedInAs issuer and client id the endpoints and tokens (stored, or staged by a
 *   sign-in this visit) were obtained with; editing either away from it means signing in again.
 */
data class SinkForm(
    val name: String = "",
    val enabled: Boolean = true,
    val kind: TransportKind = TransportKind.HTTP_PUT,
    val format: FormatKind = FormatKind.JSONL,
    val transports: Map<TransportKind, FieldValues> = TransportKind.entries.associateWith { it.module.defaults() },
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
    val transport: SinkTransportKind get() = kind.module

    /** The current kind's values. */
    val values: FieldValues get() = transports.getValue(kind)

    /** The auth kinds the editor offers for the current transport. */
    val allowedAuth: List<AuthKind> get() = transport.allowedAuth

    fun withValues(values: FieldValues) = copy(transports = transports + (kind to values))

    override fun toString() = "SinkForm(name=$name, kind=$kind, authKind=$authKind)"
}

/**
 * Common and auth fields left empty; drawn as errors. Transport fields report through
 * [SinkEditorState.transportProblems].
 */
enum class SinkField { NAME, BASIC_USER, OIDC_CLIENT_ID }

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
    /** OpenSSH `authorized_keys` line of this sink's key; null until a kind with [TransportCapability.ClientKey] is picked. */
    val publicKey: String? = null,
    val keyError: String? = null,
    val hostKey: HostKeyState = HostKeyState.Idle,
    val signIn: SignInState = SignInState.SignedOut,
    /** The current kind's [SinkTransportKind.validate] result as of the last save or action. */
    val transportProblems: Map<String, FieldProblem> = emptyMap(),
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
 * sink until the save commits them together. What is stored, and when it is forgotten, is
 * [credentials]' business.
 *
 * @param appScope runs cleanup that must finish after the screen has closed and this view model
 *   is cleared.
 * @param oidcSleep waits between device-flow polls; tests skip the interval.
 * @param io where network calls run; tests keep them on the test dispatcher.
 */
class SinkEditorViewModel(
    sinkId: String?,
    private val settings: SettingsRepository,
    private val credentials: SinkCredentials,
    private val work: WorkControl,
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
            val form = stored?.toForm() ?: SinkForm()
            val held = credentials.stored(id)
            val signedIn = held.tokens && (stored?.auth as? AuthConfig.Oidc)?.signedInEndpoints == true
            mutableState.value = SinkEditorState(
                loaded = true,
                id = id,
                isNew = stored == null,
                form = form,
                hasStoredSecret = held.secret,
                hasStoredClientSecret = held.clientSecret,
                signIn = if (signedIn) SignInState.SignedIn else SignInState.SignedOut,
            )
            if (form.transport.capability<TransportCapability.ClientKey>() != null) ensureKey()
        }
    }

    fun edit(transform: (SinkForm) -> SinkForm) {
        mutableState.update { it.copy(form = transform(it.form), saved = false) }
    }

    /**
     * Switches transport. Auth falls back to the new kind's first where the old one is not allowed.
     * A text field the new kind shares by key with the old one (URL, host) starts with what was
     * typed there if it is still empty. A kind with a [TransportCapability.ClientKey] gets its key
     * now, so its public half can be shown.
     */
    fun setTransport(kind: TransportKind) {
        val next = kind.module
        edit { f ->
            val carried = next.fields.filterIsInstance<TransportField.Text>().fold(f.transports.getValue(kind)) { values, field ->
                if (values[field.key].isBlank() && f.values[field.key].isNotBlank()) values.with(field.key, f.values[field.key]) else values
            }
            val auth = if (f.authKind in next.allowedAuth) f.authKind else next.allowedAuth.first()
            f.copy(kind = kind, authKind = auth).withValues(carried)
        }
        if (next.capability<TransportCapability.ClientKey>() != null) viewModelScope.launch { ensureKey() }
    }

    /** Sets one of the current kind's values; the kind may adjust others with it ([SinkTransportKind.adjust]). */
    fun setField(key: String, value: String?) = edit { f -> f.withValues(f.transport.adjust(f.values, f.values.with(key, value))) }

    /** Ignored for kinds the transport does not allow, so the UI can't store an impossible pair. */
    fun setAuth(kind: AuthKind) {
        if (kind in mutableState.value.form.allowedAuth) edit { it.copy(authKind = kind) }
    }

    fun clearSecret() {
        viewModelScope.launch {
            credentials.clearSecret(id)
            mutableState.update { it.copy(hasStoredSecret = false) }
            replaceUpload()
        }
    }

    /** For a kind with a [TransportCapability.HostKeyPin]: fetches the key to present, once host and port are valid. */
    fun fetchHostKey() {
        val form = mutableState.value.form
        val pin = form.transport.capability<TransportCapability.HostKeyPin>() ?: return
        val problems = form.transport.validate(form.values).filterKeys { it == pin.hostKey || it == pin.portKey }
        if (problems.isNotEmpty()) {
            mutableState.update { it.copy(transportProblems = it.transportProblems + problems) }
            return
        }
        val host = form.values[pin.hostKey].trim()
        val port = form.values[pin.portKey].trim().toInt()
        mutableState.update { it.copy(hostKey = HostKeyState.Fetching) }
        viewModelScope.launch {
            val next = try {
                HostKeyState.Presented(hostKeys.fetch(host, port))
            } catch (e: IOException) {
                HostKeyState.Failed(e.message ?: e.javaClass.simpleName)
            }
            mutableState.update { it.copy(hostKey = next) }
        }
    }

    /** Pins the presented fingerprint; stored with the next save. */
    fun trustHostKey() {
        val presented = mutableState.value.hostKey as? HostKeyState.Presented ?: return
        setPin(presented.fingerprint)
    }

    fun forgetHostKey() = setPin(null)

    private fun setPin(fingerprint: String?) {
        val pin = mutableState.value.form.transport.capability<TransportCapability.HostKeyPin>() ?: return
        mutableState.update {
            it.copy(form = it.form.withValues(it.form.values.with(pin.fingerprintKey, fingerprint)), hostKey = HostKeyState.Idle, saved = false)
        }
    }

    /**
     * Runs OIDC discovery and the device flow (RFC 8628) for the issuer and client in the form. On
     * success the tokens are staged and the discovered endpoints go into the form; [save] stores
     * them with the typed client secret and the rest of the sink.
     */
    fun signIn() {
        val form = mutableState.value.form
        val issuerProblem = validateSinkUrl(form.oidcIssuer, required = true)
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
            else -> credentials.clientSecret(id)
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
     * Drops a staged sign-in and, at once, the saved sink's stored tokens, cancelling a running
     * upload first ([SinkCredentials.signOut]); the replacing upload lets the other sinks carry on.
     */
    fun signOut() {
        signInJob?.cancel()
        stagedTokens = null
        viewModelScope.launch {
            if (credentials.signOut(id, stopUploads = work::cancelUpload)) replaceUpload()
            mutableState.update { it.copy(signIn = SignInState.SignedOut) }
        }
    }

    /**
     * Validates and saves. The sink's upload back-off is forgotten ([WorkControl.forgetRetries]):
     * the user just fixed or re-pointed it, so the next upload should try it at once.
     */
    fun save() {
        val form = mutableState.value.form
        val transportProblems = form.transport.validate(form.values)
        val issuerProblem = if (form.authKind == AuthKind.OIDC) validateSinkUrl(form.oidcIssuer, required = true) else null
        val invalid = invalidFields(form)
        mutableState.update { it.copy(transportProblems = transportProblems, issuerProblem = issuerProblem, invalid = invalid) }
        if (transportProblems.isNotEmpty() || issuerProblem != null || invalid.isNotEmpty()) return

        val config = form.toConfig()
        val staged = stagedTokens
        val edits = CredentialEdits(form.secretInput.ifEmpty { null }, form.oidcClientSecretInput.ifEmpty { null }, staged)
        viewModelScope.launch {
            val previous = credentials.save(config, edits, settings)
            work.forgetRetries(id)
            if (previous != config || !edits.isEmpty) replaceUpload()
            if (stagedTokens === staged) stagedTokens = null
            val stored = credentials.stored(id)
            mutableState.update {
                it.copy(
                    isNew = false,
                    form = it.form.copy(secretInput = "", oidcClientSecretInput = ""),
                    hasStoredSecret = stored.secret,
                    hasStoredClientSecret = stored.clientSecret,
                    signIn = if ((config.auth as? AuthConfig.Oidc)?.signedInEndpoints == true) it.signIn else SignInState.SignedOut,
                    saved = true,
                )
            }
        }
    }

    /** Deletes the sink, its credentials and its upload back-off. */
    fun delete() {
        signInJob?.cancel()
        viewModelScope.launch {
            credentials.delete(id, settings)
            work.forgetRetries(id)
            replaceUpload()
            mutableState.update { it.copy(deleted = true) }
        }
    }

    /**
     * Leaving without saving drops a staged sign-in, and [SinkCredentials.discard] undoes what the
     * visit stored (a never-saved sink's SFTP key, the grant on a folder picked but not saved). That
     * runs in [appScope] because the screen closes, and clears this view model, right away.
     */
    fun discard() {
        signInJob?.cancel()
        stagedTokens = null
        val pickedTree = mutableState.value.form.transports.firstNotNullOfOrNull { (kind, values) ->
            kind.module.capability<TransportCapability.FolderPick>()?.let { values.orNull(it.treeUriKey) }
        }
        appScope.launch { credentials.discard(id, settings, pickedTree) }
    }

    /** Typed secrets and staged tokens must not stay in memory once the visit is over. */
    override fun onCleared() {
        stagedTokens = null
        mutableState.update { it.copy(form = it.form.copy(secretInput = "", oidcClientSecretInput = "")) }
    }

    private suspend fun ensureKey() {
        if (mutableState.value.publicKey != null) return
        val line = try {
            credentials.sftpPublicKey(id)
        } catch (e: GeneralSecurityException) {
            mutableState.update { it.copy(keyError = e.message ?: e.javaClass.simpleName) }
            return
        }
        mutableState.update { it.copy(publicKey = line, keyError = null) }
    }

    private suspend fun replaceUpload() = work.uploadNow(settings.current().uploadOverCellular, replace = true)

    private fun invalidFields(f: SinkForm): Set<SinkField> = buildSet {
        if (f.name.isBlank()) add(SinkField.NAME)
        if (f.authKind == AuthKind.BASIC && f.basicUser.isBlank()) add(SinkField.BASIC_USER)
        if (f.authKind == AuthKind.OIDC && f.oidcClientId.isBlank()) add(SinkField.OIDC_CLIENT_ID)
    }

    private fun SinkForm.toConfig() = SinkConfig(
        id = id,
        name = name.trim(),
        enabled = enabled,
        transport = transport.toConfig(values),
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

internal fun SinkConfig.toForm(): SinkForm {
    val base = SinkForm(name = name, enabled = enabled, kind = transport.kind, format = format)
    val withTransport = base.withValues(transport.kind.module.toValues(transport))
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
