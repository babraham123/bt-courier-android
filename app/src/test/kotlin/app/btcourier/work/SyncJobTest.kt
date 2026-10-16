package app.btcourier.work

import app.btcourier.ble.PairedDevice
import app.btcourier.ble.PairedDevices
import app.btcourier.core.sink.CompositeSink
import app.btcourier.core.sink.Jsonl
import app.btcourier.core.sink.UploadResult
import app.btcourier.core.store.SealPolicy
import app.btcourier.core.transport.GattLink
import app.btcourier.data.DeviceKind
import app.btcourier.data.DeviceSync
import app.btcourier.data.InMemorySyncStatus
import app.btcourier.data.SyncResultKind
import app.btcourier.data.SyncStopKind
import app.btcourier.fakes.FakeColmiRing
import app.btcourier.fakes.FakeGattLink
import app.btcourier.fakes.FakeWicanLogger
import app.btcourier.fakes.ScriptedPeripheral
import app.btcourier.fakes.ScriptedPeripheral.Fault
import app.btcourier.fakes.ScriptedPeripheral.Reply
import app.btcourier.ui.FakePairedDevices
import app.btcourier.work.SyncOutcome.Recorded
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.fail
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.nio.file.Path
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * [SyncJob] against [FakeWicanLogger] behind [ScriptedPeripheral], over the real `ble_http`
 * transport, and against [FakeColmiRing], with the app's store. Expected values follow the job's
 * contract: rows reach the store exactly once, the link is closed whatever happens, the outcome
 * is what the status records under the device's address, and [SyncRetryPolicy] decides retries:
 * busy calls are retried in the run, interrupted runs are worth another run up to its limit,
 * final ones are not. Aged rows are sealed with or without a link, and each paired device keeps
 * its own cursor under a source id of its kind. An automatic sync of a ring whose cursor is already
 * yesterday skips connecting until local midnight; a manual one always connects.
 */
class SyncJobTest {
    @TempDir
    lateinit var dir: Path

    private val status = InMemorySyncStatus()

    /** Wall clock of store and job; tests advance it to cross the seal policy's age rule. */
    private var now = NOW
    private val store by lazy { openStore(dir, setOf("sink")) { now } }
    private val epoch = NOW / 1000 - 3600
    private val rows = (0 until 20).map { row(ts = epoch * 1000 + it * 1_000L, value = it) }

    private val device = logger(epoch, rows)

    private val address = "AA:BB:CC:DD:EE:01"
    private val paired = FakePairedDevices(PairedDevice(address, null, 1))
    private val wicanSource = SyncJob.sourceIdFor(DeviceKind.WICAN, address)

    private fun logger(
        epoch: Long,
        rows: List<String>,
    ) = FakeWicanLogger().apply {
        append(epoch, rows)
        rtcEpochSeconds = NOW / 1000
        clockValid = true
    }

    private fun values(rows: List<String>) = rows.map { Regex(""""value":(\d+)""").find(it)!!.groupValues[1].toInt() }

    private val unreachable = LinkFactory { _, _ -> throw IOException("connect timed out") }

    private val noLink = LinkFactory { _, _ -> fail("opened a link") }

    /** Hands out one [ScriptedPeripheral] link per open and remembers each peripheral. */
    private inner class Links(
        private val scope: TestScope,
        private val handler: (ScriptedPeripheral, ScriptedPeripheral.Request) -> Reply? = { _, r -> device.handle(r) },
    ) : LinkFactory {
        val opened = mutableListOf<ScriptedPeripheral>()

        override suspend fun open(
            address: String,
            kind: DeviceKind,
        ): GattLink {
            assertEquals(DeviceKind.WICAN, kind)
            lateinit var peripheral: ScriptedPeripheral
            peripheral = ScriptedPeripheral({ handler(peripheral, it) }, scope = scope.backgroundScope)
            opened += peripheral
            return peripheral.link
        }
    }

    private fun job(
        links: LinkFactory,
        devices: PairedDevices = paired,
        locks: DeviceLocks = DeviceLocks(),
    ) = SyncJob(devices, links, store, status, clock = { now }, locks = locks, pageLimit = 7, zone = { ZoneOffset.UTC })

