package app.btcourier.data

import app.btcourier.core.auth.Auth
import app.btcourier.core.auth.OidcClient
import app.btcourier.core.auth.OidcException
import app.btcourier.core.auth.OidcProviderMetadata
import app.btcourier.core.auth.RefreshingTokenSource
import app.btcourier.core.auth.TokenSet
import app.btcourier.core.auth.TokenSource
import app.btcourier.core.auth.TokenStore
import app.btcourier.core.sink.mqtt.MqttCredentials
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import java.security.GeneralSecurityException
import java.security.KeyPair
import java.security.interfaces.ECPublicKey
import kotlin.coroutines.CoroutineContext

/**
 * Releases the app's persisted Storage Access Framework grant on a tree; a seam over
 * `ContentResolver.releasePersistableUriPermission`. Releasing a grant the app does not hold does
 * nothing.
 */
fun interface FolderGrants {
    fun release(treeUri: String)
}

/** Which credentials are stored for a sink; never their values. */
data class StoredCredentials(
    val secret: Boolean,
    val clientSecret: Boolean,
    val tokens: Boolean,
)

/**
 * Credentials typed or obtained during one editor visit, for [SinkCredentials.save]; null keeps
 * what is stored.
 *
 * @property secret Basic password, bearer token or MQTT password.
 * @property tokens the token set of a sign-in, belonging to the issuer and client in the config
 *   it is saved with.
 */
class CredentialEdits(
    val secret: String? = null,
    val clientSecret: String? = null,
    val tokens: TokenSet? = null,
) {
    val isEmpty: Boolean get() = secret == null && clientSecret == null && tokens == null

    override fun toString() = "CredentialEdits(***)"
}

/**
 * Everything a sink authenticates with, keyed by sink id: its secret, OIDC client secret and token
 * set in [secrets], its SFTP key in [sshKeys], and the folder grant of a local-folder sink in
 * [folders]. Callers never see where or under which names these live.
 *
 * The lifecycle rules:
 * - [save] stores what the saved config's auth uses and forgets the rest, so switching a sink from
 *   Basic to None leaves no password behind, and tokens never outlive the issuer and client they
 *   were obtained for. A sink that is no longer SFTP loses its key; a tree no sink points at any
 *   more loses its grant.
 * - [delete] and [discard] forget everything stored for a sink id.
 * - A token rotation by a refresh that raced [signOut] is dropped instead of resurrecting the
 *   deleted tokens.
 *
 * @param folders only [save], [delete] and [discard] use it; a transport builder can leave it out.
 * @param io where blocking Keystore and grant calls run.
 */
