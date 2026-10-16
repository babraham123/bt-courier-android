package app.btcourier.device

import app.btcourier.R
import app.btcourier.ble.Elm327Gatt
import app.btcourier.core.elm327.Elm327Response
import app.btcourier.core.elm327.Elm327Source
import app.btcourier.core.elm327.Elm327Transport
import app.btcourier.core.elm327.VehicleProfile
import app.btcourier.core.elm327.VehicleProfiles
import app.btcourier.core.source.Source
import app.btcourier.core.transport.GattLink
import java.io.IOException
import java.time.ZoneId

/**
 * ELM327 OBD-II adapter over BLE (ticket 0002): a serial bridge whose GATT layout is discovered
 * per device ([Elm327Gatt]), polled live by [Elm327Source] with the device's vehicle profile. The
 * adapter keeps no history, so rows come only from a drive session collecting
 * [Source.subscribe]; a sync's `pull` answers an empty page.
 *
 * The probe resets the adapter (`ATZ`, answering its version) and reads the car battery voltage
 * (`ATRV`); a status line or anything else unparseable fails it.
 */
object Elm327Support : DeviceSupport {
    // Only the bond flag is read, and it is off: the WiCAN's bonded layout gets its pairing dialog
    // from Android on first access instead of a wizard step.
    override val profile = Elm327Gatt.DISCOVERY.profile(Elm327Gatt.CANDIDATES.first())
    override val gattDiscovery = Elm327Gatt.DISCOVERY
    override val filter = Elm327Gatt.FILTER
    override val sourcePrefix = "elm327:"

    // A sync has nothing to pull; the drive session collects the adapter while the car runs.
    override val syncsOnCarTrigger = false

    override val label = R.string.kind_elm327
    override val pairingText =
        PairingText(
            help = R.string.pairing_kind_elm327_help,
            explain = listOf(R.string.pairing_explain_elm327, R.string.pairing_explain_elm327_one_app),
            associate = R.string.pairing_associate_body_elm327,
            test = R.string.pairing_test_body_elm327,
            vehicleProfileChoice =
                PairingChoiceText(
                    R.string.pairing_vehicle_profile_choice,
                    R.string.pairing_vehicle_profile_choice_help,
                ),
        )

    // No history ever becomes due, but the contract has no "never": a stray sync connects and
    // stores nothing, since pull answers an empty page.
    override fun nextDueAtMs(
        cursor: String?,
        zone: ZoneId,
    ): Long? = null

    override suspend fun <T> withSource(
        link: GattLink,
        options: SourceOptions,
        block: suspend (Source) -> T,
    ): T =
        withTransport(link) { transport ->
            block(
                Elm327Source(
                    transport,
                    vehicleProfile(options.vehicleProfile),
                    Elm327Source.DEFAULT_POLL_INTERVAL,
                    options.clock,
                ),
            )
        }

    override suspend fun probe(
        link: GattLink,
        choices: PairingChoices,
    ): ProbeResult =
        withTransport(link) { transport ->
            val reset = transport.command("ATZ")
            val version =
                Elm327Source.parseVersion(reset)?.takeIf { isText(reset) }
                    ?: throw IOException("ATZ answered '$reset'")
            val battery = transport.command("ATRV")
            val voltage = Elm327Source.parseVoltage(battery) ?: throw IOException("ATRV answered '$battery'")
            ProbeResult.Elm327(version, voltage)
        }

    /**
     * The profile named [carModel], or [VehicleProfiles.GENERIC] for null or a name the shipped
     * profiles no longer have, which still polls the standard values.
     */
    fun vehicleProfile(carModel: String?): VehicleProfile =
        carModel?.let(VehicleProfiles::byModel) ?: VehicleProfiles.GENERIC

    // A version string is free text; a status line (`?`) or a bare `OK` is not one.
    private fun isText(answer: String): Boolean =
        when (Elm327Response.parse(answer)) {
            is Elm327Response.Status, Elm327Response.Ok -> false
            else -> true
        }

    /** Runs [block] with a transport on [link], its reader living as long as [block]. */
    private suspend fun <T> withTransport(
        link: GattLink,
        block: suspend (Elm327Transport) -> T,
    ): T = withReaderScope { block(Elm327Transport(link, it)) }
}
