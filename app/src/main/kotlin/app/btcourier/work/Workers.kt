package app.btcourier.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import app.btcourier.AppGraph

/**
 * Runs [SyncWork] for the device in [KEY_ADDRESS] (none for the periodic fan-out). It is a
 * foreground service (`connectedDevice`) only between the link opening and the worker returning;
 * finding an absent device happens in the background.
 */
class SyncWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val settings = AppGraph.settings.current()
        val address = inputData.getString(KEY_ADDRESS)
        val verdict =
            AppGraph.syncWork.run(
                address,
                runAttemptCount,
                settings.sealPolicy,
                settings.uploadOverCellular,
                manual = inputData.getBoolean(KEY_MANUAL, false),
            ) {
                tryForeground(Notifications.syncForeground(applicationContext, checkNotNull(address)))
            }
        return if (verdict == WorkVerdict.RETRY) Result.retry() else Result.success()
    }

    companion object {
        /** Input: upper-case address of the device to sync. */
        const val KEY_ADDRESS = "address"

        /** Input: whether the user asked for the sync; absent (false) in work queued before it existed. */
        const val KEY_MANUAL = "manual"
    }
}

/**
 * Runs [UploadWork]. Expedited by default; a backlog over [UploadJob.FOREGROUND_THRESHOLD_BYTES]
 * promotes it to a `dataSync` foreground service so a long upload is not stopped halfway.
 */
class UploadWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val settings = AppGraph.settings.current()
        val verdict =
            AppGraph.uploadWork.run(runAttemptCount, settings.sealPolicy, settings.uploadOverCellular) {
                tryForeground(Notifications.uploadForeground(applicationContext))
            }
        return if (verdict == WorkVerdict.RETRY) Result.retry() else Result.success()
    }
}
