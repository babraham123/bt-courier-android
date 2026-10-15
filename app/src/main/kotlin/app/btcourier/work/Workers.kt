package app.btcourier.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import app.btcourier.AppGraph

/**
 * Runs [SyncJob] for the device in [KEY_ADDRESS]. It is a foreground service (`connectedDevice`)
 * only between the link opening and the worker returning; finding an absent device happens in the
 * background.
 *
 * Without an address (the periodic work, or a one-time sync queued before syncs were per device)
 * it fans out instead: one sync per paired device ([SyncTrigger.Periodic]), or with none paired a
 * run that only seals.
 *
 * Interrupted syncs (link lost, truncated, busy) retry with WorkManager's back-off, at most
 * [MAX_ATTEMPTS] runs in a row; then the next trigger takes over. Whenever the run sealed new
 * batches, an upload is enqueued: also after an interrupted sync, and after one that found no
 * device, since sealing happens anyway.
 */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val settings = AppGraph.settings.current()
        val address = inputData.getString(KEY_ADDRESS)
        if (address == null && AppGraph.syncFanOut.fire(SyncTrigger.Periodic).isNotEmpty()) return Result.success()
        val outcome = AppGraph.syncJob.run(address, settings.sealPolicy) {
            tryForeground(Notifications.syncForeground(applicationContext, checkNotNull(address)))
        }
        if (outcome.newBatches) Scheduler.uploadNow(applicationContext, settings.uploadOverCellular)
        val retry = outcome is SyncOutcome.Interrupted && runAttemptCount + 1 < MAX_ATTEMPTS
        return if (retry) Result.retry() else Result.success()
    }

    companion object {
        const val MAX_ATTEMPTS = 5

        /** Input: upper-case address of the device to sync. */
        const val KEY_ADDRESS = "address"
    }
}

/**
 * Runs [UploadJob]. Expedited by default; a backlog over [UploadJob.FOREGROUND_THRESHOLD_BYTES]
 * promotes it to a `dataSync` foreground service so a long upload is not stopped halfway.
 */
class UploadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val settings = AppGraph.settings.current()
        val outcome = AppGraph.uploadJob.run(runAttemptCount, settings.sealPolicy) { tryForeground(Notifications.uploadForeground(applicationContext)) }
        return if (outcome == UploadOutcome.Retry) Result.retry() else Result.success()
    }
}
