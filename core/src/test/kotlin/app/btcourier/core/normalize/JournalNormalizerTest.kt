package app.btcourier.core.normalize

import app.btcourier.core.lines.StoredLine
import app.btcourier.core.lines.walkFiles
import app.btcourier.core.normalize.TsQuality.ANCHOR
import app.btcourier.core.normalize.TsQuality.DEVICE
import app.btcourier.core.normalize.TsQuality.JOURNAL
import app.btcourier.core.normalize.TsQuality.UNKNOWN
import app.btcourier.core.source.ClockEvent
import app.btcourier.core.testing.WicanLines.boot
import app.btcourier.core.testing.WicanLines.clockSet
import app.btcourier.core.testing.WicanLines.fileMarker
import app.btcourier.core.testing.WicanLines.journal
import app.btcourier.core.testing.WicanLines.meta
import app.btcourier.core.testing.WicanLines.rotate
import app.btcourier.core.testing.WicanLines.row
import app.btcourier.core.testing.WicanLines.storedRow
import app.btcourier.core.wican.ExportPageParser
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Expected stamps follow wican-fw `tools/log_clock.py` without in-band markers: a set applies to
 * the files it lists; a row at or before its `ts_before` gets its `delta_ms`; sets compound in
 * journal order; a row more than 5 s before its boot's (moving) origin is left alone. What is
 * asserted is what the store would keep: [Normalized.lines], read back through [walkFiles].
 */
class JournalNormalizerTest {
    private val normalizer = JournalNormalizer()

    /** (file, raw stamp, repaired stamp, quality) per stored row: what each scenario asserts. */
    private data class Fixed(
        val file: String?,
        val raw: Long?,
        val ts: Long?,
        val quality: TsQuality,
    )

    private fun fix(
        lines: List<String>,
        journal: List<ClockEvent>,
        anchor: Anchor? = null,
        openFile: String? = null,
    ) = storedRows(normalizer.fix(lines, journal, anchor, openFile), openFile)

    /** Reads the stored rows back, each attributed to its file by the markers kept in the output. */
    private fun storedRows(
        normalized: Normalized,
        openFile: String?,
    ): List<Fixed> {
        val rows = mutableListOf<Fixed>()
        walkFiles(normalized.lines, openFile) { _, kind, file ->
            if (kind !is StoredLine.Row) return@walkFiles

            fun long(key: String) = (kind.fields[key] as? JsonPrimitive)?.longOrNull
            val quality = (kind.fields["ts_quality"] as JsonPrimitive).content
            rows += Fixed(file, long("ts_raw"), long("ts"), TsQuality.entries.single { it.wire == quality })
        }
        assertEquals(rows.size, normalized.rows, "every stored row is counted")
        return rows
    }

    // A boot without a clock: 1970 + uptime until SNTP sets it, then the logger rotates by clock.
    private val unsynced = "dl_0000000001.jsonl"
    private val synced = "dl_1784000060.jsonl"
    private val syncAt = 1_784_000_060_000L
    private val syncDelta = syncAt - 60_000
    private val unsyncedBootLines =
        arrayOf(
            boot(1, ts = 20_000, upMs = 20_000, valid = false, source = "none"),
            clockSet("valid", 1, tsBefore = 60_000, tsAfter = syncAt, source = "sntp", unsynced),
            rotate(1, ts = syncAt, from = unsynced, to = synced),
        )
    private val unsyncedBoot = journal(*unsyncedBootLines)

    @Test
    fun `boot with an invalid clock then an SNTP set re-bases rows at or before ts_before`() {
        val lines =
            listOf(
                fileMarker(unsynced),
                row(25_000),
                row(59_000),
                row(60_000),
                // Queued before the set, written after it: the device already stamped it on the new clock.
                row(syncAt + 200),
            )

        assertEquals(
            listOf(
                Fixed(unsynced, 25_000, 25_000 + syncDelta, JOURNAL),
                Fixed(unsynced, 59_000, 59_000 + syncDelta, JOURNAL),
                Fixed(unsynced, 60_000, 60_000 + syncDelta, JOURNAL),
                Fixed(unsynced, syncAt + 200, syncAt + 200, DEVICE),
            ),
            fix(lines, unsyncedBoot),
        )
    }

