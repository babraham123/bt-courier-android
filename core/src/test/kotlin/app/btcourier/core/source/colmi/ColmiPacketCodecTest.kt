package app.btcourier.core.source.colmi

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * Byte layouts from `colmi_r02_client` (tahnok), Gadgetbridge ColmiR0x and PulseLoopiOS: 16-byte
 * packets, byte 0 command, bytes 1..14 payload, byte 15 = sum(bytes 0..14) & 0xFF. Checksums
 * below are worked by hand.
 */
class ColmiPacketCodecTest {
    private fun hex(s: String): ByteArray =
        s
            .split(' ')
            .filter { it.isNotEmpty() }
            .map { it.toInt(16).toByte() }
            .toByteArray()

    /** A received packet with a correct checksum, for assembler input. */
    private fun answer(vararg bytes: Int): ByteArray =
        ColmiPacketCodec.packet(bytes[0], ByteArray(bytes.size - 1) { bytes[it + 1].toByte() })

    @Test
    fun `a packet zero-pads the payload and ends with the low byte of the sum`() {
        assertArrayEquals(hex("03 00 00 00 00 00 00 00 00 00 00 00 00 00 00 03"), ColmiPacketCodec.packet(0x03))
        // 0x15 + 0xFF + 0xFF = 0x213, so the checksum keeps only 0x13.
        assertArrayEquals(
            hex("15 FF FF 00 00 00 00 00 00 00 00 00 00 00 00 13"),
            ColmiPacketCodec.packet(0x15, byteArrayOf(-1, -1)),
        )
        assertThrows<IllegalArgumentException> { ColmiPacketCodec.packet(0x15, ByteArray(15)) }
    }

    @Test
    fun `requests have the published layouts`() {
        // Set time: BCD yy MM dd HH mm ss, then language 1 (English).
        assertArrayEquals(
            hex("01 26 10 15 09 30 05 01 00 00 00 00 00 00 00 8B"),
            ColmiPacketCodec.setTime(LocalDateTime.of(2026, 10, 15, 9, 30, 5)),
        )
        assertArrayEquals(hex("03 00 00 00 00 00 00 00 00 00 00 00 00 00 00 03"), ColmiPacketCodec.batteryRequest())
        // Heart-rate log: day start as 4-byte LE; 1_700_000_000 = 0x6553F100.
        assertArrayEquals(
            hex("15 00 F1 53 65 00 00 00 00 00 00 00 00 00 00 BE"),
            ColmiPacketCodec.heartRateLogRequest(1_700_000_000),
        )
        // Steps log: day offset, then colmi_r02_client's fixed 0F 00 5F 01.
        assertArrayEquals(
            hex("43 02 0F 00 5F 01 00 00 00 00 00 00 00 00 00 B4"),
            ColmiPacketCodec.stepsLogRequest(2),
        )
        // HRV log: days ago as 4-byte LE, 0..6 only.
        assertArrayEquals(
            hex("39 03 00 00 00 00 00 00 00 00 00 00 00 00 00 3C"),
            ColmiPacketCodec.hrvLogRequest(3),
        )
        assertArrayEquals(
            hex("39 06 00 00 00 00 00 00 00 00 00 00 00 00 00 3F"),
            ColmiPacketCodec.hrvLogRequest(6),
        )
        assertThrows<IllegalArgumentException> { ColmiPacketCodec.hrvLogRequest(7) }
        assertThrows<IllegalArgumentException> { ColmiPacketCodec.hrvLogRequest(-1) }
    }

    @Test
    fun `auto heart-rate setting reads with 16 01 and writes 16 02, 01 on or 02 off, then 5 to 60 minutes`() {
        assertArrayEquals(hex("16 01 00 00 00 00 00 00 00 00 00 00 00 00 00 17"), ColmiPacketCodec.autoHrRead())
        assertArrayEquals(
            hex("16 02 01 05 00 00 00 00 00 00 00 00 00 00 00 1E"),
            ColmiPacketCodec.autoHrWrite(enabled = true, intervalMinutes = 5),
        )
        // Off is 02, not 00; 60 = 0x3C.
        assertArrayEquals(
            hex("16 02 02 3C 00 00 00 00 00 00 00 00 00 00 00 56"),
            ColmiPacketCodec.autoHrWrite(enabled = false, intervalMinutes = 60),
        )
        assertThrows<IllegalArgumentException> { ColmiPacketCodec.autoHrWrite(true, 4) }
        assertThrows<IllegalArgumentException> { ColmiPacketCodec.autoHrWrite(true, 61) }
    }

