package app.btcourier.core.transport

import java.util.UUID

/**
 * The Bluetooth SIG Device Information Service (`0x180A`): read-only strings a device may expose
 * about itself. Only the revisions are read; each is optional in the spec.
 */
object DeviceInformation {
    val SERVICE: UUID = bluetoothUuid(0x180A)
    val FIRMWARE_REVISION: UUID = bluetoothUuid(0x2A26)
    val HARDWARE_REVISION: UUID = bluetoothUuid(0x2A27)

    /** A 16-bit SIG-assigned number expanded to a full UUID over the Bluetooth base UUID. */
    fun bluetoothUuid(short: Int): UUID {
        require(short in 0..0xFFFF) { "not a 16-bit UUID: $short" }
        return UUID.fromString("%08x-0000-1000-8000-00805f9b34fb".format(short))
    }
}

/** A device's firmware and hardware revision strings, each null when it does not expose one. */
data class DeviceRevisions(
    val firmware: String?,
    val hardware: String?,
)

/**
 * Reads the [DeviceInformation] firmware and hardware revisions. A missing service or
 * characteristic, or a blank value, gives null for that string.
 *
 * @throws java.io.IOException when the link drops.
 */
suspend fun GattLink.readDeviceRevisions(): DeviceRevisions =
    DeviceRevisions(
        firmware = readDeviceString(DeviceInformation.FIRMWARE_REVISION),
        hardware = readDeviceString(DeviceInformation.HARDWARE_REVISION),
    )

private suspend fun GattLink.readDeviceString(characteristic: UUID): String? =
    try {
        // UTF-8 per the spec; some firmware pads with NULs.
        read(DeviceInformation.SERVICE, characteristic)
            .decodeToString()
            .trim { it.isWhitespace() || it == '\u0000' }
            .ifEmpty { null }
    } catch (e: CharacteristicUnavailableException) {
        null
    }
