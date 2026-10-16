package app.btcourier.work

import app.btcourier.ble.PairedDevice
import app.btcourier.core.source.ClockEvent
import app.btcourier.core.source.Page
import app.btcourier.core.source.Source
import app.btcourier.core.store.DefaultStore
import app.btcourier.core.transport.GattLink
import app.btcourier.data.AppSettings
import app.btcourier.data.DeviceKind
import app.btcourier.data.DriveSessionEnd
import app.btcourier.data.DriveSessionStatus
import app.btcourier.data.InMemorySyncStatus
import app.btcourier.device.DeviceSupport
import app.btcourier.device.LiveMode
import app.btcourier.device.SourceOptions
import app.btcourier.device.WicanSupport
import app.btcourier.fakes.FakeGattLink
import app.btcourier.fakes.FakeWicanLogger
import app.btcourier.fakes.ScriptedPeripheral
import app.btcourier.fakes.ScriptedPeripheral.Fault
import app.btcourier.ui.FakePairedDevices
import app.btcourier.ui.FakeSettings
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.fail
import java.io.IOException
import java.time.ZoneOffset

/**
 * [DriveSession] per ticket 0001 and its amendments, on virtual time: a pulled device (the WiCAN,
 * [FakeWicanLogger] behind [ScriptedPeripheral] over the real `ble_http` transport) gets a round at
 * once and then every pull interval or on [DriveSession.pullNow]; a streaming device (a scripted
 * [Source.subscribe]) has each emission kept through `StreamRecorder`. Every round that stored rows
 * is sealed and followed by the upload rule of a sync; the session ends on [DriveSession.stop]
 * (the car's with one last round), after the idle time without a stored row, at the session cap,
 * or with the link lost after two reopen attempts; it holds the device's lock throughout, so a
 * sync meanwhile is a no-op and a manual one becomes a round.
 */
class DriveSessionTest {
    private var clock: () -> Long = { NOW }

    // In memory: the file store writes on Dispatchers.IO, outside the test's virtual time.
    private val store = DefaultStore.inMemory(clock = { clock() }, sinkIds = { setOf("sink") })
    private val status = InMemorySyncStatus()
    private val work = RecordingWorkControl()
    private val locks = DeviceLocks()
    private val sessions = DriveSessions()
    private val settings =
        FakeSettings(
            AppSettings(
                maxBatchAgeMs = 0,
                driveSession = true,
                drivePullIntervalMinutes = 2,
                driveIdleMinutes = 10,
                driveMaxSessionMinutes = 180,
            ),
        )

    private val dongle = PairedDevice("AA:BB:CC:DD:EE:01", null, 1, DeviceKind.WICAN)
    private val ring = PairedDevice("AA:BB:CC:DD:EE:02", null, 2, DeviceKind.COLMI_RING)
    private val devices = FakePairedDevices(dongle, ring)
    private val wicanSource = SyncJob.sourceIdFor(DeviceKind.WICAN, dongle.address)

    private val epoch = NOW / 1000 - 3600
    private var nextValue = 0
    private val logger =
        FakeWicanLogger().apply {
            rtcEpochSeconds = NOW / 1000
            clockValid = true
        }

    /** Logs [count] more rows into the dongle's open file. */
    private fun log(count: Int) =
        logger.append(epoch, List(count) { row(ts = epoch * 1000 + nextValue * 1_000L, value = nextValue++) })

    init {
        log(5)
    }

    /** How each link open goes, in order; the last one repeats. */
    private sealed interface Open {
        /** Up; with [dropAfterExport], the link drops on the request after its nth export. */
        data class Up(
            val dropAfterExport: Int? = null,
        ) : Open

        data object Fail : Open
    }

    private inner class LoggerLinks(
        private val scope: TestScope,
        private vararg val plan: Open = arrayOf(Open.Up()),
    ) : LinkFactory {
        var attempts = 0
        val opened = mutableListOf<FakeGattLink>()

        override suspend fun open(
            address: String,
            kind: DeviceKind,
        ): GattLink {
            val step = plan[minOf(attempts++, plan.lastIndex)]
            if (step !is Open.Up) throw IOException("asleep")
            var exports = 0
            lateinit var peripheral: ScriptedPeripheral
            peripheral =
                ScriptedPeripheral({ r ->
                    if (r.path.startsWith("/api/logger/export") && ++exports == step.dropAfterExport) {
                        peripheral.inject(Fault.LinkLossAfter(1))
                    }
                    logger.handle(r)
                }, scope = scope.backgroundScope)
            opened += peripheral.link
            return peripheral.link
        }
    }

