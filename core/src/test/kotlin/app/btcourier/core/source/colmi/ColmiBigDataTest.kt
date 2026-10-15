package app.btcourier.core.source.colmi

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * Big-data layouts from PulseLoopiOS (`ColmiEncoder.swift`, `ColmiDecoder.swift`, captures in
 * `ColmiDecoderTests.swift`) and Gadgetbridge YawellRing: `bc <type> <len u16 LE> <crc u16 LE>
 * <payload>`; sleep 0x27, SpO2 0x2A, temperature 0x25.
 */
class ColmiBigDataTest {
    private fun hex(s: String): ByteArray =
        s
            .split(' ')
            .filter { it.isNotEmpty() }
            .map { it.toInt(16).toByte() }
            .toByteArray()

    private fun bytes(vararg b: Int): ByteArray = ByteArray(b.size) { b[it].toByte() }

    /** An answer as the PulseLoopiOS captures frame it: zeros where the CRC goes. */
    private fun answer(
        type: Int,
        payload: ByteArray,
    ): ByteArray = bytes(0xBC, type, payload.size and 0xFF, payload.size shr 8, 0, 0) + payload

    /**
     * A Ring 2 Pro night (PulseLoopiOS `testRing2ProCapturedNightDecodes`): one record, today,
     * 34 bytes, 02:30 to 09:14, then 15 stage pairs.
     */
    private val capturedNight =
        hex(
            "01 00 22 96 00 2a 02 " +
                "02 24 03 1e 02 0f 04 11 02 21 03 10 02 17 04 13 02 33 03 0b 02 38 04 11 02 20 03 18 02 18",
        )

    @Test
    fun `history requests reproduce the published bytes`() {
        assertArrayEquals(hex("bc 27 01 00 ff 00 ff"), ColmiBigData.historyRequest(ColmiBigData.TYPE_SLEEP))
        assertArrayEquals(hex("bc 2a 01 00 ff 00 ff"), ColmiBigData.historyRequest(ColmiBigData.TYPE_SPO2))
        assertArrayEquals(hex("bc 25 01 00 3e 81 02"), ColmiBigData.historyRequest(ColmiBigData.TYPE_TEMPERATURE))
        assertThrows<IllegalArgumentException> { ColmiBigData.historyRequest(0x43) }
    }

    @Test
    fun `the request CRC is CRC-16 MODBUS, little-endian after the length`() {
        // The catalogue check value of CRC-16/MODBUS over ASCII "123456789".
        assertEquals(0x4B37, ColmiBigData.crc16Modbus("123456789".toByteArray()))
        // 01 02 03 → 0x6161, worked by hand from the algorithm.
        assertArrayEquals(hex("bc 30 03 00 61 61 01 02 03"), ColmiBigData.frame(0x30, bytes(1, 2, 3)))
    }

    @Test
    fun `an answer split across notifications is whole at len plus 6 bytes`() {
        val frame = answer(ColmiBigData.TYPE_SLEEP, capturedNight)
        val a = ColmiBigDataAssembler()
        assertNull(a.feed(frame.copyOfRange(0, 20)))
        assertNull(a.feed(frame.copyOfRange(20, 40)))
        // Bytes past the announced length are not part of the answer.
        val whole = a.feed(frame.copyOfRange(40, frame.size) + bytes(0xEE, 0xEE))
        assertEquals(ColmiBigData.TYPE_SLEEP, whole?.type)
        assertArrayEquals(capturedNight, whole?.payload)
    }

    @Test
    fun `a short answer of another type inside a long one completes on its own and leaves the long one intact`() {
        val spo2 = answer(ColmiBigData.TYPE_SPO2, bytes(0, 96, 98) + ByteArray(46))
        val sleep = answer(ColmiBigData.TYPE_SLEEP, bytes(0))
        val a = ColmiBigDataAssembler()

        assertNull(a.feed(spo2.copyOfRange(0, 12)))
        val inner = a.feed(sleep)
        assertEquals(ColmiBigData.TYPE_SLEEP, inner?.type)
        assertArrayEquals(bytes(0), inner?.payload)
        assertNull(a.feed(spo2.copyOfRange(12, 40)))
        val outer = a.feed(spo2.copyOfRange(40, spo2.size))
        assertEquals(ColmiBigData.TYPE_SPO2, outer?.type)
        assertArrayEquals(spo2.copyOfRange(6, spo2.size), outer?.payload)
    }

    @Test
    fun `continuations without a header and headers of unknown types are not answers`() {
        val a = ColmiBigDataAssembler()
        assertNull(a.feed(bytes(1, 2, 3, 4, 5, 6, 7)))
        assertNull(a.feed(answer(0x30, bytes(1))))
        // A header that restarts its type drops what the earlier one collected.
        val temperature = answer(ColmiBigData.TYPE_TEMPERATURE, ByteArray(50) { 1 })
        assertNull(a.feed(temperature.copyOfRange(0, 20)))
        assertNull(a.feed(temperature.copyOfRange(0, 20)))
        assertNull(a.feed(temperature.copyOfRange(20, 40)))
        assertArrayEquals(ByteArray(50) { 1 }, a.feed(temperature.copyOfRange(40, temperature.size))?.payload)
    }

