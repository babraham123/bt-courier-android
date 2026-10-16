package app.btcourier.work

import android.content.Context
import app.btcourier.AppGraph

/**
 * What the app may ask of the scheduler: the one seam every enqueue, replace and cancel goes
 * through (view models, [SyncFanOut], [SyncWork], [restoreSchedules]), so the rules deciding what
 * to schedule after which event are testable on the JVM.
 */
interface WorkControl {
    /**
     * Syncs the paired device at [address]. A [manual] sync (the user asked) connects even when
     * the device can have nothing new yet; an automatic one then skips connecting ([SyncJob]).
     */
    fun syncNow(
        address: String,
        manual: Boolean,
    )

    fun uploadNow(
        allowCellular: Boolean,
        replace: Boolean,
    )

    /**
     * One follow-up upload in [delayMs], in the unique work of its [slot]: a sync's KEEP upload
     * meanwhile still goes out at once, a newer follow-up for the same slot replaces it, and one
     * for the other slot leaves it alone.
     */
    fun uploadAt(
        delayMs: Long,
        allowCellular: Boolean,
        slot: FollowUp,
    )

    /** Cancels the queued or running uploads, e.g. so they stop using credentials just revoked. */
    fun cancelUpload()

    fun setPeriodicSync(enabled: Boolean)

    /**
     * Drops [sinkId]'s upload back-off, so a sink just fixed or removed is not skipped meanwhile.
     * Returns once the back-off is gone for good, so an upload enqueued next never sees it.
     */
    suspend fun forgetRetries(sinkId: String)
}

/** The production adapter: WorkManager through [Scheduler]. */
class SchedulerWorkControl(
    context: Context,
) : WorkControl {
    private val app = context.applicationContext

    override fun syncNow(
        address: String,
        manual: Boolean,
    ) = Scheduler.syncNow(app, address, manual)

    override fun uploadNow(
        allowCellular: Boolean,
        replace: Boolean,
    ) = Scheduler.uploadNow(app, allowCellular, replace)

    override fun uploadAt(
        delayMs: Long,
        allowCellular: Boolean,
        slot: FollowUp,
    ) = Scheduler.uploadAt(app, delayMs, allowCellular, slot.workName)

    override fun cancelUpload() = Scheduler.cancelUpload(app)

    override fun setPeriodicSync(enabled: Boolean) = Scheduler.setPeriodicSync(app, enabled)

    override suspend fun forgetRetries(sinkId: String) = AppGraph.uploadJob.forgetRetries(sinkId)
}

/** Why a delayed upload ([WorkControl.uploadAt]) is due; each reason holds one follow-up. */
enum class FollowUp(
    val workName: String,
) {
    /** A round ended [UploadOutcome.Deferred]: the time a sink's server asked the app to wait. */
    SINK_RETRY(Scheduler.UPLOAD_DEFERRED),

    /** A sync left lines open: when the age rule closes them ([SyncOutcome.sealDueAtMs]). */
    SEAL(Scheduler.UPLOAD_SEAL),
}
