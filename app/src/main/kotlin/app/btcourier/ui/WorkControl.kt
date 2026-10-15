package app.btcourier.ui

import android.content.Context
import app.btcourier.AppGraph
import app.btcourier.work.Scheduler

/** What the screens may ask of the scheduler; a seam so view models are testable on the JVM. */
interface WorkControl {
    /** Syncs the paired device at [address]. */
    fun syncNow(address: String)

    fun uploadNow(allowCellular: Boolean, replace: Boolean)

    /** Cancels the queued or running upload, e.g. so it stops using credentials just revoked. */
    fun cancelUpload()

    fun setPeriodicSync(enabled: Boolean)

    /** Drops [sinkId]'s upload back-off, so a sink just fixed or removed is not skipped meanwhile. */
    fun forgetRetries(sinkId: String)
}

class SchedulerWorkControl(context: Context) : WorkControl {
    private val app = context.applicationContext

    override fun syncNow(address: String) = Scheduler.syncNow(app, address)

    override fun uploadNow(allowCellular: Boolean, replace: Boolean) = Scheduler.uploadNow(app, allowCellular, replace)

    override fun cancelUpload() = Scheduler.cancelUpload(app)

    override fun setPeriodicSync(enabled: Boolean) = Scheduler.setPeriodicSync(app, enabled)

    override fun forgetRetries(sinkId: String) = AppGraph.uploadJob.forgetRetries(sinkId)
}
