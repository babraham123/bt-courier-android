package app.btcourier.core.store

import app.btcourier.core.lines.StoredLine
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.nio.file.Path

class DefaultStoreTest {
    @TempDir
    lateinit var dir: Path

    private fun harness(
        kind: DbKind,
        sinks: Set<String> = setOf("a"),
    ) = StoreHarness(kind, dir, sinks)

    @ParameterizedTest
    @EnumSource(DbKind::class)
    fun `appending the same cursor twice stores the page once`(kind: DbKind) =
        runTest {
            val s = harness(kind).store
            assertTrue(s.appendPage("dev", "1:100", rows(1..3)))
            assertFalse(s.appendPage("dev", "1:100", rows(1..3)))
            assertEquals("1:100", s.cursor("dev"))
            assertEquals(sizeOf(rows(1..3)), s.counters().bytesStored)
        }

    @ParameterizedTest
    @EnumSource(DbKind::class)
    fun `cursors are per source`(kind: DbKind) =
        runTest {
            val s = harness(kind).store
            assertNull(s.cursor("dev"))
            assertTrue(s.appendPage("dev", "c1", rows(1..1)))
            assertTrue(s.appendPage("ring", "c1", rows(2..2)))
            assertEquals("c1", s.cursor("ring"))
        }

    @ParameterizedTest
    @EnumSource(DbKind::class)
    fun `re-pulling the latest page after it was uploaded and deleted is still a duplicate`(kind: DbKind) =
        runTest {
            val h = harness(kind)
            val s = h.store
            s.appendPage("dev", "c1", listOf(marker("f1")) + rows(1..2))
            s.seal(neverByAgeOrSize.copy(maxBatchAgeMs = 0), h.now)
            s.ack(s.pending("a").single().id, "a")
            assertEquals(0, s.counters().pendingBatches)

            assertFalse(s.appendPage("dev", "c1", listOf(marker("f1")) + rows(1..2)))
            assertEquals(0L, s.counters().bytesStored)
        }

    @ParameterizedTest
    @EnumSource(DbKind::class)
    fun `an empty page advances the cursor without storing anything`(kind: DbKind) =
        runTest {
            val s = harness(kind).store
            assertTrue(s.appendPage("dev", "c1", emptyList()))
            assertEquals("c1", s.cursor("dev"))
            assertEquals(0L, s.counters().bytesStored)
        }

    @ParameterizedTest
    @EnumSource(DbKind::class)
    fun `a new file marker closes the open batch`(kind: DbKind) =
        runTest {
            val h = harness(kind)
            val s = h.store
            s.appendPage("dev", "c1", listOf(marker("f1")) + rows(1..2))
            s.appendPage("dev", "c2", rows(3..4))
            s.appendPage("dev", "c3", listOf(marker("f2")) + rows(5..6))

            val sealed = s.seal(neverByAgeOrSize, h.now)

            assertEquals(1, sealed.size)
            val batch = s.pending("a").single()
            assertEquals(listOf(marker("f1")) + rows(1..4), batch.lines)
            assertEquals("c1", batch.firstCursor)
            // f2 is the open batch and stays unsealed until another rule fires.
            assertEquals(sizeOf(listOf(marker("f1")) + rows(1..6) + marker("f2")), s.counters().bytesStored)
        }

    @ParameterizedTest
    @EnumSource(DbKind::class)
    fun `a marker in the middle of a page splits the page between batches`(kind: DbKind) =
        runTest {
            val h = harness(kind)
            val s = h.store
            s.appendPage("dev", "c1", listOf(marker("f1")) + rows(1..2) + marker("f2") + rows(3..4))
            s.appendPage("dev", "c2", listOf(marker("f3")) + rows(5..5))

            s.seal(neverByAgeOrSize, h.now)

            assertEquals(
                listOf(listOf(marker("f1")) + rows(1..2), listOf(marker("f2")) + rows(3..4)),
                s.pending("a").map { it.lines },
            )
        }

    @ParameterizedTest
    @EnumSource(DbKind::class)
    fun `a marker repeating the current file does not split`(kind: DbKind) =
        runTest {
            val h = harness(kind)
            val s = h.store
            s.appendPage("dev", "c1", listOf(marker("f1")) + rows(1..2))
            s.appendPage("dev", "c2", listOf(marker("f1")) + rows(3..4))

            assertEquals(emptyList<String>(), s.seal(neverByAgeOrSize, h.now))
            s.seal(neverByAgeOrSize.copy(maxBatchAgeMs = 0), h.now)
            assertEquals(
                listOf(listOf(marker("f1")) + rows(1..2) + marker("f1") + rows(3..4)),
                s.pending("a").map { it.lines },
            )
        }