    @Test
    fun `markers are stored in place and rows in the file rotated to by the clock are untouched`() {
        val lines =
            listOf(
                fileMarker(unsynced),
                row(30_000),
                fileMarker(synced),
                row(syncAt + 1_000),
                row(syncAt + 2_000),
            )

        assertEquals(
            Normalized(
                listOf(
                    fileMarker(unsynced),
                    storedRow(30_000, 30_000 + syncDelta, "journal"),
                    fileMarker(synced),
                    storedRow(syncAt + 1_000, syncAt + 1_000, "device"),
                    storedRow(syncAt + 2_000, syncAt + 2_000, "device"),
                ),
                rows = 3,
                lastFile = synced,
            ),
            normalizer.fix(lines, unsyncedBoot, anchor = null),
        )
    }

    @Test
    fun `the open file carries from one page to the next`() {
        val first = normalizer.fix(listOf(fileMarker(unsynced), row(25_000)), unsyncedBoot, anchor = null)
        // The next page resumes mid-file: its first rows belong to the file the last page left open.
        val second = listOf(row(40_000), fileMarker(synced), row(syncAt + 1_000))

        assertEquals(unsynced, first.lastFile)
        assertEquals(
            listOf(
                Fixed(unsynced, 40_000, 40_000 + syncDelta, JOURNAL),
                Fixed(synced, syncAt + 1_000, syncAt + 1_000, DEVICE),
            ),
            fix(second, unsyncedBoot, openFile = first.lastFile),
        )
        assertEquals(synced, normalizer.fix(second, unsyncedBoot, anchor = null, openFile = first.lastFile).lastFile)
    }

    @Test
    fun `a page of markers only stores nothing but still moves the open file`() {
        assertEquals(
            Normalized(emptyList(), rows = 0, lastFile = synced),
            normalizer.fix(listOf(fileMarker(unsynced), fileMarker(synced)), unsyncedBoot, anchor = null),
        )
        assertEquals(
            Normalized(emptyList(), rows = 0, lastFile = unsynced),
            normalizer.fix(emptyList(), unsyncedBoot, null, unsynced),
        )
    }

    @Test
    fun `ring rows with an empty journal and no anchor keep their own stamps as device quality`() {
        val day = StoredLine.marker("colmi/2026-10-14")
        val hr = """{"ts":1760400000000,"param":"colmi.hr","value":61}"""

        assertEquals(
            Normalized(
                listOf(day, storedRow(1_760_400_000_000, 1_760_400_000_000, "device", "colmi.hr", "61")),
                1,
                "colmi/2026-10-14",
            ),
            normalizer.fix(listOf(day, hr), journal = emptyList(), anchor = null),
        )
    }

    // A boot whose RTC restore was valid but off, corrected by two small sets (no rotation: < 1 min).
    private val b0 = 1_784_100_000_000L
    private val stepped = "dl_1784099990.jsonl"
    private val openedBetween = "dl_1784100095.jsonl"
    private val twoStepBoot =
        journal(
            boot(2, ts = b0, upMs = 10_000, valid = true, source = "rtc"),
            clockSet("step", 2, tsBefore = b0 + 60_000, tsAfter = b0 + 90_000, source = "sntp", stepped),
            clockSet(
                "step",
                2,
                tsBefore = b0 + 120_000,
                tsAfter = b0 + 100_000,
                source = "sntp",
                stepped,
                openedBetween,
            ),
        )

