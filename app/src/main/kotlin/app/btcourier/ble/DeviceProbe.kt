package app.btcourier.ble

import app.btcourier.core.source.colmi.Battery
import app.btcourier.core.source.colmi.ColmiPacketCodec
import app.btcourier.core.transport.GattLink
import app.btcourier.core.transport.blehttp.BleHttpTransport
import app.btcourier.data.DeviceKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.timeout
import kotlinx.coroutines.flow.transform
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** What a successful "test connection" learned. */
sealed interface ProbeResult {
    data class Wican(val firmwareVersion: String, val mtu: Int) : ProbeResult

    data class Ring(val battery: Battery) : ProbeResult
}

/** The pair wizard's "test connection" for [kind]. Closes nothing: the caller owns [link]. */
suspend fun probe(link: GattLink, kind: DeviceKind): ProbeResult = when (kind) {
    DeviceKind.WICAN -> probeStatus(link)
    DeviceKind.COLMI_RING -> ProbeResult.Ring(probeBattery(link))
}

/**
 * Runs `GET /api/status` through the `ble_http` tunnel on [link] and returns the firmware
 * version. Closes nothing: the caller owns [link].
 */
suspend fun probeStatus(link: GattLink): ProbeResult.Wican {
    // The transport's reader must outlive single calls, but not this probe.
    val scope = CoroutineScope(currentCoroutineContext() + SupervisorJob(currentCoroutineContext()[Job]))
    try {
        val response = BleHttpTransport(link, scope).request("GET", "/api/status")
        if (response.status != 200) throw IOException("GET /api/status answered ${response.status}")
        val version = parseStatusVersion(response.body)
            ?: throw IOException("GET /api/status carried no version")
        return ProbeResult.Wican(version, link.mtu)
    } finally {
        scope.cancel()
    }
}

/**
 * Asks a Colmi ring for its battery (`0x03`, written without response like every ring command)
 * and waits up to [timeout] for the answer, skipping notifications of other commands. Leaves the
 * ring's clock alone: setting it is the sync's job. Closes nothing: the caller owns [link].
 *
 * @throws IOException on timeout or link loss.
 */
@OptIn(FlowPreview::class)
suspend fun probeBattery(link: GattLink, timeout: Duration = 5.seconds): Battery {
    link.write(ColmiPacketCodec.batteryRequest(), false)
    val answer = try {
        link.notifications
            .timeout(timeout)
            .transform { pdu -> ColmiPacketCodec.packets(pdu).forEach { emit(it) } }
            .firstOrNull { ColmiPacketCodec.isValid(it) && it[0].toInt() == ColmiPacketCodec.CMD_BATTERY }
    } catch (e: TimeoutCancellationException) {
        throw IOException("no battery answer within $timeout", e)
    }
    return ColmiPacketCodec.parseBattery(answer ?: throw IOException("link dropped before the battery answer"))
}

/** `version` from a `GET /api/status` body (dev_status_manager `HTTP_API.md`), or null. */
internal fun parseStatusVersion(body: ByteArray): String? = try {
    (Json.parseToJsonElement(body.decodeToString()) as? JsonObject)
        ?.get("version")
        ?.jsonPrimitive
        ?.contentOrNull
        ?.takeIf { it.isNotBlank() }
} catch (e: IllegalArgumentException) {
    // Malformed JSON (SerializationException) or a non-primitive `version`.
    null
}
