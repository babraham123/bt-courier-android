package app.btcourier.core.sync

import app.btcourier.core.source.ClockEvent
import app.btcourier.core.source.DeviceStatus
import app.btcourier.core.source.Page
import app.btcourier.core.source.Source
import app.btcourier.core.source.SourceException
import app.btcourier.core.store.DefaultStore
import app.btcourier.core.store.InMemoryStoreDb
import app.btcourier.core.store.SealPolicy
import app.btcourier.core.store.pendingRows
import app.btcourier.core.testing.WicanLines.boot
import app.btcourier.core.testing.WicanLines.clockSet
import app.btcourier.core.testing.WicanLines.fileMarker
import app.btcourier.core.testing.WicanLines.journal
import app.btcourier.core.testing.WicanLines.row
import app.btcourier.core.transport.blehttp.BleHttpException
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException

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
    private val clockJournal = journal(
        boot(1, ts = 20_000, upMs = 20_000, valid = false, source = "none"),
        clockSet("valid", 1, tsBefore, tsAfter, "sntp", fileA),
    )

    private val pages = mapOf(
        null to Page(listOf(fileMarker(fileA), row(30_000), row(60_000)), "1:100", more = true),
        "1:100" to Page(listOf(fileMarker(fileA), row(90_000), fileMarker(fileB), row(tsAfter + 1_000)), "1784000000:60", more = true),
        "1784000000:60" to Page(listOf(fileMarker(fileB), row(tsAfter + 2_000)), "1784000000:120", more = false),
    )

    /** A row as the normaliser rewrites it: `ts` replaced, `ts_raw` and `ts_quality` appended. */
    private fun fixed(raw: Long, ts: Long?, quality: String) =
        """{"ts":$ts,"param":"autopid.TEST","value":1,"ts_raw":$raw,"ts_quality":"$quality"}"""

    private val expectedRows = listOf(
        fixed(30_000, 30_000 + delta, "journal"),
        fixed(60_000, 60_000 + delta, "journal"),
        fixed(90_000, 90_000 + delta, "journal"),
        fixed(tsAfter + 1_000, tsAfter + 1_000, "device"),
        fixed(tsAfter + 2_000, tsAfter + 2_000, "device"),
    )

    private val store = DefaultStore(InMemoryStoreDb(), clock = { 0 }, sinkIds = { setOf("sink") })

    // Seals everything at the end of each run, so pending batches show what was stored.
    private fun runner(source: Source) =
        SyncRunner("wican", source, store, sealPolicy = SealPolicy(maxBatchAgeMs = 0), clock = { 0 })

    @Test
    fun `pulls from the stored cursor until more is false, journal first, rows re-based per file`() = runTest {
        val source = ScriptedSource(pages, clockJournal)

        val report = runner(source).run()

        val sealed = store.pending("sink").map { it.id }
        assertEquals(SyncReport(pagesPulled = 3, linesStored = 5, duplicates = 0, StopReason.CaughtUp, sealed), report)
        assertEquals(listOf("status", "journal", "pull null", "pull 1:100", "pull 1784000000:60"), source.calls)
        assertEquals("1784000000:120", store.cursor("wican"))
        val batches = store.pendingRows("sink")
        assertEquals(2, batches.size, "sealed at the file boundary")
        assertEquals(expectedRows, batches.flatten())
    }

    @Test
    fun `batches keep the device's file markers`() = runTest {
        runner(ScriptedSource(pages, clockJournal)).run()

        val batches = store.pending("sink")
        assertEquals(listOf(fileMarker(fileA), fileMarker(fileB)), batches.map { it.lines.first() })
    }

    @Test
    fun `the device status becomes the anchor for rows the journal cannot place`() = runTest {
        // Clock never set this boot: rows are 1970 + uptime and no journal event names the file.
        val unset = mapOf<String?, Page>(null to Page(listOf(fileMarker("dl_2.jsonl"), row(50_000)), "2:40", more = false))
        val status = DeviceStatus(deviceEpochMs = 100_000, phoneEpochMs = 1_784_000_100_000, clockValid = false)

        runner(ScriptedSource(unset, status = status)).run()

        val offset = 1_784_000_100_000 - 100_000
        assertEquals(listOf(fixed(50_000, 50_000 + offset, "anchor")), store.pendingRows("sink").flatten())
    }

    @Test
    fun `link loss stops cleanly at the last persisted page and the next run resumes there`() = runTest {
        val source = ScriptedSource(pages, clockJournal)
        source.failPull = { cursor, attempt -> if (cursor == "1:100" && attempt == 1) BleHttpException.LinkLost() else null }

        val first = runner(source).run()

        assertTrue(first.stoppedBecause is StopReason.LinkLost)
        assertEquals(1, first.pagesPulled)
        assertEquals(2, first.linesStored)
        assertEquals("1:100", store.cursor("wican"))
        // What was persisted is sealed and deliverable even though the sync failed.
        assertEquals(expectedRows.take(2), store.pendingRows("sink").flatten())
        val firstSealed = store.pending("sink").map { it.id }
        assertEquals(firstSealed, first.sealed)

        source.calls.clear()
        val second = runner(source).run()

        assertEquals(StopReason.CaughtUp, second.stoppedBecause)
        assertEquals(store.pending("sink").map { it.id } - firstSealed.toSet(), second.sealed, "only this run's batches")
        assertEquals("pull 1:100", source.calls[2])
        assertEquals(expectedRows, store.pendingRows("sink").flatten())
    }

    @Test
    fun `a truncated page is not stored and the cursor stays`() = runTest {
        for (failure in listOf(SourceException.Truncated("/api/logger/export"), BleHttpException.Truncated(100, 50))) {
            val local = DefaultStore(InMemoryStoreDb(), clock = { 0 }, sinkIds = { setOf("sink") })
            val source = ScriptedSource(pages, clockJournal).apply { failPull = { c, _ -> failure.takeIf { c == "1:100" } } }

            val report = SyncRunner("wican", source, local, clock = { 0 }).run()

            assertEquals(StopReason.Truncated(failure), report.stoppedBecause)
            assertEquals("1:100", local.cursor("wican"))
        }
    }

    @Test
    fun `429 is retried after 1, 2 and 4 s, then the sync stops as Busy`() = runTest {
        val flaky = ScriptedSource(pages, clockJournal).apply {
            failPull = { c, attempt -> SourceException.Busy("export").takeIf { c == null && attempt <= 2 } }
        }
        assertEquals(StopReason.CaughtUp, runner(flaky).run().stoppedBecause)
        assertEquals(3_000, currentTime)

        val stuck = ScriptedSource(pages, clockJournal).apply { failPull = { _, _ -> SourceException.Busy("export") } }
        val report = SyncRunner("wican", stuck, DefaultStore(InMemoryStoreDb(), sinkIds = { emptySet() })).run()
        assertEquals(StopReason.Busy, report.stoppedBecause)
        assertEquals(4, stuck.calls.count { it.startsWith("pull") })
        assertEquals(3_000 + 7_000, currentTime)
    }

    @Test
    fun `a journal the device refuses stops the sync before anything is pulled`() = runTest {
        // Rows stored without the journal would keep un-re-based stamps for good.
        val source = ScriptedSource(pages, clockJournal).apply { failJournal = SourceException.DeviceError(409, "journal", "file in use") }

        val report = runner(source).run()

        assertTrue(report.stoppedBecause is StopReason.DeviceError)
        assertEquals(0, report.pagesPulled)
        assertEquals(listOf("status", "journal"), source.calls)
        assertEquals(null, store.cursor("wican"))
    }

    @Test
    fun `a caught-up device stores nothing and counts no duplicates`() = runTest {
        runner(ScriptedSource(pages, clockJournal)).run()
        val caughtUp = mapOf<String?, Page>("1784000000:120" to Page(listOf(fileMarker(fileB)), "1784000000:120", more = false))

        val report = runner(ScriptedSource(caughtUp, clockJournal)).run()

        assertEquals(SyncReport(pagesPulled = 1, linesStored = 0, duplicates = 0, StopReason.CaughtUp, sealed = emptyList()), report)
        assertEquals(expectedRows, store.pendingRows("sink").flatten())
    }

    @Test
    fun `a device that promises more without moving its cursor is not polled forever`() = runTest {
        val stuck = pages + ("1:100" to Page(listOf(fileMarker(fileA), row(60_000)), "1:100", more = true))

        val report = runner(ScriptedSource(stuck, clockJournal)).run()

        val sealed = store.pending("sink").map { it.id }
        assertEquals(SyncReport(pagesPulled = 2, linesStored = 2, duplicates = 1, StopReason.NoProgress("1:100"), sealed), report)
    }

    @Test
    fun `a page of markers only advances the cursor without storing a page`() = runTest {
        val empty = mapOf<String?, Page>(
            null to Page(listOf(fileMarker("dl_5.jsonl")), "6:0", more = true),
            "6:0" to Page(listOf(fileMarker("dl_6.jsonl"), row(tsAfter)), "6:30", more = false),
        )

        val report = runner(ScriptedSource(empty)).run()

        val batches = store.pending("sink")
        assertEquals(SyncReport(pagesPulled = 2, linesStored = 1, duplicates = 0, StopReason.CaughtUp, batches.map { it.id }), report)
        assertEquals(listOf(listOf(fileMarker("dl_6.jsonl"), fixed(tsAfter, tsAfter, "device"))), batches.map { it.lines })
    }

    @Test
    fun `other link failures are reported as link loss`() = runTest {
        val source = ScriptedSource(pages, clockJournal).apply { failPull = { _, _ -> IOException("socket closed") } }

        val report = runner(source).run()

        assertEquals("socket closed", (report.stoppedBecause as StopReason.LinkLost).cause.message)
    }
}