class SinkCredentials(
    private val secrets: SecretStore,
    private val sshKeys: SshKeys,
    private val folders: FolderGrants = FolderGrants {},
    private val io: CoroutineContext = Dispatchers.IO,
) {
    suspend fun stored(sinkId: String) =
        StoredCredentials(
            secret = secrets.get(Names.secret(sinkId)) != null,
            clientSecret = secrets.get(Names.clientSecret(sinkId)) != null,
            tokens = secrets.get(Names.tokens(sinkId)) != null,
        )

    /** The stored OIDC client secret, for a sign-in when none was typed. */
    suspend fun clientSecret(sinkId: String): String? = secrets.get(Names.clientSecret(sinkId))

    /**
     * Saves [config] into [settings], replacing the sink with its id or appending it, together with
     * [edits]. Credentials the saved sink uses are written before the config, so an upload that
     * builds it finds them; what only the previous config used goes after.
     *
     * @return the config previously saved under this id, null for a new sink.
     */
    suspend fun save(
        config: SinkConfig,
        edits: CredentialEdits,
        settings: SettingsRepository,
    ): SinkConfig? {
        val id = config.id
        writeOrForget(Names.secret(id), config.auth.usesSecret, edits.secret)
        writeOrForget(Names.clientSecret(id), config.auth.usesClientSecret, edits.clientSecret)
        // Tokens from another issuer or client must not be offered to this one.
        writeOrForget(
            Names.tokens(id),
            (config.auth as? AuthConfig.Oidc)?.signedInEndpoints == true,
            edits.tokens?.encode(),
        )

        var previous: SinkConfig? = null
        var saved = emptyList<SinkConfig>()
        settings.update { s ->
            previous = s.sinks.firstOrNull { it.id == id }
            saved = if (previous != null) s.sinks.map { if (it.id == id) config else it } else s.sinks + config
            s.copy(sinks = saved)
        }
        if (config.transport !is TransportConfig.Sftp) withContext(io) { sshKeys.delete(Names.keyAlias(id)) }
        releaseUnused(previous?.treeUri, saved)
        return previous
    }

    suspend fun clearSecret(sinkId: String) = secrets.put(Names.secret(sinkId), null)

    /**
     * Deletes [sinkId]'s OIDC tokens; the client secret stays for the next sign-in. A running upload
     * holds the tokens in memory and could refresh them back, so [stopUploads] runs first, and only
     * when there is something to delete.
     *
     * @return whether tokens were deleted, i.e. whether uploads need restarting.
     */
    suspend fun signOut(
        sinkId: String,
        stopUploads: () -> Unit,
    ): Boolean {
        val name = Names.tokens(sinkId)
        if (secrets.get(name) == null) return false
        stopUploads()
        secrets.put(name, null)
        return true
    }

    /**
     * The OpenSSH `authorized_keys` line of [sinkId]'s SFTP key, creating the key first if needed.
     *
     * @throws GeneralSecurityException when the Keystore can't create or load it.
     */
    suspend fun sftpPublicKey(sinkId: String): String {
        val pair = withContext(io) { sshKeys.loadOrCreate(Names.keyAlias(sinkId)) }
        val public = pair.public as? ECPublicKey ?: throw GeneralSecurityException("not an EC key")
        return openSshPublicKey(public, comment = "btcourier-$sinkId")
    }

    /**
     * Removes [sinkId] from [settings], then everything stored for it. In that order, so no upload
     * builds the sink after its credentials are gone; an upload already running fails its batches
     * for this sink, which no longer matters since the store stops waiting for it.
     */
    suspend fun delete(
        sinkId: String,
        settings: SettingsRepository,
    ) {
        var removed: SinkConfig? = null
        var remaining = emptyList<SinkConfig>()
        settings.update { s ->
            removed = s.sinks.firstOrNull { it.id == sinkId }
            remaining = s.sinks.filterNot { it.id == sinkId }
            s.copy(sinks = remaining)
        }
        forget(sinkId)
        releaseUnused(removed?.treeUri, remaining)
    }

    /**
     * Undoes what an editor visit stored without saving: everything stored for [sinkId] if it is
     * not in [settings] (the SFTP key is created before the first save), and the grant on
     * [pickedTree] if no saved sink points at it.
     */
    suspend fun discard(
        sinkId: String,
        settings: SettingsRepository,
        pickedTree: String?,
    ) {
        val sinks = settings.current().sinks
        if (sinks.none { it.id == sinkId }) forget(sinkId)
        releaseUnused(pickedTree, sinks)
    }

    /**
     * What an HTTP transport sends for [config]'s auth. A missing secret sends an empty one, so the
     * server's refusal surfaces; an OIDC sink that can't refresh fails each request asking to sign
     * in again.
     */
    suspend fun httpAuth(
        config: SinkConfig,
        http: OkHttpClient,
    ): Auth =
        when (val auth = config.auth) {
            AuthConfig.None -> Auth.None
            is AuthConfig.Basic -> Auth.Basic(auth.user, secrets.get(Names.secret(config.id)).orEmpty())
            AuthConfig.Bearer -> Auth.Bearer(secrets.get(Names.secret(config.id)).orEmpty())
            is AuthConfig.Oidc -> Auth.Bearer(oidcTokens(config.id, auth, http))
        }

    /** The broker credentials of an MQTT sink with Basic auth; null for an anonymous one. */
    suspend fun mqttCredentials(config: SinkConfig): MqttCredentials? =
        (config.auth as? AuthConfig.Basic)?.let { MqttCredentials(it.user, secrets.get(Names.secret(config.id))) }

    /**
     * [sinkId]'s SFTP key, or null if it was never created.
     *
     * @throws GeneralSecurityException when the Keystore can't load it.
     */
    suspend fun sftpKey(sinkId: String): KeyPair? = withContext(io) { sshKeys.load(Names.keyAlias(sinkId)) }

    /**
     * Moves the secret earlier single-sink versions kept to [sinkId]'s, unless that already has one.
     * Idempotent, so a migration interrupted after it can run it again.
     */
    suspend fun adoptLegacySecret(sinkId: String) {
        val legacy = secrets.get(Names.LEGACY_SECRET) ?: return
        val target = Names.secret(sinkId)
        if (secrets.get(target) == null) secrets.put(target, legacy)
        secrets.put(Names.LEGACY_SECRET, null)
    }

    /** Deletes the legacy secret, which no sink uses once the migration is done. */
    suspend fun dropLegacySecret() = secrets.put(Names.LEGACY_SECRET, null)

    private suspend fun writeOrForget(
        name: String,
        used: Boolean,
        typed: String?,
    ) {
        when {
            !used -> secrets.put(name, null)
            typed != null -> secrets.put(name, typed)
        }
    }

    private suspend fun forget(sinkId: String) {
        Names.all(sinkId).forEach { secrets.put(it, null) }
        withContext(io) { sshKeys.delete(Names.keyAlias(sinkId)) }
    }

    // A grant belongs to the tree, not the sink: two sinks may share a folder.
    private suspend fun releaseUnused(
        treeUri: String?,
        sinks: List<SinkConfig>,
    ) {
        if (treeUri == null || sinks.any { it.treeUri == treeUri }) return
        withContext(io) { folders.release(treeUri) }
    }

    private suspend fun oidcTokens(
        id: String,
        auth: AuthConfig.Oidc,
        http: OkHttpClient,
    ): TokenSource {
        val tokenEndpoint = auth.tokenEndpoint
        val deviceEndpoint = auth.deviceAuthorizationEndpoint
        if (tokenEndpoint == null || deviceEndpoint == null) return notSignedIn("not signed in yet")
        val clientSecret =
            if (auth.clientAuthMethod ==
                OidcClientAuth.NONE
            ) {
                null
            } else {
                secrets.get(Names.clientSecret(id))
            }
        if (auth.clientAuthMethod != OidcClientAuth.NONE &&
            clientSecret == null
        ) {
            return notSignedIn("the client secret is missing")
        }
        val provider = OidcProviderMetadata(auth.issuer, tokenEndpoint.toHttpUrl(), deviceEndpoint.toHttpUrl())
        val client = OidcClient(auth.clientId, clientSecret, auth.clientAuthMethod.core)
        return RefreshingTokenSource(provider, client, http, SecretTokenStore(secrets, Names.tokens(id)))
    }

    // Sink.upload turns this into UploadResult.Failed, the same path a revoked sign-in takes.
    private fun notSignedIn(reason: String) = TokenSource { throw OidcException.ReauthorizationRequired(reason) }
}

