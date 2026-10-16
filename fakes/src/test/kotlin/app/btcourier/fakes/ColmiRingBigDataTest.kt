package app.btcourier.fakes

import app.btcourier.core.source.colmi.ColmiBigData
import app.btcourier.core.source.colmi.ColmiRingSource
import app.btcourier.core.transport.GattChannel
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.IOException
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * [FakeColmiRing]'s big-data channel → [ColmiRingSource]. Expected rows follow the published
 * layouts (PulseLoopiOS, Gadgetbridge YawellRing): SpO2 `(min, max)` per hour, sleep sessions
 * filed under the day they ended with start/end as minutes of that day, temperature per half
 * hour as `b / 10 + 20` °C; days counted back from the phone's today; stamps in the phone's zone.
 */
class ColmiRingBigDataTest {
    private val zone = ZoneId.of("America/Los_Angeles")
    private val now = LocalDateTime.of(2026, 10, 15, 9, 30, 5)
    private val nowMs = ms(now)
    private val today = LocalDate.of(2026, 10, 15)
    private val day1 = LocalDate.of(2026, 10, 13)
    private val day2 = LocalDate.of(2026, 10, 14)

    /** The Ring 2 Pro night PulseLoopiOS captured: 02:30 to 09:14, 404 minutes. */
    private val capturedNight =
        FakeColmiRing.Sleep(
            start = 150,
            end = 554,
            stages =
                listOf(
                    2 to 36,
                    3 to 30,
                    2 to 15,
                    4 to 17,
                    2 to 33,
                    3 to 16,
                    2 to 23,
                    4 to 19,
                    2 to 51,
                    3 to 11,
                    2 to 56,
                    4 to 17,
                    2 to 32,
                    3 to 24,
                    2 to 24,
                ),
        )

    private val stageNames = mapOf(2 to "light", 3 to "deep", 4 to "rem", 5 to "awake")

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

    private fun sleepRow(
        start: Long,
        end: Long,
        stages: List<Pair<Int, Int>>,
        light: Int = 0,
        deep: Int = 0,
        rem: Int = 0,
        awake: Int = 0,
    ): String {
        val list =
            stages.joinToString(
                ",",
            ) { (code, minutes) -> """{"stage":"${stageNames[code]}","minutes":$minutes}""" }
        val totals = """{"light":$light,"deep":$deep,"rem":$rem,"awake":$awake}"""
        return row(start, "colmi.sleep", """{"start_ts":$start,"end_ts":$end,"stages":[$list],"minutes":$totals}""")
    }

    private fun ring(
        bigDataChannel: Boolean = true,
        silent: Set<Int> = emptySet(),
    ) = FakeColmiRing(
        today = today,
        heartRate = mapOf(day1 to IntArray(288).apply { this[0] = 60 }),
        steps = mapOf(day1 to mapOf(1 to 120)),
        batteryLevel = 87,
        spo2 =
            mapOf(
                day1 to mapOf(3 to (95 to 99), 23 to (90 to 97)),
                day2 to mapOf(0 to (96 to 98)),
                // Today's big-data rows wait for the day to end, so this is no row here.
                today to mapOf(1 to (97 to 99)),
            ),
        sleep =
            mapOf(
                day1 to
                    listOf(
                        // 23:00 the evening before to 07:00; one stage is at most 255 minutes.
                        FakeColmiRing.Sleep(
                            start = 1380,
                            end = 420,
                            stages =
                                listOf(
                                    3 to 60,
                                    2 to 180,
                                    2 to 180,
                                    5 to 60,
                                ),
                        ),
                        // A nap, 13:00 to 13:30.
                        FakeColmiRing.Sleep(start = 780, end = 810, stages = listOf(5 to 10, 2 to 20)),
                    ),
                day2 to listOf(capturedNight),
            ),
        // 350 = 35.0 °C, 365 = 36.5 °C, 201 = 20.1 °C.
        temperature = mapOf(day1 to mapOf(0 to 350, 3 to 365), day2 to mapOf(47 to 201)),
        bigDataChannel = bigDataChannel,
        silentBigDataTypes = silent,
    )

    private fun source(ring: FakeColmiRing) = ColmiRingSource(ring.link, zone, phoneClock = { nowMs })

    /** The battery, heart-rate logging and revision rows every instance's first page ends with (fake defaults). */
    private val firstPageTail =
        listOf(
            row(nowMs, "colmi.battery", 87),
            row(nowMs, "colmi.hr_logging", """{"enabled":true,"interval_min":5}"""),
            row(
                nowMs,
                "colmi.device",
                """{"firmware":"${FakeColmiRing.FIRMWARE}","hardware":"${FakeColmiRing.HARDWARE}"}""",
            ),
        )

    private val day1UartRows =
        listOf(row(at(day1, 0, 0), "colmi.hr", 60), row(at(day1, 0, 15), "colmi.steps", 120))

