package app.btcourier.fakes

import app.btcourier.core.transport.GattChannel
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.UUID

/**
 * A Colmi R02-family ring on a [FakeGattLink], answering from tables. Packets are built here byte
 * by byte from the published layout (`colmi_r02_client`, Gadgetbridge ColmiR0x, PulseLoopiOS),
 * independently of core's codec, so a codec mistake shows up as a mismatch instead of cancelling
 * out.
 *
 * Packets: 16 bytes, byte 0 command, byte 15 = `sum(bytes 0..14) & 0xFF`. One packet per
 * notification. Days are in the ring's wall clock, which [set-time][CMD_SET_TIME] moves; its
 * "today" (the steps log's day offset 0) is the date last set, or [today] before any set.
 *
 * The big-data channel (PulseLoopiOS, Gadgetbridge YawellRing) is the link's secondary channel:
 * requests `bc <type> <len u16 LE> <crc16-modbus u16 LE> <payload>`, answers in the same framing
 * split into notifications of at most `mtu - 3` bytes. Its history answers cover the
 * [BIG_DATA_HISTORY_DAYS] days up to the ring's today, each tagged with how many days ago it is.
 *
 * Not thread-safe; drive it from one dispatcher (`runTest`).
 *
 * @param heartRate per day, readings every 5 minutes from midnight (index = slot, 0 = none).
 * @param steps per day, steps by quarter hour (0..95).
 * @param hrv per day, HRV in ms every 30 minutes from midnight (index = slot 0..47, 0 = none).
 *   Put `0x39` in [silentCommands] for older firmware, which never answers it.
 * @param autoHeartRateEnabled initial heart-rate logging setting (`0x16`); writes change it.
 * @param autoHeartRateIntervalMinutes initial logging interval. The heart-rate log answers keep
 *   their own 5-minute header whatever this says.
 * @param spo2 per day, `(min, max)` percent by hour (0..23).
 * @param sleep per day a session ended on, its sessions.
 * @param temperature per day, tenths of a degree Celsius by half hour (0..47); 20.1..45.5 °C.
 * @param bigDataChannel whether the ring has the big-data service at all.
 * @param silentBigDataTypes big-data types the ring never answers, as a model without them.
 * @param firmware the Device Information Service's Firmware Revision string; null when the ring
 *   does not expose it.
 * @param hardware its Hardware Revision string; null when not exposed.
 */
