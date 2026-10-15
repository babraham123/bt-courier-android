package app.btcourier.fakes

import app.btcourier.core.source.colmi.ColmiRingSource
import app.btcourier.core.store.DefaultStore
import app.btcourier.core.store.SealPolicy
import app.btcourier.core.store.Store
import app.btcourier.core.sync.StopReason
import app.btcourier.core.sync.SyncReport
import app.btcourier.core.sync.SyncRunner
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * [FakeColmiRing] → [ColmiRingSource] (→ [SyncRunner] → [DefaultStore]). Expected rows follow the
 * published protocol: heart rate every 5 minutes from midnight, steps per quarter hour, stamps in
 * the phone's zone because the ring is set to the phone's wall clock.
 */
class ColmiRingSourceTest {
    // A zone off UTC, so a wrong frame (UTC vs wall clock) moves every stamp.
    private val zone = ZoneId.of("America/Los_Angeles")
    private val now = LocalDateTime.of(2026, 10, 15, 9, 30, 5)
    private val nowMs = ms(now)
    private val day1 = LocalDate.of(2026, 10, 13)
    private val day2 = LocalDate.of(2026, 10, 14)

    private fun ms(wall: LocalDateTime) = wall.atZone(zone).toInstant().toEpochMilli()

    private fun at(
        day: LocalDate,
        hour: Int,
        minute: Int,
    ) = ms(day.atTime(hour, minute))

    private fun row(
        ts: Long,
        param: String,
        value: Int,
    ) = """{"ts":$ts,"param":"$param","value":$value}"""

    private fun ring() =
        FakeColmiRing(
            today = LocalDate.of(2026, 10, 15),
            // day2 has no heart-rate log, so its answer is the 0xFF no-data packet.
            heartRate =
                mapOf(
                    day1 to
                        IntArray(288).apply {
                            this[0] = 60
                            this[8] = 61
                            this[9] = 62
                            this[287] = 70
                        },
                ),
            steps = mapOf(day1 to mapOf(1 to 120, 95 to 30), day2 to mapOf(40 to 500)),
            batteryLevel = 87,
        )

    private fun source(ring: FakeColmiRing) = ColmiRingSource(ring.link, zone, phoneClock = { nowMs })

    /** One sync into [store], sealed as the app seals after every sync. */
    private suspend fun sync(
        ring: FakeColmiRing,
        store: Store,
    ): SyncReport = SyncRunner("colmi", source(ring), store).run().also { store.seal(SealPolicy(), nowMs) }

    @Test
    fun `walks two days of history from the cursor to more false`() =
        runTest {
            val ring = ring()
            val source = source(ring)

            val first = source.pull("2026-10-12")
            // Only the 2 s settle before set-time takes (virtual) time; every answer is immediate.
            assertEquals(2_000L, currentTime)
            assertEquals(
                listOf(
                    """{"_file":"colmi/2026-10-13"}""",
                    row(at(day1, 0, 0), "colmi.hr", 60),
                    row(at(day1, 0, 15), "colmi.steps", 120),
                    row(at(day1, 0, 40), "colmi.hr", 61),
                    row(at(day1, 0, 45), "colmi.hr", 62),
                    row(at(day1, 23, 45), "colmi.steps", 30),
                    row(at(day1, 23, 55), "colmi.hr", 70),
                    row(nowMs, "colmi.battery", 87),
                ),
                first.lines,
            )
            assertEquals("2026-10-13", first.nextCursor)
            assertEquals(true, first.more)

            val second = source.pull(first.nextCursor)
            assertEquals(
                listOf("""{"_file":"colmi/2026-10-14"}""", row(at(day2, 10, 0), "colmi.steps", 500)),
                second.lines,
            )
            assertEquals("2026-10-14", second.nextCursor)
            assertEquals(false, second.more)

            // Today is never pulled: caught up means an empty page at the same cursor.
            val caughtUp = source.pull(second.nextCursor)
            assertEquals(emptyList<String>(), caughtUp.lines)
            assertEquals("2026-10-14", caughtUp.nextCursor)
            assertEquals(false, caughtUp.more)

            assertEquals(listOf(now), ring.clockSets)
            assertEquals(0, ring.malformedWrites)
            val commands = ring.commands
            assertEquals(0x01, commands.first()[0].toInt())
            // Heart-rate requests carry the day's wall-clock midnight counted as UTC, little-endian.
            val hrDays =
                commands.filter { it[0].toInt() == 0x15 }.map { p ->
                    (0 until 4).fold(0L) { acc, i -> acc or ((p[1 + i].toLong() and 0xFF) shl (8 * i)) }
                }
            assertEquals(listOf(day1, day2).map { it.atStartOfDay().toEpochSecond(ZoneOffset.UTC) }, hrDays)
            // Steps requests address days by offset from the ring's today.
            assertEquals(listOf(2, 1), commands.filter { it[0].toInt() == 0x43 }.map { it[1].toInt() })
        }

