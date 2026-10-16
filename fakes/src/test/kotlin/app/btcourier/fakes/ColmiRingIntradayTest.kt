package app.btcourier.fakes

import app.btcourier.core.source.Page
import app.btcourier.core.source.colmi.ColmiRingSource
import app.btcourier.core.store.DefaultStore
import app.btcourier.core.store.SealPolicy
import app.btcourier.core.sync.StopReason
import app.btcourier.core.sync.SyncReport
import app.btcourier.core.sync.SyncRunner
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.time.Duration.Companion.minutes

/**
 * [FakeColmiRing] → [ColmiRingSource] during the day: today is pulled in slices of closed slots.
 * A slot is closed once its time has passed (heart rate 5 minutes, steps a quarter hour, HRV half an
 * hour from its start); the cut is the phone's time floored to a 5-minute heart-rate slot. Each
 * pull is a new instance, as each sync is a new connection, against a ring whose today holds the
 * readings so far.
 */
class ColmiRingIntradayTest {
    private val zone = ZoneId.of("America/Los_Angeles")
    private val day1 = LocalDate.of(2026, 10, 15)
    private val day2 = LocalDate.of(2026, 10, 16)

    private fun ms(wall: LocalDateTime) = wall.atZone(zone).toInstant().toEpochMilli()

    private fun at(
        day: LocalDate,
        hour: Int,
        minute: Int,
    ) = ms(day.atTime(hour, minute))

    private fun row(
        ts: Long,
        param: String,
        value: Any,
    ) = """{"ts":$ts,"param":"$param","value":$value}"""

    private fun marker(day: LocalDate) = """{"_file":"colmi/$day"}"""

    private fun statusRows(nowMs: Long) =
        listOf(
            row(nowMs, "colmi.battery", 87),
            row(nowMs, "colmi.hr_logging", """{"enabled":true,"interval_min":5}"""),
            row(
                nowMs,
                "colmi.device",
                """{"firmware":"${FakeColmiRing.FIRMWARE}","hardware":"${FakeColmiRing.HARDWARE}"}""",
            ),
        )

    /** Day 1's heart rate by 5-minute slot, as the ring holds it at 09:30:05 (slot 114 is 09:30). */
    private val hrAt0930 = mapOf(0 to 55, 113 to 64, 114 to 66)

    /** And at 10:20:30: slot 114 kept its reading, 123 is 10:15 and 124 (10:20) is in progress. */
    private val hrAt1020 = hrAt0930 + mapOf(123 to 70, 124 to 71)

    /** Day 1 complete: the rest of the day, read after midnight. */
    private val hrWhole = hrAt1020 + mapOf(200 to 58, 287 to 52)

    // Quarter 37 is 09:15, 38 09:30, 40 10:00, 41 10:15, 95 23:45.
    private val stepsAt0930 = mapOf(37 to 200, 38 to 40)
    private val stepsAt1020 = mapOf(37 to 200, 38 to 60, 40 to 300, 41 to 90)
    private val stepsWhole = stepsAt1020 + mapOf(41 to 150, 95 to 30)

    // Half-hour slot 18 is 09:00, 19 09:30, 20 10:00, 47 23:30.
    private val hrvAt0930 = mapOf(18 to 41, 19 to 44)
    private val hrvAt1020 = hrvAt0930 + mapOf(20 to 48)
    private val hrvWhole = hrvAt1020 + mapOf(20 to 49, 47 to 60)

    private fun slots(
        size: Int,
        values: Map<Int, Int>,
    ) = IntArray(size).apply { values.forEach { (slot, v) -> this[slot] = v } }

    private fun ring(
        today: LocalDate,
        hr: Map<Int, Int>,
        steps: Map<Int, Int>,
        hrv: Map<Int, Int>,
    ) = FakeColmiRing(
        today = today,
        heartRate = mapOf(day1 to slots(288, hr)),
        steps = mapOf(day1 to steps),
        hrv = mapOf(day1 to slots(48, hrv)),
        batteryLevel = 87,
        // SpO2 at 03:00 and 11:00 and a night ending on day 1: big-data rows wait for the day to end.
        spo2 = mapOf(day1 to mapOf(3 to (95 to 99), 11 to (96 to 98))),
        sleep = mapOf(day1 to listOf(FakeColmiRing.Sleep(start = 60, end = 420, stages = listOf(2 to 240, 3 to 120)))),
    )

    private fun source(
        ring: FakeColmiRing,
        now: LocalDateTime,
    ) = ColmiRingSource(ring.link, zone, phoneClock = { ms(now) })

    private val morning = day1.atTime(9, 30, 5)
    private val later = day1.atTime(10, 20, 30)
    private val afterMidnight = day2.atTime(1, 0, 5)