    @Test
    fun `auto heart-rate read answer is on when byte 2 is 1, with byte 3 the minutes, and the write answer carries none`() {
        assertEquals(
            AutoHeartRate(enabled = true, intervalMinutes = 10),
            ColmiPacketCodec.parseAutoHr(answer(0x16, 1, 1, 10)),
        )
        assertEquals(
            AutoHeartRate(enabled = false, intervalMinutes = 5),
            ColmiPacketCodec.parseAutoHr(answer(0x16, 1, 2, 5)),
        )
        assertEquals(
            AutoHeartRate(enabled = false, intervalMinutes = 30),
            ColmiPacketCodec.parseAutoHr(answer(0x16, 1, 0, 30)),
        )
        assertNull(ColmiPacketCodec.parseAutoHr(answer(0x16, 2)))
        assertTrue(ColmiPacketCodec.isAutoHrWriteAnswer(answer(0x16, 2)))
        assertFalse(ColmiPacketCodec.isAutoHrWriteAnswer(answer(0x16, 1, 1, 10)))
        assertFalse(ColmiPacketCodec.isAutoHrWriteAnswer(answer(0x15, 2)))
    }

    @Test
    fun `HRV log takes 12 readings from byte 3 of sub-index 1 and 13 from byte 2 of 2 to 4, 48 half hours`() {
        val a = HrvLogAssembler()
        assertFalse(a.started)
        // Header: sub-index 0, byte 2 the packet count.
        assertNull(a.feed(answer(0x39, 0, 5)))
        assertTrue(a.started)
        // Byte 2 of sub-index 1 is not a reading; slots 0..11 follow.
        assertNull(a.feed(answer(0x39, 1, 0x7F, 40, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 41)))
        // Slots 12..24, 25..37, then 38..50 of which 48..50 are past the day.
        assertNull(a.feed(answer(0x39, 2, 42, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)))
        assertNull(a.feed(answer(0x39, 3, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 43)))
        val log = a.feed(answer(0x39, 4, 44, 0, 0, 0, 0, 0, 0, 0, 0, 45, 99, 99, 99))

        val expected =
            MutableList(48) { 0 }.apply {
                this[0] = 40
                this[11] = 41
                this[12] = 42
                this[37] = 43
                this[38] = 44
                this[47] = 45
            }
        assertEquals(expected, log)
    }

    @Test
    fun `HRV log waits for every sub-index 1 to 4 and ignores repeats and others`() {
        val a = HrvLogAssembler()
        val sub1 = answer(0x39, 1, 0, 50)
        assertNull(a.feed(sub1))
        assertNull(a.feed(answer(0x39, 2, 51)))
        // Sub-index 3 is lost: the last one alone does not complete the day.
        assertNull(a.feed(answer(0x39, 4, 53)))
        assertNull(a.feed(sub1))
        assertNull(a.feed(answer(0x39, 5, 99)))
        val log = a.feed(answer(0x39, 3, 52))

        val expected =
            MutableList(48) { 0 }.apply {
                this[0] = 50
                this[12] = 51
                this[25] = 52
                this[38] = 53
            }
        assertEquals(expected, log)
        assertEquals(emptyList<Int>(), HrvLogAssembler().feed(answer(0x39, 0xFF)))
    }

    @Test
    fun `validity needs 16 bytes and a matching checksum, and a notification splits into whole packets`() {
        val good = hex("03 57 01 00 00 00 00 00 00 00 00 00 00 00 00 5B")
        assertTrue(ColmiPacketCodec.isValid(good))
        assertFalse(ColmiPacketCodec.isValid(good.copyOf().also { it[15] = 0x5C }))
        assertFalse(ColmiPacketCodec.isValid(good.copyOf(15)))

        assertEquals(2, ColmiPacketCodec.packets(good + good).size)
        assertArrayEquals(good, ColmiPacketCodec.packets(good).single())
        assertTrue(ColmiPacketCodec.packets(good.copyOf(20)).isEmpty())
    }

    @Test
    fun `battery answer is level then charging`() {
        assertEquals(
            Battery(level = 87, charging = true),
            ColmiPacketCodec.parseBattery(hex("03 57 01 00 00 00 00 00 00 00 00 00 00 00 00 5B")),
        )
        assertEquals(Battery(level = 100, charging = false), ColmiPacketCodec.parseBattery(answer(0x03, 100, 0)))
    }

