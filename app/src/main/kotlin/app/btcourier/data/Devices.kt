@file:OptIn(ExperimentalSerializationApi::class)

package app.btcourier.data

import app.btcourier.ble.GattLayout
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer

/**
 * The kinds of BLE device the app syncs, as stored in settings. What differs per kind (GATT
 * profile, pairing, source, triggers) lives behind [app.btcourier.device.support].
 */
@Serializable
enum class DeviceKind {
    /** MeatPi WiCAN Pro: bonded `ble_http` tunnel, `WicanLoggerSource`. */
    WICAN,

    /** Colmi R02-family smart ring: unbonded UART-like service, `ColmiRingSource`. */
    COLMI_RING,

    /** ELM327 OBD-II adapter over BLE: discovered serial service, live `Elm327Source`. */
    ELM327,
}

/**
 * What the app knows about a paired device beyond its `CompanionDeviceManager` association, which
 * stores no app metadata. Written when the association is made and removed on Forget; the
 * association stays the record of whether the device is paired.
 *
 * An association with no matching entry reads as [DeviceKind.WICAN]: every association made
 * before device kinds existed is a WiCAN, so no migration writes entries for them.
 *
 * @property address upper-case MAC, as the association reports it.
 * @property name the advertised name picked in the chooser, if known.
 * @property vehicleProfile an ELM327's vehicle profile, a `car_model` of
 *   [VehicleProfiles][app.btcourier.core.elm327.VehicleProfiles]; null for the generic one.
 * @property gatt the GATT layout found on the device's first link, for a kind whose layout varies
 *   by model ([app.btcourier.ble.GattDiscovery]); null until then and for other kinds.
 */
@Serializable
data class DeviceConfig(
    val address: String,
    val kind: DeviceKind,
    val name: String? = null,
    // Left out while unset, so entries of kinds that never set them read as they always have.
    @EncodeDefault(EncodeDefault.Mode.NEVER) val vehicleProfile: String? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val gatt: GattLayout? = null,
)

/** The kind of the device at [address] (case-insensitive), [DeviceKind.WICAN] when unknown. */
fun List<DeviceConfig>.kindOf(address: String): DeviceKind =
    firstOrNull { it.address.equals(address, ignoreCase = true) }?.kind ?: DeviceKind.WICAN

/** Adds [device], replacing any entry for the same address. */
fun List<DeviceConfig>.with(device: DeviceConfig): List<DeviceConfig> = without(device.address) + device

fun List<DeviceConfig>.without(address: String): List<DeviceConfig> =
    filterNot {
        it.address.equals(address, ignoreCase = true)
    }

private val deviceListSerializer = ListSerializer(DeviceConfig.serializer())

fun encodeDevices(devices: List<DeviceConfig>): String = sinkJson.encodeToString(deviceListSerializer, devices)

/** @throws IllegalArgumentException for malformed JSON or an unknown kind. */
fun decodeDevices(json: String): List<DeviceConfig> = sinkJson.decodeFromString(deviceListSerializer, json)
