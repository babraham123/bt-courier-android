package app.btcourier.core.source.colmi

import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

/**
 * GATT layout of the Colmi R02 family (R02/R06/R10...), as implemented by PulseLoopiOS
 * (github.com/saksham2001/PulseLoopiOS): a Nordic-UART-like service where the app writes
 * commands to [RX] and the ring answers on [TX]. A `GattLink` for the ring binds writes to [RX]
 * and notifications to [TX].
 */
object ColmiGatt {
    val SERVICE: UUID = UUID.fromString("6e40fff0-b5a3-f393-e0a9-e50e24dcca9e")

    /** App → ring commands. */
    val RX: UUID = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e")

    /** Ring → app answers, notify. */
    val TX: UUID = UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e")
}

/**
 * The ring's 16-byte packets: byte 0 the command, bytes 1..14 the payload (zero-padded), byte 15
 * the checksum `sum(bytes 0..14) & 0xFF`. Answers echo the command in byte 0.
 *
 * Ring timestamps (the heart-rate request and the log answers) are in the ring's frame: the
 * wall clock it was set to with [setTime], counted as if it were UTC. See [ColmiRingSource].
 */
object ColmiPacketCodec {
    const val SIZE: Int = 16
    const val PAYLOAD_MAX: Int = SIZE - 2

    const val CMD_SET_TIME: Int = 0x01
    const val CMD_BATTERY: Int = 0x03
    const val CMD_HEART_RATE_LOG: Int = 0x15
    const val CMD_STEPS_LOG: Int = 0x43

    /** Byte 1 of a log answer when the ring has nothing for the requested day. */
    const val NO_DATA: Int = 0xFF

    /** Language byte of set-time; 1 is English, 0 Chinese. */
    private const val LANGUAGE_ENGLISH = 1

    // Copied verbatim from PulseLoopiOS's steps request after the day offset; their meaning
    // (probably a quarter-hour range 0x0F..0x5F and a format flag) is not documented.
    private val STEPS_REQUEST_TAIL = byteArrayOf(0x0F, 0x00, 0x5F, 0x01)

    fun packet(command: Int, payload: ByteArray = ByteArray(0)): ByteArray {
        require(command in 0..0xFF) { "command must be one byte, got $command" }
        require(payload.size <= PAYLOAD_MAX) { "payload of ${payload.size} bytes exceeds $PAYLOAD_MAX" }
        val p = ByteArray(SIZE)
        p[0] = command.toByte()
        payload.copyInto(p, destinationOffset = 1)
        p[SIZE - 1] = checksum(p).toByte()
        return p
    }

    /** `sum(bytes 0..14) & 0xFF` of a [SIZE]-byte packet. */
    fun checksum(packet: ByteArray): Int {
        require(packet.size == SIZE) { "packet must be $SIZE bytes, got ${packet.size}" }
        var sum = 0
        for (i in 0 until SIZE - 1) sum += packet[i].toInt() and 0xFF
        return sum and 0xFF
    }

    fun isValid(packet: ByteArray): Boolean =
        packet.size == SIZE && checksum(packet) == (packet[SIZE - 1].toInt() and 0xFF)

    /**
     * The packets in one notification. The ring is believed to send exactly one per notification;
     * a PDU that is a whole multiple of [SIZE] is split in case it batches several, anything else
     * is not this protocol and yields nothing.
     */
    fun packets(pdu: ByteArray): List<ByteArray> =
        if (pdu.isEmpty() || pdu.size % SIZE != 0) emptyList()
        else (0 until pdu.size / SIZE).map { pdu.copyOfRange(it * SIZE, (it + 1) * SIZE) }

    /** `0x01`: sets the ring's clock to [wall], BCD `yy MM dd HH mm ss`, then the language byte. */
    fun setTime(wall: LocalDateTime): ByteArray {
        require(wall.year in 2000..2099) { "the ring stores a two-digit year, got ${wall.year}" }
        val fields = intArrayOf(wall.year % 100, wall.monthValue, wall.dayOfMonth, wall.hour, wall.minute, wall.second)
        return packet(CMD_SET_TIME, ByteArray(7) { if (it < 6) toBcd(fields[it]) else LANGUAGE_ENGLISH.toByte() })
    }

