package app.btcourier.data.sinkkind

import app.btcourier.R
import app.btcourier.data.AuthKind
import app.btcourier.data.SinkConfig
import app.btcourier.data.SinkCredentials
import app.btcourier.data.SinkEnvironment
import app.btcourier.data.TransportConfig
import app.btcourier.data.TransportKind
import app.btcourier.data.saf.SafUploadTransport

/** Writes into a Storage Access Framework tree the app holds a persisted grant for; no auth. */
object LocalFolderKind : SinkTransportKind {
    const val TREE_URI = "treeUri"

    override val kind = TransportKind.LOCAL_FOLDER
    override val label = R.string.transport_local_folder
    override val allowedAuth = listOf(AuthKind.NONE)
    override val fields = emptyList<TransportField>()
    override val capabilities = listOf(TransportCapability.FolderPick(TREE_URI))

    override fun defaults() = FieldValues()

    override fun toValues(config: TransportConfig) = FieldValues(TREE_URI to (config as TransportConfig.LocalFolder).treeUri)

    override fun validate(values: FieldValues) = problemsOf(TREE_URI to values.blankProblem(TREE_URI))

    override fun toConfig(values: FieldValues) = TransportConfig.LocalFolder(requireNotNull(values.orNull(TREE_URI)) { "no folder picked" })

    override suspend fun build(config: SinkConfig, credentials: SinkCredentials, env: SinkEnvironment) =
        SafUploadTransport(env.folders.tree((config.transport as TransportConfig.LocalFolder).treeUri))
}
