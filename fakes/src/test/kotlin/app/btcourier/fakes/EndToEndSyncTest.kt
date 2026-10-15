package app.btcourier.fakes

import app.btcourier.core.sink.Batch
import app.btcourier.core.sink.CompositeSink
import app.btcourier.core.sink.Delivery
import app.btcourier.core.sink.Jsonl
import app.btcourier.core.sink.LocalDirectoryUploadTransport
import app.btcourier.core.sink.UploadResult
import app.btcourier.core.sink.UploadTransport
import app.btcourier.core.store.DefaultStore
import app.btcourier.core.store.FileStoreDb
import app.btcourier.core.store.SealPolicy
import app.btcourier.core.sync.StopReason
import app.btcourier.core.sync.SyncReport
import app.btcourier.core.sync.SyncRunner
import app.btcourier.core.transport.blehttp.BleHttpTransport
import app.btcourier.core.wican.WicanLoggerSource
import app.btcourier.fakes.ScriptedPeripheral.Fault
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.GZIPInputStream
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readBytes

/**
 * T7: [FakeWicanLogger] behind [ScriptedPeripheral] → [BleHttpTransport] → [WicanLoggerSource] →
 * [SyncRunner] → [DefaultStore] on [FileStoreDb] → seal → [CompositeSink] of
 * [LocalDirectoryUploadTransport] and [Jsonl]. Expected stamps follow the plan's guarantees and
 * log_clock.py's rule: rows of a file a clock set lists, stamped at or before its `ts_before`,
 * move by its `delta_ms`.
 */
class EndToEndSyncTest {
    @TempDir
    lateinit var dir: Path

    private val storeDir: Path get() = dir.resolve("store")
    private val outDir: Path get() = dir.resolve("out")

    // Boot 7 started without a clock: file A holds 1970 + uptime stamps until SNTP set the clock
    // at uptime 95 s, which rotated the logger into file B, named by the corrected clock.
    private val epochA = 1_783_000_001L
    private val epochB = 1_784_000_000L
    private val fileA = "dl_$epochA.jsonl"
    private val fileB = "dl_$epochB.jsonl"
    private val tsBefore = 95_000L
    private val tsAfter = epochB * 1000
    private val delta = tsAfter - tsBefore

    private val rowsA = (0 until 30).map { row(ts = 30_000L + it * 2_000, value = it) }
    private val rowsB = (0 until 30).map { row(ts = tsAfter + (it + 1) * 1_000L, value = 100 + it) }

    private val journal = listOf(
        """{"ev":"boot","boot":7,"ts":20000,"up_ms":20000,"valid":false,"source":"none"}""",
        """{"ev":"valid","boot":7,"ts_before":$tsBefore,"ts_after":$tsAfter,"delta_ms":$delta,"source":"sntp","files":["$fileA"]}""",
        """{"ev":"rotate","boot":7,"ts":$tsAfter,"stream":"params","from":"$fileA","to":"$fileB","why":"clock"}""",
    ).joinToString("\n", postfix = "\n")

    private fun row(ts: Long, value: Int) = """{"ts":$ts,"param":"autopid.TEST","value":$value}"""

    private fun device() = FakeWicanLogger().apply {
        append(epochA, rowsA)
        append(epochB, rowsB)
        journal = this@EndToEndSyncTest.journal
        rtcEpochSeconds = epochB + 600
        clockValid = true
    }

    /** Counts uploads on the way to the local directory. */
    private class CountingUpload(private val inner: UploadTransport) : UploadTransport {
        var uploads = 0

        override suspend fun upload(batch: Batch): UploadResult {
            uploads++
            return inner.upload(batch)
        }
    }

    // Lazy: the temp dir is injected after construction.
    private val upload by lazy { CountingUpload(LocalDirectoryUploadTransport(outDir)) }
    private val sink by lazy { CompositeSink("local", upload, Jsonl) }

    /** Opens the store from disk, as a fresh process would. */
    private fun openStore() = DefaultStore(FileStoreDb.open(storeDir), clock = { 0 }, sinkIds = { setOf(sink.id) })

    /**
     * One connection: a new link, transport and source over [device], one sync, then delivery.
     * [beforeExport] runs as the device answers each export request (1-based), to inject faults.
     */
    private suspend fun TestScope.connectAndSync(
        device: FakeWicanLogger,
        store: DefaultStore,
        limit: Int = 7,
        beforeExport: ScriptedPeripheral.(Int) -> Unit = {},
    ): Synced {
        lateinit var peripheral: ScriptedPeripheral
        peripheral = ScriptedPeripheral(
            { request ->
                device.handle(request).also { if (request.path.startsWith("/api/logger/export")) peripheral.beforeExport(device.exports) }
            },
            scope = backgroundScope,
        )
        val transport = BleHttpTransport(peripheral.link, backgroundScope)
        val source = WicanLoggerSource(transport, limit, phoneClock = { (epochB + 600) * 1000 })
        val report = SyncRunner("wican", source, store).run()
        // Age 0 seals the open file too, so every stored row is deliverable after each sync.
        val sealed = store.seal(SealPolicy(maxBatchAgeMs = 0), 0)
        assertNull(Delivery(store).deliverPending(sink), "everything sealed was delivered")
        return Synced(report, sealed, peripheral)
    }

