package app.btcourier.ui.status

import android.content.Context
import android.text.format.DateUtils
import androidx.annotation.StringRes
import app.btcourier.R
import app.btcourier.data.DriveSessionEnd
import app.btcourier.data.DriveSessionStatus

/**
 * The drive session's one line, shared by the status screen and the session's notification:
 * "3 rounds, 120 rows, since 14:05" while it runs, its end time, reason and totals once it ended.
 */
internal fun driveSessionSummary(
    context: Context,
    session: DriveSessionStatus,
): String {
    val res = context.resources
    val rounds = res.getQuantityString(R.plurals.drive_session_rounds, session.rounds, session.rounds)
    val rows =
        res.getQuantityString(
            R.plurals.drive_session_rows,
            session.rows.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
            session.rows,
        )
    val end = session.end
    return if (end == null) {
        val since = DateUtils.formatDateTime(context, session.startedAtMs, DateUtils.FORMAT_SHOW_TIME)
        context.getString(R.string.drive_session_running, rounds, rows, since)
    } else {
        val endedAt = formatStatusTime(context, session.endedAtMs ?: session.startedAtMs)
        context.getString(R.string.drive_session_ended, endedAt, context.getString(driveEndLabel(end)), rounds, rows)
    }
}

@StringRes
private fun driveEndLabel(end: DriveSessionEnd): Int =
    when (end) {
        DriveSessionEnd.CAR_DISCONNECTED -> R.string.drive_end_car_disconnected
        DriveSessionEnd.USER_STOPPED -> R.string.drive_end_user_stopped
        DriveSessionEnd.IDLE -> R.string.drive_end_idle
        DriveSessionEnd.SESSION_CAP -> R.string.drive_end_session_cap
        DriveSessionEnd.LINK_LOST -> R.string.drive_end_link_lost
        DriveSessionEnd.UNREACHABLE -> R.string.drive_end_unreachable
        DriveSessionEnd.BUSY -> R.string.drive_end_busy
        DriveSessionEnd.NOT_LIVE -> R.string.drive_end_not_live
    }
