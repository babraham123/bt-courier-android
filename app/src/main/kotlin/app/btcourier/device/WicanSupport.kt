package app.btcourier.device

import app.btcourier.R
import app.btcourier.ble.WicanGatt
import app.btcourier.core.source.Source
import app.btcourier.core.transport.GattLink
import app.btcourier.core.transport.blehttp.BleHttpTransport
import app.btcourier.core.wican.WicanLoggerSource
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException
import java.time.ZoneId

/**
 * MeatPi WiCAN Pro: bonded `ble_http` tunnel ([WicanGatt]), trip logs through [WicanLoggerSource].
 * The probe runs `GET /api/status` through the tunnel and reports the firmware version.
 */
object WicanSupport : DeviceSupport {
    override val profile = WicanGatt.PROFILE
    override val filter = WicanGatt.FILTER
    override val sourcePrefix = "wican:"

    // Only a dongle plugged into the car is awake when the head unit connects.
    override val syncsOnCarTrigger = true

    // The logger appends while the car runs, and the export serves rows from the stored cursor.
    override val liveMode = LiveMode.PULL

    override val label = R.string.kind_wican
    override val pairingText =
        PairingText(
            help = R.string.pairing_kind_wican_help,
            explain =
                listOf(
                    R.string.pairing_explain_ble,
                    R.string.pairing_explain_passkey,
                    R.string.pairing_explain_window,
                ),
            associate = R.string.pairing_associate_body,
            test = R.string.pairing_test_body,
        )

    // The logger appends while the car runs, so no cursor says when the next rows will exist.
    override fun nextDueAtMs(
        cursor: String?,
        zone: ZoneId,
    ): Long? = null

    override suspend fun <T> withSource(
        link: GattLink,
        options: SourceOptions,
        block: suspend (Source) -> T,
    ): T = withTransport(link) { block(WicanLoggerSource(it, options.pageLimit, options.clock)) }

    override suspend fun probe(
        link: GattLink,
        choices: PairingChoices,
    ): ProbeResult =
        withTransport(link) { transport ->
            val response = transport.request("GET", "/api/status")
            if (response.status != 200) throw IOException("GET /api/status answered ${response.status}")
            val version = parseStatusVersion(response.body) ?: throw IOException("GET /api/status carried no version")
            ProbeResult.Wican(version, link.mtu)
        }

    /** Runs [block] with a tunnel transport on [link], its reader living as long as [block]. */
    private suspend fun <T> withTransport(
        link: GattLink,
        block: suspend (BleHttpTransport) -> T,
    ): T = withReaderScope { block(BleHttpTransport(link, it)) }
}

/** `version` from a `GET /api/status` body (dev_status_manager `HTTP_API.md`), or null. */
internal fun parseStatusVersion(body: ByteArray): String? =
    try {
        (Json.parseToJsonElement(body.decodeToString()) as? JsonObject)
            ?.get("version")
            ?.jsonPrimitive
            ?.contentOrNull
            ?.takeIf { it.isNotBlank() }
    } catch (e: IllegalArgumentException) {
        // Malformed JSON (SerializationException) or a non-primitive `version`.
        null
    }