    @ParameterizedTest
    @EnumSource(DbKind::class)
    fun `batches split where the shared classifier sees a marker and nowhere else`(kind: DbKind) =
        runTest {
            val h = harness(kind)
            val s = h.store
            // Spaced marker: a marker. A row that also carries _file: a row, so it neither splits nor
            // stands in for the marker a batch restates.
            val spaced = """ { "_file" : "f2" } """
            val rowWithFile = """{"_file":"f9","ts":1700000000005,"param":"SPEED","value":5}"""
            s.appendPage("dev", "c1", listOf(marker("f1")) + rows(1..2) + spaced + rows(3..4))
            s.appendPage("dev", "c2", listOf(rowWithFile) + rows(6..6) + marker("f3"))

            s.seal(neverByAgeOrSize, h.now)

            assertEquals(
                listOf(listOf(marker("f1")) + rows(1..2), listOf(spaced) + rows(3..4) + rowWithFile + rows(6..6)),
                s.pending("a").map { it.lines },
            )
        }

    @ParameterizedTest
    @EnumSource(DbKind::class)
    fun `file boundaries are ignored when the policy says so`(kind: DbKind) =
        runTest {
            val h = harness(kind)
            val s = h.store
            s.appendPage("dev", "c1", listOf(marker("f1")) + rows(1..1) + marker("f2") + rows(2..2))
            assertEquals(emptyList<String>(), s.seal(neverByAgeOrSize.copy(onFileBoundary = false), h.now))
        }

    @ParameterizedTest
    @EnumSource(DbKind::class)
    fun `a batch is sealed once it reaches the byte limit`(kind: DbKind) =
        runTest {
            val h = harness(kind)
            val s = h.store
            val page = rows(1..10)
            val policy = neverByAgeOrSize.copy(maxBatchBytes = 2 * sizeOf(page))
            s.appendPage("dev", "c1", page)
            assertEquals(emptyList<String>(), s.seal(policy, h.now))

            s.appendPage("dev", "c2", rows(11..20))
            s.appendPage("dev", "c3", rows(21..30))
            assertEquals(1, s.seal(policy, h.now).size)

            assertEquals(listOf(rows(1..20)), s.pending("a").map { it.lines })
        }

    @ParameterizedTest
    @EnumSource(DbKind::class)
    fun `a batch cut mid-file restates the file marker`(kind: DbKind) =
        runTest {
            val h = harness(kind)
            val s = h.store
            val first = listOf(marker("f1")) + rows(1..10)
            s.appendPage("dev", "c1", first)
            s.appendPage("dev", "c2", rows(11..20))
            s.seal(neverByAgeOrSize.copy(maxBatchBytes = sizeOf(first), maxBatchAgeMs = 0), h.now)

            val batches = s.pending("a")
            assertEquals(listOf(first, listOf(marker("f1")) + rows(11..20)), batches.map { it.lines })
            // The restated marker is not stored data, so it costs nothing against the cap.
            assertEquals(sizeOf(first + rows(11..20)), s.counters().bytesStored)
        }

    @ParameterizedTest
    @EnumSource(DbKind::class)
    fun `the open batch is sealed once its oldest page reaches the age limit`(kind: DbKind) =
        runTest {
            val h = harness(kind)
            val s = h.store
            val policy = SealPolicy(maxBatchAgeMs = 30 * 60 * 1000)
            val t0 = h.now
            s.appendPage("dev", "c1", rows(1..2))
            h.now = t0 + 10 * 60 * 1000
            s.appendPage("dev", "c2", rows(3..4))

            assertEquals(emptyList<String>(), s.seal(policy, t0 + policy.maxBatchAgeMs - 1))
            assertEquals(1, s.seal(policy, t0 + policy.maxBatchAgeMs).size)
            assertEquals(listOf(rows(1..4)), s.pending("a").map { it.lines })
        }