    @Test
    fun `a null or too-old cursor starts at the oldest day the ring keeps`() =
        runTest {
            val oldest = """{"_file":"colmi/2026-10-09"}"""
            assertEquals(oldest, source(ring()).pull(null).lines.first())
            assertEquals(oldest, source(ring()).pull("2026-09-01").lines.first())
            assertEquals("2026-10-09", source(ring()).pull("2026-09-01").nextCursor)
        }

    @Test
    fun `a ring that stops answering fails the pull as a link error`() =
        runTest {
            val silent = FakeGattLink(mtu = 23)
            val failure =
                runCatching {
                    ColmiRingSource(
                        silent,
                        zone,
                        phoneClock = { nowMs },
                    ).pull("2026-10-12")
                }.exceptionOrNull()
            assertInstanceOf(IOException::class.java, failure)
        }

    @Test
    fun `set-time waits for the ring to settle and for its echo, and a lost echo sets the clock again on the next pull`() =
        runTest {
            val ring = ring()
            ring.silentCommands += FakeColmiRing.CMD_SET_TIME
            val source = source(ring)

            val failure = runCatching { source.pull("2026-10-12") }.exceptionOrNull()
            assertInstanceOf(IOException::class.java, failure)
            // Gadgetbridge's 2 s settle, then the 5 s answer timeout; no log was asked for.
            assertEquals(7_000L, currentTime)
            assertEquals(listOf(0x01), ring.commands.map { it[0].toInt() })

            ring.silentCommands.clear()
            assertEquals("2026-10-13", source.pull("2026-10-12").nextCursor)
            // The settle wait is once per connection: the retry goes straight to set-time.
            assertEquals(7_000L, currentTime)
            assertEquals(listOf(now, now), ring.clockSets)
        }

    @Test
    fun `a sync stores one file per day, resumes at the stored cursor and rates stamps device`() =
        runTest {
            val ring = ring()
            val store = DefaultStore.inMemory(clock = { nowMs }, sinkIds = { setOf("sink") })
            store.appendPage("colmi", "2026-10-12", emptyList())

            val report = sync(ring, store)
            assertEquals(StopReason.CaughtUp, report.stoppedBecause)
            assertEquals(2, report.pagesPulled)
            assertEquals(8L, report.linesStored)
            assertEquals("2026-10-14", store.cursor("colmi"))

            // The day-1 batch is sealed by day 2's marker; every row keeps its stamp as `device`.
            val day1Batch = store.pending("sink").first()
            assertEquals("""{"_file":"colmi/2026-10-13"}""", day1Batch.lines.first())
            val rows = day1Batch.lines.drop(1)
            assertEquals(7, rows.size)
            assertTrue(rows.all { it.contains("\"ts_quality\":\"device\"") }, rows.joinToString("\n"))

            val again = sync(ring, store)
            assertEquals(StopReason.CaughtUp, again.stoppedBecause)
            assertEquals(1, again.pagesPulled)
            assertEquals(0L, again.linesStored)
        }

    @Test
    fun `a packet lost mid-answer interrupts the sync without moving the cursor, and the next sync stores the whole day`() =
        runTest {
            val ring = ring()
            val store = DefaultStore.inMemory(clock = { nowMs }, sinkIds = { setOf("sink") })
            store.appendPage("colmi", "2026-10-12", emptyList())

            // Day 1's heart-rate answer is 24 packets; sub-type 2 holds slot 9 (62 bpm) and 23 ends it.
            ring.corruptNextHeartRatePacket(2)
            val hrLost = sync(ring, store)
            assertInstanceOf(StopReason.LinkLost::class.java, hrLost.stoppedBecause)
            assertEquals(0L, hrLost.linesStored)
            assertEquals("2026-10-12", store.cursor("colmi"))

            // Day 1's steps answer is indexes 0 (quarter 1) and 1 (quarter 95, the last).
            ring.corruptNextStepsPacket(0)
            val stepsLost = sync(ring, store)
            assertInstanceOf(StopReason.LinkLost::class.java, stepsLost.stoppedBecause)
            assertEquals(0L, stepsLost.linesStored)
            assertEquals("2026-10-12", store.cursor("colmi"))
            assertEquals(emptyList<Any>(), store.pending("sink"))

            val recovered = sync(ring, store)
            assertEquals(StopReason.CaughtUp, recovered.stoppedBecause)
            assertEquals("2026-10-14", store.cursor("colmi"))
            val day1Batch = store.pending("sink").single()
            assertEquals("""{"_file":"colmi/2026-10-13"}""", day1Batch.lines.first())
            // Stored rows are the source's with ts_raw and ts_quality appended.
            assertEquals(
                listOf(
                    row(at(day1, 0, 0), "colmi.hr", 60),
                    row(at(day1, 0, 15), "colmi.steps", 120),
                    row(at(day1, 0, 40), "colmi.hr", 61),
                    row(at(day1, 0, 45), "colmi.hr", 62),
                    row(at(day1, 23, 45), "colmi.steps", 30),
                    row(at(day1, 23, 55), "colmi.hr", 70),
                    row(nowMs, "colmi.battery", 87),
                ),
                day1Batch.lines.drop(1).map { it.substringBefore(""","ts_raw"""") + "}" },
            )
        }
}
