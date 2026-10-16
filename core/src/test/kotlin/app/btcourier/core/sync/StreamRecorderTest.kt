package app.btcourier.core.sync

import app.btcourier.core.store.DefaultStore
import app.btcourier.core.store.SealPolicy
import app.btcourier.core.store.pendingRows
import app.btcourier.core.testing.WicanLines.fileMarker
import app.btcourier.core.testing.WicanLines.row
import app.btcourier.core.testing.WicanLines.storedRow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * [StreamRecorder] against the store: per its contract, each emission with rows is one page under
 * `session:<start>:<n>`, the session's first page opens the file `<kind>/<start>`, rows are
 * normalised as a pull without journal or anchor would normalise them, and emissions without rows
 * store nothing.
 */
class StreamRecorderTest {
    private val sourceId = "elm327:AA:BB:CC:DD:EE:01"
    private val start = 1_790_000_000_000L
    private var now = start
    private val store = DefaultStore.inMemory(clock = { now }, sinkIds = { setOf("sink") })
    private val sealEverything = SealPolicy(maxBatchAgeMs = 0)

    private fun recorder() = StreamRecorder(sourceId, store, clock = { now })

    @Test
    fun `the first emission opens the session's file, and each one is a page numbered from 1`() =
        runTest {
            val recorder = recorder()

            assertEquals(2, recorder.record(listOf(row(start + 100), row(start + 200))))
            assertEquals("session:$start:1", store.cursor(sourceId))
            assertEquals(1, recorder.record(listOf(row(start + 2_100))))
            assertEquals("session:$start:2", store.cursor(sourceId))

            assertEquals("elm327/$start", recorder.file)
            assertEquals("elm327/$start", store.openFile(sourceId))
            store.seal(sealEverything, now)
            val batch = store.pending("sink").single()
            assertEquals(fileMarker("elm327/$start"), batch.lines.first())
            assertEquals("session:$start:1", batch.firstCursor)
            assertEquals(
                listOf(
                    storedRow(start + 100, start + 100, "device"),
                    storedRow(start + 200, start + 200, "device"),
                    storedRow(start + 2_100, start + 2_100, "device"),
                ),
                store.pendingRows("sink").flatten(),
            )
        }

    @Test
    fun `an emission without rows stores nothing and uses no page number`() =
        runTest {
            val recorder = recorder()

            assertEquals(0, recorder.record(emptyList()))
            assertEquals(0, recorder.record(listOf("", "  ")))
            assertNull(store.cursor(sourceId))

            assertEquals(1, recorder.record(listOf(row(start + 100))))
            assertEquals("session:$start:1", store.cursor(sourceId))
            store.seal(sealEverything, now)
            assertEquals(
                fileMarker("elm327/$start"),
                store
                    .pending("sink")
                    .single()
                    .lines
                    .first(),
            )
        }

    @Test
    fun `without a journal or anchor an implausible stamp is stored unknown, as a pull would store it`() =
        runTest {
            recorder().record(listOf(row(5_000)))

            store.seal(sealEverything, now)
            assertEquals(listOf(storedRow(5_000, null, "unknown")), store.pendingRows("sink").flatten())
        }

    @Test
    fun `a later session opens a file of its own, so its rows seal into a batch of their own`() =
        runTest {
            recorder().record(listOf(row(start + 100)))
            now += 3_600_000
            val second = recorder()

            second.record(listOf(row(now + 100)))

            assertEquals("session:$now:1", store.cursor(sourceId))
            store.seal(sealEverything, now)
            assertEquals(
                listOf(fileMarker("elm327/$start"), fileMarker("elm327/$now")),
                store.pending("sink").map { it.lines.first() },
            )
        }
}