    @Test
    fun `multiple steps in one boot compound in journal order`() {
        val lines =
            listOf(
                fileMarker(stepped),
                row(b0 + 30_000), // before both: +30 s puts it at b0+60 s, still before the second
                row(b0 + 100_000), // between them, on the once-corrected clock
                row(b0 + 125_000), // after both
            )

        assertEquals(
            listOf(
                Fixed(stepped, b0 + 30_000, b0 + 30_000 + 30_000 - 20_000, JOURNAL),
                Fixed(stepped, b0 + 100_000, b0 + 100_000 - 20_000, JOURNAL),
                Fixed(stepped, b0 + 125_000, b0 + 125_000, DEVICE),
            ),
            fix(lines, twoStepBoot),
        )
    }

    @Test
    fun `rows from an earlier boot in a resumed file keep their stamp, against the origin moved by earlier sets`() {
        // Origin = b0 - 10 s; after the +30 s step it is b0 + 20 s, so the cut-off is b0 + 15 s.
        val lines =
            listOf(
                fileMarker(stepped),
                row(b0 - 16_000),
                fileMarker(openedBetween),
                row(b0 + 14_000),
                row(b0 + 110_000),
            )

        assertEquals(
            listOf(
                Fixed(stepped, b0 - 16_000, b0 - 16_000, DEVICE),
                Fixed(openedBetween, b0 + 14_000, b0 + 14_000, DEVICE),
                Fixed(openedBetween, b0 + 110_000, b0 + 110_000 - 20_000, JOURNAL),
            ),
            fix(lines, twoStepBoot),
        )
    }

    // A backward step of 2 h that rotates the file.
    private val c0 = 1_784_200_000_000L
    private val beforeBack = "dl_1784199995.jsonl"
    private val afterBack = "dl_1784193400.jsonl"
    private val backAt = c0 + 600_000 - 7_200_000
    private val backwardBoot =
        journal(
            boot(3, ts = c0, upMs = 5_000, valid = true, source = "rtc"),
            clockSet("step", 3, tsBefore = c0 + 600_000, tsAfter = backAt, source = "manual", beforeBack),
            rotate(3, ts = backAt, from = beforeBack, to = afterBack),
        )

    @Test
    fun `after a backward step only files the step lists are shifted`() {
        val lines = listOf(fileMarker(beforeBack), row(c0 + 300_000), fileMarker(afterBack), row(backAt + 1_000))

        assertEquals(
            listOf(
                Fixed(beforeBack, c0 + 300_000, c0 + 300_000 - 7_200_000, JOURNAL),
                // Stamped before ts_before, but its file opened after the step and is not listed.
                Fixed(afterBack, backAt + 1_000, backAt + 1_000, DEVICE),
            ),
            fix(lines, backwardBoot),
        )
    }

    @Test
    fun `without markers a corrected row stamped at or before ts_before of a small backward step is shifted again`() {
        // Written after the -20 s step at b0+100 s, yet stamped before its ts_before (b0+120 s) and
        // after the boot origin: log_clock.py's no-marker rule cannot tell, and neither can we.
        val lines = listOf(fileMarker(stepped), row(b0 + 105_000))

        assertEquals(listOf(Fixed(stepped, b0 + 105_000, b0 + 105_000 - 20_000, JOURNAL)), fix(lines, twoStepBoot))
    }

    @Test
    fun `a corrected row stamped before the boot origin after a large backward step keeps its stamp`() {
        // 2 h back takes the clock below the boot's origin (c0 - 5 s), so the earlier-boot rule
        // shields rows written after the step.
        val lines = listOf(fileMarker(beforeBack), row(backAt + 500))

        assertEquals(listOf(Fixed(beforeBack, backAt + 500, backAt + 500, DEVICE)), fix(lines, backwardBoot))
    }

    @Test
    fun `a file with no journal events keeps plausible stamps and cannot place implausible ones`() {
        val lines = listOf(fileMarker("dl_1784300000.jsonl"), row(1_784_300_001_000), row(5_000))

        val normalized = normalizer.fix(lines, unsyncedBoot, anchor = null)

        assertEquals(
            listOf(
                Fixed("dl_1784300000.jsonl", 1_784_300_001_000, 1_784_300_001_000, DEVICE),
                Fixed("dl_1784300000.jsonl", 5_000, null, UNKNOWN),
            ),
            storedRows(normalized, openFile = null),
        )
        assertEquals(storedRow(5_000, null, "unknown"), normalized.lines[2])
    }

