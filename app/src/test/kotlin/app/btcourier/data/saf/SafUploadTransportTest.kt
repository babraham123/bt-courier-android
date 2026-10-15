package app.btcourier.data.saf

import app.btcourier.core.sink.Batch
import app.btcourier.core.sink.UploadResult
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SafUploadTransportTest {
    private val tree = FakeDocumentTree()
    private val transport = SafUploadTransport(tree)

    private val name = "wican-1700000000_0-0123456789abcdef.jsonl.gz"
    private val batch = Batch("7", ByteArray(300) { it.toByte() }, name)
    private val part = "$name.part"

    private fun assertOnlyObject(expected: Batch = batch) {
        assertEquals(setOf(expected.name), tree.files.keys)
        assertArrayEquals(expected.bytes, tree.files.getValue(expected.name))
    }

    @Test
    fun `a batch is written as a part file, then renamed to its name with the gzip mime type`() = runTest {
        assertEquals(UploadResult.Acked, transport.upload(batch))

        assertEquals(listOf("create $part", "write $part", "rename $part -> $name"), tree.ops)
        assertOnlyObject()
        assertEquals("application/gzip", tree.mimeTypeOf(name))
    }

    @Test
    fun `re-uploading a batch already stored with the same size acks without rewriting`() = runTest {
        transport.upload(batch)
        tree.ops.clear()

        assertEquals(UploadResult.Acked, transport.upload(batch))

        assertEquals(emptyList<String>(), tree.ops)
        assertOnlyObject()
    }

    @Test
    fun `an existing object of a different size is replaced`() = runTest {
        tree.put(name, ByteArray(10))

        assertEquals(UploadResult.Acked, transport.upload(batch))

        assertEquals(listOf("create $part", "write $part", "delete $name", "rename $part -> $name"), tree.ops)
        assertOnlyObject()
    }

    @Test
    fun `a part file left by a crash is overwritten and renamed`() = runTest {
        tree.put(part, ByteArray(1000) { 1 })

        assertEquals(UploadResult.Acked, transport.upload(batch))

        assertEquals(listOf("write $part", "rename $part -> $name"), tree.ops)
        assertOnlyObject()
    }

    @Test
    fun `a write failure is retryable and the retry leaves one complete object`() = runTest {
        tree.failNextWrite = true

        val failed = transport.upload(batch)
        assertTrue(failed is UploadResult.Retry, "$failed")
        assertEquals(setOf(part), tree.files.keys, "only the part file, never a partial object")

        assertEquals(UploadResult.Acked, transport.upload(batch))
        assertOnlyObject()
    }

    @Test
    fun `a revoked grant fails permanently, whether noticed up front or mid-upload`() = runTest {
        val lost = UploadResult.Failed("folder access lost; pick it again")

        tree.revoked = true
        assertEquals(lost, transport.upload(batch))

        // The grant passes the up-front check, then the provider rejects the write.
        val midway = object : DocumentTree by tree {
            override fun exists() = true
            override fun isWritable() = true
        }
        assertEquals(lost, SafUploadTransport(midway).upload(batch))

        assertEquals(emptyList<String>(), tree.ops)
    }

    @Test
    fun `a missing or read-only folder fails permanently without touching it`() = runTest {
        val lost = UploadResult.Failed("folder access lost; pick it again")

        tree.present = false
        assertEquals(lost, transport.upload(batch))
        tree.present = true
        tree.writable = false
        assertEquals(lost, transport.upload(batch))

        assertEquals(emptyList<String>(), tree.ops)
    }

    @Test
    fun `a round of several batches lists the folder once, however many files it holds`() = runTest {
        repeat(50) { tree.put("older-$it.jsonl.gz", ByteArray(it)) }
        val batches = (1..5).map { Batch("$it", ByteArray(100 + it), "wican-$it.jsonl.gz") }

        batches.forEach { assertEquals(UploadResult.Acked, transport.upload(it)) }
        // Each name is looked up again, now against what the round itself wrote.
        batches.forEach { assertEquals(UploadResult.Acked, transport.upload(it)) }

        assertEquals(1, tree.listings)
        batches.forEach { assertArrayEquals(it.bytes, tree.files.getValue(it.name)) }
        assertEquals(15, tree.ops.size, "create, write and rename per batch; the repeats are no-ops")
    }

    @Test
    fun `a name clash the index did not predict is retried against a fresh listing`() = runTest {
        val other = Batch("8", ByteArray(50) { 2 }, "wican-1700000001_0-fedcba9876543210.jsonl.gz")
        assertEquals(UploadResult.Acked, transport.upload(batch))
        // Another app drops a part file into the folder after the round listed it.
        tree.put("${other.name}.part", ByteArray(5))

        val clash = transport.upload(other)
        assertTrue(clash is UploadResult.Retry, "$clash")

        assertEquals(UploadResult.Acked, transport.upload(other))
        assertEquals(2, tree.listings)
        assertEquals(setOf(batch.name, other.name), tree.files.keys)
        assertArrayEquals(other.bytes, tree.files.getValue(other.name))
    }

    @Test
    fun `only gzip names get the gzip mime type`() {
        assertEquals("application/gzip", mimeTypeFor("a.jsonl.gz"))
        assertEquals("application/octet-stream", mimeTypeFor("a.jsonl"))
    }
}
