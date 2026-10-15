package app.btcourier

import android.annotation.SuppressLint
import android.content.Context
import app.btcourier.core.store.DefaultStore
import app.btcourier.core.store.FileStoreDb
import app.btcourier.core.store.Store
import app.btcourier.data.DataStoreSettings
import app.btcourier.data.DataStoreSyncStatus
import app.btcourier.data.KeystoreSecretStore
import app.btcourier.data.SecretStore
import app.btcourier.data.SettingsRepository
import app.btcourier.data.SyncStatusRepository
import app.btcourier.data.buildSinks
import app.btcourier.data.configuredSinkIds
import app.btcourier.log.AppLog
import app.btcourier.work.LinkFactory
import app.btcourier.work.PairedAddress
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
 * The store must be a single instance per process: [FileStoreDb] keeps its index in memory and
 * [DefaultStore] serialises writers with an in-process lock.
 */
// The application context lives as long as the process, so holding it statically leaks nothing.
// Suppressed on the object: lint does not see the annotation on a Kotlin property's backing field.
@SuppressLint("StaticFieldLeak")
object AppGraph {
    private lateinit var app: Context

    /** Opens BLE links for syncs; read on every sync, so it can be swapped at runtime (tests, debug). */
    @Volatile
    var linkFactory: LinkFactory = LinkFactory.Unavailable

    @Volatile
    var pairedAddress: PairedAddress = PairedAddress { null }

    /** Outlives any screen; for fire-and-forget work such as a receiver's `goAsync`. */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val settings: SettingsRepository by lazy { DataStoreSettings(app) }
    val status: SyncStatusRepository by lazy { DataStoreSyncStatus(app) }
    val secrets: SecretStore by lazy { KeystoreSecretStore(File(app.noBackupFilesDir, "secrets")) }
    val log: AppLog by lazy { AppLog(file = File(app.noBackupFilesDir, "app.log")) }

    /** [FileStoreDb]'s directory; also listed by the diagnostics export. */
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

    // DefaultStore asks for sink ids synchronously. Until settings load this is empty, which only
    // postpones deleting acked batches to the next ack or seal, never deletes too early.
    private val sinkIds: StateFlow<Set<String>> by lazy {
        settings.settings.map(::configuredSinkIds).stateIn(scope, SharingStarted.Eagerly, emptySet())
    }

    val store: Store by lazy {
        DefaultStore(FileStoreDb.open(storeDir.toPath()), sinkIds = { sinkIds.value })
    }

    val syncJob: SyncJob by lazy {
        SyncJob(
            pairedAddress = { pairedAddress.current() },
            links = { linkFactory.open(it) },
            store = store,
            status = status,
            log = log,
        )
    }

    val uploadJob: UploadJob by lazy {
        UploadJob(store, sinks = { buildSinks(settings.current(), secrets, httpClient) }, status = status, log = log)
    }

    fun init(context: Context, links: LinkFactory, paired: PairedAddress) {
        app = context.applicationContext
        linkFactory = links
        pairedAddress = paired
        // Starts loading sink ids now, so they are known before the first upload acks.
        sinkIds
    }
}
