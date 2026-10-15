package app.btcourier.data.sinkkind

import app.btcourier.R
import app.btcourier.core.sink.UploadTransport
import app.btcourier.core.sink.sftp.SftpUploadTransport
import app.btcourier.data.AuthKind
import app.btcourier.data.FailingTransport
import app.btcourier.data.SinkConfig
import app.btcourier.data.SinkCredentials
import app.btcourier.data.SinkEnvironment
import app.btcourier.data.TransportConfig
import app.btcourier.data.TransportKind

/**
 * Authenticates with the sink's own Keystore key, so no auth kind but None applies, and uploads
 * only to a server whose host key the user confirmed (trust on first use).
 */
object SftpKind : SinkTransportKind {
    const val HOST = "host"
    const val PORT = "sftpPort"
    const val USERNAME = "username"
    const val REMOTE_DIR = "remoteDir"
    const val FINGERPRINT = "hostKeyFingerprint"

    override val kind = TransportKind.SFTP
    override val label = R.string.transport_sftp
    override val allowedAuth = listOf(AuthKind.NONE)
    override val fields =
        listOf(
            TransportField.Text(HOST, R.string.sink_host, keyboard = FieldKeyboard.URI),
            TransportField.Text(PORT, R.string.sink_port, keyboard = FieldKeyboard.NUMBER),
            TransportField.Text(USERNAME, R.string.settings_username),
            TransportField.Text(REMOTE_DIR, R.string.sink_remote_dir, R.string.sink_remote_dir_help),
        )
    override val capabilities =
        listOf(TransportCapability.ClientKey, TransportCapability.HostKeyPin(HOST, PORT, FINGERPRINT))

    override fun defaults() = FieldValues(PORT to "22")

    override fun toValues(config: TransportConfig): FieldValues {
        val t = config as TransportConfig.Sftp
        return FieldValues(HOST to t.host, PORT to t.port.toString(), USERNAME to t.username, REMOTE_DIR to t.remoteDir)
            .with(FINGERPRINT, t.hostKeyFingerprint)
    }

    override fun validate(values: FieldValues) =
        problemsOf(
            HOST to values.blankProblem(HOST),
            PORT to values.portProblem(PORT),
            USERNAME to values.blankProblem(USERNAME),
        )

    override fun toConfig(values: FieldValues) =
        TransportConfig.Sftp(
            host = values[HOST].trim(),
            port = values[PORT].trim().toInt(),
            username = values[USERNAME].trim(),
            remoteDir = values[REMOTE_DIR].trim(),
            hostKeyFingerprint = values.orNull(FINGERPRINT),
        )

    override suspend fun build(
        config: SinkConfig,
        credentials: SinkCredentials,
        env: SinkEnvironment,
    ): UploadTransport {
        val t = config.transport as TransportConfig.Sftp
        val pin =
            t.hostKeyFingerprint
                ?: return FailingTransport("SFTP host key not confirmed; fetch it in the sink settings")
        val keys =
            credentials.sftpKey(config.id)
                ?: return FailingTransport("SFTP key missing; open the sink settings to create one")
        return SftpUploadTransport(
            t.host.trim(),
            t.port,
            t.username.trim(),
            t.remoteDir.trim(),
            keys.private,
            keys.public,
            pin,
        )
    }
}