    /** A streaming kind the app does not have yet: its source is [source], its rows go under `elm327:`. */
    private fun streaming(source: Source): (DeviceKind) -> DeviceSupport =
        {
            object : DeviceSupport by WicanSupport {
                override val sourcePrefix = "elm327:"
                override val liveMode = LiveMode.STREAM

                override suspend fun <T> withSource(
                    link: GattLink,
                    options: SourceOptions,
                    block: suspend (Source) -> T,
                ): T = block(source)
            }
        }

    /** Emits what [emissions] produces, as a stream device's [Source.subscribe] would. */
    private class StreamSource(
        private val emissions: suspend kotlinx.coroutines.flow.FlowCollector<List<String>>.() -> Unit,
    ) : Source {
        override suspend fun pull(cursor: String?) = Page(emptyList(), cursor.orEmpty(), more = false)

        override suspend fun clockJournal() = emptyList<ClockEvent>()

        override fun subscribe(): Flow<List<String>> = flow(emissions)
    }

    private fun TestScope.session(
        links: LinkFactory,
        supportOf: ((DeviceKind) -> DeviceSupport)? = null,
    ): DriveSession {
        clock = { NOW + currentTime }
        return if (supportOf == null) {
            DriveSession(devices, links, store, status, work, locks, clock, settings, sessions, zone = {
                ZoneOffset.UTC
            }, pageLimit = 7)
        } else {
            DriveSession(
                devices,
                links,
                store,
                status,
                work,
                locks,
                clock,
                settings,
                sessions,
                supportOf,
                { ZoneOffset.UTC },
                7,
            )
        }
    }

    private fun TestScope.start(
        session: DriveSession,
        address: String = dongle.address,
    ): Deferred<DriveSessionOutcome> = async { session.run(address) }.also { runCurrent() }

    private val recorded get() = status.status.value.driveSession!!

    private suspend fun storedRows() = store.pendingRows("sink")

    @Test
    fun `a pulled device gets a round at once and then one every pull interval, and only rounds that stored rows seal and upload`() =
        runTest {
            val links = LoggerLinks(this)
            val session = start(session(links))

            assertEquals(DriveSessionStatus(dongle.address, NOW, rounds = 1, rows = 5), recorded)
            assertEquals(5, storedRows().size, "round one stored and sealed the 5 logged rows")
            assertEquals(listOf(false to false), work.uploads, "KEEP, unmetered only")
            assertTrue(locks.of(dongle.address).isLocked, "the session holds the device's lock")
            assertEquals(setOf(dongle.address), sessions.running())

            log(3)
            advanceTimeBy(2 * MINUTE - 1)
            assertEquals(1, recorded.rounds, "not before the interval")
            advanceTimeBy(1)
            runCurrent()
            assertEquals(DriveSessionStatus(dongle.address, NOW, rounds = 2, rows = 8), recorded)
            assertEquals(
                (0 until 8).toList(),
                storedRows().map { Regex(""""value":(\d+)""").find(it)!!.groupValues[1].toInt() },
            )
            assertEquals(2, work.uploads.size)

            advanceTimeBy(2 * MINUTE)
            runCurrent()
            assertEquals(3, recorded.rounds)
            assertEquals(2, work.uploads.size, "a round that stored nothing sealed nothing")

            assertTrue(sessions.stop(dongle.address, DriveStop.USER))
            val outcome = session.await()

            assertEquals(
                DriveSessionOutcome(dongle.address, NOW, NOW + 4 * MINUTE, 3, 8, DriveSessionEnd.USER_STOPPED),
                outcome,
            )
            assertEquals(
                DriveSessionStatus(dongle.address, NOW, 3, 8, NOW + 4 * MINUTE, DriveSessionEnd.USER_STOPPED),
                recorded,
            )
            assertEquals(1, links.attempts)
            assertTrue(links.opened.single().closed)
            assertFalse(locks.of(dongle.address).isLocked)
            assertEquals(emptySet<String>(), sessions.running())
        }

    @Test
    fun `rows a round leaves open get the age-seal upload a sync would schedule`() =
        runTest {
            settings.update { it.copy(maxBatchAgeMs = 5 * MINUTE, uploadOverCellular = true) }
            val session = start(session(LoggerLinks(this)))

            assertEquals(emptyList<Pair<Boolean, Boolean>>(), work.uploads, "nothing sealed yet")
            assertEquals(listOf(5 * MINUTE to true), work.sealUploads)

            log(1)
            advanceTimeBy(2 * MINUTE)
            runCurrent()
            assertEquals(
                listOf(5 * MINUTE to true, 3 * MINUTE to true),
                work.sealUploads,
                "still due 5 min after the oldest open row was stored",
            )

            sessions.stop(dongle.address, DriveStop.USER)
            session.await()
        }

