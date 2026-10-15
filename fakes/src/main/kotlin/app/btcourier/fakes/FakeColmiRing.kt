package app.btcourier.fakes

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset

/**
 * A Colmi R02-family ring on a [FakeGattLink], answering from tables. Packets are built here byte
 * by byte from the published layout (`colmi_r02_client`, Gadgetbridge ColmiR0x), independently
 * of core's codec, so a codec mistake shows up as a mismatch instead of cancelling out.
 *
 * Packets: 16 bytes, byte 0 command, byte 15 = `sum(bytes 0..14) & 0xFF`. One packet per
 * notification. Days are in the ring's wall clock, which [set-time][CMD_SET_TIME] moves; its
 * "today" (the steps log's day offset 0) is the date last set, or [today] before any set.
 *
 * Not thread-safe; drive it from one dispatcher (`runTest`).
 *
 * @param heartRate per day, readings every 5 minutes from midnight (index = slot, 0 = none).
 * @param steps per day, steps by quarter hour (0..95).
 */
class FakeColmiRing(
    private var today: LocalDate,
    private val heartRate: Map<LocalDate, IntArray> = emptyMap(),
    private val steps: Map<LocalDate, Map<Int, Int>> = emptyMap(),
    private val batteryLevel: Int = 80,
    private val charging: Boolean = false,
) {
    val link: FakeGattLink = FakeGattLink(mtu = 23) { bytes, _ -> receive(bytes) }

    private val _clockSets = mutableListOf<LocalDateTime>()
    private val _commands = mutableListOf<ByteArray>()

    /** Wall clocks the app set, in order. */
    val clockSets: List<LocalDateTime> get() = _clockSets.toList()

    /** Every well-formed command packet received, in order. */
    val commands: List<ByteArray> get() = _commands.map { it.copyOf() }

    /** Writes that were not a 16-byte packet with a correct checksum. */
    var malformedWrites: Int = 0
        private set

    private var corruptHeartRateSub: Int? = null
    private var corruptStepsIndex: Int? = null

    /**
     * The next heart-rate log answer sends sub-type [sub] with a wrong checksum, as a packet
     * damaged on air would arrive; later answers are intact again.
     */
    fun corruptNextHeartRatePacket(sub: Int) {
        corruptHeartRateSub = sub
    }

    /** The next steps log answer sends packet [index] with a wrong checksum; later answers are intact. */
    fun corruptNextStepsPacket(index: Int) {
        corruptStepsIndex = index
    }

    private fun receive(bytes: ByteArray) {
        if (bytes.size != 16 || sum(bytes) != (bytes[15].toInt() and 0xFF)) {
            malformedWrites++
            return
        }
        _commands += bytes.copyOf()
        when (bytes[0].toInt() and 0xFF) {
            CMD_SET_TIME -> setTime(bytes)
            CMD_BATTERY -> send(CMD_BATTERY, batteryLevel, if (charging) 1 else 0)
            CMD_HEART_RATE_LOG -> heartRateLog(bytes)
            CMD_STEPS_LOG -> stepsLog(bytes)
        }
    }

    private fun setTime(p: ByteArray) {
        val f = IntArray(6) { bcd(p[it + 1].toInt() and 0xFF) }
        val wall = LocalDateTime.of(2000 + f[0], f[1], f[2], f[3], f[4], f[5])
        _clockSets += wall
        today = wall.toLocalDate()
        // Believed to answer set-time; whether or not it does, the app must skip it.
        send(CMD_SET_TIME)
    }

    private fun heartRateLog(p: ByteArray) {
        val ts = (0 until 4).fold(0L) { acc, i -> acc or ((p[1 + i].toLong() and 0xFF) shl (8 * i)) }
        val day = LocalDateTime.ofEpochSecond(ts, 0, ZoneOffset.UTC).toLocalDate()
        val readings = heartRate[day] ?: return send(CMD_HEART_RATE_LOG, 0xFF)
        val corrupt = corruptHeartRateSub.also { corruptHeartRateSub = null }
        // 288 slots: 9 in sub-type 1 and 13 in each later one, so 24 packets with the header.
        val dataPackets = 1 + (288 - 9 + 12) / 13
        send(CMD_HEART_RATE_LOG, 0, dataPackets + 1, 5)
        send(CMD_HEART_RATE_LOG, 1, *IntArray(4) { ((ts shr (8 * it)) and 0xFF).toInt() }, *IntArray(9) { readings.getOrElse(it) { 0 } }, corrupt = corrupt == 1)
        for (sub in 2..dataPackets) {
            val first = 9 + (sub - 2) * 13
            send(CMD_HEART_RATE_LOG, sub, *IntArray(13) { readings.getOrElse(first + it) { 0 } }, corrupt = corrupt == sub)
        }
    }

    private fun stepsLog(p: ByteArray) {
        val day = today.minusDays((p[1].toInt() and 0xFF).toLong())
        val quarters = steps[day]?.toSortedMap()
        if (quarters.isNullOrEmpty()) return send(CMD_STEPS_LOG, 0xFF)
        val corrupt = corruptStepsIndex.also { corruptStepsIndex = null }
        send(CMD_STEPS_LOG, 0xF0, 0x05, 0x01)
        quarters.entries.forEachIndexed { i, (quarter, n) ->
            val calories = n / 20
            val distance = n * 7 / 10
            send(
                CMD_STEPS_LOG,
                toBcd(day.year % 100), toBcd(day.monthValue), toBcd(day.dayOfMonth),
                quarter, i, quarters.size,
                calories and 0xFF, calories shr 8,
                n and 0xFF, n shr 8,
                distance and 0xFF, distance shr 8,
                corrupt = corrupt == i,
            )
        }
    }

    private fun send(command: Int, vararg payload: Int, corrupt: Boolean = false) {
        val p = ByteArray(16)
        p[0] = command.toByte()
        payload.forEachIndexed { i, b -> p[i + 1] = b.toByte() }
        p[15] = (sum(p) + if (corrupt) 1 else 0).toByte()
        link.notify(p)
    }

    private fun sum(p: ByteArray): Int = (0 until 15).sumOf { p[it].toInt() and 0xFF } and 0xFF

    private fun bcd(b: Int): Int = (b shr 4) * 10 + (b and 0x0F)

    private fun toBcd(v: Int): Int = (v / 10 shl 4) or (v % 10)

    companion object {
        const val CMD_SET_TIME: Int = 0x01
        const val CMD_BATTERY: Int = 0x03
        const val CMD_HEART_RATE_LOG: Int = 0x15
        const val CMD_STEPS_LOG: Int = 0x43
    }
}
