package app.btcourier.core.source.colmi

/**
 * Framing and history parsers of the ring's big-data channel ([ColmiBigDataGatt]), reimplemented
 * from the byte layouts in PulseLoopiOS (`ColmiEncoder.swift`, `ColmiDriver.swift`,
 * `ColmiDecoder.swift`) and Gadgetbridge YawellRing.
 *
 * A frame, in both directions, is `bc <type> <len u16 LE> <crc u16 LE> <payload: len bytes>`,
 * not padded to the UART's 16 bytes. The CRC is CRC-16/MODBUS of the payload; it reproduces all
 * three published requests (sleep and SpO2 `bc xx 01 00 ff 00 ff`, temperature
 * `bc 25 01 00 3e 81 02`). Answers' CRCs are not checked: the published answer captures carry
 * zeros there, so whether the ring fills them in is unknown.
 *
 * Every history answer holds all the days the ring keeps, each tagged with how many days before
 * the ring's today it is (`daysAgo`); the caller maps that to a date.
 */
object ColmiBigData {
    const val MAGIC: Int = 0xBC
    const val HEADER_SIZE: Int = 6

    const val TYPE_TEMPERATURE: Int = 0x25
    const val TYPE_SLEEP: Int = 0x27
    const val TYPE_SPO2: Int = 0x2A

    /** The types [historyRequest] asks for, and the only ones [ColmiBigDataAssembler] recognises. */
    val HISTORY_TYPES: Set<Int> = setOf(TYPE_TEMPERATURE, TYPE_SLEEP, TYPE_SPO2)

    internal const val MINUTES_PER_DAY: Int = 24 * 60

    /** Temperature answers shorter than one day's record (Gadgetbridge's threshold) hold no readings. */
    private const val TEMPERATURE_MIN_LENGTH = 50

    /** Temperature bytes are tenths of a degree above 20 °C. */
    private const val TEMPERATURE_BASE_DECI_CELSIUS = 200

    fun frame(
        type: Int,
        payload: ByteArray,
    ): ByteArray {
        require(type in 0..0xFF) { "type must be one byte, got $type" }
        require(payload.size <= 0xFFFF) { "payload of ${payload.size} bytes exceeds the u16 length" }
        val crc = crc16Modbus(payload)
        val header =
            byteArrayOf(
                MAGIC.toByte(),
                type.toByte(),
                payload.size.toByte(),
                (payload.size shr 8).toByte(),
                crc.toByte(),
                (crc shr 8).toByte(),
            )
        return header + payload
    }

    /**
     * The history request for [type], with the one-byte payload the published clients send:
     * `ff` for sleep and SpO2, `02` for temperature (meaning undocumented).
     */
    fun historyRequest(type: Int): ByteArray =
        when (type) {
            TYPE_SLEEP, TYPE_SPO2 -> frame(type, byteArrayOf(0xFF.toByte()))
            TYPE_TEMPERATURE -> frame(type, byteArrayOf(0x02))
            else -> throw IllegalArgumentException("no history request for type 0x%02x".format(type))
        }

    /** CRC-16/MODBUS: reflected polynomial 0xA001, initial value 0xFFFF, no final XOR. */
    fun crc16Modbus(bytes: ByteArray): Int {
        var crc = 0xFFFF
        for (b in bytes) {
            crc = crc xor (b.toInt() and 0xFF)
            repeat(8) { crc = if (crc and 1 != 0) (crc ushr 1) xor 0xA001 else crc ushr 1 }
        }
        return crc
    }

    /**
     * `0x2A`: per day, `daysAgo` then 24 hourly `(min, max)` byte pairs, until the record with
     * `daysAgo == 0` (today). Hours where either value is 0 have no reading and are left out.
     */
    fun parseSpo2(payload: ByteArray): List<ColmiSpo2Hour> =
        buildList {
            var i = 0
            while (i < payload.size) {
                val daysAgo = u8(payload, i++)
                for (hour in 0 until 24) {
                    if (i + 1 >= payload.size) break
                    val min = u8(payload, i)
                    val max = u8(payload, i + 1)
                    i += 2
                    if (min > 0 && max > 0) add(ColmiSpo2Hour(daysAgo, hour, min, max))
                }
                if (daysAgo == 0) break
            }
        }

    /**
     * `0x27`: byte 0 the number of records, then per record `daysAgo`, its byte count `n`, start
     * and end as u16 LE minute of day, and `(n - 4) / 2` `(stage, minutes)` pairs. Stages of zero
     * minutes and records without any stage are left out, as are a truncated record and anything
     * after it.
     */
    fun parseSleep(payload: ByteArray): List<ColmiSleepSession> =
        buildList {
            if (payload.isEmpty()) return@buildList
            var i = 1
            repeat(u8(payload, 0)) {
                if (i + 6 > payload.size) return@buildList
                val count = u8(payload, i + 1)
                val recordEnd = i + 2 + count
                if (count < 4 || recordEnd > payload.size) return@buildList
                val stages =
                    (i + 6 until recordEnd - 1 step 2)
                        .map { ColmiSleepStage(u8(payload, it), u8(payload, it + 1)) }
                        .filter { it.minutes > 0 }
                if (stages.isNotEmpty()) {
                    add(ColmiSleepSession(u8(payload, i), u16(payload, i + 2), u16(payload, i + 4), stages))
                }
                i = recordEnd
            }
        }