    /** Day 1's sleep row, the session 01:00 to 07:00. */
    private val day1Sleep =
        row(
            at(day1, 1, 0),
            "colmi.sleep",
            """{"start_ts":${at(day1, 1, 0)},"end_ts":${at(day1, 7, 0)},""" +
                """"stages":[{"stage":"light","minutes":240},{"stage":"deep","minutes":120}],""" +
                """"minutes":{"light":240,"deep":120,"rem":0,"awake":0}}""",
        )

    @Test
    fun `the first pull of today emits the day's marker and the slots closed by the cut, and no big-data rows`() =
        runTest {
            val ring = ring(day1, hrAt0930, stepsAt0930, hrvAt0930)

            val page = source(ring, morning).pull("2026-10-14")

            assertEquals(
                listOf(
                    marker(day1),
                    row(at(day1, 0, 0), "colmi.hr", 55),
                    row(at(day1, 9, 0), "colmi.hrv", 41),
                    row(at(day1, 9, 15), "colmi.steps", 200),
                    row(at(day1, 9, 25), "colmi.hr", 64),
                ) + statusRows(ms(morning)),
                page.lines,
            )
            assertEquals("2026-10-15@09:30", page.nextCursor)
            assertEquals(false, page.more)
            assertEquals(emptyList<Int>(), ring.bigDataRequests)
        }

    @Test
    fun `a later pull emits only the slots closed since the cut, without a marker`() =
        runTest {
            val page = source(ring(day1, hrAt1020, stepsAt1020, hrvAt1020), later).pull("2026-10-15@09:30")

            // The quarter from 10:15 and the half hour from 10:00 are still open at the 10:20 cut.
            assertEquals(
                listOf(
                    row(at(day1, 9, 30), "colmi.hr", 66),
                    row(at(day1, 9, 30), "colmi.steps", 60),
                    row(at(day1, 9, 30), "colmi.hrv", 44),
                    row(at(day1, 10, 0), "colmi.steps", 300),
                    row(at(day1, 10, 15), "colmi.hr", 70),
                ) + statusRows(ms(later)),
                page.lines,
            )
            assertEquals("2026-10-15@10:20", page.nextCursor)
            assertEquals(false, page.more)
        }

    @Test
    fun `with no slot closed since the cut the page is empty and keeps the cursor`() =
        runTest {
            val sameSlot = day1.atTime(9, 34, 59)
            assertEquals(
                Page(emptyList(), "2026-10-15@09:30", more = false),
                source(ring(day1, hrAt0930, stepsAt0930, hrvAt0930), sameSlot).pull("2026-10-15@09:30"),
            )

            // Just after midnight nothing of the new day has closed: yesterday whole stays the cursor.
            val ring = ring(day2, hrWhole, stepsWhole, hrvWhole)
            assertEquals(
                Page(emptyList(), "2026-10-15", more = false),
                source(ring, day2.atTime(0, 4, 59)).pull("2026-10-15"),
            )
        }

    @Test
    fun `after midnight the cut day is finished with its remaining slots and big-data rows, then today's slice follows`() =
        runTest {
            val ring = ring(day2, hrWhole, stepsWhole, hrvWhole)
            val source = source(ring, afterMidnight)

            val finished = source.pull("2026-10-15@10:20")
            assertEquals(
                listOf(
                    day1Sleep,
                    row(at(day1, 3, 0), "colmi.spo2", """{"min":95,"max":99}"""),
                    row(at(day1, 10, 0), "colmi.hrv", 49),
                    row(at(day1, 10, 15), "colmi.steps", 150),
                    row(at(day1, 10, 20), "colmi.hr", 71),
                    row(at(day1, 11, 0), "colmi.spo2", """{"min":96,"max":98}"""),
                    row(at(day1, 16, 40), "colmi.hr", 58),
                    row(at(day1, 23, 30), "colmi.hrv", 60),
                    row(at(day1, 23, 45), "colmi.steps", 30),
                    row(at(day1, 23, 55), "colmi.hr", 52),
                ) + statusRows(ms(afterMidnight)),
                finished.lines,
            )
            assertEquals("2026-10-15", finished.nextCursor)
            assertEquals(true, finished.more)

            // Day 2 has no readings yet: its slice is its marker, cut at 01:00.
            assertEquals(Page(listOf(marker(day2)), "2026-10-16@01:00", more = false), source.pull(finished.nextCursor))
        }

    private var storeNow = morning

    private val store = DefaultStore.inMemory(clock = { ms(storeNow) }, sinkIds = { setOf("sink") })

    /** One sync into [store] at [now], sealed as the app seals after every sync. */
    private suspend fun sync(
        ring: FakeColmiRing,
        now: LocalDateTime,
    ): SyncReport {
        storeNow = now
        return SyncRunner("colmi", source(ring, now), store).run().also { store.seal(SealPolicy(), ms(now)) }
    }