    fun batteryRequest(): ByteArray = packet(CMD_BATTERY)

    /** `0x15`: the heart-rate log of the day starting at [dayStartRingEpochSeconds], 4 bytes LE. */
    fun heartRateLogRequest(dayStartRingEpochSeconds: Long): ByteArray {
        require(dayStartRingEpochSeconds in 0..0xFFFF_FFFFL) { "timestamp must fit 4 bytes" }
        val t = dayStartRingEpochSeconds
        return packet(CMD_HEART_RATE_LOG, ByteArray(4) { (t shr (8 * it)).toByte() })
    }

    /** `0x43`: the steps log of the day [dayOffset] days before the ring's today (0 = today). */
    fun stepsLogRequest(dayOffset: Int): ByteArray {
        require(dayOffset in 0..0xFF) { "day offset must be one byte, got $dayOffset" }
        return packet(CMD_STEPS_LOG, byteArrayOf(dayOffset.toByte()) + STEPS_REQUEST_TAIL)
    }

    /** `0x03` answer: byte 1 the level in percent, byte 2 non-zero while charging. */
    fun parseBattery(packet: ByteArray): Battery {
        requireAnswer(packet, CMD_BATTERY)
        return Battery(level = u8(packet, 1), charging = u8(packet, 2) != 0)
    }

    internal fun requireAnswer(packet: ByteArray, command: Int) {
        require(isValid(packet)) { "not a valid packet" }
        require(u8(packet, 0) == command) { "expected command 0x%02x, got 0x%02x".format(command, u8(packet, 0)) }
    }

    internal fun u8(packet: ByteArray, index: Int): Int = packet[index].toInt() and 0xFF

    internal fun toBcd(value: Int): Byte {
        require(value in 0..99) { "BCD holds 0..99, got $value" }
        return ((value / 10 shl 4) or (value % 10)).toByte()
    }

    internal fun fromBcd(b: Int): Int = (b shr 4) * 10 + (b and 0x0F)
}

data class Battery(val level: Int, val charging: Boolean)

/** Collects the packets of one multi-packet answer; [feed] returns the result once it is whole. */
fun interface ColmiAssembler<T : Any> {
    /** [packet] is valid and carries the answer's command. */
    fun feed(packet: ByteArray): T?
}

/**
 * One day of the heart-rate log.
 *
 * @property values one reading per [intervalMinutes] from the start of the requested day, in
 *   the ring's wall clock; 0 means no reading in that slot.
 */
data class HeartRateLog(val intervalMinutes: Int, val values: List<Int>) {
    companion object {
        val EMPTY = HeartRateLog(DEFAULT_INTERVAL_MINUTES, emptyList())
        const val DEFAULT_INTERVAL_MINUTES: Int = 5
    }
}

/**
 * The `0x15` answer, by byte 1 (sub-type):
 * - `0xFF`: no data for the day; the only packet.
 * - `0`: header; byte 2 the number of packets in the answer including this one, byte 3 the
 *   minutes between readings.
 * - `1`: bytes 2..5 the day start as an LE ring timestamp, bytes 6..14 the first 9 readings.
 * - `2..n-1`: 13 readings each in bytes 2..14.
 *
 * The log is complete only once every sub-type `1..n-1` has arrived, not merely the last one: the
 * source drops packets that fail the checksum, and a day assembled around a gap would be stored
 * with those readings missing and never fetched again. With a gap the assembler keeps waiting, so
 * the request times out and the day is retried on the next sync. Readings are placed by sub-type
 * rather than arrival count, so a duplicated packet cannot shift the rest of the day; sub-types
 * outside the announced range are ignored.
 */