    @Test
    fun `each day gets its SpO2, sleep and temperature rows in time order with the UART rows, from one request per type`() =
        runTest {
            val ring = ring()
            val source = source(ring)

            val first = source.pull("2026-10-12")
            assertEquals(
                listOf(
                    """{"_file":"colmi/2026-10-13"}""",
                    // Began at 23:00 on the 12th; filed under the 13th, when it ended.
                    sleepRow(
                        at(day1.minusDays(1), 23, 0),
                        at(day1, 7, 0),
                        listOf(3 to 60, 2 to 180, 2 to 180, 5 to 60),
                        light = 360,
                        deep = 60,
                        awake = 60,
                    ),
                    row(at(day1, 0, 0), "colmi.hr", 60),
                    row(at(day1, 0, 0), "colmi.temp", """{"c":35.0}"""),
                    row(at(day1, 0, 15), "colmi.steps", 120),
                    row(at(day1, 1, 30), "colmi.temp", """{"c":36.5}"""),
                    row(at(day1, 3, 0), "colmi.spo2", """{"min":95,"max":99}"""),
                    sleepRow(at(day1, 13, 0), at(day1, 13, 30), listOf(5 to 10, 2 to 20), light = 20, awake = 10),
                    row(at(day1, 23, 0), "colmi.spo2", """{"min":90,"max":97}"""),
                    *firstPageTail.toTypedArray(),
                ),
                first.lines,
            )

            val second = source.pull(first.nextCursor)
            assertEquals(
                listOf(
                    """{"_file":"colmi/2026-10-14"}""",
                    row(at(day2, 0, 0), "colmi.spo2", """{"min":96,"max":98}"""),
                    sleepRow(at(day2, 2, 30), at(day2, 9, 14), capturedNight.stages, light = 270, deep = 81, rem = 53),
                    row(at(day2, 23, 30), "colmi.temp", """{"c":20.1}"""),
                ),
                second.lines,
            )
            assertEquals(true, second.more, "today's slice follows")

            // One answer holds every day: each type was asked for once, on the first pull.
            assertEquals(listOf(0x2A, 0x27, 0x25), ring.bigDataRequests)
            assertEquals(0, ring.malformedWrites)
        }

    @Test
    fun `a type the ring never answers yields no rows and is not asked for again, and the day still completes`() =
        runTest {
            val ring = ring(silent = setOf(FakeColmiRing.BIG_DATA_TEMPERATURE))
            val source = source(ring)

            val first = source.pull("2026-10-12")
            assertEquals("2026-10-13", first.nextCursor)
            assertFalse(first.lines.any { "colmi.temp" in it })
            assertEquals(2, first.lines.count { "colmi.spo2" in it })
            assertEquals(2, first.lines.count { "colmi.sleep" in it })

            val second = source.pull(first.nextCursor)
            assertEquals(
                listOf("colmi.spo2", "colmi.sleep"),
                second.lines.drop(1).map {
                    it.substringAfter("\"param\":\"").substringBefore('"')
                },
            )
            assertEquals(1, ring.bigDataRequests.count { it == FakeColmiRing.BIG_DATA_TEMPERATURE })
        }

    @Test
    fun `a ring without the big-data service syncs its UART logs alone`() =
        runTest {
            val ring = ring(bigDataChannel = false)

            val first = source(ring).pull("2026-10-12")
            assertEquals(
                listOf("""{"_file":"colmi/2026-10-13"}""") + day1UartRows + firstPageTail,
                first.lines,
            )
            assertEquals(emptyList<Int>(), ring.bigDataRequests)
        }

    @Test
    fun `a link that drops during a big-data request fails the day`() =
        runTest {
            val ring = ring()
            ring.dropLinkOnBigDataRequest(FakeColmiRing.BIG_DATA_SLEEP)
            assertThrows<IOException> { source(ring).pull("2026-10-12") }
        }

    @Test
    fun `the fake frames an answer with length and CRC and splits it at the link MTU`() =
        runTest {
            val ring = ring()
            // The published SpO2 request.
            ring.link.write(
                byteArrayOf(0xBC.toByte(), 0x2A, 1, 0, 0xFF.toByte(), 0, 0xFF.toByte()),
                false,
                GattChannel.SECONDARY,
            )
            ring.link.drop()
            val chunks = ring.link.notifications(GattChannel.SECONDARY).toList()

            // Seven days of daysAgo + 24 (min, max) pairs: 343 bytes after the 6-byte header.
            val frame = chunks.fold(ByteArray(0)) { acc, c -> acc + c }
            assertEquals(6 + 7 * 49, frame.size)
            assertTrue(chunks.dropLast(1).all { it.size == 20 } && chunks.last().size <= 20)
            val u = { i: Int -> frame[i].toInt() and 0xFF }
            assertEquals(listOf(0xBC, 0x2A, 343 and 0xFF, 343 shr 8), (0 until 4).map(u))
            assertEquals(ColmiBigData.crc16Modbus(frame.copyOfRange(6, frame.size)), u(4) or (u(5) shl 8))
            assertEquals(listOf(6, 5, 4, 3, 2, 1, 0), (0 until 7).map { u(6 + it * 49) })
            // Today's hour 1 (97..99) sits in the last record.
            assertEquals(listOf(97, 99), listOf(u(6 + 6 * 49 + 3), u(6 + 6 * 49 + 4)))
        }
}
