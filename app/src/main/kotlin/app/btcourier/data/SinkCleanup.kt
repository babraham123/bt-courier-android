package app.btcourier.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext

/**
 * Deletes every secret and the SFTP key that may exist for sink [id], whatever its transport.
 *
 * @param io where the blocking Keystore call runs.
 */
suspend fun deleteSinkCredentials(id: String, secrets: SecretStore, sshKeys: SshKeys, io: CoroutineContext = Dispatchers.IO) {
    SinkSecrets.all(id).forEach { secrets.put(it, null) }
    withContext(io) { sshKeys.delete(sftpKeyAlias(id)) }
}

/**
 * Removes sink [id] from the settings, then its credentials. In that order, so no upload builds the
 * sink after its secrets are gone; an upload already running fails its batches for this sink, which
 * no longer matters since the store stops waiting for it.
 */
suspend fun deleteSink(
    id: String,
    settings: SettingsRepository,
    secrets: SecretStore,
    sshKeys: SshKeys,
    io: CoroutineContext = Dispatchers.IO,
) {
    settings.update { s -> s.copy(sinks = s.sinks.filterNot { it.id == id }) }
    deleteSinkCredentials(id, secrets, sshKeys, io)
}