    private suspend fun SyncJob.run(
        policy: SealPolicy,
        attempt: Int = 0,
        onConnected: suspend () -> Unit = {},
    ) = run(address, policy, attempt, onConnected = onConnected) as Recorded

    private val sync get() = status.status.value.syncOf(address)!!

    private suspend fun sealedIds() = store.pending("sink").map { it.id }

    @Test
    fun `a sync stores every row once, seals it, records the outcome and closes the link`() =
        runTest {
            val links = Links(this)
            var foreground = 0

            val outcome = job(links).run(sealEverything) { foreground++ }

            val pages = outcome.pages
            assertEquals(
                Recorded(
                    SyncResultKind.CAUGHT_UP,
                    SyncStopKind.CAUGHT_UP,
                    null,
                    20,
                    pages,
                    store.cursor(wicanSource),
                    sealedIds(),
                ),
                outcome,
            )
            assertTrue(pages >= 3, "20 rows in pages of 7")
            assertTrue(outcome.newBatches)
            assertEquals((0 until 20).toList(), values(store.pendingRows("sink")))
            assertEquals(1, foreground, "foreground exactly once, after connecting")
            assertTrue(
                links.opened
                    .single()
                    .link.closed,
            )
            val s = status.status.value
            assertEquals(
                DeviceSync(NOW, SyncResultKind.CAUGHT_UP, 20, pages, SyncStopKind.CAUGHT_UP, store.cursor(wicanSource)),
                sync,
            )
            assertEquals(setOf(address), s.lastSyncByDevice.keys)
            assertEquals(1, s.pendingBatches)
            assertEquals(listOf(wicanSource), store.pending("sink").map { it.sourceId })
            assertTrue(s.bytesStored > 0)
            assertNull(s.lastError)

            val again = job(links).run(sealEverything)

            assertEquals(false, again.newBatches, "nothing new, so no upload is needed")
            assertEquals(0, again.linesStored)
            assertFalse(again.retryWorthwhile)
        }

    @Test
    fun `a link lost mid-transfer is worth another run, and that run resumes without duplicates`() =
        runTest {
            var exports = 0
            val links =
                Links(this) { p, r ->
                    if (r.path.startsWith("/api/logger/export") && ++exports == 2) p.inject(Fault.LinkLossAfter(1))
                    device.handle(r)
                }

            val cut = job(links).run(sealEverything)

            assertEquals(SyncResultKind.INTERRUPTED to SyncStopKind.LINK_LOST, cut.result to cut.stop)
            assertTrue(cut.retryWorthwhile)
            assertEquals(sealedIds(), cut.sealed, "the page stored before the loss is sealed and deliverable")
            assertTrue(cut.newBatches)
            assertTrue(
                links.opened
                    .single()
                    .link.closed,
            )
            assertEquals(cut.deviceSync(NOW), sync)
            assertTrue(cut.error!!.startsWith("link lost"))
            assertEquals(cut.error, status.status.value.lastError)

            val resumed = job(links).run(sealEverything, attempt = 1)

            assertEquals(SyncResultKind.CAUGHT_UP, resumed.result)
            assertFalse(resumed.retryWorthwhile)
            assertEquals(20, store.pendingRows("sink").size, "every row exactly once")
            assertEquals(20, store.pendingRows("sink").toSet().size)
        }

    @Test
    fun `a sync whose seal evicts as many batches as it seals still asks for an upload`() =
        runTest {
            // An undelivered batch fills the cache, so sealing this sync's rows evicts it.
            store.appendPage("other", "1:1", listOf(marker("dl_1.jsonl")) + (0 until 200).map { row(it.toLong(), it) })
            store.seal(sealEverything, now)
            val full = sealEverything.copy(maxStoreBytes = store.counters().bytesStored)

            val outcome = job(Links(this)).run(full)

            assertTrue(outcome.newBatches)
            assertEquals(1, store.counters().pendingBatches, "one batch sealed, one evicted")
            assertEquals((0 until 20).toList(), values(store.pendingRows("sink")))
        }

