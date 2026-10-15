package app.btcourier.core.normalize

import app.btcourier.core.lines.StoredLine
import app.btcourier.core.source.ClockEvent

/**
 * Turns one pulled page into exactly the lines the store keeps, repairing device timestamps
 * logged before the device clock was valid or before it was stepped.
 *
 * Primary method re-bases each `_file` by the journal's `delta_ms` (wican-fw
 * `tools/log_clock.py` semantics); implausible rows the journal cannot place fall back to the
 * per-sync [Anchor].
 */
interface Normalizer {
    /**
     * @param page the page's lines as the source sent them, [StoredLine.Marker]s included.
     * @param journal the device clock journal, in journal order.
     * @param anchor null if no anchor was taken this sync.
     * @param openFile the file that rows before the page's first marker belong to: the previous
     *   page's [Normalized.lastFile], or null for the first page of a sync.
     */
    fun fix(
        page: List<String>,
        journal: List<ClockEvent>,
        anchor: Anchor?,
        openFile: String? = null,
    ): Normalized
}

/**
 * A page ready for the store.
 *
 * @property lines what to store, in page order: markers verbatim and in place (the store splits
 *   batches on them), every other non-blank line as its row was repaired. Empty when the page has
 *   no rows, since markers alone would seal into a batch with no data.
 * @property rows lines of [lines] that are not markers.
 * @property lastFile the file open after the page, even when [lines] is empty: the next page's
 *   `openFile`.
 */
data class Normalized(
    val lines: List<String>,
    val rows: Int,
    val lastFile: String?,
)

/** Phone and device wall clocks sampled together during a sync. */
data class Anchor(
    val phoneEpochMs: Long,
    val deviceEpochMs: Long,
) {
    val offsetMs: Long get() = phoneEpochMs - deviceEpochMs
}

/**
 * How a stored row's `ts` was derived, so receivers can weigh or filter rows. [wire] is the
 * `ts_quality` value written into the row.
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

    /** No basis for repair; `ts` is null. */
    UNKNOWN("unknown"),
}
