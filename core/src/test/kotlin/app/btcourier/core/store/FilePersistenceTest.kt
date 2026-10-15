package app.btcourier.core.store

import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name

/**
 * [FilePersistence]'s own promises, through the store: crash atomicity, cleanup, and the on-disk
 * format existing installs already have. Store behaviour on every adapter is [DefaultStoreTest]'s.
 */
class FilePersistenceTest {
    @TempDir
    lateinit var dir: Path

    private class Crash : RuntimeException()

    private suspend fun assertCrashes(block: suspend () -> Unit) {
        try {
            block()
        } catch (_: Crash) {
            return
        }
        fail<Unit>("expected the simulated crash")
    }

    private fun files(): List<String> =
        (dir.listDirectoryEntries() + dir.resolve("pages").listDirectoryEntries()).map { it.name }.sorted()

    @Test
    fun `a crash between writing and renaming the index loses the page and its cursor together`() = runTest {
        val h = StoreHarness(DbKind.FILE, dir)
        h.store.appendPage("dev", "c1", rows(1..2))
        val committed = files()

        var crash = true
        val s = h.reopen(beforeCommit = { if (crash) throw Crash() })
        assertCrashes { s.appendPage("dev", "c2", rows(3..4)) }
        assertTrue(Files.exists(dir.resolve("index.json.tmp")))

        crash = false
        val reopened = h.reopen()
        assertEquals(committed, files())
        assertEquals("c1", reopened.cursor("dev"))
        assertEquals(sizeOf(rows(1..2)), reopened.counters().bytesStored)
        // Not stored, so the resumed sync re-pulls it from c1 and it is accepted exactly once.
        assertTrue(reopened.appendPage("dev", "c2", rows(3..4)))
        reopened.seal(neverByAgeOrSize.copy(maxBatchAgeMs = 0), h.now)
        assertEquals(listOf(rows(1..4)), reopened.pendingRows("a"))
    }

    @Test
    fun `a crash while sealing leaves the pages unsealed and a retry seals them identically`() = runTest {
        val h = StoreHarness(DbKind.FILE, dir)
        h.store.appendPage("dev", "c1", listOf(marker("f1")) + rows(1..2))
        h.store.appendPage("dev", "c2", listOf(marker("f2")) + rows(3..4))

        val s = h.reopen(beforeCommit = { throw Crash() })
        assertCrashes { s.seal(neverByAgeOrSize, h.now) }

        val reopened = h.reopen()
        assertEquals(emptyList<SealedBatch>(), reopened.pending("a"))
        assertEquals(1, reopened.seal(neverByAgeOrSize, h.now).size)
        assertEquals(listOf(listOf(marker("f1")) + rows(1..2)), reopened.pending("a").map { it.lines })
    }

    @Test
    fun `a caller cancelled mid-commit leaves the open store agreeing with disk`() = runTest {
        val h = StoreHarness(DbKind.FILE, dir)
        var caller: Job? = null
        val s = h.reopen(beforeCommit = { caller?.cancel() })
        val cancelled = launch { s.appendPage("dev", "c1", rows(1..2)) }.also { caller = it }
        cancelled.join()
        caller = null
        assertTrue(cancelled.isCancelled)

        // The commit reached disk, so the open store must see it and build on it.
        assertEquals("c1", s.cursor("dev"))
        assertTrue(s.appendPage("dev", "c2", rows(3..4)))
        s.seal(neverByAgeOrSize.copy(maxBatchAgeMs = 0), h.now)

        val reopened = h.reopen()
        assertEquals("c2", s.cursor("dev"))
        assertEquals("c2", reopened.cursor("dev"))
        assertEquals(listOf(rows(1..4)), s.pendingRows("a"))
        assertEquals(listOf(rows(1..4)), reopened.pendingRows("a"))
        assertEquals(s.counters(), reopened.counters())
    }

    @Test
    fun `page files of deleted batches are removed`() = runTest {
        val h = StoreHarness(DbKind.FILE, dir)
        h.store.appendPage("dev", "c1", rows(1..2))
        val id = h.store.seal(neverByAgeOrSize.copy(maxBatchAgeMs = 0), h.now).single()
        h.store.ack(id, "a")
        assertEquals(listOf("index.json", "pages"), files())
    }