    @Test
    fun `rows a sync left open are sealed by age while the device is out of reach, and delivered`() =
        runTest {
            val policy = SealPolicy()

            val stored = job(Links(this)).run(policy)

            assertEquals(20, stored.linesStored)
            assertFalse(stored.newBatches, "one small open file is not sealed yet")
            assertEquals(
                NOW + policy.maxBatchAgeMs,
                stored.sealDueAtMs,
                "the age rule closes it 30 min after it arrived",
            )

            now += 29 * MINUTE
            val waiting = job(unreachable).run(policy)
            assertFalse(waiting.newBatches)
            assertEquals(NOW + policy.maxBatchAgeMs, waiting.sealDueAtMs, "a later run does not push the deadline back")
            now += 1 * MINUTE
            val aged = job(unreachable).run(policy)
            assertEquals(SyncResultKind.UNREACHABLE, aged.result)
            assertEquals(sealedIds(), aged.sealed)
            assertTrue(aged.newBatches)
            assertNull(aged.sealDueAtMs, "nothing left open")
            assertEquals(1, status.status.value.pendingBatches)

            val upload = ScriptedUpload(UploadResult.Acked)
            val delivered =
                UploadJob(store, {
                    listOf(CompositeSink("sink", upload, Jsonl))
                }, status, clock = { now }).run(0, policy)

            assertEquals(UploadOutcome.Delivered, delivered)
            assertEquals((0 until 20).toList(), values(upload.ackedRows))
        }

    @Test
    fun `without a paired device aged rows are still sealed`() =
        runTest {
            job(Links(this)).run(SealPolicy())
            now += 30 * MINUTE

            val outcome = job(noLink).run(null, SealPolicy())

            assertEquals(Recorded(SyncResultKind.NOT_PAIRED, error = "no device paired", sealed = sealedIds()), outcome)
            assertTrue(outcome.newBatches)
            assertEquals((0 until 20).toList(), values(store.pendingRows("sink")))
        }

    @Test
    fun `a replacement device gets its own cursor and is pulled from its oldest file`() =
        runTest {
            var current = device
            val replacement = "aa:bb:cc:dd:ee:03"
            paired.devices += PairedDevice(replacement.uppercase(), null, 2)
            val links = Links(this) { _, r -> current.handle(r) }
            job(links).run(sealEverything)
            val firstCursor = store.cursor(wicanSource)
            assertNotNull(firstCursor)

            // The replacement's only file is older than anything the first device had.
            val olderEpoch = epoch - 86_400
            current =
                logger(olderEpoch, (0 until 5).map { row(ts = olderEpoch * 1000 + it * 1_000L, value = 100 + it) })
            val outcome = job(links).run(replacement, sealEverything) as Recorded

            assertEquals(5, outcome.linesStored)
            assertEquals((0 until 20).toList() + (100 until 105).toList(), values(store.pendingRows("sink")))
            val secondCursor = store.cursor(SyncJob.sourceIdFor(DeviceKind.WICAN, replacement))
            assertTrue(secondCursor!!.startsWith("$olderEpoch:"), secondCursor)
            assertEquals(firstCursor, store.cursor(wicanSource), "the first device's cursor is untouched")
            assertEquals(
                secondCursor,
                status.status.value
                    .syncOf(replacement)
                    ?.cursor,
            )
            assertEquals(firstCursor, sync.cursor)
            assertEquals("wican:AA:BB:CC:DD:EE:03", SyncJob.sourceIdFor(DeviceKind.WICAN, replacement))
        }

    /** A device that answers every export 429. */
    private fun busyLinks(scope: TestScope) =
        Links(scope) { _, r ->
            if (r.path.startsWith("/api/logger/export")) Reply(429, """{"err":"busy"}""") else device.handle(r)
        }

    @Test
    fun `a busy device is retried in the run after 1, 2 and 4 s, then the sync is interrupted`() =
        runTest {
            val links = busyLinks(this)

            val start = currentTime

            val outcome = job(links).run(sealEverything)

            val exports =
                links.opened
                    .single()
                    .requests
                    .count { it.path.startsWith("/api/logger/export") }
            assertEquals(4, exports, "the first try and three retries, on one link")
            assertTrue(currentTime - start >= 7_000, "waited 1 + 2 + 4 s")
            assertEquals(
                Recorded(SyncResultKind.INTERRUPTED, SyncStopKind.BUSY, "device busy", retryWorthwhile = true),
                outcome,
            )
            assertTrue(
                links.opened
                    .single()
                    .link.closed,
            )
            assertEquals("device busy", status.status.value.lastError)
        }

