package app.btcourier.ui

import android.content.Context
import app.btcourier.work.Scheduler

/** What the screens may ask of the scheduler; a seam so view models are testable on the JVM. */
interface WorkControl {
    fun syncNow()

    fun uploadNow(allowCellular: Boolean, replace: Boolean)

    fun setPeriodicSync(enabled: Boolean)
}

class SchedulerWorkControl(context: Context) : WorkControl {
    private val app = context.applicationContext

    override fun syncNow() = Scheduler.syncNow(app)

    override fun uploadNow(allowCellular: Boolean, replace: Boolean) = Scheduler.uploadNow(app, allowCellular, replace)

    override fun setPeriodicSync(enabled: Boolean) = Scheduler.setPeriodicSync(app, enabled)
}