    private data class Synced(val report: SyncReport, val sealed: List<String>, val peripheral: ScriptedPeripheral)

    private fun objects(): List<Path> = outDir.listDirectoryEntries().sortedBy { it.name }

    private fun objectLines(): List<List<String>> =
        objects().map { GZIPInputStream(it.readBytes().inputStream()).readBytes().decodeToString().lines().filter(String::isNotEmpty) }

    private data class Out(val ts: Long?, val tsRaw: Long, val quality: String, val value: Int)

    private val field = { name: String -> Regex(""""$name":("[^"]*"|[^,}]*)""") }

    private fun parse(line: String): Out {
        fun get(name: String) = field(name).find(line)!!.groupValues[1].trim('"')
        return Out(get("ts").toLongOrNull(), get("ts_raw").toLong(), get("ts_quality"), get("value").toInt())
    }

    /** Asserts that every device row is delivered exactly once, re-based as the journal says. */
    private fun assertDeliveredExactlyOnce() {
        val objects = objectLines()
        assertTrue(objects.all { it.first().startsWith("""{"_file":""") }, "every object starts with its file marker")
        val rows = objects.flatten().filterNot { it.startsWith("""{"_file":""") }.map(::parse)
        assertEquals((0 until 30).toList() + (100 until 130).toList(), rows.map { it.value }.sorted(), "each row exactly once")

        val expected = rowsA.mapIndexed { i, _ -> 30_000L + i * 2_000 }.associateWith { Out(it + delta, it, "journal", 0) } +
            rowsB.mapIndexed { i, _ -> tsAfter + (i + 1) * 1_000L }.associateWith { Out(it, it, "device", 0) }
        for (row in rows) {
            val want = expected.getValue(row.tsRaw)
            assertEquals(want.ts to want.quality, row.ts to row.quality, "row ${row.value}")
        }
    }

    @Test
    fun `a multi-page sync delivers every row once, file A re-based by the journal, then a rerun moves nothing`() = runTest {
        val device = device()
        val store = openStore()

        val (first, firstSealed) = connectAndSync(device, store)

        // 7-line pages: 4 of file A, one spanning A and B, 4 more of B.
        assertEquals(SyncReport(pagesPulled = 9, linesStored = 60, duplicates = 0, StopReason.CaughtUp), first)
        assertEquals(2, firstSealed.size, "one batch per file")
        assertDeliveredExactlyOnce()
        assertEquals(2, objects().size, "one object per file")
        val uploaded = upload.uploads
        val names = objects().map { it.name }

        val (second, secondSealed, peripheral) = connectAndSync(device, store)

        assertEquals(SyncReport(pagesPulled = 1, linesStored = 0, duplicates = 0, StopReason.CaughtUp), second)
        assertEquals(emptyList<String>(), secondSealed)
        val sizeB = rowsB.sumOf { it.length + 1 }
        assertTrue(peripheral.requests.any { it.path.endsWith("since=$epochB:$sizeB&limit=7") }, "resumed from the end")
        assertEquals(uploaded, upload.uploads, "nothing uploaded")
        assertEquals(names, objects().map { it.name })
        assertTrue(Files.list(outDir).use { s -> s.noneMatch { it.name.endsWith(".part") } })
    }

    @Test
    fun `a sync cut by link loss resumes from the persisted cursor without duplicate rows`() = runTest {
        val device = device()

        // The link drops while the 4th page is in flight.
        val (cut) = connectAndSync(device, openStore()) { n -> if (n == 3) inject(Fault.LinkLossAfter(1)) }

        assertTrue(cut.stoppedBecause is StopReason.LinkLost, "${cut.stoppedBecause}")
        assertEquals(3, cut.pagesPulled)
        assertEquals(21, cut.linesStored)
        val store = openStore()
        val persisted = store.cursor("wican")
        assertEquals("$epochA:${rowsA.take(21).sumOf { it.length + 1 }}", persisted)

        val (resumed, resumedSealed, peripheral) = connectAndSync(device, store)

        assertEquals(SyncReport(pagesPulled = 6, linesStored = 39, duplicates = 0, StopReason.CaughtUp), resumed)
        assertEquals(2, resumedSealed.size, "the rest of file A, then file B")
        val firstExport = peripheral.requests.first { it.path.startsWith("/api/logger/export") }
        assertTrue(firstExport.path.contains("since=$persisted&"), firstExport.path)
        assertDeliveredExactlyOnce()
    }

    @Test
    fun `a page ending exactly at the end of a file still reaches the next file and never repeats it`() = runTest {
        // limit 7 with 7 rows in file A: the device's budget runs out on A's last line.
        val device = FakeWicanLogger().apply {
            append(epochA, rowsB.take(7))
            append(epochB, rowsB.drop(7).take(3))
        }
        val store = openStore()

        val (first) = connectAndSync(device, store)
        val (second) = connectAndSync(device, store)

        assertEquals(StopReason.CaughtUp, first.stoppedBecause)
        assertEquals(10, first.linesStored)
        assertEquals(0, second.linesStored)
        val values = objectLines().flatten().filterNot { it.startsWith("""{"_file":""") }.map { parse(it).value }
        assertEquals((100 until 110).toList(), values.sorted())
    }
}
