package app.btcourier.work

import androidx.work.NetworkType
import app.btcourier.ble.PairedDevice
import app.btcourier.core.sink.CompositeSink
import app.btcourier.core.sink.Jsonl
import app.btcourier.core.sink.Sink
import app.btcourier.core.sink.UploadResult
import app.btcourier.data.AppSettings
import app.btcourier.data.DeviceKind
import app.btcourier.data.InMemorySyncStatus
import app.btcourier.fakes.FakeWicanLogger
import app.btcourier.fakes.ScriptedPeripheral
import app.btcourier.fakes.ScriptedPeripheral.Fault
import app.btcourier.ui.FakePairedDevices
import app.btcourier.ui.FakeSettings
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.fail
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneOffset

/**
 * The scheduling rules of the system entry points, through [WorkControl]: what a sync worker run
 * enqueues and whether it asks to run again ([SyncWork]), what app start and boot re-register
 * ([restoreSchedules]), what an upload worker run asks for next ([UploadWork]), and which network
 * an upload waits for ([Scheduler.uploadConstraints]).
 * [SyncWork] runs the real [SyncJob] against a [FakeWicanLogger] and the app's store.
 */
class WorkRulesTest {
    @TempDir
    lateinit var dir: Path

    private val store by lazy { openStore(dir, setOf("sink")) }
    private val epoch = NOW / 1000 - 3600
    private val logger =
        FakeWicanLogger().apply {
            append(epoch, (0 until 20).map { row(ts = epoch * 1000 + it * 1_000L, value = it) })
            rtcEpochSeconds = NOW / 1000
            clockValid = true
        }

    private val dongle = PairedDevice("AA:BB:CC:DD:EE:01", null, 1, DeviceKind.WICAN)
    private val ring = PairedDevice("AA:BB:CC:DD:EE:02", null, 2, DeviceKind.COLMI_RING)
    private val devices = FakePairedDevices(dongle)
    private val work = RecordingWorkControl()

    private val noLink = LinkFactory { _, _ -> fail("opened a link") }
    private val unreachable = LinkFactory { _, _ -> throw IOException("connect timed out") }

    /** Links to [logger]; with [loseLink] each link drops during its second export page. */
    private fun TestScope.loggerLinks(loseLink: Boolean = false) =
        LinkFactory { _, _ ->
            var exports = 0
            lateinit var peripheral: ScriptedPeripheral
            peripheral =
                ScriptedPeripheral({ r ->
                    if (loseLink && r.path.startsWith("/api/logger/export") &&
                        ++exports == 2
                    ) {
                        peripheral.inject(Fault.LinkLossAfter(1))
                    }
                    logger.handle(r)
                }, scope = backgroundScope)
            peripheral.link
        }

    private fun syncWork(links: LinkFactory): SyncWork {
        val job =
            SyncJob(devices, links, store, InMemorySyncStatus(), clock = {
                NOW
            }, locks = DeviceLocks(), pageLimit = 7, zone = { ZoneOffset.UTC })
        return SyncWork(job, SyncFanOut(devices) { work.syncNow(it, manual = false) }, work)
    }

    @Test
    fun `a periodic run enqueues one sync per paired device and does no sync itself`() =
        runTest {
            devices.devices = listOf(dongle, ring)

            val verdict = syncWork(noLink).run(address = null, attempt = 0, sealEverything, uploadOverCellular = false)

            assertEquals(WorkVerdict.SUCCESS, verdict)
            assertEquals(listOf(dongle.address, ring.address), work.syncs)
            assertEquals(emptyList<Pair<Boolean, Boolean>>(), work.uploads)
        }

    @Test
    fun `a periodic run with nothing paired only seals, and enqueues an upload for what it sealed`() =
        runTest {
            devices.devices = emptyList()
            store.appendPage("other", "1:1", listOf(marker("dl_1.jsonl"), row(1, 1)))

            val verdict = syncWork(noLink).run(address = null, attempt = 0, sealEverything, uploadOverCellular = true)

            assertEquals(WorkVerdict.SUCCESS, verdict)
            assertEquals(emptyList<String>(), work.syncs)
            assertEquals(1, store.pending("sink").size)
            assertEquals(listOf(true to false), work.uploads, "KEEP, on the cellular setting")
        }

    @Test
    fun `a sync that sealed batches enqueues an upload, and one that sealed nothing does not`() =
        runTest {
            val sync = syncWork(loggerLinks())

            assertEquals(
                WorkVerdict.SUCCESS,
                sync.run(dongle.address, attempt = 0, sealEverything, uploadOverCellular = false),
            )
            assertEquals(listOf(false to false), work.uploads, "KEEP, unmetered only")

            assertEquals(
                WorkVerdict.SUCCESS,
                sync.run(dongle.address, attempt = 0, sealEverything, uploadOverCellular = false),
            )
            assertEquals(1, work.uploads.size, "caught up: nothing new sealed")
            assertEquals(emptyList<String>(), work.syncs)
        }