class HeartRateLogAssembler : ColmiAssembler<HeartRateLog> {
    private var packets = -1
    private var interval = HeartRateLog.DEFAULT_INTERVAL_MINUTES
    private val values = sortedMapOf<Int, Int>()
    private val received = mutableSetOf<Int>()

    override fun feed(packet: ByteArray): HeartRateLog? {
        val sub = ColmiPacketCodec.u8(packet, 1)
        if (sub == ColmiPacketCodec.NO_DATA) return HeartRateLog.EMPTY
        if (sub == 0) {
            packets = ColmiPacketCodec.u8(packet, 2)
            interval = ColmiPacketCodec.u8(packet, 3).takeIf { it > 0 } ?: HeartRateLog.DEFAULT_INTERVAL_MINUTES
            values.clear()
            received.clear()
            return if (packets <= 1) HeartRateLog(interval, emptyList()) else null
        }
        // A data packet before any header belongs to an answer this assembler did not see begin.
        if (packets < 0 || sub >= packets) return null
        val (first, from) = if (sub == 1) 0 to 6 else (FIRST_PACKET_READINGS + (sub - 2) * READINGS_PER_PACKET) to 2
        for (i in from until ColmiPacketCodec.SIZE - 1) values[first + i - from] = ColmiPacketCodec.u8(packet, i)
        received += sub
        // Sub-types are confined to 1..packets-1, so the count alone proves the set is whole.
        if (received.size < packets - 1) return null
        val slots = minOf(values.lastKey() + 1, MINUTES_PER_DAY / interval)
        return HeartRateLog(interval, List(slots) { values[it] ?: 0 })
    }

    private companion object {
        const val FIRST_PACKET_READINGS = 9
        const val READINGS_PER_PACKET = 13
        const val MINUTES_PER_DAY = 24 * 60
    }
}

/**
 * Steps in one quarter hour of the ring's wall clock.
 *
 * @property quarter 0..95, the quarter hour of [date] the steps were counted in.
 */
data class StepsSample(val date: LocalDate, val quarter: Int, val steps: Int)

/**
 * The `0x43` answer, by byte 1:
 * - `0xFF` in the first packet: no data for the day.
 * - `0xF0` in the first packet: header (byte 3 = 1 flags the newer calorie scale; unused here).
 * - otherwise one quarter hour: bytes 1..3 the date (BCD `yy MM dd`), byte 4 the quarter, byte 5
 *   this packet's index, byte 6 the packet count, bytes 7..8 calories, 9..10 steps, 11..12
 *   distance (LE).
 *
 * The log is complete only once every index `0..count-1` has arrived, for the same reason as
 * [HeartRateLogAssembler]: a gap left by a dropped packet keeps the assembler waiting until the
 * request times out, instead of yielding a day with quarters missing. Samples are keyed by index,
 * so a duplicated packet replaces itself; indexes outside the announced count are ignored.
 */
class StepsLogAssembler : ColmiAssembler<List<StepsSample>> {
    private var started = false
    private val samples = sortedMapOf<Int, StepsSample>()

    override fun feed(packet: ByteArray): List<StepsSample>? {
        val b1 = ColmiPacketCodec.u8(packet, 1)
        if (!started) {
            started = true
            if (b1 == ColmiPacketCodec.NO_DATA) return emptyList()
            if (b1 == HEADER) return null
        }
        val u = { i: Int -> ColmiPacketCodec.u8(packet, i) }
        val index = u(5)
        val count = u(6)
        if (index >= count) return null
        samples[index] = StepsSample(
            date = LocalDate.of(2000 + ColmiPacketCodec.fromBcd(b1), ColmiPacketCodec.fromBcd(u(2)), ColmiPacketCodec.fromBcd(u(3))),
            quarter = u(4),
            steps = u(9) or (u(10) shl 8),
        )
        return if ((0 until count).all { it in samples }) samples.values.toList() else null
    }

    private companion object {
        const val HEADER = 0xF0
    }
}
