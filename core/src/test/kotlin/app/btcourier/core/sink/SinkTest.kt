package app.btcourier.core.sink

import app.btcourier.core.auth.OidcException
import app.btcourier.core.store.DbKind
import app.btcourier.core.store.SealPolicy
import app.btcourier.core.store.SealedBatch
import app.btcourier.core.store.StoreCounters
import app.btcourier.core.store.StoreHarness
import app.btcourier.core.store.marker
import app.btcourier.core.store.rows
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readBytes

class SinkTest {
    @TempDir
    lateinit var dir: Path

    private val lines = listOf(marker("1700000000.jsonl")) + rows(1..50)
    private fun batch(lines: List<String> = this.lines) = SealedBatch("7", "wican", "1700000000:0", lines, sealedAtMs = 0)

    private fun gunzip(bytes: ByteArray) = GZIPInputStream(bytes.inputStream()).readBytes()

    private fun sha256Hex(bytes: ByteArray) =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    @Test
    fun `jsonl joins lines with a newline after each`() {
        assertEquals("a\nb\n", Jsonl.encode(listOf("a", "b"), "wican").decodeToString())
        assertEquals("", Jsonl.encode(emptyList(), "wican").decodeToString())
        assertEquals("jsonl", Jsonl.extension)
    }

    @Test
    fun `identical batches encode to identical bytes and names`() {
        val a = encodeBatch(batch(), Jsonl)
        val b = encodeBatch(batch(lines.toList()), Jsonl)
        assertEquals(a, b)
        assertArrayEquals(Gzip.compress(Jsonl.encode(lines, "wican")), a.bytes)
    }

    @Test
    fun `gzip header has no file name and a zero mtime, and round-trips`() {
        val gz = encodeBatch(batch(), Jsonl).bytes
        assertEquals(0x1f, gz[0].toInt() and 0xff)
        assertEquals(0x8b, gz[1].toInt() and 0xff)
        assertEquals(0, gz[3].toInt(), "FLG: no FNAME, FEXTRA or FCOMMENT")
        assertArrayEquals(ByteArray(4), gz.copyOfRange(4, 8), "MTIME")
        assertArrayEquals(Jsonl.encode(lines, "wican"), gunzip(gz))
    }

    @Test
    fun `batch name is source, sanitised first cursor and content hash prefix`() {
        val name = encodeBatch(batch(), Jsonl).name
        val hash = sha256Hex(Jsonl.encode(lines, "wican")).take(16)
        assertEquals("wican-1700000000_0-$hash.jsonl.gz", name)
    }

    @Test
    fun `local directory transport writes the object atomically and idempotently`() = runTest {
        val transport = LocalDirectoryUploadTransport(dir)
        val b = encodeBatch(batch(), Jsonl)
        assertEquals(UploadResult.Acked, transport.upload(b))
        assertEquals(UploadResult.Acked, transport.upload(b))
        assertEquals(listOf(b.name), dir.listDirectoryEntries().map { it.name })
        assertArrayEquals(b.bytes, dir.resolve(b.name).readBytes())
    }

    @Test
    fun `local directory transport reports an unwritable target as retryable`() = runTest {
        val notADir = Files.writeString(dir.resolve("file"), "x")
        val result = LocalDirectoryUploadTransport(notADir).upload(encodeBatch(batch(), Jsonl))
        assertTrue(result is UploadResult.Retry, "$result")
    }

    @Test
    fun `two sinks each receive every batch and the store empties once both acked`() = runTest {
        val h = StoreHarness(DbKind.FILE, dir.resolve("store"), sinks = setOf("one", "two"))
        val s = h.store
        val one = CompositeSink("one", LocalDirectoryUploadTransport(dir.resolve("one")), Jsonl)
        val two = CompositeSink("two", LocalDirectoryUploadTransport(dir.resolve("two")), Jsonl)
        s.appendPage("wican", "1:0", listOf(marker("f1")) + rows(1..5))
        s.appendPage("wican", "2:0", listOf(marker("f2")) + rows(6..9))
        s.seal(SealPolicy(maxBatchAgeMs = 0), h.now)
        val expected = s.pending("one").map { encodeBatch(it, Jsonl) }
        assertEquals(2, expected.size)

        assertNull(deliverPending(s, one))
        assertEquals(2, s.counters().pendingBatches)
        assertNull(deliverPending(s, two))
        assertEquals(StoreCounters(0, 0, 0, 0), s.counters())

        for (sinkDir in listOf("one", "two")) {
            val written = dir.resolve(sinkDir).listDirectoryEntries().associate { it.name to it.readBytes() }
            assertEquals(expected.map { it.name }.toSet(), written.keys)
            expected.forEach { assertArrayEquals(it.bytes, written.getValue(it.name)) }
        }
    }

    @Test
    fun `delivery stops at the first failure and keeps the batch`() = runTest {
        val h = StoreHarness(DbKind.MEMORY, dir, sinks = setOf("flaky"))
        val s = h.store
        val flaky = CompositeSink("flaky", object : UploadTransport {
            override suspend fun upload(batch: Batch) = UploadResult.Retry("offline")
        }, Jsonl)
        s.appendPage("wican", "1:0", rows(1..3))
        s.seal(SealPolicy(maxBatchAgeMs = 0), h.now)

        assertEquals(UploadResult.Retry("offline"), deliverPending(s, flaky))
        assertEquals(1, s.pending("flaky").size)
    }

    @Test
    fun `a revoked sign-in is a permanent failure, not a crash or a retry`() = runTest {
        val signedOut = CompositeSink("oidc", object : UploadTransport {
            override suspend fun upload(batch: Batch): UploadResult =
                throw OidcException.ReauthorizationRequired("invalid_grant")
        }, Jsonl)

        val result = assertInstanceOf(UploadResult.Failed::class.java, signedOut.upload(batch()))
        assertTrue(result.reason.contains("sign in again"), result.reason)
    }
}