    @Test
    fun `an implausible row the journal cannot place falls back to the anchor offset`() {
        val anchor = Anchor(phoneEpochMs = 1_784_400_000_000, deviceEpochMs = 90_000)
        val lines = listOf(fileMarker("dl_0000000002.jsonl"), row(70_000), row(1_784_300_001_000))

        assertEquals(
            listOf(
                Fixed("dl_0000000002.jsonl", 70_000, 70_000 + 1_784_400_000_000 - 90_000, ANCHOR),
                Fixed("dl_0000000002.jsonl", 1_784_300_001_000, 1_784_300_001_000, DEVICE),
            ),
            fix(lines, journal = emptyList(), anchor = anchor),
        )
    }

    @Test
    fun `a journal fix wins over the anchor`() {
        val anchor = Anchor(phoneEpochMs = 1_784_400_000_000, deviceEpochMs = 90_000)

        assertEquals(
            listOf(Fixed(unsynced, 25_000, 25_000 + syncDelta, JOURNAL)),
            fix(listOf(fileMarker(unsynced), row(25_000)), unsyncedBoot, anchor),
        )
    }

    @Test
    fun `an anchor taken on a valid device clock cannot place a 1970 row`() {
        val anchor = Anchor(phoneEpochMs = 1_784_400_000_300, deviceEpochMs = 1_784_400_000_000)

        assertEquals(
            listOf(Fixed("dl_0000000002.jsonl", 70_000, null, UNKNOWN)),
            fix(listOf(fileMarker("dl_0000000002.jsonl"), row(70_000)), emptyList(), anchor),
        )
    }

    @Test
    fun `output keeps key order, number literals and extra fields, with ts_raw and ts_quality appended`() {
        val line = """{"ts":25000,"param":"autopid.TEST","value":0.500000,"unit":"kPa"}"""

        val stored = normalizer.fix(listOf(fileMarker(unsynced), line), unsyncedBoot, anchor = null).lines

        assertEquals(
            """{"ts":${25_000 + syncDelta},"param":"autopid.TEST","value":0.500000,"unit":"kPa",""" +
                """"ts_raw":25000,"ts_quality":"journal"}""",
            stored.last(),
        )
    }

    @Test
    fun `odd lines are stored without a timestamp, blank lines and a stray cursor line are dropped`() {
        val lines =
            listOf("not json", "", """{"param":"autopid.TEST","value":2}""", meta("1:10", more = false), "[1,2]")

        assertEquals(
            Normalized(
                listOf(
                    "not json",
                    """{"param":"autopid.TEST","value":2,"ts_raw":null,"ts_quality":"unknown"}""",
                    "[1,2]",
                ),
                rows = 3,
                lastFile = null,
            ),
            normalizer.fix(lines, unsyncedBoot, anchor = null),
        )
    }

    @Test
    fun `identical input gives identical output bytes`() {
        val body =
            listOf(
                fileMarker(unsynced),
                row(25_000, value = "1.25"),
                """{"ts":26000,"value":3,"param":"autopid.é","x":{"b":1,"a":[2,1]}}""",
                fileMarker(synced),
                row(syncAt + 1_000),
                row(7_000),
                meta("1784000060:512", more = false),
            ).joinToString("\n")
        val anchor = Anchor(phoneEpochMs = 1_784_400_000_000, deviceEpochMs = 90_000)

        // Fresh parser output and normalizer each run, as a re-sync after a restart would have.
        fun run() =
            JournalNormalizer()
                .fix(ExportPageParser.parse(body).lines, journal(*unsyncedBootLines), anchor)
                .lines
                .joinToString("\n")
                .encodeToByteArray()

        assertArrayEquals(run(), run())
    }
}
