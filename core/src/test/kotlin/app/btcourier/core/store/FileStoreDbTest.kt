package app.btcourier.core.store

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

class FileStoreDbTest {
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
    fun `cursor, pages, batches and acks survive a reopen`() = runTest {
        val h = StoreHarness(DbKind.FILE, dir, sinks = setOf("http", "local"))
        h.store.appendPage("dev", "1:100", listOf(marker("f1")) + rows(1..3))
        h.store.appendPage("dev", "1:200", rows(4..5))
        val id = h.store.seal(neverByAgeOrSize.copy(maxBatchAgeMs = 0), h.now).single()
        h.store.ack(id, "http")
        h.store.appendPage("dev", "2:0", listOf(marker("f2")) + rows(6..6))
        val before = h.store.counters()

        val s = h.reopen()

        assertEquals("2:0", s.cursor("dev"))
        assertFalse(s.appendPage("dev", "2:0", listOf(marker("f2")) + rows(6..6)))
        assertEquals(before, s.counters())
        assertEquals(emptyList<SealedBatch>(), s.pending("http"))
        assertEquals(listOf(listOf(marker("f1")) + rows(1..5)), s.pending("local").map { it.lines })
    }

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
    fun `page files of deleted batches are removed`() = runTest {
        val h = StoreHarness(DbKind.FILE, dir)
        h.store.appendPage("dev", "c1", rows(1..2))
        val id = h.store.seal(neverByAgeOrSize.copy(maxBatchAgeMs = 0), h.now).single()
        h.store.ack(id, "a")
        assertEquals(listOf("index.json", "pages"), files())
    }
}
