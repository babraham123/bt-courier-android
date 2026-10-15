package app.btcourier.work

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import app.btcourier.R

/** The low-priority notification a worker shows while it runs as a foreground service. */
object Notifications {
    private const val CHANNEL_TRANSFER = "transfer"
    private const val ID_SYNC = 1
    private const val ID_UPLOAD = 2

    fun createChannels(context: Context) {
        val channel = NotificationChannel(
            CHANNEL_TRANSFER,
            context.getString(R.string.notification_channel_transfer),
            // Low: visible in the shade while a transfer runs, but never sounds or peeks.
            NotificationManager.IMPORTANCE_LOW,
        )
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    fun syncForeground(context: Context) = foregroundInfo(
        context,
        ID_SYNC,
        R.string.notification_syncing,
        android.R.drawable.stat_sys_data_bluetooth,
        ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
    )

    fun uploadForeground(context: Context) = foregroundInfo(
        context,
        ID_UPLOAD,
        R.string.notification_uploading,
        android.R.drawable.stat_sys_upload,
        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
    )

    private fun foregroundInfo(context: Context, id: Int, text: Int, icon: Int, type: Int): ForegroundInfo {
        val notification = NotificationCompat.Builder(context, CHANNEL_TRANSFER)
            .setSmallIcon(icon)
            .setContentTitle(context.getString(text))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setSilent(true)
            .build()
        return ForegroundInfo(id, notification, type)
    }

    /**
     * Asks for POST_NOTIFICATIONS on API 33+ if it was never granted. Without it the foreground
     * services still run, their notification is just hidden, so a denial needs no handling.
     * Call from [ComponentActivity.onCreate] (launchers must be registered before STARTED).
     */
    fun requestPermission(activity: ComponentActivity) {
        if (Build.VERSION.SDK_INT < 33) return
        val permission = Manifest.permission.POST_NOTIFICATIONS
        if (ContextCompat.checkSelfPermission(activity, permission) == PackageManager.PERMISSION_GRANTED) return
        activity.registerForActivityResult(ActivityResultContracts.RequestPermission()) {}.launch(permission)
    }
}

/**
 * Promotes the worker to a foreground service, or carries on in the background if the system
 * refuses (background FGS start restrictions, a revoked permission): a transfer is short enough
 * that losing the promotion is better than losing the transfer.
 */
internal suspend fun CoroutineWorker.tryForeground(info: ForegroundInfo) {
    try {
        setForeground(info)
    } catch (e: IllegalStateException) {
        Log.w("Worker", "foreground refused, continuing in background: ${e.javaClass.simpleName}")
    } catch (e: SecurityException) {
        Log.w("Worker", "foreground not permitted, continuing in background: ${e.javaClass.simpleName}")
    }
}
