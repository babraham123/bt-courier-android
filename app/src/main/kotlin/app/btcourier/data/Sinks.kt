package app.btcourier.data

import app.btcourier.core.sink.Batch
import app.btcourier.core.sink.CompositeSink
import app.btcourier.core.sink.Sink
import app.btcourier.core.sink.UploadResult
import app.btcourier.core.sink.UploadTransport
import app.btcourier.core.sink.mqtt.MqttUploadTransport
import app.btcourier.data.saf.DocumentTree
import app.btcourier.data.sinkkind.module
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
 * One sink per enabled [SinkConfig], built by its transport kind's module
 * ([app.btcourier.data.sinkkind.SinkTransportKind.build]) with credentials from [secrets] and
 * [SinkEnvironment.sshKeys] ([SinkCredentials]).
 *
 * A sink that can't work as configured (missing secret, no SFTP key or host-key pin, a URL that
 * does not parse, an OIDC sink never signed in) is still built: its uploads fail with the reason,
 * which the status screen shows, instead of the data silently waiting for a sink that never
 * appears or one bad sink stopping the others.
 *
 * Some transports ([MqttUploadTransport]) are [java.io.Closeable]; the caller closes them after
 * the upload round.
 */
suspend fun buildSinks(
    settings: AppSettings,
    secrets: SecretStore,
    env: SinkEnvironment,
): List<Sink> =
    settings.sinks.filter { it.enabled }.map { config ->
        val transport =
            try {
                buildTransport(config, SinkCredentials(secrets, env.sshKeys), env)
            } catch (e: IllegalArgumentException) {
                FailingTransport("invalid sink configuration: ${e.message}")
            } catch (e: GeneralSecurityException) {
                FailingTransport("SFTP key unavailable: ${e.javaClass.simpleName}")
            }
        CompositeSink(config.id, transport, config.format.format)
    }

private suspend fun buildTransport(
    config: SinkConfig,
    credentials: SinkCredentials,
    env: SinkEnvironment,
): UploadTransport {
    val kind = config.transport.kind
    require(config.auth.kind in kind.module.allowedAuth) { "$kind does not support ${config.auth.kind} authentication" }
    return kind.module.build(config, credentials, env)
}

/** Stands in for a transport that can't be built, so the sink's uploads report why. */
internal class FailingTransport(
    private val reason: String,
) : UploadTransport {
    override suspend fun upload(batch: Batch): UploadResult = UploadResult.Failed(reason)

    override fun toString() = "FailingTransport($reason)"
}
