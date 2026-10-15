package app.btcourier.core.sink

import app.btcourier.core.auth.OidcException
import app.btcourier.core.store.SealedBatch
import app.btcourier.core.store.marker
import app.btcourier.core.store.rows
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
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
    fun `a revoked sign-in is a permanent failure, not a crash or a retry`() = runTest {
        val signedOut = CompositeSink("oidc", object : UploadTransport {
            override suspend fun upload(batch: Batch): UploadResult =
                throw OidcException.ReauthorizationRequired("invalid_grant")
        }, Jsonl)

        val result = assertInstanceOf(UploadResult.Failed::class.java, signedOut.upload(batch()))
        assertTrue(result.reason.contains("sign in again"), result.reason)
    }
}
