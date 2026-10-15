package app.btcourier

import android.annotation.SuppressLint
import android.content.Context
import androidx.core.net.toUri
import app.btcourier.ble.Association
import app.btcourier.ble.Associations
import app.btcourier.ble.CompanionPairedDeviceRepository
import app.btcourier.ble.PairedDeviceRepository
import app.btcourier.ble.newestAssociation
import app.btcourier.core.store.DefaultStore
import app.btcourier.core.store.Store
import app.btcourier.data.AndroidKeystoreSshKeys
import app.btcourier.data.DataStoreSettings
import app.btcourier.data.DataStoreSyncStatus
import app.btcourier.data.FolderAccess
import app.btcourier.data.FolderGrants
import app.btcourier.data.KeystoreSecretStore
import app.btcourier.data.SecretStore
import app.btcourier.data.SettingsRepository
import app.btcourier.data.SinkCredentials
import app.btcourier.data.SinkEnvironment
import app.btcourier.data.SshKeys
import app.btcourier.data.SyncStatusRepository
import app.btcourier.data.buildSinks
import app.btcourier.data.statusDataStore
import app.btcourier.data.configuredSinkIds
import app.btcourier.data.saf.SafDocumentTree
import app.btcourier.data.saf.releaseGrant
import app.btcourier.data.settingsDataStore
import app.btcourier.log.AppLog
import app.btcourier.work.LinkFactory
import app.btcourier.work.Scheduler
import app.btcourier.work.SyncFanOut
import app.btcourier.work.SyncJob
import app.btcourier.work.UploadJob
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Manual dependency graph, initialised once by [BtCourierApp]. Workers, services and receivers
 * reach it statically because the system instantiates them.
 *
 * The store must be a single instance per process: [DefaultStore] keeps its index in memory and
 * serialises writers with an in-process lock.
 */
// The application context lives as long as the process, so holding it statically leaks nothing.
// Suppressed on the object: lint does not see the annotation on a Kotlin property's backing field.
@SuppressLint("StaticFieldLeak")
object AppGraph {
    private lateinit var app: Context

    /** Opens BLE links for syncs; read on every sync, so it can be swapped at runtime (tests, debug). */
    @Volatile
    var linkFactory: LinkFactory = LinkFactory.Unavailable

    /** The companion-device associations, i.e. which devices are paired. */
    @Volatile
    var associations: Associations = object : Associations {
        override fun list(): List<Association> = emptyList()

        override fun remove(association: Association) = Unit
    }

    /** Outlives any screen; for fire-and-forget work such as a receiver's `goAsync`. */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val settings: SettingsRepository by lazy { DataStoreSettings(settingsDataStore(app, sinkCredentials)) }
    val status: SyncStatusRepository by lazy {
        DataStoreSyncStatus(statusDataStore(app) { newestAssociation(associations.list())?.address })
    }
    val pairedDevices: PairedDeviceRepository by lazy { CompanionPairedDeviceRepository(associations, settings) }
    val syncFanOut: SyncFanOut by lazy { SyncFanOut(pairedDevices) { Scheduler.syncNow(app, it) } }
    val secrets: SecretStore by lazy { KeystoreSecretStore(File(app.noBackupFilesDir, "secrets")) }
    val sshKeys: SshKeys by lazy { AndroidKeystoreSshKeys() }
    val sinkCredentials: SinkCredentials by lazy {
        SinkCredentials(secrets, sshKeys, FolderGrants { releaseGrant(app, it.toUri()) })
    }
    val log: AppLog by lazy { AppLog(file = File(app.noBackupFilesDir, "app.log")) }

    /** The store's directory; also listed by the diagnostics export. */
    val storeDir: File get() = File(app.filesDir, "store")

    val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            // Batches are at most ~1 MB compressed; a minute covers a slow uplink.
            .writeTimeout(60, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            // A redirected PUT is replayed as a GET, and that GET's 200 would be taken as an ack.
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
    }

    val sinkEnvironment: SinkEnvironment by lazy {
        SinkEnvironment(httpClient, FolderAccess { SafDocumentTree(app, it.toUri()) }, sshKeys)
    }

    // DefaultStore asks for sink ids synchronously. Until settings load this is empty, which only
    // postpones deleting acked batches to the next ack or seal, never deletes too early.
    private val sinkIds: StateFlow<Set<String>> by lazy {
        settings.settings.map(::configuredSinkIds).stateIn(scope, SharingStarted.Eagerly, emptySet())
    }

    val store: Store by lazy {
        DefaultStore.open(storeDir.toPath(), sinkIds = { sinkIds.value })
    }

    val syncJob: SyncJob by lazy {
        SyncJob(
            devices = pairedDevices,
            links = { address, kind -> linkFactory.open(address, kind) },
            store = store,
            status = status,
            log = log,
        )
    }

    // One instance for the process: it keeps each sink's retry schedule across rounds, while the
    // sinks themselves are rebuilt every round from the current settings.
    val uploadJob: UploadJob by lazy {
        UploadJob(store, sinks = { buildSinks(settings.current(), secrets, sinkEnvironment) }, status = status, log = log)
    }

    fun init(context: Context, links: LinkFactory, associations: Associations) {
        app = context.applicationContext
        linkFactory = links
        this.associations = associations
        // Starts loading sink ids now, so they are known before the first upload acks.
        sinkIds
    }
}
