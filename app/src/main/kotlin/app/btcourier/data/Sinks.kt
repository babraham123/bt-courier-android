package app.btcourier.data

import app.btcourier.core.sink.Batch
import app.btcourier.core.sink.CompositeSink
import app.btcourier.core.sink.Sink
import app.btcourier.core.sink.UploadResult
import app.btcourier.core.sink.UploadTransport
import app.btcourier.core.sink.http.HttpPostUploadTransport
import app.btcourier.core.sink.http.HttpPutUploadTransport
import app.btcourier.core.sink.mqtt.MqttConfig
import app.btcourier.core.sink.mqtt.MqttUploadTransport
import app.btcourier.core.sink.sftp.SftpUploadTransport
import app.btcourier.data.saf.DocumentTree
import app.btcourier.data.saf.SafUploadTransport
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
 * One sink per enabled [SinkConfig], with credentials from [secrets] and [SinkEnvironment.sshKeys]
 * ([SinkCredentials]).
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
            buildTransport(config, SinkCredentials(secrets, env.sshKeys), env)
        } catch (e: IllegalArgumentException) {
            FailingTransport("invalid sink configuration: ${e.message}")
        } catch (e: GeneralSecurityException) {
            FailingTransport("SFTP key unavailable: ${e.javaClass.simpleName}")
        }
        CompositeSink(config.id, transport, config.format.format)
    }

private suspend fun buildTransport(config: SinkConfig, credentials: SinkCredentials, env: SinkEnvironment): UploadTransport {
    require(config.auth.kind in config.transport.kind.allowedAuth) {
        "${config.transport.kind} does not support ${config.auth.kind} authentication"
    }
    return when (val t = config.transport) {
        is TransportConfig.HttpPut ->
            HttpPutUploadTransport(t.url.trim(), env.http, credentials.httpAuth(config, env.http), t.compress)
        is TransportConfig.HttpPost ->
            HttpPostUploadTransport(t.url.trim(), env.http, credentials.httpAuth(config, env.http), t.encoding.core, t.nameQuery)
        is TransportConfig.Sftp -> {
            val pin = t.hostKeyFingerprint ?: return FailingTransport("SFTP host key not confirmed; fetch it in the sink settings")
            val keys = credentials.sftpKey(config.id) ?: return FailingTransport("SFTP key missing; open the sink settings to create one")
            SftpUploadTransport(t.host.trim(), t.port, t.username.trim(), t.remoteDir.trim(), keys.private, keys.public, pin)
        }
        is TransportConfig.Mqtt -> {
            MqttUploadTransport(MqttConfig(t.host.trim(), t.clientId, t.baseTopic.trim(), t.tls, t.port, credentials.mqttCredentials(config)))
        }
        is TransportConfig.LocalFolder -> SafUploadTransport(env.folders.tree(t.treeUri))
    }
}

/** Stands in for a transport that can't be built, so the sink's uploads report why. */
private class FailingTransport(private val reason: String) : UploadTransport {
    override suspend fun upload(batch: Batch): UploadResult = UploadResult.Failed(reason)

    override fun toString() = "FailingTransport($reason)"
}
