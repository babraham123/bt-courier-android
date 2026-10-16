package app.btcourier.device

import app.btcourier.R
import app.btcourier.ble.ColmiRingGatt
import app.btcourier.core.source.Source
import app.btcourier.core.source.colmi.ColmiPacketCodec
import app.btcourier.core.source.colmi.ColmiRingSource
import app.btcourier.core.source.colmi.ColmiTimeoutException
import app.btcourier.core.source.colmi.colmiRequest
import app.btcourier.core.source.colmi.readColmiAutoHeartRate
import app.btcourier.core.source.colmi.writeColmiAutoHeartRate
import app.btcourier.core.transport.GattLink
import app.btcourier.core.transport.readDeviceRevisions
import java.time.ZoneId
import kotlin.time.Duration.Companion.seconds

/**
 * Colmi R02-family smart ring: unbonded UART-like service ([ColmiRingGatt]), day logs through
 * [ColmiRingSource]. The probe asks for the battery (`0x03`) and leaves the ring's clock alone:
 * setting it is the sync's job. It then applies [PairingChoices.autoHeartRate] and reads the
 * logging setting back (`0x16`): the ring records a heart-rate log only with it on, and the vendor
 * app owns it, so the wizard asks once instead of writing it on every sync. Last it reads the
 * firmware and hardware revisions from the Device Information Service, either of which may be
 * missing. Protocol assumptions are unverified on hardware; see [ColmiRingSource].
 */
object ColmiRingSupport : DeviceSupport {
    private val REQUEST_TIMEOUT = 5.seconds

    override val profile = ColmiRingGatt.PROFILE
    override val filter = ColmiRingGatt.FILTER
    override val sourcePrefix = "colmi:"

    // Worn, not in the car: the head unit says nothing about whether the ring is in range.
    override val syncsOnCarTrigger = false

    // Its logs fill in 5-minute slots and are pulled hourly; a held link would buy nothing.
    override val liveMode = LiveMode.NONE

    /** The interval the pair wizard turns logging on with; its checkbox label states it. */
    const val PAIRING_AUTO_HR_INTERVAL_MINUTES: Int = 5

    override val label = R.string.kind_colmi_ring
    override val pairingText =
        PairingText(
            help = R.string.pairing_kind_colmi_ring_help,
            explain = listOf(R.string.pairing_explain_ring),
            associate = R.string.pairing_associate_body_ring,
            test = R.string.pairing_test_body_ring,
            autoHeartRateChoice =
                PairingChoiceText(R.string.pairing_auto_hr_choice, R.string.pairing_auto_hr_choice_help),
        )

    // An hour after the cursor's cut, enough newly closed slots to be worth a connection.
    override fun nextDueAtMs(
        cursor: String?,
        zone: ZoneId,
    ): Long? = ColmiRingSource.nextDueAtMs(cursor, zone)

    override suspend fun <T> withSource(
        link: GattLink,
        options: SourceOptions,
        block: suspend (Source) -> T,
    ): T = block(ColmiRingSource(link, options.zone, options.clock))

    override suspend fun probe(
        link: GattLink,
        choices: PairingChoices,
    ): ProbeResult {
        val battery =
            link.colmiRequest(ColmiPacketCodec.batteryRequest(), REQUEST_TIMEOUT) {
                ColmiPacketCodec.parseBattery(it)
            }
        // The battery answer already proved the link; a ring silent on 0x16 still passes the test,
        // with the setting unconfirmed. A dropped link fails it.
        val autoHeartRate =
            try {
                if (choices.autoHeartRate) {
                    setAutoHeartRate(link, enabled = true, intervalMinutes = PAIRING_AUTO_HR_INTERVAL_MINUTES)
                }
                link.readColmiAutoHeartRate(REQUEST_TIMEOUT)
            } catch (e: ColmiTimeoutException) {
                null
            }
        val revisions = link.readDeviceRevisions()
        return ProbeResult.Ring(battery, autoHeartRate, revisions.firmware, revisions.hardware)
    }

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
