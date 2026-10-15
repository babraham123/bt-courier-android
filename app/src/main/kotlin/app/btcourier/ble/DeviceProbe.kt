package app.btcourier.ble

import app.btcourier.core.transport.GattLink
import app.btcourier.core.transport.blehttp.BleHttpTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException

/** What a successful "test connection" learned. */
data class ProbeResult(val firmwareVersion: String, val mtu: Int)

/**
 * Runs `GET /api/status` through the `ble_http` tunnel on [link] and returns the firmware
 * version. Closes nothing: the caller owns [link].
 */
suspend fun probeStatus(link: GattLink): ProbeResult {
    // The transport's reader must outlive single calls, but not this probe.
    val scope = CoroutineScope(currentCoroutineContext() + SupervisorJob(currentCoroutineContext()[Job]))
    try {
        val response = BleHttpTransport(link, scope).request("GET", "/api/status")
        if (response.status != 200) throw IOException("GET /api/status answered ${response.status}")
        val version = parseStatusVersion(response.body)
            ?: throw IOException("GET /api/status carried no version")
        return ProbeResult(version, link.mtu)
    } finally {
        scope.cancel()
    }
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
