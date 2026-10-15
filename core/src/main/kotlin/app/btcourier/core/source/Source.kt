package app.btcourier.core.source

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/**
 * A device the app reads data from.
 *
 * Pull-with-history devices serve what they kept incrementally from an opaque cursor through
 * [pull]: `WicanLoggerSource` (`/api/logger/export`), `ColmiRingSource` (per-day logs). Stream and
 * broadcast devices, which keep nothing, deliver through [subscribe] instead.
 */
interface Source {
    /**
     * Fetches the page after [cursor]. Pass `null` to start from the oldest retained data.
     * Callers loop until [Page.more] is false, persisting [Page.nextCursor] with each page.
     */
    suspend fun pull(cursor: String?): Page

    /** The device's clock journal, used to re-base timestamps logged before its clock was valid. */
    suspend fun clockJournal(): List<ClockEvent>

    /** The device's wall clock sampled against the phone's, or null if the device exposes none. */
    suspend fun deviceStatus(): DeviceStatus? = null

    /**
     * Live data from a stream-while-connected or broadcast device (HR strap, ELM327 live, Ruuvi,
     * TPMS): each emission is a group of JSONL rows the device produced together, in the same row
     * shape as [pull]'s, `{"ts":<epoch ms>,"param":"<name>","value":<value>}`.
     *
     * There is no cursor and no `{"_file":...}` marker or cursor line: nothing is kept on the
     * device, so whatever arrives while nobody collects is gone, and a new collection starts at
     * "now". Collection ends when the link drops (the flow completes, or fails with the
     * transport's [java.io.IOException]) or when the collector cancels.
     *
     * Pull-with-history sources keep the default, which completes at once without emitting.
     */
    fun subscribe(): Flow<List<String>> = emptyFlow()
}

/**
 * The device clock as read during a sync, paired with the phone clock at the same moment: the
 * input of the normaliser's anchor.
 *
 * @property deviceEpochMs the device's wall clock; 1970 + uptime while [clockValid] is false.
 * @property phoneEpochMs the phone's wall clock at the moment [deviceEpochMs] is estimated for.
 * @property clockValid whether the device trusts its clock this boot (RTC restore, SNTP, manual).
 */
data class DeviceStatus(
    val deviceEpochMs: Long,
    val phoneEpochMs: Long,
    val clockValid: Boolean,
)

/**
 * A device answer a [Source] cannot turn into data. Link failures are not these: they surface as
 * the transport's [java.io.IOException]s. In every case nothing was consumed, so the caller
 * repeats the call from the same cursor.
 */
sealed class SourceException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    /**
     * HTTP 429: another request holds the device (the `ble_http` tunnel's `busy`, or the web
     * server serving someone else). Backing off is the caller's call.
     */
    class Busy(val path: String) : SourceException("device busy (429) for $path")

    /** The response ended before its trailing cursor line: the transfer was cut short. */
    class Truncated(val path: String, cause: Throwable? = null) :
        SourceException("truncated response for $path", cause)

    /** Any other non-2xx status. [detail] is the start of the body, for logs. */
    class DeviceError(val status: Int, val path: String, val detail: String) :
        SourceException("HTTP $status for $path: $detail")

    /** The device answered in a shape this source cannot use, e.g. its logger writes CSV. */
    class Unsupported(message: String) : SourceException(message)
}

/**
 * One export page.
 *
 * @property lines raw JSONL rows exactly as the device sent them, including `{"_file":...}`
 *   markers and excluding the trailing cursor line.
 * @property nextCursor opaque to core; WiCAN's is `<file epoch>:<byte offset>`, Colmi's the last
 *   day pulled (`YYYY-MM-DD`).
 */
data class Page(
    val lines: List<String>,
    val nextCursor: String,
    val more: Boolean,
)

/**
 * One line of the device clock journal (WiCAN: `/sd/logs/clock.jsonl`, issue #984), in journal
 * order. Field names follow the journal's keys in camelCase; all clock values are epoch millis as
 * the device's clock read them at the time, so a value logged before the clock was set is
 * 1970 + uptime.
 */
sealed interface ClockEvent {
    /** Device boot counter; null if the line lacks it (nothing depends on it but display). */
    val boot: Long?

    /**
     * `{"ev":"boot","boot","ts","up_ms","valid","source"}`: the device booted.
     *
     * @property ts the clock at this line; null if absent.
     * @property upMs uptime at this line; null if absent.
     * @property valid whether the clock was trusted at boot (e.g. restored from the RTC).
     * @property source who set the clock: `none`, `rtc`, `sntp` or `manual`.
     */
    data class Boot(
        override val boot: Long?,
        val ts: Long?,
        val upMs: Long?,
        val valid: Boolean,
        val source: String,
    ) : ClockEvent {
        /**
         * The wall-clock instant of boot on the clock as it read at this line. Rows stamped more
         * than a few seconds earlier were written by a previous boot.
         */
        val originMs: Long? get() = if (ts != null && upMs != null) ts - upMs else null
    }

    /**
     * A set of the clock: `valid` for the first one of a boot without a clock, `step` for every
     * later one. Both carry the same fields and re-base identically.
     *
     * @property tsBefore the clock just before the set.
     * @property tsAfter the clock just after the set; null if absent (unused without in-band markers).
     * @property deltaMs how far the clock moved (`ts_after - ts_before`, measured by the device).
     * @property files the log files open at the time, as named in the export's `_file` markers.
     */
    sealed interface ClockSet : ClockEvent {
        val tsBefore: Long
        val tsAfter: Long?
        val deltaMs: Long
        val source: String
        val files: List<String>
    }

    /** `{"ev":"valid",...}`: the clock was set for the first time this boot. */
    data class Valid(
        override val boot: Long?,
        override val tsBefore: Long,
        override val tsAfter: Long?,
        override val deltaMs: Long,
        override val source: String,
        override val files: List<String>,
    ) : ClockSet

    /** `{"ev":"step",...}`: a later set of an already valid clock. */
    data class Step(
        override val boot: Long?,
        override val tsBefore: Long,
        override val tsAfter: Long?,
        override val deltaMs: Long,
        override val source: String,
        override val files: List<String>,
    ) : ClockSet

    /**
     * `{"ev":"rotate","boot","ts","stream","from","to","why"}`: the logger moved [stream] from file
     * [from] to [to]; [why] is `clock` when a clock set caused it. Informational: re-basing needs
     * only the [ClockSet] file lists.
     */
    data class Rotate(
        override val boot: Long?,
        val ts: Long?,
        val stream: String,
        val from: String,
        val to: String,
        val why: String,
    ) : ClockEvent
}
