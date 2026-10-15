package app.btcourier.core.normalize

import app.btcourier.core.source.ClockEvent

/**
 * Repairs device timestamps logged before the device clock was valid or before it was stepped.
 *
 * Primary method re-bases each `_file` by the journal's `delta_ms` (wican-fw
 * `tools/log_clock.py` semantics); implausible rows the journal cannot place fall back to the
 * per-sync [Anchor].
 */
interface Normalizer {
    /**
     * @param lines raw export lines, including `{"_file":...}` markers.
     * @param journal the device clock journal, in journal order.
     * @param anchor null if no anchor was taken this sync.
     * @param openFile the file that rows before the first `_file` marker belong to (a page that
     *   starts mid-file), or null if unknown.
     * @return one [Record] per data row, in input order. Markers produce no record.
     */
    fun fix(
        lines: List<String>,
        journal: List<ClockEvent>,
        anchor: Anchor?,
        openFile: String? = null,
    ): List<Record>
}

/** Phone and device wall clocks sampled together during a sync. */
data class Anchor(
    val phoneEpochMs: Long,
    val deviceEpochMs: Long,
) {
    val offsetMs: Long get() = phoneEpochMs - deviceEpochMs
}

/**
 * A data row with its repaired timestamp.
 *
 * @property line the row as sinks receive it: the original JSON object with `ts` replaced by
 *   [ts] and `ts_raw`, `ts_quality` appended, other keys in their original order. A line that is
 *   not a JSON object is passed through verbatim.
 * @property ts repaired epoch millis, or null when [tsQuality] is [TsQuality.UNKNOWN].
 * @property tsRaw the timestamp exactly as the device logged it, or null if the row had none.
 * @property file the `_file` the row came from, or null if the page did not say.
 */
data class Record(
    val line: String,
    val ts: Long?,
    val tsRaw: Long?,
    val tsQuality: TsQuality,
    val file: String?,
)

/**
 * How [Record.ts] was derived, so receivers can weigh or filter rows. [wire] is the `ts_quality`
 * value written into the row.
 */
enum class TsQuality(val wire: String) {
    /** Re-based by at least one journal clock set that the row was stamped before. */
    JOURNAL("journal"),

    /**
     * The device's own stamp, unchanged: no journal set applies to the row and the stamp is
     * plausible (on or after 2020-01-01). Trusted as far as the device clock was; the journal
     * cannot confirm a clock it never saw set (e.g. a file opened after the boot's RTC restore).
     */
    DEVICE("device"),

    /**
     * Implausible stamp shifted by the sync's phone/device offset. Only right if the device has
     * not rebooted since the row was written, so this is a best guess.
     */
    ANCHOR("anchor"),

    /** No basis for repair; [Record.ts] is null. */
    UNKNOWN("unknown"),
}