    @Test
    fun `an interrupted sync is worth another run until five runs in a row were made`() =
        runTest {
            val links = busyLinks(this)

            val verdicts = (0 until 5).map { attempt -> job(links).run(sealEverything, attempt).retryWorthwhile }

            assertEquals(listOf(true, true, true, true, false), verdicts)
            assertEquals(SyncResultKind.INTERRUPTED, sync.result, "the last run is still recorded as interrupted")
        }

    @Test
    fun `a device error is final and recorded`() =
        runTest {
            val links =
                Links(this) { _, r ->
                    if (r.path ==
                        "/api/rtc"
                    ) {
                        Reply(500, "rtc failed", "text/plain")
                    } else {
                        device.handle(r)
                    }
                }

            val outcome = job(links).run(sealEverything)

            assertEquals(SyncResultKind.DEVICE_ERROR to SyncStopKind.DEVICE_ERROR, outcome.result to outcome.stop)
            assertFalse(outcome.retryWorthwhile)
            assertEquals(outcome.deviceSync(NOW), sync)
            assertTrue(
                status.status.value.lastError!!
                    .contains("HTTP 500"),
            )
            assertNull(store.cursor(wicanSource), "nothing pulled")
        }

    @Test
    fun `an unreachable device is recorded, keeps its cursor, is not retried, and never goes foreground`() =
        runTest {
            job(Links(this)).run(sealEverything)
            val cursor = store.cursor(wicanSource)
            now += MINUTE

            val outcome = job(unreachable).run(sealEverything) { fail("foreground without a link") }

            assertEquals(
                Recorded(SyncResultKind.UNREACHABLE, error = "device unreachable: connect timed out", cursor = cursor),
                outcome,
            )
            assertEquals(DeviceSync(now, SyncResultKind.UNREACHABLE, cursor = cursor), sync)
            assertNotNull(status.status.value.lastErrorAtMs)
        }

    @Test
    fun `without a paired device no link is opened and no device is recorded`() =
        runTest {
            assertEquals(
                Recorded(SyncResultKind.NOT_PAIRED, error = "no device paired"),
                job(noLink).run(null, sealEverything),
            )
            assertEquals(
                Recorded(SyncResultKind.NOT_PAIRED, error = "device not paired"),
                job(noLink).run("AA:BB:CC:DD:EE:09", sealEverything),
            )

            assertEquals(emptyMap<String, DeviceSync>(), status.status.value.lastSyncByDevice)
            assertEquals("device not paired", status.status.value.lastError)
        }

    @Test
    fun `a sync of a device that is already syncing returns at once, another device still syncs`() =
        runTest {
            val locks = DeviceLocks()
            val other = "AA:BB:CC:DD:EE:02"
            paired.devices += PairedDevice(other, null, 2)
            val connecting = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val first =
                async {
                    job(
                        LinkFactory {
                            _,
                            _,
                            ->
                            connecting.complete(Unit)
                            release.await()
                            throw IOException("gone")
                        },
                        locks = locks,
                    ).run(sealEverything)
                }
            connecting.await()

            val second = job(noLink, locks = locks).run(address.lowercase(), sealEverything)
            val parallel = job(unreachable, locks = locks).run(other, sealEverything)

            assertEquals(SyncOutcome.AlreadyRunning, second)
            assertFalse(second.retryWorthwhile || second.newBatches)
            assertEquals(SyncResultKind.UNREACHABLE, (parallel as Recorded).result)
            release.complete(Unit)
            assertEquals(SyncResultKind.UNREACHABLE, first.await().result)
            runCurrent()
            assertFalse(locks.of(address).isLocked, "the lock is released after the first sync")
        }

