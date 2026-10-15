package app.btcourier.device

import app.btcourier.R
import app.btcourier.ble.ColmiRingGatt
import app.btcourier.core.source.Source
import app.btcourier.core.source.colmi.ColmiPacketCodec
import app.btcourier.core.source.colmi.ColmiRingSource
import app.btcourier.core.source.colmi.colmiRequest
import app.btcourier.core.source.colmi.writeColmiAutoHeartRate
import app.btcourier.core.transport.GattLink
import kotlin.time.Duration.Companion.seconds

/**
 * Colmi R02-family smart ring: unbonded UART-like service ([ColmiRingGatt]), day logs through
 * [ColmiRingSource]. The probe asks for the battery (`0x03`) and leaves the ring's clock alone:
 * setting it is the sync's job. Protocol assumptions are unverified on hardware; see
 * [ColmiRingSource].
 */
object ColmiRingSupport : DeviceSupport {
    private val REQUEST_TIMEOUT = 5.seconds

    override val profile = ColmiRingGatt.PROFILE
    override val filter = ColmiRingGatt.FILTER
    override val sourcePrefix = "colmi:"

    // Worn, not in the car: the head unit says nothing about whether the ring is in range.
    override val syncsOnCarTrigger = false

    override val label = R.string.kind_colmi_ring
    override val pairingText =
        PairingText(
            help = R.string.pairing_kind_colmi_ring_help,
            explain = listOf(R.string.pairing_explain_ring),
            associate = R.string.pairing_associate_body_ring,
            test = R.string.pairing_test_body_ring,
        )

    override suspend fun <T> withSource(
        link: GattLink,
        options: SourceOptions,
        block: suspend (Source) -> T,
    ): T = block(ColmiRingSource(link, options.zone, options.clock))

    override suspend fun probe(link: GattLink): ProbeResult =
        ProbeResult.Ring(
            link.colmiRequest(ColmiPacketCodec.batteryRequest(), REQUEST_TIMEOUT) {
                ColmiPacketCodec.parseBattery(it)
            },
        )

    /**
     * Turns the ring's own heart-rate logging on or off with a reading every [intervalMinutes]
     * (in [ColmiPacketCodec.AUTO_HR_INTERVALS]) on the connected [link], and returns once the ring
     * has answered. A ring with logging off records no heart rate for the sync to pull. Closes
     * nothing.
     *
     * @throws java.io.IOException when the ring does not answer in time or the link drops.
     */
    suspend fun setAutoHeartRate(
        link: GattLink,
        enabled: Boolean,
        intervalMinutes: Int,
    ) = link.writeColmiAutoHeartRate(enabled, intervalMinutes, REQUEST_TIMEOUT)
}