    /**
     * `0x25`: per day, `daysAgo`, one byte of unknown meaning, then 24 hourly `(:00, :30)` bytes,
     * until the record with `daysAgo == 0`. A byte `b > 0` is `b / 10 + 20` °C; 0 is no reading.
     * An answer shorter than one record (a model without the sensor) holds nothing.
     */
    fun parseTemperature(payload: ByteArray): List<ColmiTemperatureSlot> =
        buildList {
            if (payload.size < TEMPERATURE_MIN_LENGTH) return@buildList
            var i = 0
            while (i + 1 < payload.size) {
                val daysAgo = u8(payload, i)
                i += 2
                for (slot in 0 until 48) {
                    if (i >= payload.size) break
                    val b = u8(payload, i++)
                    if (b > 0) add(ColmiTemperatureSlot(daysAgo, slot, TEMPERATURE_BASE_DECI_CELSIUS + b))
                }
                if (daysAgo == 0) break
            }
        }

    internal fun isHeader(pdu: ByteArray): Boolean =
        pdu.size >= HEADER_SIZE && u8(pdu, 0) == MAGIC && u8(pdu, 1) in HISTORY_TYPES

    /** Payload length a frame starting with [header] announces. */
    internal fun length(header: ByteArray): Int = u16(header, 2)

    private fun u8(
        bytes: ByteArray,
        index: Int,
    ): Int = bytes[index].toInt() and 0xFF

    private fun u16(
        bytes: ByteArray,
        index: Int,
    ): Int = u8(bytes, index) or (u8(bytes, index + 1) shl 8)
}

/** One whole big-data answer: its [type] and the [payload] after the 6-byte header. */
class ColmiBigDataFrame(
    val type: Int,
    val payload: ByteArray,
)

/**
 * Reassembles big-data answers from notifications. A notification that starts with `bc` and a
 * [history type][ColmiBigData.HISTORY_TYPES] and holds a whole header starts (or restarts) that
 * type's buffer; any other notification continues the buffer still waiting for bytes, and is
 * dropped when none is. A buffer is whole at `len + 6` bytes; bytes past that are dropped.
 *
 * One buffer per type, as PulseLoopiOS keeps them after a capture showed a short answer of one
 * type arriving inside a longer one of another. Requiring a known type keeps a continuation
 * that happens to start with `bc` (a 38.8 °C temperature byte) from restarting a buffer.
 */
class ColmiBigDataAssembler {
    private val buffers = LinkedHashMap<Int, ByteArray>()
    private var waiting: Int? = null

    /** Feeds one notification; returns the answer it completes, if any. */
    fun feed(pdu: ByteArray): ColmiBigDataFrame? {
        val type =
            if (ColmiBigData.isHeader(pdu)) {
                (pdu[1].toInt() and 0xFF).also { buffers[it] = pdu.copyOf() }
            } else {
                val open = waiting ?: return null
                buffers[open] = buffers.getValue(open) + pdu
                open
            }
        val buffer = buffers.getValue(type)
        val size = ColmiBigData.HEADER_SIZE + ColmiBigData.length(buffer)
        if (buffer.size < size) {
            waiting = type
            return null
        }
        buffers.remove(type)
        waiting = buffers.keys.firstOrNull()
        return ColmiBigDataFrame(type, buffer.copyOfRange(ColmiBigData.HEADER_SIZE, size))
    }
}

/** SpO2 range in percent over one [hour] (0..23) of the day [daysAgo] days before the ring's today. */
data class ColmiSpo2Hour(
    val daysAgo: Int,
    val hour: Int,
    val min: Int,
    val max: Int,
)

/** One stretch of a sleep session: stage [code] for [minutes]. */
data class ColmiSleepStage(
    val code: Int,
    val minutes: Int,
) {
    /** `light`, `deep`, `rem`, `awake`, or `stage_<code>` for a code the references do not name. */
    val label: String
        get() = STAGE_LABELS[code] ?: "stage_$code"

    companion object {
        /** Codes 2..5 as Gadgetbridge and PulseLoopiOS map them. */
        val STAGE_LABELS: Map<Int, String> = linkedMapOf(2 to "light", 3 to "deep", 4 to "rem", 5 to "awake")
    }
}

/**
 * One sleep session, filed under the day it ended, [daysAgo] days before the ring's today.
 *
 * @property startMinute minute of day it began, on the wire; greater than [endMinute] when it
 *   began the evening before.
 * @property endMinute minute of the day it ended.
 */
data class ColmiSleepSession(
    val daysAgo: Int,
    val startMinute: Int,
    val endMinute: Int,
    val stages: List<ColmiSleepStage>,
) {
    /** Minutes from the start of the day it ended to its start; negative when it began the evening before. */
    val startOffsetMinutes: Int
        get() = if (startMinute > endMinute) startMinute - ColmiBigData.MINUTES_PER_DAY else startMinute
}

/**
 * One temperature reading in tenths of a degree Celsius, for half-hour [slot] (0..47) of the day
 * [daysAgo] days before the ring's today.
 */
data class ColmiTemperatureSlot(
    val daysAgo: Int,
    val slot: Int,
    val deciCelsius: Int,
)