    // The ring, in the job's zone (UTC here): today and the two days before it.
    private val today = Instant.ofEpochMilli(NOW).atZone(ZoneOffset.UTC).toLocalDate()
    private val dayBefore = today.minusDays(2)
    private val yesterday = today.minusDays(1)
    private val ringAddress = "AA:BB:CC:DD:EE:02"
    private val ringSource = "colmi:$ringAddress"

    private fun ring() =
        FakeColmiRing(
            today = today,
            heartRate =
                mapOf(
                    dayBefore to
                        IntArray(288).apply {
                            this[0] = 60
                            this[12] = 72
                        },
                ),
            steps = mapOf(yesterday to mapOf(40 to 500)),
            batteryLevel = 87,
        )

    private fun ringLinks(ring: FakeColmiRing) =
        LinkFactory { a, kind ->
            assertEquals(ringAddress, a)
            assertEquals(DeviceKind.COLMI_RING, kind)
            ring.link
        }

    private fun param(line: String) = Regex(""""param":"([^"]+)"""").find(line)?.groupValues?.get(1)

    private fun utcMs(
        day: LocalDate,
        hour: Int,
        minute: Int,
    ) = day.atTime(hour, minute).toInstant(ZoneOffset.UTC).toEpochMilli()

    @Test
    fun `a ring sync stores one colmi file per day under its own source id and records the pages`() =
        runTest {
            paired.devices += PairedDevice(ringAddress, "R02_0000", 2, DeviceKind.COLMI_RING)
            val ring = ring()
            var foreground = 0

            val outcome = job(ringLinks(ring)).run(ringAddress, sealEverything) { foreground++ } as Recorded

            assertEquals(SyncStopKind.CAUGHT_UP, outcome.stop)
            // No cursor yet: the ring's history, today - 7 up to yesterday; today is never pulled.
            val days = (7 downTo 1).map { today.minusDays(it.toLong()) }
            assertEquals(days.size, outcome.pages)
            assertEquals(yesterday.toString(), store.cursor(ringSource))
            assertNull(store.cursor(wicanSource), "the dongle is untouched")

            val batches = store.pending("sink")
            assertEquals(setOf(ringSource), batches.map { it.sourceId }.toSet())
            // A day with no rows seals no batch; the first page's battery row keeps the oldest day's.
            val daysWithRows = listOf(days.first(), dayBefore, yesterday)
            assertEquals(
                daysWithRows.map { """{"_file":"colmi/$it"}""" },
                batches.flatMap { it.lines }.filter(::isMarker),
            )
            val rows = batches.flatMap { it.lines }.filterNot(::isMarker)
            assertEquals(
                listOf("colmi.battery", "colmi.device", "colmi.hr", "colmi.hr", "colmi.hr_logging", "colmi.steps"),
                rows.mapNotNull(::param).sorted(),
            )
            assertTrue(
                rows.any {
                    it.contains(""""ts":${utcMs(dayBefore, 1, 0)},""") && it.contains(""""value":72""")
                },
                rows.joinToString("\n"),
            )
            assertTrue(
                rows.any {
                    it.contains(""""ts":${utcMs(yesterday, 10, 0)},""") && it.contains(""""value":500""")
                },
                rows.joinToString("\n"),
            )

            assertEquals(1, foreground)
            assertTrue(ring.link.closed)
            assertEquals(1, ring.clockSets.size, "the ring's clock is set once per sync")
            assertEquals(
                DeviceSync(NOW, SyncResultKind.CAUGHT_UP, 6, days.size, SyncStopKind.CAUGHT_UP, yesterday.toString()),
                status.status.value.syncOf(ringAddress),
            )
            assertNull(status.status.value.syncOf(address))
        }

    @Test
    fun `a ring that stops answering is an interrupted sync that resumes at the stored day`() =
        runTest {
            paired.devices += PairedDevice(ringAddress, null, 2, DeviceKind.COLMI_RING)
            store.appendPage(ringSource, dayBefore.toString(), emptyList())

            val cut = job(LinkFactory { _, _ -> FakeGattLink(mtu = 23) }).run(ringAddress, sealEverything) as Recorded

            assertEquals(SyncStopKind.LINK_LOST, cut.stop)
            assertTrue(cut.retryWorthwhile)
            assertEquals(dayBefore.toString(), store.cursor(ringSource))

            val resumed = job(ringLinks(ring())).run(ringAddress, sealEverything) as Recorded

            assertEquals(1, resumed.pages, "only yesterday is left")
            assertEquals(
                listOf("""{"_file":"colmi/$yesterday"}"""),
                store.pending("sink").flatMap { it.lines }.filter(::isMarker),
            )
        }

    /** Midnight (UTC, the job's zone) that ends today: from then on today is a whole day to pull. */
    private val midnight =
        today
            .plusDays(1)
            .atStartOfDay(ZoneOffset.UTC)
            .toInstant()
            .toEpochMilli()

    @Test
    fun `automatic syncs of a caught-up ring skip connecting until midnight, and the first periodic one after it connects`() =
        runTest {
            paired.devices += PairedDevice(ringAddress, null, 2, DeviceKind.COLMI_RING)
            store.appendPage(ringSource, yesterday.toString(), emptyList())
            store.appendPage("other", "1:1", listOf(marker("dl_1.jsonl"), row(1, 1)))
            val locks = DeviceLocks()
            // A sync holding the lock does not turn a skip into AlreadyRunning: the skip needs no link.
            assertTrue(locks.of(ringAddress).tryLock())

            now = midnight - 20 * MINUTE
            val skipped = job(noLink, locks = locks).run(ringAddress, sealEverything) as Recorded

            assertEquals(
                Recorded(
                    SyncResultKind.NOT_DUE,
                    cursor = yesterday.toString(),
                    sealed = sealedIds(),
                    dueAtMs = midnight,
                ),
                skipped,
                "sealed like a run without a device",
            )
            assertTrue(skipped.newBatches)
            assertEquals(
                DeviceSync(now, SyncResultKind.NOT_DUE, cursor = yesterday.toString(), dueAtMs = midnight),
                status.status.value.syncOf(ringAddress),
            )
            assertNull(status.status.value.lastError, "nothing went wrong")
            locks.of(ringAddress).unlock()

            // The periodic work's 15-minute cadence: one more skip, then the first run after midnight.
            now += 15 * MINUTE
            assertEquals(SyncResultKind.NOT_DUE, (job(noLink).run(ringAddress, sealEverything) as Recorded).result)
            now += 15 * MINUTE
            val ring = ring()
            val pulled = job(ringLinks(ring)).run(ringAddress, sealEverything) as Recorded

            assertEquals(SyncResultKind.CAUGHT_UP, pulled.result)
            assertEquals(1, pulled.pages, "the day that just ended")
            assertEquals(today.toString(), store.cursor(ringSource))
            assertNull(
                status.status.value
                    .syncOf(ringAddress)!!
                    .dueAtMs,
            )
            assertTrue(ring.link.closed)
        }

    @Test
    fun `a manual sync of a caught-up ring connects anyway`() =
        runTest {
            paired.devices += PairedDevice(ringAddress, null, 2, DeviceKind.COLMI_RING)
            store.appendPage(ringSource, yesterday.toString(), emptyList())
            val ring = ring()
            var foreground = 0

            val outcome =
                job(ringLinks(ring)).run(ringAddress, sealEverything, manual = true) { foreground++ } as Recorded

            assertEquals(
                Recorded(SyncResultKind.CAUGHT_UP, SyncStopKind.CAUGHT_UP, pages = 1, cursor = yesterday.toString()),
                outcome,
            )
            assertEquals(1, foreground)
            assertEquals(1, ring.clockSets.size, "the ring was reached")
            assertTrue(ring.link.closed)
        }

    @Test
    fun `a ring never synced, and a WiCAN whatever its cursor, are always due`() =
        runTest {
            paired.devices += PairedDevice(ringAddress, null, 2, DeviceKind.COLMI_RING)

            val ring = job(ringLinks(ring())).run(ringAddress, sealEverything) as Recorded
            assertEquals(SyncResultKind.CAUGHT_UP, ring.result)

            val links = Links(this)
            job(links).run(sealEverything)
            job(links).run(sealEverything)
            assertEquals(2, links.opened.size, "a caught-up WiCAN is connected to again")
        }
}