    @Test
    fun `a day's slices and its completion reach the sink as that day's file with every row once`() =
        runTest {
            store.appendPage("colmi", "2026-10-14", emptyList())

            assertEquals(
                StopReason.CaughtUp,
                sync(ring(day1, hrAt0930, stepsAt0930, hrvAt0930), morning).stoppedBecause,
            )
            assertEquals(StopReason.CaughtUp, sync(ring(day1, hrAt1020, stepsAt1020, hrvAt1020), later).stoppedBecause)
            val next = sync(ring(day2, hrWhole, stepsWhole, hrvWhole), afterMidnight)
            assertEquals(StopReason.CaughtUp, next.stoppedBecause)
            assertEquals(2, next.pagesPulled, "day 1's rest, then day 2's slice")
            assertEquals("2026-10-16@01:00", store.cursor("colmi"))

            // Both slices were sealed together by age at the second sync. Day 2's slice has no rows
            // to store yet, so the day's rest is sealed by age too, as the next sync's seal does.
            // Each batch is day 1's file: its marker opens the first and is restated on the second.
            store.seal(SealPolicy(), ms(afterMidnight.plusMinutes(30)))
            val batches = store.pending("sink")
            val lines = batches.flatMap { it.lines }
            assertEquals(listOf(marker(day1), marker(day1)), lines.filter { it.startsWith("""{"_file"""") })
            assertEquals(listOf(marker(day1), marker(day1)), batches.map { it.lines.first() })
            val rows =
                lines
                    .filterNot {
                        it.startsWith(
                            """{"_file"""",
                        )
                    }.map { it.substringBefore(""","ts_raw"""") + "}" }
            val expected =
                listOf(
                    row(at(day1, 0, 0), "colmi.hr", 55),
                    row(at(day1, 9, 0), "colmi.hrv", 41),
                    row(at(day1, 9, 15), "colmi.steps", 200),
                    row(at(day1, 9, 25), "colmi.hr", 64),
                    row(at(day1, 9, 30), "colmi.hr", 66),
                    row(at(day1, 9, 30), "colmi.steps", 60),
                    row(at(day1, 9, 30), "colmi.hrv", 44),
                    row(at(day1, 10, 0), "colmi.steps", 300),
                    row(at(day1, 10, 15), "colmi.hr", 70),
                    day1Sleep,
                    row(at(day1, 3, 0), "colmi.spo2", """{"min":95,"max":99}"""),
                    row(at(day1, 10, 0), "colmi.hrv", 49),
                    row(at(day1, 10, 15), "colmi.steps", 150),
                    row(at(day1, 10, 20), "colmi.hr", 71),
                    row(at(day1, 11, 0), "colmi.spo2", """{"min":96,"max":98}"""),
                    row(at(day1, 16, 40), "colmi.hr", 58),
                    row(at(day1, 23, 30), "colmi.hrv", 60),
                    row(at(day1, 23, 45), "colmi.steps", 30),
                    row(at(day1, 23, 55), "colmi.hr", 52),
                ) + statusRows(ms(morning)) + statusRows(ms(later)) + statusRows(ms(afterMidnight))
            assertEquals(expected.sorted(), rows.sorted())
        }

    @Test
    fun `a cut day older than the ring's history clamps to the oldest day it keeps, like a whole day`() =
        runTest {
            val ring = ring(day1, hrAt0930, stepsAt0930, hrvAt0930)
            // The ring keeps 2026-10-08 (today - 7) to today.
            for (cursor in listOf("2026-10-01@12:00", "2026-10-07@23:55")) {
                val page = source(ring, morning).pull(cursor)
                assertEquals(marker(LocalDate.of(2026, 10, 8)), page.lines.first(), cursor)
                assertEquals("2026-10-08", page.nextCursor, cursor)
                assertEquals(true, page.more, cursor)
            }

            // The oldest day itself is still on the ring, so its rest is finished: no marker.
            val oldest = source(ring, morning).pull("2026-10-08@12:00")
            assertEquals(statusRows(ms(morning)), oldest.lines)
            assertEquals("2026-10-08", oldest.nextCursor)
        }

    @Test
    fun `a cursor that is neither form fails the pull`() =
        runTest {
            for (cursor in listOf("2026-10-15@9:30", "2026-10-15@", "2026-10-15@25:00", "1790000000:42")) {
                assertThrows<IllegalArgumentException>(cursor) {
                    source(ring(day1, hrAt0930, stepsAt0930, hrvAt0930), morning).pull(cursor)
                }
            }
        }

    @Test
    fun `an instance is due its own interval after the cursor's cut`() {
        val source = ColmiRingSource(FakeGattLink(mtu = 23), zone, intradayInterval = 30.minutes)

        assertEquals(at(day1, 10, 0), source.nextDueAtMs("2026-10-15@09:30"))
        assertEquals(at(day2, 0, 30), source.nextDueAtMs("2026-10-15"))
    }
}
