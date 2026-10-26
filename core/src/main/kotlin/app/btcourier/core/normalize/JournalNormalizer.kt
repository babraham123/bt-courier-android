package app.btcourier.core.normalize

import app.btcourier.core.lines.StoredLine
import app.btcourier.core.lines.walkFiles
import app.btcourier.core.source.ClockEvent
import app.btcourier.core.wican.long
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * Re-bases export rows with the clock journal, the way wican-fw `tools/log_clock.py` re-bases a
 * file that has no in-band `clock_step` markers (the export never emits them):
 *
 * - A clock set (`valid` or `step`) applies only to the files it lists as open at the time.
 * - Within such a file, a row is "before" the set when its stamp is at or before the set's
 *   `ts_before`, and then gets the set's `delta_ms` added.
 * - Sets are applied in journal order to the row's running stamp, so a row older than several
 *   sets of one boot collects all their deltas, each compared in the clock frame of its time.
 * - A row stamped more than 5 s before its boot's origin (`boot.ts - boot.up_ms`, moved along by
 *   each later set of that boot) was written by an earlier boot into a resumed file and is left
 *   alone by that set.
 *
 * Known limit of the no-marker rule, shared with log_clock.py: after a backward set, rows the
 * device wrote on the corrected clock but stamped at or before `ts_before` (still inside the
 * file the set lists) are shifted again, unless the step took the clock below the boot's origin
 * (then the earlier-boot rule shields them). The device rotates files when the clock moves by a
 * minute or more, which keeps that window under a minute.
 *
 * Quality, per row: [TsQuality.JOURNAL] if any set applied; else [TsQuality.DEVICE] if the stamp
 * is plausible; else [TsQuality.ANCHOR] if an anchor makes it plausible; else
 * [TsQuality.UNKNOWN]. A plausible untouched row is not called JOURNAL because the journal only
 * records sets, so it cannot vouch that the clock was right when a row it never mentions was
 * written.
 *
 * Lines: a row (any JSON object that is not a [StoredLine.Marker]) is rewritten with `ts`
 * repaired in place and `ts_raw`, `ts_quality` appended; a line that is not a JSON object is kept
 * verbatim and counted as a row, so nothing the device sent is lost; blank lines and a stray
 * `_cursor` meta object are dropped.
 *
 * Output is a pure function of the input: identical lines, journal, anchor and open file give
 * identical bytes, which batch hashing relies on.
 */
class JournalNormalizer : Normalizer {
    override fun fix(
        page: List<String>,
        journal: List<ClockEvent>,
        anchor: Anchor?,
        openFile: String?,
    ): Normalized {
        val setsByFile = setsByFile(journal)
        val out = ArrayList<String>(page.size)
        var rows = 0
        val lastFile =
            walkFiles(page, openFile) { line, kind, file ->
                when (kind) {
                    is StoredLine.Marker -> {
                        out += line
                    }

                    is StoredLine.Row -> {
                        if (!kind.fields.containsKey("_cursor")) {
                            out += fixRow(kind.fields, setsByFile[file].orEmpty(), anchor)
                            rows++
                        }
                    }

                    StoredLine.Other -> {
                        if (line.isNotBlank()) {
                            out += line
                            rows++
                        }
                    }
                }
            }
        return Normalized(if (rows == 0) emptyList() else out, rows, lastFile)
    }

    private fun fixRow(
        row: JsonObject,
        sets: List<FileSet>,
        anchor: Anchor?,
    ): String {
        val raw = row.long("ts")
        val (ts, quality) =
            when {
                raw == null -> null to TsQuality.UNKNOWN
                else -> repair(raw, sets, anchor)
            }
        return rewrite(row, ts, quality)
    }

    private fun repair(
        raw: Long,
        sets: List<FileSet>,
        anchor: Anchor?,
    ): Pair<Long?, TsQuality> {
        var ts = raw
        var applied = false
        for (set in sets) {
            val stampedBefore = ts <= set.tsBefore
            val fromEarlierBoot = set.originMs != null && ts < set.originMs - RESUMED_FILE_SLACK_MS
            if (stampedBefore && !fromEarlierBoot) {
                ts += set.deltaMs
                applied = true
            }
        }
        return when {
            applied -> ts to TsQuality.JOURNAL

            isPlausible(raw) -> raw to TsQuality.DEVICE

            // An anchor taken while the device clock was already valid leaves a 1970 stamp in
            // 1970: the row predates the current clock regime, so the offset says nothing about it.
            anchor != null && isPlausible(raw + anchor.offsetMs) -> raw + anchor.offsetMs to TsQuality.ANCHOR

            else -> null to TsQuality.UNKNOWN
        }
    }

    /** `ts` replaced in place; any device-sent `ts_raw`/`ts_quality` replaced by ours at the end. */
    private fun rewrite(
        row: JsonObject,
        ts: Long?,
        quality: TsQuality,
    ): String =
        buildJsonObject {
            for ((key, value) in row) {
                when (key) {
                    "ts" -> put(key, ts?.let(::JsonPrimitive) ?: JsonNull)
                    "ts_raw", "ts_quality" -> Unit
                    else -> put(key, value)
                }
            }
            put("ts_raw", row["ts"] ?: JsonNull)
            put("ts_quality", JsonPrimitive(quality.wire))
        }.toString()

    /** A clock set as seen by one file it lists, with its boot's origin at the time of the set. */
    private data class FileSet(
        val tsBefore: Long,
        val deltaMs: Long,
        val originMs: Long?,
    )

    private fun setsByFile(journal: List<ClockEvent>): Map<String, List<FileSet>> {
        val out = LinkedHashMap<String, MutableList<FileSet>>()
        var origin: Long? = null
        for (event in journal) {
            when (event) {
                is ClockEvent.Boot -> {
                    origin = event.originMs
                }

                is ClockEvent.ClockSet -> {
                    for (f in event.files.distinct()) {
                        out.getOrPut(f) { mutableListOf() } += FileSet(event.tsBefore, event.deltaMs, origin)
                    }
                    // The origin is on the clock as it read at the boot line; each set moves it.
                    origin = origin?.plus(event.deltaMs)
                }

                is ClockEvent.Rotate -> {}
            }
        }
        return out
    }

    companion object {
        /** 2020-01-01T00:00:00Z, the floor the firmware accepts for a manual clock set. */
        const val PLAUSIBLE_FROM_MS: Long = 1_577_836_800_000

        /** log_clock.py's tolerance for a row stamped just before its boot's computed origin. */
        private const val RESUMED_FILE_SLACK_MS = 5_000L

        fun isPlausible(epochMs: Long): Boolean = epochMs >= PLAUSIBLE_FROM_MS
    }
}
