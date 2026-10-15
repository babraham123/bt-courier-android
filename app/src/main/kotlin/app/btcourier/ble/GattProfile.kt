package app.btcourier.ble

import app.btcourier.core.source.colmi.ColmiBigDataGatt
import app.btcourier.core.source.colmi.ColmiGatt
import app.btcourier.core.transport.GattChannel
import com.juul.kable.WriteType
import java.util.UUID

/**
 * Where a [KableGattLink] subscribes and writes: the primary channel's notify and write
 * characteristics in [service], and optionally a [secondary] channel in a service of its own.
 *
 * @property mtu ATT MTU to ask for after service discovery; [DEFAULT_MTU] or less asks for none.
 * @property wantsBond whether pairing bonds with the device (system passkey dialog) before use.
 * @property writeTypeRule how the caller's `withResponse` flag becomes the ATT write type.
 * @property secondary the `GattChannel.SECONDARY` characteristics, or null when the device has
 *   only the primary channel.
 */
data class GattProfile(
    val service: UUID,
    val notify: UUID,
    val write: UUID,
    val mtu: Int,
    val wantsBond: Boolean,
    val writeTypeRule: WriteTypeRule = WriteTypeRule.AS_ASKED,
    val secondary: ChannelSpec? = null,
) {
    /** One channel's characteristics: notifications from [notify], writes to [write], both in [service]. */
    data class ChannelSpec(
        val service: UUID,
        val notify: UUID,
        val write: UUID,
    )

    /** One `GattLink.write` routed to its characteristic and ATT write type. */
    data class WriteRoute(
        val characteristic: UUID,
        val writeType: WriteType,
    )

    enum class WriteTypeRule {
        /** The caller's flag, whatever the characteristic declares. */
        AS_ASKED,

        /**
         * The caller's flag, except that a write with response falls back to one without when the
         * characteristic does not declare the write property.
         */
        AS_ASKED_IF_SUPPORTED,
    }

    /**
     * Every write goes to [write]; the caller's flag and [writeTypeRule] pick the ATT write type.
     *
     * @param writeWithResponseSupported whether the discovered [write] characteristic declares the
     *   write (with response) property.
     */
    fun routeWrite(
        withResponse: Boolean,
        writeWithResponseSupported: Boolean,
    ): WriteRoute {
        val response =
            when (writeTypeRule) {
                WriteTypeRule.AS_ASKED -> withResponse
                WriteTypeRule.AS_ASKED_IF_SUPPORTED -> withResponse && writeWithResponseSupported
            }
        return WriteRoute(write, if (response) WriteType.WithResponse else WriteType.WithoutResponse)
    }

    /** [channel]'s characteristics, or null when the profile does not declare it. */
    fun channel(channel: GattChannel): ChannelSpec? =
        when (channel) {
            GattChannel.PRIMARY -> ChannelSpec(service, notify, write)
            GattChannel.SECONDARY -> secondary
        }

    /** One write on a channel: [route] to a characteristic of [service]. */
    data class ChannelWrite(
        val service: UUID,
        val route: WriteRoute,
    )

    /**
     * A write on [channel]: to that channel's write characteristic, with the write type
     * [routeWrite] picks; null when the profile does not declare [channel].
     *
     * @param writeWithResponseSupported whether that channel's discovered write characteristic
     *   declares the write (with response) property.
     */
    fun routeWrite(
        withResponse: Boolean,
        writeWithResponseSupported: Boolean,
        channel: GattChannel,
    ): ChannelWrite? {
        val spec = channel(channel) ?: return null
        val route = routeWrite(withResponse, writeWithResponseSupported).copy(characteristic = spec.write)
        return ChannelWrite(spec.service, route)
    }

    companion object {
        /** ATT default before any exchange. */
        const val DEFAULT_MTU: Int = 23
    }
}

/**
 * The Colmi R02 family's UART-like service ([ColmiGatt]). The ring has no passkey and is used
 * unbonded, as `colmi_r02_client` and Gadgetbridge do. Its packets are 16 bytes, so the default
 * MTU carries them and none is negotiated. Commands ask for a write with response, which goes
 * without one when RX does not declare it, as PulseLoopiOS picks by the characteristic's
 * properties (`colmi_r02_client` always writes without).
 *
 * The secondary channel is the big-data service ([ColmiBigDataGatt]); a model without it still
 * connects, with only the primary channel.
 */
object ColmiRingGatt {
    private val BIG_DATA =
        GattProfile.ChannelSpec(
            ColmiBigDataGatt.SERVICE,
            notify = ColmiBigDataGatt.NOTIFY,
            write = ColmiBigDataGatt.WRITE,
        )

    val PROFILE =
        GattProfile(
            ColmiGatt.SERVICE,
            notify = ColmiGatt.TX,
            write = ColmiGatt.RX,
            mtu = GattProfile.DEFAULT_MTU,
            wantsBond = false,
            writeTypeRule = GattProfile.WriteTypeRule.AS_ASKED_IF_SUPPORTED,
            secondary = BIG_DATA,
        )

    /**
     * The model, optionally after the brand, then `_` and an id: `R02_...`, `COLMI R07_...`
     * (PulseLoopiOS's model table, Gadgetbridge's name patterns). R1x rings speak another protocol
     * and stay out by name. Unverified on hardware.
     */
    val NAME_PATTERN: Regex = Regex("^(COLMI )?R0\\d_.*")

    /**
     * Name or service, as PulseLoopiOS matches: `6E40FFF0` is this protocol's own service rather
     * than a generic UART UUID, so either alone identifies the ring, and a ring that leaves one of
     * them out of its advertising is still offered.
     */
    val FILTER = DeviceFilterSpec(ColmiGatt.SERVICE, NAME_PATTERN, DeviceFilterSpec.Match.NAME_OR_SERVICE)
}

/**
 * What a companion-device scan must match; kept free of Android types so it can be tested.
 *
 * @property match how the name and service conditions combine. Each kind picks its own: a kind
 *   whose service UUID other devices share (the WiCAN's FFF0) needs both.
 */
data class DeviceFilterSpec(
    val serviceUuid: UUID,
    val namePattern: Regex,
    val match: Match,
) {
    enum class Match { NAME_AND_SERVICE, NAME_OR_SERVICE }

    fun matches(
        advertisedName: String?,
        advertisedServices: Collection<UUID>,
    ): Boolean {
        val name = advertisedName != null && namePattern.matches(advertisedName)
        val service = serviceUuid in advertisedServices
        return when (match) {
            Match.NAME_AND_SERVICE -> name && service
            Match.NAME_OR_SERVICE -> name || service
        }
    }
}
