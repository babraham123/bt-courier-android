package app.btcourier.work

import app.btcourier.ble.PairedDevices
import app.btcourier.core.store.SealPolicy
import app.btcourier.data.SettingsRepository

/** What a worker asks WorkManager for once its run ends. */
enum class WorkVerdict { SUCCESS, RETRY }

/**
 * The rules of one [SyncWorker] run, kept out of the worker so they run on the JVM; the worker is
 * a thin adapter that reads its inputs and maps the [WorkVerdict] to a WorkManager result.
 *
 * - Without an address (the periodic work, or a one-time sync queued before syncs were per
 *   device) the run fans out: one automatic sync per paired device it syncs
 *   ([SyncTrigger.Periodic]). With none paired it runs [SyncJob] without a device, which only
 *   seals.
 * - After every run, the upload rule of [uploadAfterSeal] applies to its seal: also after an
 *   interrupted sync and after one that found no device, since sealing happens anyway.
 * - Whether to run again is the outcome's [SyncOutcome.retryWorthwhile] ([SyncRetryPolicy] says
 *   when), which the worker turns into WorkManager's back-off.
 */
class SyncWork(
    private val job: SyncJob,
    private val fanOut: SyncFanOut,
    private val work: WorkControl,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /**
     * @param address the device to sync; null fans out.
     * @param attempt WorkManager's `runAttemptCount`, see [SyncJob.run].
     * @param uploadOverCellular whether the upload this run may enqueue may use a metered network.
     * @param manual see [SyncJob.run].
     * @param onConnected see [SyncJob.run].
     */
    suspend fun run(
        address: String?,
        attempt: Int,
        sealPolicy: SealPolicy,
        uploadOverCellular: Boolean,
        manual: Boolean = false,
        onConnected: suspend () -> Unit = {},
    ): WorkVerdict {
        if (address == null) {
            // Paired devices the periodic work does not sync (streaming ones) are not "nothing paired".
            if (fanOut.fire(SyncTrigger.Periodic).isNotEmpty() || fanOut.anyPaired()) return WorkVerdict.SUCCESS
        }
        val outcome = job.run(address, sealPolicy, attempt, manual, onConnected)
        work.uploadAfterSeal(outcome.newBatches, outcome.sealDueAtMs, uploadOverCellular, clock())
        return if (outcome.retryWorthwhile) WorkVerdict.RETRY else WorkVerdict.SUCCESS
    }
}

/**
 * The rules of one [UploadWorker] run, kept out of the worker like [SyncWork]: a round that
 * ended [UploadOutcome.Retry] runs again on WorkManager's back-off; one that ended
 * [UploadOutcome.Deferred] enqueues the follow-up for when the waiting sink is due
 * ([FollowUp.SINK_RETRY]) and ends this work, so the attempt budget is spent on attempts only.
 */
class UploadWork(
    private val job: UploadJob,
    private val work: WorkControl,
) {
    /** @param attempt WorkManager's `runAttemptCount`; the rest as [UploadJob.run]. */
    suspend fun run(
        attempt: Int,
        sealPolicy: SealPolicy,
        uploadOverCellular: Boolean,
        onLargeBacklog: suspend () -> Unit = {},
    ): WorkVerdict =
        when (val outcome = job.run(attempt, sealPolicy, onLargeBacklog)) {
            UploadOutcome.Retry -> {
                WorkVerdict.RETRY
            }

            is UploadOutcome.Deferred -> {
                work.uploadAt(outcome.delayMs, uploadOverCellular, FollowUp.SINK_RETRY)
                WorkVerdict.SUCCESS
            }

            else -> {
                WorkVerdict.SUCCESS
            }
        }
}

/**
 * Re-registers what the system may have dropped, at app start and after a reboot: the periodic
 * sync per the settings toggle, and presence observation of every paired device. Safe to repeat:
 * the periodic sync is registered KEEP, and observing a device already observed keeps observing it.
 *
 * @param observePresence `DevicePresence.startObserving` in the app.
 */
suspend fun restoreSchedules(
    settings: SettingsRepository,
    devices: PairedDevices,
    work: WorkControl,
    observePresence: (address: String) -> Unit,
) {
    work.setPeriodicSync(settings.current().periodicSync)
    devices.all().forEach { observePresence(it.address) }
}
