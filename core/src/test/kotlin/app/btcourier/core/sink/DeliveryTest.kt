package app.btcourier.core.sink

import app.btcourier.core.store.DbKind
import app.btcourier.core.store.SealPolicy
import app.btcourier.core.store.StoreCounters
import app.btcourier.core.store.StoreHarness
import app.btcourier.core.store.marker
import app.btcourier.core.store.rows
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readBytes

/**
 * [Delivery] through its interface, over a real store. Expected values follow its contract:
 * batches go out oldest first and each sink stops at its first failure; a sink that answered
 * Retry is not contacted again before the server's `Retry-After` (capped at a day) or, without
 * one, 30 s doubling per consecutive retry up to an hour; Acked, Failed or [Delivery.forget]
 * starts over; all of it keyed by sink id, so rebuilding a sink keeps its schedule.
 */
class DeliveryTest {
    @TempDir
    lateinit var dir: Path

    private lateinit var h: StoreHarness
    private val store get() = h.store

    /** A store holding [count] sealed batches, one per device file, pending for [sinks]. */
    private suspend fun storeWith(count: Int, vararg sinks: String) {
        h = StoreHarness(DbKind.MEMORY, dir.resolve("store"), sinks = sinks.toSet())
        for (i in 1..count) store.appendPage("wican", "$i:0", listOf(marker("f$i")) + rows(i * 10 until i * 10 + 3))
        store.seal(SealPolicy(maxBatchAgeMs = 0), h.now)
    }

    private fun delivery() = Delivery(store, clock = { h.now })

    /** Records the ids of the batches it was sent, in order, and answers with [answer]. */
    private class Recording(private val answer: suspend (Batch) -> UploadResult) : UploadTransport {
        val sent = mutableListOf<String>()

        override suspend fun upload(batch: Batch): UploadResult = answer(batch).also { sent += batch.id }
    }

    /** Answers uploads with [results] in turn, repeating the last. */
    private fun scripted(vararg results: UploadResult): Recording {
        var next = 0
        return Recording { results[minOf(next++, results.lastIndex)] }
    }

    private fun sink(id: String, transport: UploadTransport) = CompositeSink(id, transport, Jsonl)

    private suspend fun pendingIds(sinkId: String) = store.pending(sinkId).map { it.id }

    /** How long [sink] must still wait, asserting the round reported Deferred and sent nothing. */
    private suspend fun Delivery.waitLeft(sink: Sink, transport: Recording): Long {
        val before = transport.sent.size
        val result = assertInstanceOf(DeliveryResult.Deferred::class.java, deliverPending(sink))
        assertEquals(before, transport.sent.size, "a waiting sink must not be contacted")
        return result.dueAtMs - h.now
    }

    @Test
    fun `every sink receives every pending batch oldest first, and the store empties once all acked`() = runTest {
        storeWith(3, "one", "two")
        val expected = store.pending("one").map { encodeBatch(it, Jsonl) }
        val order = pendingIds("one")
        val delivery = delivery()

        for (id in listOf("one", "two")) {
            val local = Recording(LocalDirectoryUploadTransport(dir.resolve(id))::upload)
            assertEquals(DeliveryResult.Delivered, delivery.deliverPending(sink(id, local)))
            assertEquals(order, local.sent)
        }

        assertEquals(StoreCounters(0, 0, 0, 0), store.counters())
        for (id in listOf("one", "two")) {
            val written = dir.resolve(id).listDirectoryEntries().associate { it.name to it.readBytes() }
            assertEquals(expected.map { it.name }.toSet(), written.keys)
            expected.forEach { assertArrayEquals(it.bytes, written.getValue(it.name)) }
        }
    }

    @Test
    fun `a sink stops at its first failure, keeping that batch and the later ones, without holding back others`() = runTest {
        storeWith(3, "retrying", "refused", "ok")
        val order = pendingIds("ok")
        val retrying = scripted(UploadResult.Acked, UploadResult.Retry("offline"))
        val refused = scripted(UploadResult.Acked, UploadResult.Failed("HTTP 403"))
        val ok = scripted(UploadResult.Acked)
        val delivery = delivery()

        assertEquals(DeliveryResult.Stopped(UploadResult.Retry("offline")), delivery.deliverPending(sink("retrying", retrying)))
        assertEquals(DeliveryResult.Stopped(UploadResult.Failed("HTTP 403")), delivery.deliverPending(sink("refused", refused)))
        assertEquals(DeliveryResult.Delivered, delivery.deliverPending(sink("ok", ok)))

        assertEquals(order.take(2), retrying.sent)
        assertEquals(order.drop(1), pendingIds("retrying"))
        assertEquals(order.take(2), refused.sent)
        assertEquals(order.drop(1), pendingIds("refused"))
        assertEquals(order, ok.sent)
    }

