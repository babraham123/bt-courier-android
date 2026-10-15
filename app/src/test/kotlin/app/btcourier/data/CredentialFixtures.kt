package app.btcourier.data

import app.btcourier.work.InMemorySshKeys
import app.btcourier.work.MapSecretStore
import kotlinx.coroutines.Dispatchers

/**
 * The on-device names [SinkCredentials] must keep: renaming one orphans what earlier versions
 * stored. Tests seed and inspect the stores by these.
 */
object StoredNames {
    fun secret(sinkId: String) = "sink_${sinkId}_secret"

    fun clientSecret(sinkId: String) = "sink_${sinkId}_oidc_client_secret"

    fun tokens(sinkId: String) = "sink_${sinkId}_oidc_tokens"

    fun all(sinkId: String) = listOf(secret(sinkId), clientSecret(sinkId), tokens(sinkId))

    fun keyAlias(sinkId: String) = "btcourier.sftp.$sinkId"

    /** The single sink's secret in versions before multiple sinks. */
    const val LEGACY_SECRET = "upload"
}

/** Records released trees instead of calling `ContentResolver`. */
class RecordingFolderGrants : FolderGrants {
    val released = mutableListOf<String>()

    override fun release(treeUri: String) {
        released += treeUri
    }
}

/** [SinkCredentials] over in-memory stores, with its blocking calls on the caller's thread. */
fun testCredentials(
    secrets: MapSecretStore = MapSecretStore(),
    sshKeys: InMemorySshKeys = InMemorySshKeys(),
    folders: FolderGrants = RecordingFolderGrants(),
) = SinkCredentials(secrets, sshKeys, folders, io = Dispatchers.Unconfined)