    @Test
    fun `the session ends once no row was stored for the idle time`() =
        runTest {
            val session = start(session(LoggerLinks(this)))
            advanceTimeBy(3 * MINUTE)
            log(1)

            val outcome = session.await()

            // Rows stored at 0 and at the 4-minute round; rounds at 0, 2, ..., 12, none at 14.
            assertEquals(DriveSessionEnd.IDLE, outcome.end)
            assertEquals(NOW + 14 * MINUTE, outcome.endedAtMs)
            assertEquals(7, outcome.rounds)
            assertEquals(6, outcome.rowsStored)
        }

    @Test
    fun `a streaming device has each emission kept as a session page, and empty ones count as rounds`() =
        runTest {
            val source =
                StreamSource {
                    emit(listOf(row(NOW + 100, 1), row(NOW + 200, 2)))
                    delay(2_000)
                    emit(emptyList())
                    delay(2_000)
                    emit(listOf(row(NOW + 4_100, 3)))
                    awaitCancellation()
                }
            val session = start(session(LoggerLinks(this), streaming(source)))
            advanceTimeBy(5_000)

            val elm = "elm327:" + dongle.address
            assertEquals(DriveSessionStatus(dongle.address, NOW, rounds = 3, rows = 3), recorded)
            assertEquals("session:$NOW:2", store.cursor(elm))
            assertEquals(
                marker("elm327/$NOW"),
                store
                    .pending("sink")
                    .first()
                    .lines
                    .first(),
            )
            assertEquals(3, storedRows().size)
            assertTrue(storedRows().all { it.endsWith(""""ts_quality":"device"}""") })
            assertEquals(2, work.uploads.size, "one upload per emission that stored rows")

            // A stream keeps nothing on the device, so the car's disconnect takes no last round.
            sessions.stop(dongle.address, DriveStop.CAR_DISCONNECTED)
            val outcome = session.await()

            assertEquals(DriveSessionEnd.CAR_DISCONNECTED, outcome.end)
            assertEquals(3, outcome.rounds)
        }

    @Test
    fun `a streaming device that keeps emitting is ended by the session cap`() =
        runTest {
            settings.update { it.copy(driveMaxSessionMinutes = 30) }
            val source =
                StreamSource {
                    var i = 0
                    while (true) {
                        emit(listOf(row(NOW + currentTime, i++)))
                        delay(70_000)
                    }
                }

            val outcome = start(session(LoggerLinks(this), streaming(source))).await()

            // Emissions at 0, 70 s, ..., 1750 s: 26 before the 30-minute cap.
            assertEquals(
                DriveSessionOutcome(dongle.address, NOW, NOW + 30 * MINUTE, 26, 26, DriveSessionEnd.SESSION_CAP),
                outcome,
            )
        }

    @Test
    fun `a stream that ends is a lost link, reopened up to two times before the session gives up`() =
        runTest {
            val source = StreamSource { emit(listOf(row(NOW + currentTime, 1))) }
            val links = LoggerLinks(this, Open.Up(), Open.Up(), Open.Fail)

            val outcome = start(session(links, streaming(source))).await()

            assertEquals(DriveSessionEnd.LINK_LOST, outcome.end)
            assertEquals(4, links.attempts, "the first link, one reopened, then two failed attempts")
            assertEquals(2, outcome.rounds)
            assertEquals(NOW + 3 * DriveSession.REOPEN_DELAY.inWholeMilliseconds, outcome.endedAtMs)
            assertTrue(links.opened.all { it.closed })
            assertTrue(
                status.status.value.lastError!!
                    .startsWith("drive session: link lost"),
            )
        }

    @Test
    fun `a pulled link that drops is reopened and pulled at once, and given up after two failed reopens`() =
        runTest {
            val links = LoggerLinks(this, Open.Up(dropAfterExport = 1), Open.Up(dropAfterExport = 1), Open.Fail)
            val session = start(session(links))
            log(2)

            val outcome = session.await()

            // Round 1 at 0; round 2 at 2 min loses the link; reopened 10 s later, round 3 stores
            // the 2 rows; round 4 two minutes later loses it again; both reopens fail.
            assertEquals(DriveSessionEnd.LINK_LOST, outcome.end)
            assertEquals(4, outcome.rounds)
            assertEquals(7, outcome.rowsStored)
            assertEquals(7, storedRows().toSet().size, "every row exactly once")
            assertEquals(4, links.attempts)
            assertEquals(NOW + 4 * MINUTE + 30_000, outcome.endedAtMs)
        }

