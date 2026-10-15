package app.btcourier.core.source.colmi

import java.util.UUID

/**
 * The Colmi ring's second GATT service, the "big-data" channel (PulseLoopiOS
 * `ColmiProtocol.swift` V2 UUIDs, Gadgetbridge YawellRing `CHARACTERISTIC_COMMAND` /
 * `CHARACTERISTIC_NOTIFY_V2`): the app writes requests to [WRITE] and the ring answers on
 * [NOTIFY]. A `GattLink` for the ring binds it as its secondary channel. Not every model is
 * believed to have it.
 */
object ColmiBigDataGatt {
    val SERVICE: UUID = UUID.fromString("de5bf728-d711-4e47-af26-65e3012a5dc7")

    /** App → ring big-data requests. */
    val WRITE: UUID = UUID.fromString("de5bf72a-d711-4e47-af26-65e3012a5dc7")

    /** Ring → app big-data answers, notify. */
    val NOTIFY: UUID = UUID.fromString("de5bf729-d711-4e47-af26-65e3012a5dc7")
}
