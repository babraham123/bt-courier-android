package app.btcourier.data

import app.btcourier.core.auth.Auth
import app.btcourier.core.auth.OidcClient
import app.btcourier.core.auth.OidcException
import app.btcourier.core.auth.OidcProviderMetadata
import app.btcourier.core.auth.RefreshingTokenSource
import app.btcourier.core.auth.TokenSource
import app.btcourier.core.sink.Batch
import app.btcourier.core.sink.CompositeSink
import app.btcourier.core.sink.Sink
import app.btcourier.core.sink.UploadResult
import app.btcourier.core.sink.UploadTransport
import app.btcourier.core.sink.http.HttpPostUploadTransport
import app.btcourier.core.sink.http.HttpPutUploadTransport
import app.btcourier.core.sink.mqtt.MqttConfig
import app.btcourier.core.sink.mqtt.MqttCredentials
import app.btcourier.core.sink.mqtt.MqttUploadTransport
import app.btcourier.core.sink.sftp.SftpUploadTransport
import app.btcourier.data.saf.DocumentTree
import app.btcourier.data.saf.SafUploadTransport
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import java.security.GeneralSecurityException

/** Opens the SAF tree behind a [TransportConfig.LocalFolder]; a seam over `SafDocumentTree`. */
fun interface FolderAccess {
    fun tree(treeUri: String): DocumentTree
}

/** What [buildSinks] needs beyond settings and secrets; the Android parts sit behind interfaces. */
class SinkEnvironment(
    val http: OkHttpClient,
    val folders: FolderAccess,
    val sshKeys: SshKeys,
)

/** Ids of the enabled sinks; the store keeps every batch until all of them ack it. */
fun configuredSinkIds(settings: AppSettings): Set<String> =
    settings.sinks.filter { it.enabled }.mapTo(linkedSetOf()) { it.id }

/**
 * One sink per enabled [SinkConfig], with secrets from [secrets].
 *
 * A sink that can't work as configured (missing secret, no SFTP key or host-key pin, a URL that
 * does not parse, an OIDC sink never signed in) is still built: its uploads fail with the reason,
 * which the status screen shows, instead of the data silently waiting for a sink that never
 * appears or one bad sink stopping the others.
 *
 * Some transports ([MqttUploadTransport]) are [java.io.Closeable]; the caller closes them after
 * the upload round.
 */
suspend fun buildSinks(settings: AppSettings, secrets: SecretStore, env: SinkEnvironment): List<Sink> =
    settings.sinks.filter { it.enabled }.map { config ->
        val transport = try {
            buildTransport(config, secrets, env)
        } catch (e: IllegalArgumentException) {
            FailingTransport("invalid sink configuration: ${e.message}")
        } catch (e: GeneralSecurityException) {
            FailingTransport("SFTP key unavailable: ${e.javaClass.simpleName}")
        }
        CompositeSink(config.id, transport, config.format.format)
    }

private suspend fun buildTransport(config: SinkConfig, secrets: SecretStore, env: SinkEnvironment): UploadTransport {
    val id = config.id
    require(config.auth.kind in config.transport.kind.allowedAuth) {
        "${config.transport.kind} does not support ${config.auth.kind} authentication"
    }
    return when (val t = config.transport) {
        is TransportConfig.HttpPut ->
            HttpPutUploadTransport(t.url.trim(), env.http, httpAuth(config, secrets, env), t.compress)
        is TransportConfig.HttpPost ->
            HttpPostUploadTransport(t.url.trim(), env.http, httpAuth(config, secrets, env), t.encoding.core, t.nameQuery)
        is TransportConfig.Sftp -> {
            val pin = t.hostKeyFingerprint ?: return FailingTransport("SFTP host key not confirmed; fetch it in the sink settings")
            val keys = env.sshKeys.load(t.keyAlias) ?: return FailingTransport("SFTP key missing; open the sink settings to create one")
            SftpUploadTransport(t.host.trim(), t.port, t.username.trim(), t.remoteDir.trim(), keys.private, keys.public, pin)
        }
        is TransportConfig.Mqtt -> {
            val credentials = (config.auth as? AuthConfig.Basic)?.let {
                MqttCredentials(it.user, secrets.get(SinkSecrets.secret(id)))
            }
            MqttUploadTransport(MqttConfig(t.host.trim(), t.clientId, t.baseTopic.trim(), t.tls, t.port, credentials))
        }
        is TransportConfig.LocalFolder -> SafUploadTransport(env.folders.tree(t.treeUri))
    }
}

private suspend fun httpAuth(config: SinkConfig, secrets: SecretStore, env: SinkEnvironment): Auth {
    val id = config.id
    return when (val auth = config.auth) {
        AuthConfig.None -> Auth.None
        is AuthConfig.Basic -> Auth.Basic(auth.user, secrets.get(SinkSecrets.secret(id)).orEmpty())
        AuthConfig.Bearer -> Auth.Bearer(secrets.get(SinkSecrets.secret(id)).orEmpty())
        is AuthConfig.Oidc -> Auth.Bearer(oidcTokens(id, auth, secrets, env.http))
    }
}

private suspend fun oidcTokens(id: String, auth: AuthConfig.Oidc, secrets: SecretStore, http: OkHttpClient): TokenSource {
    val tokenEndpoint = auth.tokenEndpoint
    val deviceEndpoint = auth.deviceAuthorizationEndpoint
    if (tokenEndpoint == null || deviceEndpoint == null) return notSignedIn("not signed in yet")
    val clientSecret = if (auth.clientAuthMethod == OidcClientAuth.NONE) null else secrets.get(SinkSecrets.oidcClientSecret(id))
    if (auth.clientAuthMethod != OidcClientAuth.NONE && clientSecret == null) return notSignedIn("the client secret is missing")
    val provider = OidcProviderMetadata(auth.issuer, tokenEndpoint.toHttpUrl(), deviceEndpoint.toHttpUrl())
    val client = OidcClient(auth.clientId, clientSecret, auth.clientAuthMethod.core)
    return RefreshingTokenSource(provider, client, http, SecretTokenStore(secrets, SinkSecrets.oidcTokens(id)))
}

// Sink.upload turns this into UploadResult.Failed, the same path a revoked sign-in takes.
private fun notSignedIn(reason: String) = TokenSource { throw OidcException.ReauthorizationRequired(reason) }

/** Stands in for a transport that can't be built, so the sink's uploads report why. */
private class FailingTransport(private val reason: String) : UploadTransport {
    override suspend fun upload(batch: Batch): UploadResult = UploadResult.Failed(reason)

    override fun toString() = "FailingTransport($reason)"
}