    @Test
    fun `index and page files keep the format existing installs have on disk`() = runTest {
        val h = StoreHarness(DbKind.FILE, dir, sinks = setOf("http", "local"))
        writeFormatScenario(h)

        assertEquals(formatIndex, Files.readAllBytes(dir.resolve("index.json")).decodeToString())
        assertEquals(formatPages.keys.map { "$it.json" } + "index.json" + "pages", files())
        for ((seq, content) in formatPages) {
            assertEquals(content, Files.readAllBytes(dir.resolve("pages/$seq.json")).decodeToString())
        }
    }

    @Test
    fun `a store already on disk in that format opens and carries on`() = runTest {
        Files.createDirectories(dir.resolve("pages"))
        Files.write(dir.resolve("index.json"), formatIndex.encodeToByteArray())
        for ((seq, content) in formatPages) Files.write(dir.resolve("pages/$seq.json"), content.encodeToByteArray())

        val s = StoreHarness(DbKind.FILE, dir, sinks = setOf("http", "local")).store

        assertEquals("1:400", s.cursor("dev"))
        assertEquals("f3", s.openFile("dev"))
        assertEquals("colmi/2024-01-01", s.openFile("ring"))
        assertEquals(StoreCounters(bytesStored = 185, pendingBatches = 1, droppedLines = 5, droppedBytes = 203), s.counters())
        assertEquals(emptyList<SealedBatch>(), s.pending("local"))
        val batch = s.pending("http").single()
        assertEquals(SealedBatch("2", "dev", "1:200", listOf(marker("f2")) + rows(5..5), sealedAtMs = 6_000), batch)
        assertEquals(listOf("3", "4"), s.seal(neverByAgeOrSize.copy(maxBatchAgeMs = 0), 7_000))
    }

    /** Touches every index field: split pages, a non-ASCII line, acks, eviction, an empty page. */
    private suspend fun writeFormatScenario(h: StoreHarness) {
        val s = h.store
        h.now = 1_000
        s.appendPage("dev", "1:100", listOf(marker("f1")) + rows(1..2))
        h.now = 2_000
        s.appendPage("dev", "1:200", rows(3..4) + marker("f2") + rows(5..5))
        s.appendPage("ring", "2024-01-01", listOf(marker("colmi/2024-01-01"), """{"ts":1,"param":"é","value":1}"""))
        val first = s.seal(neverByAgeOrSize, 5_000)
        s.ack(first.single(), "http")
        s.appendPage("dev", "1:300", emptyList())
        s.appendPage("dev", "1:400", listOf(marker("f3")) + rows(6..6))
        val second = s.seal(neverByAgeOrSize.copy(maxStoreBytes = 250), 6_000)
        s.ack(second.single(), "local")
    }

    // What existing installs have on disk; if this has to change, they can no longer read their cache.
    private val formatIndex =
        """{"sources":{"dev":{"sourceId":"dev","cursor":"1:400","fileMarker":"{\"_file\":\"f3\"}"},"ring":{"sourceId":"ring","cursor":"2024-01-01","fileMarker":"{\"_file\":\"colmi/2024-01-01\"}"}},"pages":[{"seq":3,"sourceId":"dev","cursor":"1:200","part":1,"lineCount":2,"bytes":62,"receivedAtMs":2000,"fileMarker":"{\"_file\":\"f2\"}","startsFile":true,"batchId":2},{"seq":4,"sourceId":"ring","cursor":"2024-01-01","part":0,"lineCount":2,"bytes":61,"receivedAtMs":2000,"fileMarker":"{\"_file\":\"colmi/2024-01-01\"}","startsFile":true},{"seq":5,"sourceId":"dev","cursor":"1:400","part":0,"lineCount":2,"bytes":62,"receivedAtMs":2000,"fileMarker":"{\"_file\":\"f3\"}","startsFile":true}],"batches":[{"id":2,"sourceId":"dev","firstCursor":"1:200","sealedAtMs":6000,"acks":["local"]}],"dropped":{"lines":5,"bytes":203},"nextPageSeq":6,"nextBatchId":3}"""

    private val formatPages = mapOf(
        3 to """["{\"_file\":\"f2\"}","{\"ts\":1700000000005,\"param\":\"SPEED\",\"value\":5}"]""",
        4 to """["{\"_file\":\"colmi/2024-01-01\"}","{\"ts\":1,\"param\":\"é\",\"value\":1}"]""",
        5 to """["{\"_file\":\"f3\"}","{\"ts\":1700000000006,\"param\":\"SPEED\",\"value\":6}"]""",
    )
}
