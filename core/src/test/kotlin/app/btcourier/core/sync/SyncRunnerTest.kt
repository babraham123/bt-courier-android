package app.btcourier.core.sync

import app.btcourier.core.source.ClockEvent
import app.btcourier.core.source.DeviceStatus
import app.btcourier.core.source.Page
import app.btcourier.core.source.Source
import app.btcourier.core.source.SourceException
import app.btcourier.core.store.DefaultStore
import app.btcourier.core.store.SealPolicy
import app.btcourier.core.store.pendingRows
import app.btcourier.core.testing.WicanLines.boot
import app.btcourier.core.testing.WicanLines.clockSet
import app.btcourier.core.testing.WicanLines.fileMarker
import app.btcourier.core.testing.WicanLines.journal
import app.btcourier.core.testing.WicanLines.row
import app.btcourier.core.testing.WicanLines.storedRow
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException
import kotlin.time.Duration.Companion.seconds

/**
 * The loop around the [app.btcourier.core.normalize.Normalizer] and the store: what is pulled,
 * in which order, what stops a sync and what it reports. How rows are re-based is the
 * normaliser's spec (`JournalNormalizerTest`); here the real one runs only to show the journal,
 * the anchor and the open file reach it. What a stop means to the app (record, retry, seal) is
 * `SyncJobTest`'s.
 */
class SyncRunnerTest {
    /** Serves [pages] by the cursor asked for and records every call in order. */
    private class ScriptedSource(
        private val pages: Map<String?, Page>,
        private val journal: List<ClockEvent> = emptyList(),
        private val status: DeviceStatus? = null,
    ) : Source {
        val calls = mutableListOf<String>()
        var failPull: (cursor: String?, attempt: Int) -> Exception? = { _, _ -> null }
        var failJournal: Exception? = null
        private val attempts = HashMap<String?, Int>()

        override suspend fun pull(cursor: String?): Page {
            calls += "pull $cursor"
            val attempt = attempts.merge(cursor, 1, Int::plus)!!
            failPull(cursor, attempt)?.let { throw it }
            return pages.getValue(cursor)
        }

        override suspend fun clockJournal(): List<ClockEvent> {
            calls += "journal"
            failJournal?.let { throw it }
            return journal
        }

        override suspend fun deviceStatus(): DeviceStatus? {
            calls += "status"
            return status
        }
    }

    // File A was written before the clock was set (1970 + uptime) and is named by the journal's
    // `valid` event; file B was opened on the set clock.
    private val fileA = "dl_1.jsonl"
    private val fileB = "dl_1784000000.jsonl"
    private val tsBefore = 95_000L
    private val tsAfter = 1_784_000_000_000L
    private val delta = tsAfter - tsBefore
    private val clockJournal =
        journal(
            boot(1, ts = 20_000, upMs = 20_000, valid = false, source = "none"),
            clockSet("valid", 1, tsBefore, tsAfter, "sntp", fileA),
        )

    // Each page restates the marker of the file it starts in, as the WiCAN export does.
    private val pages =
        mapOf(
            null to Page(listOf(fileMarker(fileA), row(30_000), row(60_000)), "1:100", more = true),
            "1:100" to
                Page(
                    listOf(fileMarker(fileA), row(90_000), fileMarker(fileB), row(tsAfter + 1_000)),
                    "1784000000:60",
                    more = true,
                ),
            "1784000000:60" to Page(listOf(fileMarker(fileB), row(tsAfter + 2_000)), "1784000000:120", more = false),
        )

    private val expectedRows =
        listOf(
            storedRow(30_000, 30_000 + delta, "journal"),
            storedRow(60_000, 60_000 + delta, "journal"),
            storedRow(90_000, 90_000 + delta, "journal"),
            storedRow(tsAfter + 1_000, tsAfter + 1_000, "device"),
            storedRow(tsAfter + 2_000, tsAfter + 2_000, "device"),
        )

    private val store = DefaultStore.inMemory(clock = { 0 }, sinkIds = { setOf("sink") })

    private val busyBackoff = listOf(1.seconds, 2.seconds, 4.seconds)