    @Test
    fun `Retry-After is honoured across rounds and sink rebuilds, per sink id`() = runTest {
        storeWith(2, "throttled", "other")
        val delivery = delivery()
        val first = scripted(UploadResult.Retry("HTTP 429", retryAfterMs = 120_000))
        delivery.deliverPending(sink("throttled", first))

        h.now += 119_999
        val rebuilt = scripted(UploadResult.Acked)
        val waiting = assertInstanceOf(DeliveryResult.Deferred::class.java, delivery.deliverPending(sink("throttled", rebuilt)))
        assertEquals(DeliveryResult.Deferred("HTTP 429", h.now + 1), waiting)
        assertEquals(emptyList<String>(), rebuilt.sent, "a rebuilt sink with the same id keeps the schedule")
        assertEquals(DeliveryResult.Delivered, delivery.deliverPending(sink("other", scripted(UploadResult.Acked))), "another sink is not held back")

        h.now += 1
        assertEquals(DeliveryResult.Delivered, delivery.deliverPending(sink("throttled", rebuilt)))
        assertEquals(2, rebuilt.sent.size)
    }

    @Test
    fun `a far-future Retry-After is capped at a day`() = runTest {
        storeWith(1, "a")
        val delivery = delivery()
        val down = scripted(UploadResult.Retry("HTTP 503", retryAfterMs = 7 * 24 * 3_600_000L))
        delivery.deliverPending(sink("a", down))

        assertEquals(24 * 3_600_000L, delivery.waitLeft(sink("a", down), down))
    }

    @Test
    fun `without a hint the wait starts at 30 s and doubles per consecutive retry, up to an hour`() = runTest {
        storeWith(1, "a")
        val delivery = delivery()
        val down = scripted(UploadResult.Retry("connection refused"))
        val a = sink("a", down)

        val waits = (1..10).map {
            assertInstanceOf(DeliveryResult.Stopped::class.java, delivery.deliverPending(a))
            val wait = delivery.waitLeft(a, down)
            h.now += wait - 1
            assertEquals(1, delivery.waitLeft(a, down))
            h.now += 1
            wait
        }

        assertEquals(listOf(30, 60, 120, 240, 480, 960, 1920, 3600, 3600, 3600).map { it * 1000L }, waits)
        assertEquals(10, down.sent.size, "one attempt per due round, none while waiting")
    }

    @Test
    fun `an ack resets the back-off`() = runTest {
        storeWith(2, "a")
        val delivery = delivery()
        val flaky = scripted(UploadResult.Retry("x"), UploadResult.Retry("x"), UploadResult.Acked, UploadResult.Retry("x"))
        val a = sink("a", flaky)
        repeat(2) {
            delivery.deliverPending(a)
            h.now += delivery.waitLeft(a, flaky)
        }

        assertEquals(DeliveryResult.Stopped(UploadResult.Retry("x")), delivery.deliverPending(a), "first batch acked, second retried")
        assertEquals(30_000, delivery.waitLeft(a, flaky))
    }

    @Test
    fun `a permanent failure clears the schedule`() = runTest {
        storeWith(1, "a")
        val delivery = delivery()
        val script = scripted(UploadResult.Retry("x"), UploadResult.Failed("HTTP 401"), UploadResult.Acked)
        val a = sink("a", script)
        delivery.deliverPending(a)
        h.now += delivery.waitLeft(a, script)

        assertEquals(DeliveryResult.Stopped(UploadResult.Failed("HTTP 401")), delivery.deliverPending(a))
        assertEquals(DeliveryResult.Delivered, delivery.deliverPending(a), "after a config fix the sink is tried at once")
    }

    @Test
    fun `forgetting a sink makes it due at once with a fresh streak, leaving other sinks' schedules`() = runTest {
        storeWith(1, "edited", "other")
        val delivery = delivery()
        val down = scripted(UploadResult.Retry("connection refused"))
        val otherDown = scripted(UploadResult.Retry("offline", retryAfterMs = 600_000))
        repeat(3) {
            delivery.deliverPending(sink("edited", down))
            h.now += delivery.waitLeft(sink("edited", down), down)
        }
        delivery.deliverPending(sink("edited", down))
        delivery.deliverPending(sink("other", otherDown))

        delivery.forget("edited")

        val fixed = scripted(UploadResult.Retry("still down"))
        assertEquals(DeliveryResult.Stopped(UploadResult.Retry("still down")), delivery.deliverPending(sink("edited", fixed)), "contacted without waiting")
        assertEquals(30_000, delivery.waitLeft(sink("edited", fixed), fixed), "the streak starts over")
        assertEquals(600_000, delivery.waitLeft(sink("other", otherDown), otherDown))
    }

    @Test
    fun `a waiting sink with nothing pending has nothing to report`() = runTest {
        storeWith(1, "a")
        val delivery = delivery()
        val batch = store.pending("a").single()
        delivery.deliverPending(sink("a", scripted(UploadResult.Retry("x"))))
        store.ack(batch.id, "a")

        assertEquals(DeliveryResult.Delivered, delivery.deliverPending(sink("a", scripted(UploadResult.Acked))))
    }
}