    @Test
    fun `the car disconnecting takes one last round on the open link, then ends the session`() =
        runTest {
            val session = start(session(LoggerLinks(this)))
            advanceTimeBy(30_000)
            log(2)
            advanceTimeBy(30_000)

            assertTrue(sessions.stop(dongle.address, DriveStop.CAR_DISCONNECTED))
            val outcome = session.await()

            assertEquals(
                DriveSessionOutcome(dongle.address, NOW, NOW + MINUTE, 2, 7, DriveSessionEnd.CAR_DISCONNECTED),
                outcome,
            )
            assertEquals(7, storedRows().size, "the last round landed the trip's last rows")
        }

    @Test
    fun `pull now runs a round at once, and the next one follows an interval later`() =
        runTest {
            val session = session(LoggerLinks(this))
            val running = start(session)
            advanceTimeBy(30_000)
            log(1)

            assertTrue(session.pullNow())
            runCurrent()
            assertEquals(DriveSessionStatus(dongle.address, NOW, rounds = 2, rows = 6), recorded)

            advanceTimeBy(2 * MINUTE - 1)
            assertEquals(2, recorded.rounds)
            advanceTimeBy(1)
            runCurrent()
            assertEquals(3, recorded.rounds)

            sessions.stop(dongle.address, DriveStop.USER)
            running.await()
            assertFalse(session.pullNow(), "nothing runs any more")
        }

    @Test
    fun `while a session holds the link an automatic sync is a no-op and a manual one becomes a round`() =
        runTest {
            val running = start(session(LoggerLinks(this)))
            val job =
                SyncJob(
                    devices,
                    LinkFactory { _, _ -> fail("a second link was opened") },
                    store,
                    status,
                    clock = clock,
                    locks = locks,
                    zone = { ZoneOffset.UTC },
                    sessions = sessions,
                )
            log(1)

            assertEquals(SyncOutcome.AlreadyRunning, job.run(dongle.address, sealEverything))
            assertEquals(1, recorded.rounds)
            assertEquals(
                SyncOutcome.HandedToSession,
                job.run(dongle.address.lowercase(), sealEverything, manual = true),
            )
            runCurrent()
            assertEquals(DriveSessionStatus(dongle.address, NOW, rounds = 2, rows = 6), recorded)

            sessions.stop(dongle.address, DriveStop.USER)
            running.await()
            val lock = locks.of(dongle.address)
            assertTrue(lock.tryLock())
            assertEquals(
                SyncOutcome.AlreadyRunning,
                job.run(dongle.address, sealEverything, manual = true),
                "a lock held by something other than a session is still a no-op",
            )
            lock.unlock()
        }

    @Test
    fun `a device without a live mode, an unpaired one and one already in a session get no session`() =
        runTest {
            val noLink = LinkFactory { _, _ -> fail("opened a link") }

            assertEquals(DriveSessionEnd.NOT_LIVE, session(noLink).run(ring.address).end)
            assertEquals(DriveSessionEnd.NOT_LIVE, session(noLink).run("AA:BB:CC:DD:EE:09").end)

            val first = start(session(LoggerLinks(this)))
            assertEquals(DriveSessionEnd.BUSY, session(noLink).run(dongle.address).end)
            assertEquals(1, recorded.rounds, "the running session is untouched")

            sessions.stop(dongle.address, DriveStop.USER)
            assertEquals(DriveSessionEnd.USER_STOPPED, first.await().end)
        }

    @Test
    fun `a device that cannot be reached ends the session at once and records why`() =
        runTest {
            val links = LoggerLinks(this, Open.Fail)

            val outcome = start(session(links)).await()

            assertEquals(DriveSessionOutcome(dongle.address, NOW, NOW, 0, 0, DriveSessionEnd.UNREACHABLE), outcome)
            assertEquals(1, links.attempts)
            assertEquals(DriveSessionStatus(dongle.address, NOW, 0, 0, NOW, DriveSessionEnd.UNREACHABLE), recorded)
            assertTrue(
                status.status.value.lastError!!
                    .startsWith("drive session: device unreachable"),
            )
            assertFalse(locks.of(dongle.address).isLocked)
        }
}