    /** Runs one sync, then seals everything, so pending batches show what was stored. */
    private suspend fun sync(source: Source): SyncReport =
        SyncRunner("wican", source, store, busyBackoff = busyBackoff).run().also {
            store.seal(SealPolicy(maxBatchAgeMs = 0), 0)
        }

    @Test
    fun `pulls from the stored cursor until more is false, journal first, rows re-based per file`() =
        runTest {
            val source = ScriptedSource(pages, clockJournal)

            val report = sync(source)

            assertEquals(SyncReport(pagesPulled = 3, linesStored = 5, duplicates = 0, StopReason.CaughtUp), report)
            assertEquals(listOf("status", "journal", "pull null", "pull 1:100", "pull 1784000000:60"), source.calls)
            assertEquals("1784000000:120", store.cursor("wican"))
            val batches = store.pendingRows("sink")
            assertEquals(2, batches.size, "sealed at the file boundary")
            assertEquals(expectedRows, batches.flatten())
            assertEquals(listOf(fileMarker(fileA), fileMarker(fileB)), store.pending("sink").map { it.lines.first() })
        }

    @Test
    fun `a page that starts mid-file is normalised as the file the previous page left open`() =
        runTest {
            val resumed =
                pages +
                    (
                        "1:100" to
                            Page(
                                listOf(row(90_000), fileMarker(fileB), row(tsAfter + 1_000)),
                                "1784000000:60",
                                more = true,
                            )
                    )

            sync(ScriptedSource(resumed, clockJournal))

            assertEquals(expectedRows, store.pendingRows("sink").flatten())
        }

    @Test
    fun `a sync whose first page resumes mid-file without a marker re-bases it as the file the store left open`() =
        runTest {
            val firstSync =
                mapOf<String?, Page>(
                    null to Page(listOf(fileMarker(fileA), row(30_000), row(60_000)), "1:100", more = false),
                )
            sync(ScriptedSource(firstSync, clockJournal))

            val resumed = mapOf<String?, Page>("1:100" to Page(listOf(row(90_000)), "1:130", more = false))
            sync(ScriptedSource(resumed, clockJournal))

            assertEquals(expectedRows.take(3), store.pendingRows("sink").flatten())
        }

    @Test
    fun `the device status becomes the anchor for rows the journal cannot place`() =
        runTest {
            // Clock never set this boot: rows are 1970 + uptime and no journal event names the file.
            val unset =
                mapOf<String?, Page>(null to Page(listOf(fileMarker("dl_2.jsonl"), row(50_000)), "2:40", more = false))
            val status = DeviceStatus(deviceEpochMs = 100_000, phoneEpochMs = 1_784_000_100_000, clockValid = false)

            sync(ScriptedSource(unset, status = status))

            val offset = 1_784_000_100_000 - 100_000
            assertEquals(listOf(storedRow(50_000, 50_000 + offset, "anchor")), store.pendingRows("sink").flatten())
        }

    @Test
    fun `link loss stops cleanly at the last persisted page and the next run resumes there`() =
        runTest {
            val source = ScriptedSource(pages, clockJournal)
            source.failPull =
                { cursor, attempt -> if (cursor == "1:100" && attempt == 1) IOException("link lost") else null }

            val first = sync(source)

            assertTrue(first.stoppedBecause is StopReason.LinkLost)
            assertEquals(1, first.pagesPulled)
            assertEquals(2, first.linesStored)
            assertEquals("1:100", store.cursor("wican"))
            assertEquals(expectedRows.take(2), store.pendingRows("sink").flatten())

            source.calls.clear()
            val second = sync(source)

            assertEquals(StopReason.CaughtUp, second.stoppedBecause)
            assertEquals("pull 1:100", source.calls[2])
            assertEquals(expectedRows, store.pendingRows("sink").flatten())
        }

    @Test
    fun `a truncated page is not stored and the cursor stays`() =
        runTest {
            val failure = SourceException.Truncated("/api/logger/export")
            val source =
                ScriptedSource(pages, clockJournal).apply {
                    failPull = {
                        c,
                        _,
                        ->
                        failure.takeIf { c == "1:100" }
                    }
                }

            val report = sync(source)

            assertEquals(StopReason.Truncated(failure), report.stoppedBecause)
            assertEquals("1:100", store.cursor("wican"))
        }

