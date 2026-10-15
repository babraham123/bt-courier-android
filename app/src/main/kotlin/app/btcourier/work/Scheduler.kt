package app.btcourier.work

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkRequest
import androidx.work.workDataOf
import app.btcourier.AppGraph
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

/**
 * Sync and upload triggers (plan, "Scheduler"). Every request is unique work, so repeated
 * triggers coalesce instead of queueing up.
 */
object Scheduler {
    const val SYNC_PERIODIC = "sync-periodic"
    const val UPLOAD = "upload"
    const val UPLOAD_DEFERRED = "upload-deferred"

    /** Unique work name of one device's one-time sync. */
    fun syncNowName(address: String) = "sync-now-" + address.uppercase()

    /**
     * Every 15 min, WorkManager's minimum; KEEP so re-registering never resets the period. It
     * carries no address, so each run fans out to every paired device ([SyncWorker]).
     */
    fun schedulePeriodicSync(context: Context) {
        val request = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, WorkRequest.MIN_BACKOFF_MILLIS, TimeUnit.MILLISECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(SYNC_PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    /** Registers or cancels the periodic sync per the settings toggle. */
    fun setPeriodicSync(context: Context, enabled: Boolean) {
        if (enabled) {
            schedulePeriodicSync(context)
        } else {
            WorkManager.getInstance(context).cancelUniqueWork(SYNC_PERIODIC)
        }
    }

    /**
     * One sync of the device at [address] as soon as possible: presence, the car, the periodic
     * fan-out and the manual button. KEEP: a sync of that device already queued or running will
     * pull everything it has anyway.
     */
    fun syncNow(context: Context, address: String) {
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setInputData(workDataOf(SyncWorker.KEY_ADDRESS to address.uppercase()))
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(syncNowName(address), ExistingWorkPolicy.KEEP, request)
    }

    /**
     * Delivers pending batches once the network allows. KEEP: an upload waiting for its network
     * delivers whatever is pending when it runs. [replace] re-enqueues with new constraints, for a
     * changed network policy or sink.
     */
    fun uploadNow(context: Context, allowCellular: Boolean, replace: Boolean = false) {
        val request = OneTimeWorkRequestBuilder<UploadWorker>()
            .setConstraints(uploadConstraints(allowCellular))
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, WorkRequest.DEFAULT_BACKOFF_DELAY_MILLIS, TimeUnit.MILLISECONDS)
            .build()
        val policy = if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP
        WorkManager.getInstance(context).enqueueUniqueWork(UPLOAD, policy, request)
    }

    /**
     * The follow-up a deferred round asks for ([WorkControl.uploadAt]). Not expedited: WorkManager
     * refuses a delay on expedited work, and a run due later needs no quota anyway. REPLACE, so a
     * later round's (later) due time wins; the back-off is irrelevant since a deferred round's own
     * outcome decides the next one.
     */
    fun uploadAt(context: Context, delayMs: Long, allowCellular: Boolean) {
        val request = OneTimeWorkRequestBuilder<UploadWorker>()
            .setConstraints(uploadConstraints(allowCellular))
            .setInitialDelay(delayMs.coerceAtLeast(0), TimeUnit.MILLISECONDS)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, WorkRequest.DEFAULT_BACKOFF_DELAY_MILLIS, TimeUnit.MILLISECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(UPLOAD_DEFERRED, ExistingWorkPolicy.REPLACE, request)
    }

    fun cancelUpload(context: Context) {
        WorkManager.getInstance(context).apply {
            cancelUniqueWork(UPLOAD)
            cancelUniqueWork(UPLOAD_DEFERRED)
        }
    }

    fun uploadConstraints(allowCellular: Boolean): Constraints =
        Constraints.Builder()
            .setRequiredNetworkType(if (allowCellular) NetworkType.CONNECTED else NetworkType.UNMETERED)
            .build()
}

/** Re-registers the periodic sync and presence observation after a reboot ([restoreSchedules]). */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val app = context.applicationContext
        val pending = goAsync()
        AppGraph.scope.launch {
            try {
                restoreSchedules(AppGraph.settings, AppGraph.pairedDevices, AppGraph.work) { DevicePresence.startObserving(app, it) }
            } finally {
                pending.finish()
            }
        }
    }
}
