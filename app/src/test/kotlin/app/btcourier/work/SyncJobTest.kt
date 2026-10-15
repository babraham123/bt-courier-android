package app.btcourier.work

import app.btcourier.core.sync.StopReason
import app.btcourier.core.transport.GattLink
import app.btcourier.data.InMemorySyncStatus
import app.btcourier.data.SyncResultKind
import app.btcourier.data.SyncStopKind
import app.btcourier.fakes.FakeWicanLogger
import app.btcourier.fakes.ScriptedPeripheral
import app.btcourier.fakes.ScriptedPeripheral.Fault
import app.btcourier.fakes.ScriptedPeripheral.Reply
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.fail
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.nio.file.Path

/**
 * [SyncJob] against [FakeWicanLogger] behind [ScriptedPeripheral], over the real `ble_http`
 * transport and the app's store. Expected values follow the job's contract: rows reach the store
 * exactly once, the link is closed whatever happens, interrupted syncs ask for a retry, final
 * ones don't, and the status records each outcome.
 */
class SyncJobTest {
    @TempDir
    lateinit var dir: Path

    private val status = InMemorySyncStatus()
    private val store by lazy { openStore(dir, setOf("sink")) }
    private val epoch = NOW / 1000 - 3600
    private val rows = (0 until 20).map { row(ts = epoch * 1000 + it * 1_000L, value = it) }

    private val device = FakeWicanLogger().apply {
        append(epoch, rows)
        rtcEpochSeconds = NOW / 1000
        clockValid = true
    }

    private val paired = PairedAddress { "00:11:22:33:44:55" }

    /** Hands out one [ScriptedPeripheral] link per open and remembers each peripheral. */
    private inner class Links(
        private val scope: TestScope,
        private val handler: (ScriptedPeripheral, ScriptedPeripheral.Request) -> Reply? = { _, r -> device.handle(r) },
    ) : LinkFactory {
        val opened = mutableListOf<ScriptedPeripheral>()

        override suspend fun open(address: String): GattLink {
            lateinit var peripheral: ScriptedPeripheral
            peripheral = ScriptedPeripheral({ handler(peripheral, it) }, scope = scope.backgroundScope)
            opened += peripheral
            return peripheral.link
        }
    }

    private fun job(links: LinkFactory, pairedAddress: PairedAddress = paired, lock: Mutex = Mutex()) =
        SyncJob(pairedAddress, links, store, status, clock = { NOW }, lock = lock, pageLimit = 7)

    @Test
    fun `a sync stores every row once, seals it, records the outcome and closes the link`() = runTest {
        val links = Links(this)
        var foreground = 0

        val outcome = job(links).run(sealEverything) { foreground++ }

        assertInstanceOf(SyncOutcome.Done::class.java, outcome)
        outcome as SyncOutcome.Done
        assertEquals(StopReason.CaughtUp, outcome.report.stoppedBecause)
        assertTrue(outcome.newBatches)
        assertEquals((0 until 20).toList(), store.pendingRows("sink").map { Regex(""""value":(\d+)""").find(it)!!.groupValues[1].toInt() })
        assertEquals(1, foreground, "foreground exactly once, after connecting")
        assertTrue(links.opened.single().link.closed)
        val s = status.status.value
        assertEquals(SyncResultKind.CAUGHT_UP, s.lastSync)
        assertEquals(NOW, s.lastSyncAtMs)
        assertEquals(20, s.lastSyncLinesStored)
        assertEquals(outcome.report.pagesPulled, s.lastSyncPages)
        assertEquals(SyncStopKind.CAUGHT_UP, s.lastSyncStop)
        assertEquals(store.cursor(SyncJob.SOURCE_ID), s.cursor)
        assertEquals(1, s.pendingBatches)
        assertTrue(s.bytesStored > 0)
        assertNull(s.lastError)

        val again = job(links).run(sealEverything)

        assertEquals(false, (again as SyncOutcome.Done).newBatches, "nothing new, so no upload is needed")
        assertEquals(0, again.report.linesStored)
    }