    @Test
    fun `heart-rate log assembles 9 readings from sub-type 1 and 13 from each later one`() {
        val a = HeartRateLogAssembler()
        // Header: 3 packets in the answer, a reading every 5 minutes.
        assertNull(a.feed(answer(0x15, 0, 3, 5)))
        val sub1 = answer(0x15, 1, 0x00, 0xF1, 0x53, 0x65, 60, 0, 0, 0, 0, 0, 0, 0, 61)
        assertNull(a.feed(sub1))
        // A repeated packet lands on the same slots instead of shifting the day.
        assertNull(a.feed(sub1))
        val log = a.feed(answer(0x15, 2, 62, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 200))

        val expected =
            MutableList(9 + 13) { 0 }.apply {
                this[0] = 60
                this[8] = 61
                this[9] = 62
                this[21] = 200
            }
        assertEquals(HeartRateLog(5, expected), log)
    }

    @Test
    fun `heart-rate log waits for every sub-type, not just the last`() {
        val a = HeartRateLogAssembler()
        // Header: 4 packets, so sub-types 1, 2 and 3 carry readings.
        assertNull(a.feed(answer(0x15, 0, 4, 5)))
        val sub1 = answer(0x15, 1, 0x00, 0xF1, 0x53, 0x65, 60, 0, 0, 0, 0, 0, 0, 0, 0)
        assertNull(a.feed(sub1))
        // Sub-type 2 is lost: the last sub-type alone does not complete the day.
        assertNull(a.feed(answer(0x15, 3, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 90)))
        // Nor does a repeat of one already seen standing in for the missing one.
        assertNull(a.feed(sub1))
        val log = a.feed(answer(0x15, 2, 70, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 80))

        // 9 readings in sub-type 1, then 13 each in sub-types 2 and 3: slots 0..34.
        val expected =
            MutableList(9 + 13 + 13) { 0 }.apply {
                this[0] = 60
                this[9] = 70
                this[21] = 80
                this[34] = 90
            }
        assertEquals(HeartRateLog(5, expected), log)
    }

    @Test
    fun `heart-rate and steps logs answer 0xFF for a day without data`() {
        assertEquals(HeartRateLog.EMPTY, HeartRateLogAssembler().feed(answer(0x15, 0xFF)))
        assertEquals(emptyList<StepsSample>(), StepsLogAssembler().feed(answer(0x43, 0xFF)))
    }

    @Test
    fun `steps log is a 0xF0 header then one packet per quarter hour until index count-1`() {
        val a = StepsLogAssembler()
        assertNull(a.feed(answer(0x43, 0xF0, 0x05, 0x01)))
        // 2026-10-13 (BCD), quarter, index, count, calories LE, steps LE, distance LE.
        assertNull(a.feed(answer(0x43, 0x26, 0x10, 0x13, 0, 0, 2, 6, 0, 120, 0, 84, 0)))
        val steps = a.feed(answer(0x43, 0x26, 0x10, 0x13, 95, 1, 2, 15, 0, 0x2C, 0x01, 0x20, 0x01))

        val day = LocalDate.of(2026, 10, 13)
        assertEquals(listOf(StepsSample(day, 0, 120), StepsSample(day, 95, 300)), steps)
    }

    @Test
    fun `steps log waits for every index, not just count-1`() {
        val a = StepsLogAssembler()
        assertNull(a.feed(answer(0x43, 0xF0, 0x05, 0x01)))
        // 2026-10-13, three quarters at indexes 0..2; index 1 is lost.
        val first = answer(0x43, 0x26, 0x10, 0x13, 4, 0, 3, 1, 0, 20, 0, 14, 0)
        assertNull(a.feed(first))
        assertNull(a.feed(answer(0x43, 0x26, 0x10, 0x13, 90, 2, 3, 2, 0, 50, 0, 35, 0)))
        // A repeat of index 0 neither completes the log nor counts twice.
        assertNull(a.feed(first))
        val steps = a.feed(answer(0x43, 0x26, 0x10, 0x13, 40, 1, 3, 25, 0, 0xF4, 0x01, 0x5E, 0x01))

        val day = LocalDate.of(2026, 10, 13)
        assertEquals(listOf(StepsSample(day, 4, 20), StepsSample(day, 40, 500), StepsSample(day, 90, 50)), steps)
    }
}