    @ParameterizedTest
    @EnumSource(DbKind::class)
    fun `with age 0 every seal closes whatever is open`(kind: DbKind) =
        runTest {
            val h = harness(kind)
            val s = h.store
            s.appendPage("dev", "c1", listOf(marker("f1")) + rows(1..2))

            assertEquals(
                1,
                s.seal(neverByAgeOrSize.copy(maxBatchAgeMs = 0), h.now).size,
                "sealed the moment it is stored",
            )
            assertEquals(listOf(rows(1..2)), s.pendingRows("a"))
            assertEquals(
                emptyList<String>(),
                s.seal(neverByAgeOrSize.copy(maxBatchAgeMs = 0), h.now),
                "nothing left open",
            )
        }

    @ParameterizedTest
    @EnumSource(DbKind::class)
    fun `the oldest unsealed line's arrival is counted until it is sealed`(kind: DbKind) =
        runTest {
            val h = harness(kind)
            val s = h.store
            assertNull(s.counters().oldestUnsealedAtMs, "empty store")

            val t0 = h.now
            s.appendPage("dev", "c1", listOf(marker("f1")) + rows(1..2))
            h.now = t0 + 60_000
            s.appendPage("ring", "r1", rows(3..4))
            assertEquals(t0, s.counters().oldestUnsealedAtMs, "the oldest across sources")

            h.now = t0 + 120_000
            s.appendPage("dev", "c2", listOf(marker("f2")) + rows(5..6))
            s.seal(neverByAgeOrSize, h.now)
            assertEquals(t0 + 60_000, s.counters().oldestUnsealedAtMs, "f1 sealed at the f2 boundary")

            s.seal(neverByAgeOrSize.copy(maxBatchAgeMs = 0), h.now)
            assertNull(s.counters().oldestUnsealedAtMs, "everything sealed")
        }

    @ParameterizedTest
    @EnumSource(DbKind::class)
    fun `a batch stays until every configured sink acked it`(kind: DbKind) =
        runTest {
            val h = harness(kind, sinks = setOf("http", "local"))
            val s = h.store
            s.appendPage("dev", "c1", rows(1..3))
            val id = s.seal(neverByAgeOrSize.copy(maxBatchAgeMs = 0), h.now).single()

            s.ack(id, "http")
            assertEquals(emptyList<SealedBatch>(), s.pending("http"))
            assertEquals(listOf(id), s.pending("local").map { it.id })
            assertEquals(1, s.counters().pendingBatches)

            s.ack(id, "local")
            assertEquals(StoreCounters(0, 0, 0, 0, null), s.counters())
        }

    @ParameterizedTest
    @EnumSource(DbKind::class)
    fun `removing a sink releases batches only it was holding`(kind: DbKind) =
        runTest {
            val h = harness(kind, sinks = setOf("http", "local"))
            val s = h.store
            s.appendPage("dev", "c1", rows(1..3))
            val id = s.seal(neverByAgeOrSize.copy(maxBatchAgeMs = 0), h.now).single()
            s.ack(id, "http")

            h.sinks = setOf("http")
            s.seal(neverByAgeOrSize, h.now)
            assertEquals(0, s.counters().pendingBatches)
        }

    @ParameterizedTest
    @EnumSource(DbKind::class)
    fun `without configured sinks nothing is deleted`(kind: DbKind) =
        runTest {
            val h = harness(kind, sinks = emptySet())
            val s = h.store
            s.appendPage("dev", "c1", rows(1..3))
            val id = s.seal(neverByAgeOrSize.copy(maxBatchAgeMs = 0), h.now).single()
            s.ack(id, "stray")
            assertEquals(1, s.counters().pendingBatches)
        }

    @ParameterizedTest
    @EnumSource(DbKind::class)
    fun `over the cap the oldest sealed batches go first, then the oldest unsealed pages`(kind: DbKind) =
        runTest {
            val h = harness(kind, sinks = setOf("http", "local"))
            val s = h.store
            val f1 = listOf(marker("f1")) + rows(1..10)
            val f2 = listOf(marker("f2")) + rows(11..20)
            val f3 = listOf(marker("f3")) + rows(21..30)
            val open1 = rows(31..40)
            val open2 = rows(41..50)
            s.appendPage("dev", "c1", f1)
            s.appendPage("dev", "c2", f2)
            s.appendPage("dev", "c3", f3)
            s.appendPage("dev", "c4", open1)
            s.appendPage("ring", "r1", open2)
            val (b1, _) = s.seal(neverByAgeOrSize, h.now)
            s.ack(b1, "http") // acked by some sinks still counts as evictable
            val total = sizeOf(f1 + f2 + f3 + open1 + open2)
            assertEquals(total, s.counters().bytesStored)

            // Room for exactly open1 and open2: f1 and f2 go, then the oldest unsealed page (f3).
            val cap = sizeOf(open1 + open2)
            s.seal(neverByAgeOrSize.copy(maxStoreBytes = cap), h.now)

            val evicted = f1 + f2 + f3
            assertEquals(
                StoreCounters(total - sizeOf(evicted), 0, evicted.size.toLong(), sizeOf(evicted), h.now),
                s.counters(),
            )
            assertEquals(emptyList<SealedBatch>(), s.pending("local"))
            // Eviction loses data on purpose; cursors never rewind to re-pull it.
            assertEquals("c4", s.cursor("dev"))
        }

