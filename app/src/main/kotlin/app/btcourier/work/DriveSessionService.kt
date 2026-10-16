package app.btcourier.work

import android.app.ForegroundServiceStartNotAllowedException
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import app.btcourier.AppGraph
import app.btcourier.data.DriveSessionStatus
import app.btcourier.data.SettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Hosts [DriveSession]s as a `connectedDevice` foreground service, so a session may hold its link
 * for the whole drive. A thin adapter: it goes foreground, runs one session per requested device
 * on its scope, forwards the notification's Stop ([DriveStop.USER]), keeps the notification in
 * step with the recorded [app.btcourier.data.DriveSessionStatus], and stops itself when the last
 * session ends. Every rule lives in [DriveSession]; which trigger starts or stops a session lives
 * in [planTrigger].
 *
 * Sticky: if the system kills the process mid-session, it restarts the service without an intent,
 * and the service resumes the session the status still records as running, while the setting is
 * on.
 */
class DriveSessionService : Service() {
    // Main thread: the map of runs is only touched there.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val runs = mutableMapOf<String, Job>()

    // The session as last recorded; each notification is built from it.
    private var lastStatus: DriveSessionStatus? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        scope.launch {
            AppGraph.status.status
                .map { it.driveSession }
                .distinctUntilChanged()
                .collect { session ->
                    lastStatus = session
                    // After the last run ended the notification is gone; a late update must not bring it back.
                    val address = session?.address ?: runs.keys.firstOrNull()
                    if (runs.isNotEmpty() && address != null) {
                        getSystemService(NotificationManager::class.java)
                            .notify(Notifications.ID_DRIVE_SESSION, notification(address))
                    }
                }
        }
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        val address = intent?.getStringExtra(EXTRA_ADDRESS)?.uppercase()
        if (intent?.action == ACTION_STOP) {
            address?.let { AppGraph.driveSessions.stop(it, DriveStop.USER) }
            stopIfIdle()
            return START_STICKY
        }
        if (address == null) {
            resume()
        } else if (goForeground(address)) {
            start(address)
        } else {
            // Refused (no foreground start allowed now, or a permission missing): the trigger's rows
            // still land through the sync it would have enqueued without the setting.
            AppGraph.work.syncNow(address, manual = false)
            stopIfIdle()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun resume() {
        scope.launch {
            val session =
                AppGraph.status.status
                    .first()
                    .driveSession
            val address = session?.takeIf { it.running && AppGraph.settings.current().driveSession }?.address
            if (address != null && goForeground(address)) start(address) else stopIfIdle()
        }
    }

    private fun goForeground(address: String): Boolean =
        try {
            startForeground(
                Notifications.ID_DRIVE_SESSION,
                notification(address),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
            )
            true
        } catch (e: ForegroundServiceStartNotAllowedException) {
            Log.w(TAG, "drive session not started: ${e.javaClass.simpleName}")
            false
        } catch (e: SecurityException) {
            Log.w(TAG, "drive session not permitted: ${e.javaClass.simpleName}")
            false
        }

    private fun start(address: String) {
        if (address in runs) return
        runs[address] =
            scope.launch {
                try {
                    withContext(Dispatchers.Default) { AppGraph.driveSession().run(address) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // A bug in one session must not take the process, and with it every other job, down.
                    Log.w(TAG, "drive session failed", e)
                } finally {
                    runs.remove(address)
                    stopIfIdle()
                }
            }
    }

    private fun stopIfIdle() {
        if (runs.isNotEmpty()) return
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun notification(address: String) =
        Notifications.driveSession(
            this,
            lastStatus,
            PendingIntent.getService(
                this,
                address.hashCode(),
                Intent(this, DriveSessionService::class.java).setAction(ACTION_STOP).putExtra(EXTRA_ADDRESS, address),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            ),
        )

    companion object {
        private const val TAG = "DriveSessionService"
        private const val ACTION_STOP = "app.btcourier.action.STOP_DRIVE_SESSION"
        private const val EXTRA_ADDRESS = "address"

        /** The intent that asks for a session with the device at [address]. */
        fun startIntent(
            context: Context,
            address: String,
        ): Intent = Intent(context, DriveSessionService::class.java).putExtra(EXTRA_ADDRESS, address.uppercase())
    }
}

/**
 * The app's [DriveSessionStarter]: the setting from [settings], running sessions from [sessions],
 * starts through [DriveSessionService] and the car's disconnect posted to the running session.
 * Presence and the car trigger start the service from the background, which the companion
 * association allows (`REQUEST_COMPANION_START_FOREGROUND_SERVICES_FROM_BACKGROUND`); a refusal
 * is reported, so the trigger syncs instead.
 */
class ServiceDriveSessionStarter(
    context: Context,
    private val settings: SettingsRepository,
    private val sessions: DriveSessions,
) : DriveSessionStarter {
    private val app = context.applicationContext

    override suspend fun enabled(): Boolean = settings.current().driveSession

    override fun running(): Set<String> = sessions.running()

    override fun start(address: String): Boolean =
        try {
            app.startForegroundService(DriveSessionService.startIntent(app, address))
            true
        } catch (e: ForegroundServiceStartNotAllowedException) {
            Log.w(TAG, "drive session refused, syncing instead: ${e.javaClass.simpleName}")
            false
        }

    override fun carDisconnected(address: String) {
        sessions.stop(address, DriveStop.CAR_DISCONNECTED)
    }

    private companion object {
        const val TAG = "DriveSessionStarter"
    }
}