    @Test
    fun `an interrupted sync asks to run again until its last attempt, and still enqueues an upload`() =
        runTest {
            val sync = syncWork(loggerLinks(loseLink = true))
            val lastAttempt = SyncRetryPolicy().maxRuns - 1

            assertEquals(
                WorkVerdict.RETRY,
                sync.run(dongle.address, attempt = 0, sealEverything, uploadOverCellular = false),
            )
            assertEquals(listOf(false to false), work.uploads, "the page stored before the loss was sealed")

            assertEquals(
                WorkVerdict.SUCCESS,
                sync.run(dongle.address, attempt = lastAttempt, sealEverything, uploadOverCellular = false),
            )
        }

    @Test
    fun `a sync that never reaches its device does not ask to run again`() =
        runTest {
            assertEquals(
                WorkVerdict.SUCCESS,
                syncWork(unreachable).run(dongle.address, attempt = 0, sealEverything, uploadOverCellular = false),
            )
            assertEquals(emptyList<Pair<Boolean, Boolean>>(), work.uploads, "nothing stored, so nothing sealed")
        }

    @Test
    fun `a caught-up ring is skipped by an automatic sync run and connected to by a manual one`() =
        runTest {
            devices.devices = listOf(ring)
            val yesterday =
                Instant
                    .ofEpochMilli(NOW)
                    .atZone(ZoneOffset.UTC)
                    .toLocalDate()
                    .minusDays(1)
            store.appendPage(
                SyncJob.sourceIdFor(DeviceKind.COLMI_RING, ring.address),
                yesterday.toString(),
                emptyList(),
            )
            var opened = 0
            val asleep =
                LinkFactory { _, _ ->
                    opened++
                    throw IOException("asleep")
                }

            syncWork(asleep).run(ring.address, attempt = 0, sealEverything, uploadOverCellular = false)
            assertEquals(0, opened, "automatic: nothing due before midnight")

            syncWork(asleep).run(ring.address, attempt = 0, sealEverything, uploadOverCellular = false, manual = true)
            assertEquals(1, opened, "manual: the user asked")
        }

    @Test
    fun `restoring schedules follows the periodic toggle and observes presence of every paired device`() =
        runTest {
            devices.devices = listOf(dongle, ring)
            val observed = mutableListOf<String>()

            restoreSchedules(FakeSettings(AppSettings(periodicSync = true)), devices, work) { observed += it }
            restoreSchedules(FakeSettings(AppSettings(periodicSync = false)), devices, work) { observed += it }

            assertEquals(listOf(true, false), work.periodic)
            assertEquals(listOf(dongle.address, ring.address, dongle.address, ring.address), observed)
        }

    @Test
    fun `restoring schedules with nothing paired observes nothing`() =
        runTest {
            devices.devices = emptyList()
            val observed = mutableListOf<String>()

            restoreSchedules(FakeSettings(), devices, work) { observed += it }

            assertEquals(listOf(AppSettings().periodicSync), work.periodic)
            assertTrue(observed.isEmpty())
        }

    private fun uploadWork(
        vararg sinks: Sink,
        clock: () -> Long = { NOW },
    ): UploadWork = UploadWork(UploadJob(store, { sinks.toList() }, InMemorySyncStatus(), clock = clock), work)

    @Test
    fun `an upload round that a sink answered Retry runs again on the back-off, one that only waited schedules the follow-up`() =
        runTest {
            store.appendPage("wican", "1:0", listOf(marker("dl_1.jsonl"), row(1_000, 1)))
            store.seal(sealEverything, NOW)
            val throttled =
                CompositeSink("sink", ScriptedUpload(UploadResult.Retry("HTTP 429", retryAfterMs = 90_000)), Jsonl)
            var now = NOW
            val upload = uploadWork(throttled, clock = { now })

            assertEquals(WorkVerdict.RETRY, upload.run(attempt = 0, sealEverything, uploadOverCellular = false))
            assertEquals(emptyList<Pair<Long, Boolean>>(), work.deferredUploads)

            now += 30_000
            assertEquals(WorkVerdict.SUCCESS, upload.run(attempt = 1, sealEverything, uploadOverCellular = true))
            assertEquals(
                listOf(60_000L to true),
                work.deferredUploads,
                "the follow-up is due when the sink is, under the current network policy",
            )
        }

    @Test
    fun `an upload round with nothing to wait for ends the work without a follow-up`() =
        runTest {
            store.appendPage("wican", "1:0", listOf(marker("dl_1.jsonl"), row(1_000, 1)))
            store.seal(sealEverything, NOW)

            assertEquals(
                WorkVerdict.SUCCESS,
                uploadWork(
                    CompositeSink("sink", ScriptedUpload(UploadResult.Acked), Jsonl),
                ).run(0, sealEverything, false),
            )
            assertEquals(
                WorkVerdict.SUCCESS,
                uploadWork(
                    CompositeSink("sink", ScriptedUpload(UploadResult.Failed("HTTP 403")), Jsonl),
                ).run(0, sealEverything, false),
            )

            assertEquals(emptyList<Pair<Long, Boolean>>(), work.deferredUploads)
        }

    @Test
    fun `uploads wait for any network with cellular allowed, and for an unmetered one without`() {
        assertEquals(NetworkType.CONNECTED, Scheduler.uploadConstraints(allowCellular = true).requiredNetworkType)
        assertEquals(NetworkType.UNMETERED, Scheduler.uploadConstraints(allowCellular = false).requiredNetworkType)
    }
}