    @Test
    fun `the captured Ring 2 Pro night is one session from 02 30 to 09 14 with its stage split`() {
        val session = ColmiBigData.parseSleep(capturedNight).single()
        assertEquals(0, session.daysAgo)
        assertEquals(150, session.startMinute)
        assertEquals(554, session.endMinute)
        assertEquals(150, session.startOffsetMinutes)
        assertEquals(15, session.stages.size)
        assertEquals(ColmiSleepStage(2, 36), session.stages.first())
        val minutes = session.stages.groupBy { it.label }.mapValues { (_, s) -> s.sumOf { it.minutes } }
        // The split PulseLoopiOS, its Android build and QRing all showed: 6h 44m in total.
        assertEquals(mapOf("light" to 270, "deep" to 81, "rem" to 53), minutes)
        assertEquals(554 - 150, minutes.values.sum())
    }

    @Test
    fun `sleep records parse by their byte count, start the evening before when start exceeds end, and stop at a truncated one`() {
        // 1 day ago, 23:00 (0x0564) to 07:00 (0x01A4): deep 60, awake 0 (dropped), light 200.
        val evening = bytes(1, 10, 0x64, 0x05, 0xA4, 0x01, 3, 60, 5, 0, 2, 200)
        // Today, 13:00 (0x030C) to 13:30 (0x032A): awake 10, then a code no reference names.
        val nap = bytes(0, 8, 0x0C, 0x03, 0x2A, 0x03, 5, 10, 6, 20)

        val sessions = ColmiBigData.parseSleep(bytes(2) + evening + nap)
        assertEquals(
            listOf(
                // 1380 (23:00) > 420 (07:00): it began an hour before midnight.
                ColmiSleepSession(1, 1380, 420, listOf(ColmiSleepStage(3, 60), ColmiSleepStage(2, 200))),
                ColmiSleepSession(0, 780, 810, listOf(ColmiSleepStage(5, 10), ColmiSleepStage(6, 20))),
            ),
            sessions,
        )
        assertEquals(-60, sessions[0].startOffsetMinutes)
        assertEquals(listOf("deep", "light", "awake", "stage_6"), sessions.flatMap { s -> s.stages.map { it.label } })

        // The header says three records, the last is cut off: the whole ones still parse.
        assertEquals(sessions, ColmiBigData.parseSleep(bytes(3) + evening + nap + nap.copyOf(7)))
        // A record whose stages are all zero minutes holds no session.
        assertEquals(emptyList<ColmiSleepSession>(), ColmiBigData.parseSleep(bytes(1, 0, 6, 0, 0, 10, 0, 2, 0)))
        assertEquals(emptyList<ColmiSleepSession>(), ColmiBigData.parseSleep(ByteArray(0)))
    }

    @Test
    fun `SpO2 is daysAgo then 24 hourly min-max pairs per day until today`() {
        val yesterday = ByteArray(49).also { it[0] = 1 }
        // Hour 3: 95..99; hour 23: 90..97; hour 5 has only a max, so no reading.
        yesterday[1 + 3 * 2] = 95
        yesterday[2 + 3 * 2] = 99
        yesterday[2 + 5 * 2] = 97
        yesterday[1 + 23 * 2] = 90
        yesterday[2 + 23 * 2] = 97
        val today =
            ByteArray(49).also {
                it[1] = 96
                it[2] = 98
            }
        // Anything after today's record is not read.
        val trailing = bytes(1, 80, 81)

        assertEquals(
            listOf(ColmiSpo2Hour(1, 3, 95, 99), ColmiSpo2Hour(1, 23, 90, 97), ColmiSpo2Hour(0, 0, 96, 98)),
            ColmiBigData.parseSpo2(yesterday + today + trailing),
        )
        // A truncated day keeps the hours that arrived whole.
        assertEquals(listOf(ColmiSpo2Hour(0, 0, 96, 98)), ColmiBigData.parseSpo2(today.copyOf(4)))
        assertEquals(emptyList<ColmiSpo2Hour>(), ColmiBigData.parseSpo2(ByteArray(0)))
    }

    @Test
    fun `temperature is daysAgo, an unknown byte, then 48 half-hour readings of b over 10 plus 20 degrees`() {
        // Two days; 150 = 35.0 °C at 00:00 yesterday, 165 = 36.5 °C at 01:30 and 1 = 20.1 °C at
        // 23:30 today.
        val yesterday =
            ByteArray(50).also {
                it[0] = 1
                it[1] = 0x1E
                it[2] = 150.toByte()
            }
        val today =
            ByteArray(50).also {
                it[1] = 0x1E
                it[2 + 3] = 165.toByte()
                it[2 + 47] = 1
            }

        assertEquals(
            listOf(ColmiTemperatureSlot(1, 0, 350), ColmiTemperatureSlot(0, 3, 365), ColmiTemperatureSlot(0, 47, 201)),
            ColmiBigData.parseTemperature(yesterday + today),
        )
    }

    @Test
    fun `a temperature answer shorter than one day holds nothing`() {
        assertEquals(emptyList<ColmiTemperatureSlot>(), ColmiBigData.parseTemperature(ByteArray(49) { 100 }))
        assertEquals(emptyList<ColmiTemperatureSlot>(), ColmiBigData.parseTemperature(ByteArray(0)))
    }
}