    @ParameterizedTest
    @EnumSource(DbKind::class)
    fun `every row lands in exactly one batch despite duplicate pages`(kind: DbKind) =
        runTest {
            val h = harness(kind)
            val s = h.store
            val policy = SealPolicy(maxBatchBytes = sizeOf(rows(1..25)), maxBatchAgeMs = 60_000)
            val expected = mutableListOf<String>()
            for (p in 0 until 40) {
                val lines = (if (p % 7 == 0) listOf(marker("f$p")) else emptyList()) + rows(p * 10 until p * 10 + 10)
                assertTrue(s.appendPage("dev", "c$p", lines))
                expected += lines.filterNot(StoredLine::isMarker)
                // A link drop makes the source resend the page it last served.
                assertFalse(s.appendPage("dev", "c$p", lines))
                h.now += 5_000
                if (p % 3 == 0) s.seal(policy, h.now)
            }
            s.seal(policy, h.now + 60_000)

            assertEquals(expected, s.pendingRows("a").flatten())
        }

    @ParameterizedTest
    @EnumSource(DbKind::class)
    fun `the open file follows the latest stored marker and outlives its pages`(kind: DbKind) =
        runTest {
            val h = harness(kind)
            val s = h.store
            assertNull(s.openFile("dev"))
            s.appendPage("dev", "c1", rows(1..1))
            assertNull(s.openFile("dev"))

            s.appendPage("dev", "c2", listOf(marker("f1")) + rows(2..2) + marker("f2") + rows(3..3))
            s.appendPage("dev", "c3", rows(4..4))
            s.appendPage("ring", "r1", listOf(marker("colmi/day")))
            assertEquals("f2", s.openFile("dev"))
            assertEquals("colmi/day", s.openFile("ring"))

            // Uploaded and deleted, or evicted: the next pull still resumes in f2.
            s.seal(neverByAgeOrSize.copy(maxBatchAgeMs = 0), h.now)
            s.pending("a").forEach { s.ack(it.id, "a") }
            assertEquals(0L, s.counters().bytesStored)
            assertEquals("f2", h.reopen().openFile("dev"))
        }

    @ParameterizedTest
    @EnumSource(DbKind::class)
    fun `cursor, pages, batches, acks and counters survive a reopen`(kind: DbKind) =
        runTest {
            val h = harness(kind, sinks = setOf("http", "local"))
            val f0 = listOf(marker("f0")) + rows(0..0)
            val f1 = listOf(marker("f1")) + rows(1..5)
            val f2 = listOf(marker("f2")) + rows(6..6)
            h.store.appendPage("dev", "0:0", f0)
            h.store.appendPage("dev", "1:100", f1.take(4))
            h.store.appendPage("dev", "1:200", f1.drop(4))
            h.store.appendPage("dev", "2:0", f2)
            val (b0, b1) = h.store.seal(neverByAgeOrSize, h.now)
            h.store.ack(b1, "http")
            h.store.seal(neverByAgeOrSize.copy(maxStoreBytes = sizeOf(f1 + f2)), h.now)
            val before = h.store.counters()
            assertEquals(StoreCounters(sizeOf(f1 + f2), 1, f0.size.toLong(), sizeOf(f0), h.now), before)

            val s = h.reopen()

            assertEquals("2:0", s.cursor("dev"))
            assertFalse(s.appendPage("dev", "2:0", f2))
            assertEquals(before, s.counters())
            assertEquals(emptyList<SealedBatch>(), s.pending("http"))
            assertEquals(listOf(f1), s.pending("local").map { it.lines })
            // Batch ids are never reused, even for the evicted batch.
            val b2 = s.seal(neverByAgeOrSize.copy(maxBatchAgeMs = 0), h.now).single()
            assertTrue(b2 != b0 && b2 != b1)
            assertEquals(listOf(f1, f2), s.pending("local").map { it.lines })
        }
}