class FakeColmiRing(
    private var today: LocalDate,
    private val heartRate: Map<LocalDate, IntArray> = emptyMap(),
    private val steps: Map<LocalDate, Map<Int, Int>> = emptyMap(),
    private val hrv: Map<LocalDate, IntArray> = emptyMap(),
    private val batteryLevel: Int = 80,
    private val charging: Boolean = false,
    autoHeartRateEnabled: Boolean = true,
    autoHeartRateIntervalMinutes: Int = 5,
    private val spo2: Map<LocalDate, Map<Int, Pair<Int, Int>>> = emptyMap(),
    private val sleep: Map<LocalDate, List<Sleep>> = emptyMap(),
    private val temperature: Map<LocalDate, Map<Int, Int>> = emptyMap(),
    bigDataChannel: Boolean = true,
    private val silentBigDataTypes: Set<Int> = emptySet(),
    firmware: String? = FIRMWARE,
    hardware: String? = HARDWARE,
) {
    val link: FakeGattLink =
        FakeGattLink(
            mtu = 23,
            characteristics =
                buildMap {
                    firmware?.let { put(DIS_SERVICE to DIS_FIRMWARE_REVISION, it.encodeToByteArray()) }
                    hardware?.let { put(DIS_SERVICE to DIS_HARDWARE_REVISION, it.encodeToByteArray()) }
                },
            onSecondaryWrite = if (bigDataChannel) { bytes, _ -> receiveBigData(bytes) } else null,
        ) { bytes, _ -> receive(bytes) }

    /** The heart-rate logging setting, as last written with `16 02`. */
    var autoHeartRateEnabled: Boolean = autoHeartRateEnabled
        private set

    /** The logging interval in minutes, as last written with `16 02`. */
    var autoHeartRateIntervalMinutes: Int = autoHeartRateIntervalMinutes
        private set

    private val _clockSets = mutableListOf<LocalDateTime>()
    private val _commands = mutableListOf<ByteArray>()

    /** Wall clocks the app set, in order. */
    val clockSets: List<LocalDateTime> get() = _clockSets.toList()

    /** Every well-formed command packet received, in order. */
    val commands: List<ByteArray> get() = _commands.map { it.copyOf() }

    /**
     * Writes that were not a 16-byte packet with a correct checksum, or on the big-data channel
     * not a request with matching length and CRC.
     */
    var malformedWrites: Int = 0
        private set

    /**
     * Commands the ring acts on but never answers, as when the answer is lost on air or (for a
     * command the firmware does not know) never sent.
     */
    val silentCommands: MutableSet<Int> = mutableSetOf()

    // Command -> sub-type or index of the packet its next answer damages.
    private val corruptNext = mutableMapOf<Int, Int>()

    /**
     * The next heart-rate log answer sends sub-type [sub] with a wrong checksum, as a packet
     * damaged on air would arrive; later answers are intact again.
     */
    fun corruptNextHeartRatePacket(sub: Int) {
        corruptNext[CMD_HEART_RATE_LOG] = sub
    }

    /** The next steps log answer sends packet [index] with a wrong checksum; later answers are intact. */
    fun corruptNextStepsPacket(index: Int) {
        corruptNext[CMD_STEPS_LOG] = index
    }

    /** The next HRV log answer sends sub-index [sub] with a wrong checksum; later answers are intact. */
    fun corruptNextHrvPacket(sub: Int) {
        corruptNext[CMD_HRV_LOG] = sub
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
            CMD_HRV_LOG -> hrvLog(bytes)
            CMD_AUTO_HEART_RATE -> autoHeartRate(bytes)
        }
    }

    // Gadgetbridge / PulseLoopiOS: `16 01` reads, `16 02 <01 on, 02 off> <minutes>` writes and is
    // answered with a bare `16 02`.
    private fun autoHeartRate(p: ByteArray) {
        when (p[1].toInt()) {
            1 -> {
                // The read answer is assumed to mirror the write's 01/02.
                send(CMD_AUTO_HEART_RATE, 1, if (autoHeartRateEnabled) 1 else 2, autoHeartRateIntervalMinutes)
            }

            2 -> {
                autoHeartRateEnabled = p[2].toInt() == 1
                autoHeartRateIntervalMinutes = p[3].toInt() and 0xFF
                send(CMD_AUTO_HEART_RATE, 2)
            }
        }
    }

    private fun setTime(p: ByteArray) {
        val f = IntArray(6) { bcd(p[it + 1].toInt() and 0xFF) }
        val wall = LocalDateTime.of(2000 + f[0], f[1], f[2], f[3], f[4], f[5])
        _clockSets += wall
        today = wall.toLocalDate()
        // The echo Gadgetbridge logs as the set date/time response; the app waits for it.
        send(CMD_SET_TIME)
    }

    private fun heartRateLog(p: ByteArray) {
        val ts = u32le(p)
        val day = LocalDateTime.ofEpochSecond(ts, 0, ZoneOffset.UTC).toLocalDate()
        val readings = heartRate[day] ?: return send(CMD_HEART_RATE_LOG, 0xFF)
        val corrupt = corruptNext.remove(CMD_HEART_RATE_LOG)
        // 288 slots: 9 in sub-type 1 and 13 in each later one, so 24 packets with the header.
        val dataPackets = 1 + (288 - 9 + 12) / 13
        send(CMD_HEART_RATE_LOG, 0, dataPackets + 1, 5)
        send(
            CMD_HEART_RATE_LOG,
            1,
            *IntArray(4) {
                ((ts shr (8 * it)) and 0xFF).toInt()
            },
            *IntArray(9) { readings.getOrElse(it) { 0 } },
            corrupt =
                corrupt == 1,
        )
        for (sub in 2..dataPackets) {
            val first = 9 + (sub - 2) * 13
            send(
                CMD_HEART_RATE_LOG,
                sub,
                *IntArray(13) { readings.getOrElse(first + it) { 0 } },
                corrupt =
                    corrupt == sub,
            )
        }
    }

    private fun stepsLog(p: ByteArray) {
        val day = today.minusDays((p[1].toInt() and 0xFF).toLong())
        val quarters = steps[day]?.toSortedMap()
        if (quarters.isNullOrEmpty()) return send(CMD_STEPS_LOG, 0xFF)
        val corrupt = corruptNext.remove(CMD_STEPS_LOG)
        send(CMD_STEPS_LOG, 0xF0, 0x05, 0x01)
        quarters.entries.forEachIndexed { i, (quarter, n) ->
            val calories = n / 20
            val distance = n * 7 / 10
            send(
                CMD_STEPS_LOG,
                toBcd(day.year % 100),
                toBcd(day.monthValue),
                toBcd(day.dayOfMonth),
                quarter,
                i,
                quarters.size,
                calories and 0xFF,
                calories shr 8,
                n and 0xFF,
                n shr 8,
                distance and 0xFF,
                distance shr 8,
                corrupt = corrupt == i,
            )
        }
    }

    // PulseLoopiOS / Gadgetbridge layout: header, then 12 readings from byte 3 of sub-index 1 and 13
    // from byte 2 of each of 2..4.
    private fun hrvLog(p: ByteArray) {
        val readings = hrv[today.minusDays(u32le(p))] ?: return send(CMD_HRV_LOG, 0xFF)
        val corrupt = corruptNext.remove(CMD_HRV_LOG)
        send(CMD_HRV_LOG, 0, 5)
        // Byte 2 of sub-index 1 is undocumented; sent as 0.
        send(CMD_HRV_LOG, 1, 0, *IntArray(12) { readings.getOrElse(it) { 0 } }, corrupt = corrupt == 1)
        for (sub in 2..4) {
            val first = 12 + (sub - 2) * 13
            send(CMD_HRV_LOG, sub, *IntArray(13) { readings.getOrElse(first + it) { 0 } }, corrupt = corrupt == sub)
        }
    }

    private fun u32le(p: ByteArray): Long =
        (0 until 4).fold(0L) { acc, i ->
            acc or
                ((p[1 + i].toLong() and 0xFF) shl (8 * i))
        }

    private fun send(
        command: Int,
        vararg payload: Int,
        corrupt: Boolean = false,
    ) {
        if (command in silentCommands) return
        val p = ByteArray(16)
        p[0] = command.toByte()
        payload.forEachIndexed { i, b -> p[i + 1] = b.toByte() }
        p[15] = (sum(p) + if (corrupt) 1 else 0).toByte()
        link.notify(p)
    }

    private fun sum(p: ByteArray): Int = (0 until 15).sumOf { p[it].toInt() and 0xFF } and 0xFF

    private fun bcd(b: Int): Int = (b shr 4) * 10 + (b and 0x0F)

    private fun toBcd(v: Int): Int = (v / 10 shl 4) or (v % 10)

    /**
     * One sleep session as the ring sends it.
     *
     * @property start minute of day it began; greater than [end] when that was the evening before.
     * @property end minute of the day it ended.
     * @property stages `(stage code, minutes)` in order; 2 light, 3 deep, 4 REM, 5 awake.
     */
    data class Sleep(
        val start: Int,
        val end: Int,
        val stages: List<Pair<Int, Int>>,
    )

    private val _bigDataRequests = mutableListOf<Int>()
    private var dropOnBigData: Int? = null

    /** Types of the well-formed big-data requests received, in order. */
    val bigDataRequests: List<Int> get() = _bigDataRequests.toList()

    /** The next big-data request of [type] drops the link instead of being answered. */
    fun dropLinkOnBigDataRequest(type: Int) {
        dropOnBigData = type
    }

    private fun receiveBigData(bytes: ByteArray) {
        val u = { i: Int -> bytes[i].toInt() and 0xFF }
        val wellFramed =
            bytes.size >= 6 &&
                u(0) == BIG_DATA_MAGIC &&
                (u(2) or (u(3) shl 8)) == bytes.size - 6 &&
                (u(4) or (u(5) shl 8)) == crc16Modbus(bytes.copyOfRange(6, bytes.size))
        if (!wellFramed) {
            malformedWrites++
            return
        }
        val type = u(1)
        _bigDataRequests += type
        if (dropOnBigData == type) {
            dropOnBigData = null
            return link.drop()
        }
        if (type in silentBigDataTypes) return
        val payload =
            when (type) {
                BIG_DATA_SPO2 -> spo2Answer()
                BIG_DATA_SLEEP -> sleepAnswer()
                BIG_DATA_TEMPERATURE -> temperatureAnswer()
                else -> return
            }
        val frame =
            byteArrayOf(BIG_DATA_MAGIC.toByte(), type.toByte()) + u16(payload.size) + u16(crc16Modbus(payload)) +
                payload
        frame.toList().chunked(link.mtu - 3).forEach { link.notify(it.toByteArray(), GattChannel.SECONDARY) }
    }

    /** The history window, oldest first, as `(daysAgo, date)`. */
    private fun historyDays(): List<Pair<Int, LocalDate>> {
        val oldest = BIG_DATA_HISTORY_DAYS - 1
        return (oldest downTo 0).map { daysAgo -> daysAgo to today.minusDays(daysAgo.toLong()) }
    }

    private fun spo2Answer(): ByteArray =
        historyDays().fold(ByteArray(0)) { acc, (daysAgo, date) ->
            val hours = spo2[date].orEmpty()
            acc + byteArrayOf(daysAgo.toByte()) +
                ByteArray(48) { i -> hours[i / 2]?.let { if (i % 2 == 0) it.first else it.second }?.toByte() ?: 0 }
        }

    private fun temperatureAnswer(): ByteArray =
        historyDays().fold(ByteArray(0)) { acc, (daysAgo, date) ->
            val slots = temperature[date].orEmpty()
            acc + byteArrayOf(daysAgo.toByte(), 0x1E) +
                ByteArray(48) { slot -> slots[slot]?.let { (it - 200).toByte() } ?: 0 }
        }

    private fun sleepAnswer(): ByteArray {
        val records =
            historyDays().flatMap { (daysAgo, date) ->
                sleep[date].orEmpty().map { s ->
                    val stages = s.stages.flatMap { (code, minutes) -> listOf(code.toByte(), byte(minutes)) }
                    val body = u16(s.start) + u16(s.end) + stages.toByteArray()
                    byteArrayOf(daysAgo.toByte(), byte(body.size)) + body
                }
            }
        return records.fold(byteArrayOf(byte(records.size))) { acc, r -> acc + r }
    }

    private fun u16(v: Int): ByteArray = byteArrayOf(v.toByte(), (v shr 8).toByte())

    private fun byte(v: Int): Byte {
        require(v in 0..0xFF) { "$v does not fit the one byte the ring sends" }
        return v.toByte()
    }

    private fun crc16Modbus(bytes: ByteArray): Int {
        var crc = 0xFFFF
        for (b in bytes) {
            crc = crc xor (b.toInt() and 0xFF)
            repeat(8) { crc = if (crc and 1 == 1) (crc shr 1) xor 0xA001 else crc shr 1 }
        }
        return crc
    }

    companion object {
        const val CMD_SET_TIME: Int = 0x01
        const val CMD_BATTERY: Int = 0x03
        const val CMD_HEART_RATE_LOG: Int = 0x15
        const val CMD_STEPS_LOG: Int = 0x43
        const val CMD_HRV_LOG: Int = 0x39
        const val CMD_AUTO_HEART_RATE: Int = 0x16

        const val BIG_DATA_MAGIC: Int = 0xBC
        const val BIG_DATA_TEMPERATURE: Int = 0x25
        const val BIG_DATA_SLEEP: Int = 0x27
        const val BIG_DATA_SPO2: Int = 0x2A
        const val BIG_DATA_HISTORY_DAYS: Int = 7

        // The SIG Device Information Service, written out over the Bluetooth base UUID.
        val DIS_SERVICE: UUID = UUID.fromString("0000180a-0000-1000-8000-00805f9b34fb")
        val DIS_FIRMWARE_REVISION: UUID = UUID.fromString("00002a26-0000-1000-8000-00805f9b34fb")
        val DIS_HARDWARE_REVISION: UUID = UUID.fromString("00002a27-0000-1000-8000-00805f9b34fb")

        /** Made-up revision strings in the shape the R02 family reports. */
        const val FIRMWARE: String = "R02_3.00.00_000000"
        const val HARDWARE: String = "R02_V1.0"
    }
}