/**
 * Where a sink's credentials live. These are on-device data: renaming any of them orphans what
 * earlier versions stored. Secret names match [SecretStore]'s `[A-Za-z0-9_-]+` rule.
 */
private object Names {
    fun secret(sinkId: String) = "sink_${sinkId}_secret"

    fun clientSecret(sinkId: String) = "sink_${sinkId}_oidc_client_secret"

    /** [TokenSet] JSON. */
    fun tokens(sinkId: String) = "sink_${sinkId}_oidc_tokens"

    fun all(sinkId: String) = listOf(secret(sinkId), clientSecret(sinkId), tokens(sinkId))

    /** AndroidKeyStore alias of the SFTP client key. */
    fun keyAlias(sinkId: String) = "btcourier.sftp.$sinkId"

    /** The single sink's password or token in versions before multiple sinks. */
    const val LEGACY_SECRET = "upload"
}

private val AuthConfig.usesSecret get() = this is AuthConfig.Basic || this is AuthConfig.Bearer

private val AuthConfig.usesClientSecret get() = this is AuthConfig.Oidc && clientAuthMethod != OidcClientAuth.NONE

private val SinkConfig.treeUri get() = (transport as? TransportConfig.LocalFolder)?.treeUri

/**
 * A [TokenStore] keeping one sink's [TokenSet] as JSON in [secrets] under [name].
 *
 * [TokenStore] is synchronous because `RefreshingTokenSource` calls it under a monitor from
 * `Auth.applyTo`, which core runs on `Dispatchers.IO`. Bridging with [runBlocking] is safe there:
 * it parks that IO thread only for one small file read or write, the secret store's mutex is held
 * by nobody that waits on this thread, and its own `withContext(Dispatchers.IO)` takes another
 * thread from the 64-thread IO pool rather than needing this one.
 *
 * A stored set that no longer parses reads as signed out, so the user is asked to sign in again
 * instead of every upload crashing.
 *
 * Only `RefreshingTokenSource` writes through [save], and only to rotate a set it loaded earlier;
 * the first set of a sign-in is written by [SinkCredentials.save]. So a rotation that finds the
 * slot empty is one that raced a sign-out (WorkManager cancellation is cooperative, a refresh in
 * flight still completes) and is dropped rather than resurrecting the deleted credentials.
 */
private class SecretTokenStore(
    private val secrets: SecretStore,
    private val name: String,
) : TokenStore {
    override fun load(): TokenSet? {
        val json = runBlocking { secrets.get(name) } ?: return null
        return try {
            TokenSet.decode(json)
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    override fun save(tokens: TokenSet?) =
        runBlocking {
            if (tokens != null && secrets.get(name) == null) return@runBlocking
            secrets.put(name, tokens?.encode())
        }

    override fun toString() = "SecretTokenStore(***)"
}