    @Test
    fun `a link lost mid-transfer asks for a retry, and the retry resumes without duplicates`() = runTest {
        var exports = 0
        val links = Links(this) { p, r ->
            if (r.path.startsWith("/api/logger/export") && ++exports == 2) p.inject(Fault.LinkLossAfter(1))
            device.handle(r)
        }

        val cut = job(links).run(sealEverything)

        assertInstanceOf(SyncOutcome.Interrupted::class.java, cut)
        assertInstanceOf(StopReason.LinkLost::class.java, (cut as SyncOutcome.Interrupted).report.stoppedBecause)
        assertTrue(cut.newBatches, "the page stored before the loss is sealed and deliverable")
        assertTrue(links.opened.single().link.closed)
        assertEquals(SyncResultKind.INTERRUPTED, status.status.value.lastSync)
        assertTrue(status.status.value.lastError!!.startsWith("link lost"))

        val resumed = job(links).run(sealEverything)

        assertInstanceOf(SyncOutcome.Done::class.java, resumed)
        assertEquals(20, store.pendingRows("sink").size, "every row exactly once")
        assertEquals(20, store.pendingRows("sink").toSet().size)
    }

    @Test
    fun `a device busy on every try is an interrupted sync`() = runTest {
        val links = Links(this) { _, r ->
            if (r.path.startsWith("/api/logger/export")) Reply(429, """{"err":"busy"}""") else device.handle(r)
        }

        val outcome = job(links).run(sealEverything)

        assertEquals(StopReason.Busy, (outcome as SyncOutcome.Interrupted).report.stoppedBecause)
        assertTrue(links.opened.single().link.closed)
        assertEquals("device busy", status.status.value.lastError)
    }

    @Test
    fun `a device error is final and recorded`() = runTest {
        val links = Links(this) { _, r -> if (r.path == "/api/rtc") Reply(500, "rtc failed", "text/plain") else device.handle(r) }

        val outcome = job(links).run(sealEverything)

        assertInstanceOf(SyncOutcome.Done::class.java, outcome)
        assertEquals(SyncResultKind.DEVICE_ERROR, status.status.value.lastSync)
        assertTrue(status.status.value.lastError!!.contains("HTTP 500"))
        assertNull(store.cursor(SyncJob.SOURCE_ID), "nothing pulled")
    }

    @Test
    fun `an unreachable device is recorded, not retried, and never goes foreground`() = runTest {
        val links = LinkFactory { throw IOException("connect timed out") }

        val outcome = job(links).run(sealEverything) { fail("foreground without a link") }

        assertEquals(SyncOutcome.Unreachable, outcome)
        assertEquals(SyncResultKind.UNREACHABLE, status.status.value.lastSync)
        assertNotNull(status.status.value.lastErrorAtMs)
    }

    @Test
    fun `without a paired device no link is opened`() = runTest {
        val outcome = job(LinkFactory { fail("opened a link") }, pairedAddress = { null }).run(sealEverything)

        assertEquals(SyncOutcome.NotPaired, outcome)
        assertEquals(SyncResultKind.NOT_PAIRED, status.status.value.lastSync)
    }

    @Test
    fun `a sync started while another runs returns at once without opening a link`() = runTest {
        val lock = Mutex()
        val connecting = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val first = async {
            job(LinkFactory { connecting.complete(Unit); release.await(); throw IOException("gone") }, lock = lock).run(sealEverything)
        }
        connecting.await()

        val second = job(LinkFactory { fail("second link") }, lock = lock).run(sealEverything)

        assertEquals(SyncOutcome.AlreadyRunning, second)
        release.complete(Unit)
        assertEquals(SyncOutcome.Unreachable, first.await())
        runCurrent()
        assertFalse(lock.isLocked, "the lock is released after the first sync")
    }
}
