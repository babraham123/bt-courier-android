package app.btcourier.core.source.colmi

import app.btcourier.core.transport.GattLink
import kotlin.time.Duration

/**
 * Reads the ring's own heart-rate logging setting (`16 01`).
 *
 * @throws java.io.IOException on timeout or when the link drops.
 */
suspend fun GattLink.readColmiAutoHeartRate(timeout: Duration): AutoHeartRate =
    colmiRequest(ColmiPacketCodec.autoHrRead(), timeout) { ColmiPacketCodec.parseAutoHr(it) }

/**
 * Turns the ring's own heart-rate logging on or off with a reading every [intervalMinutes]
 * (`16 02`, see [ColmiPacketCodec.autoHrWrite]) and waits for the ring's `16 02` answer. With
 * logging off the ring records no heart rate, and every `0x15` day comes back empty.
 *
 * @throws java.io.IOException on timeout or when the link drops.
 */
suspend fun GattLink.writeColmiAutoHeartRate(
    enabled: Boolean,
    intervalMinutes: Int,
    timeout: Duration,
) {
    colmiRequest(ColmiPacketCodec.autoHrWrite(enabled, intervalMinutes), timeout) {
        if (ColmiPacketCodec.isAutoHrWriteAnswer(it)) Unit else null
    }
}