    @Test
    fun `429 is retried after each back-off wait, then the sync stops as Busy`() =
        runTest {
            val flaky =
                ScriptedSource(pages, clockJournal).apply {
                    failPull = { c, attempt -> SourceException.Busy("export").takeIf { c == null && attempt <= 2 } }
                }
            assertEquals(StopReason.CaughtUp, sync(flaky).stoppedBecause)
            assertEquals(3_000, currentTime)

            val stuck =
                ScriptedSource(pages, clockJournal).apply {
                    failPull =
                        { _, _ -> SourceException.Busy("export") }
                }
            val report =
                SyncRunner(
                    "wican",
                    stuck,
                    DefaultStore.inMemory(sinkIds = {
                        emptySet()
                    }),
                    busyBackoff = busyBackoff,
                ).run()
            assertEquals(StopReason.Busy, report.stoppedBecause)
            assertEquals(4, stuck.calls.count { it.startsWith("pull") })
            assertEquals(3_000 + 7_000, currentTime)

            val impatient =
                ScriptedSource(pages, clockJournal).apply {
                    failPull =
                        { _, _ -> SourceException.Busy("export") }
                }
            assertEquals(StopReason.Busy, SyncRunner("wican", impatient, store).run().stoppedBecause)
            assertEquals(1, impatient.calls.count { it.startsWith("pull") }, "no waits, no retries")
        }

    @Test
    fun `a journal the device refuses stops the sync before anything is pulled`() =
        runTest {
            // Rows stored without the journal would keep un-re-based stamps for good.
            val source =
                ScriptedSource(pages, clockJournal).apply {
                    failJournal =
                        SourceException.DeviceError(409, "journal", "file in use")
                }

            val report = sync(source)

            assertTrue(report.stoppedBecause is StopReason.DeviceError)
            assertEquals(0, report.pagesPulled)
            assertEquals(listOf("status", "journal"), source.calls)
            assertEquals(null, store.cursor("wican"))
        }

    @Test
    fun `a caught-up device stores nothing and counts no duplicates`() =
        runTest {
            sync(ScriptedSource(pages, clockJournal))
            val caughtUp =
                mapOf<String?, Page>(
                    "1784000000:120" to Page(listOf(fileMarker(fileB)), "1784000000:120", more = false),
                )

            val report = sync(ScriptedSource(caughtUp, clockJournal))

            assertEquals(SyncReport(pagesPulled = 1, linesStored = 0, duplicates = 0, StopReason.CaughtUp), report)
            assertEquals(expectedRows, store.pendingRows("sink").flatten())
        }

    @Test
    fun `a device that promises more without moving its cursor is not polled forever`() =
        runTest {
            val stuck = pages + ("1:100" to Page(listOf(fileMarker(fileA), row(60_000)), "1:100", more = true))

            val report = sync(ScriptedSource(stuck, clockJournal))

            assertEquals(
                SyncReport(pagesPulled = 2, linesStored = 2, duplicates = 1, StopReason.NoProgress("1:100")),
                report,
            )
        }

    @Test
    fun `a page of markers only advances the cursor without storing a page`() =
        runTest {
            val empty =
                mapOf<String?, Page>(
                    null to Page(listOf(fileMarker("dl_5.jsonl")), "6:0", more = true),
                    "6:0" to Page(listOf(fileMarker("dl_6.jsonl"), row(tsAfter)), "6:30", more = false),
                )

            val report = sync(ScriptedSource(empty))

            val batches = store.pending("sink")
            assertEquals(SyncReport(pagesPulled = 2, linesStored = 1, duplicates = 0, StopReason.CaughtUp), report)
            assertEquals(
                listOf(listOf(fileMarker("dl_6.jsonl"), storedRow(tsAfter, tsAfter, "device"))),
                batches.map { it.lines },
            )
        }

    @Test
    fun `other link failures are reported as link loss`() =
        runTest {
            val source =
                ScriptedSource(pages, clockJournal).apply {
                    failPull = {
                        _,
                        _,
                        ->
                        IOException("socket closed")
                    }
                }

            val report = sync(source)

            assertEquals("socket closed", (report.stoppedBecause as StopReason.LinkLost).cause.message)
        }
}
