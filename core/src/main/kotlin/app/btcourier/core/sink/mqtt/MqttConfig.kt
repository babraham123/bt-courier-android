package app.btcourier.core.sink.mqtt

import javax.net.ssl.TrustManagerFactory

/** Protocol version [MqttUploadTransport] connects with first. */
enum class MqttVersion { V5, V3_1_1 }

/**
 * Broker username and optional password. Never revealed by `toString()`, so an [MqttConfig] can
 * sit in logs or crash reports.
 */
class MqttCredentials(val username: String, val password: String? = null) {
    override fun toString() = "MqttCredentials(***)"
}

/**
 * Where and how [MqttUploadTransport] publishes.
 *
 * @property port defaults to 8883 with [tls], 1883 without.
 * @property tls on unless plain TCP is explicitly chosen; credentials would otherwise cross the
 *   network in clear text.
 * @property trustManagerFactory for brokers with a private CA or self-signed certificate; null
 *   uses the platform trust store. Hostnames are verified either way.
 * @property clientId sent on every connection. Use one per device: two clients with the same id
 *   disconnect each other. Empty asks the broker to assign one (MQTT 5, or 3.1.1 with a broker
 *   that allows it).
 * @property baseTopic batches go to `<baseTopic>/<batch name>`; empty publishes at the batch name.
 *   A trailing `/` is ignored. Wildcards and the reserved `$` prefix are rejected.
 * @property fallbackToV311 with [MqttVersion.V5], retry a connection the broker rejects as an
 *   unsupported protocol with MQTT 3.1.1, and keep using 3.1.1 once that works.
 * @property connectTimeoutMs per phase: TCP connect, TLS handshake, and waiting for CONNACK.
 * @property publishTimeoutMs from sending PUBLISH to receiving PUBACK; size it for a 1 MB
 *   batch over the slowest uplink in use.
 */
data class MqttConfig(
    val host: String,
    val clientId: String,
    val baseTopic: String,
    val tls: Boolean = true,
    val port: Int = if (tls) 8883 else 1883,
    val credentials: MqttCredentials? = null,
    val version: MqttVersion = MqttVersion.V5,
    val fallbackToV311: Boolean = true,
    val trustManagerFactory: TrustManagerFactory? = null,
    val connectTimeoutMs: Long = 15_000,
    val publishTimeoutMs: Long = 60_000,
) {
    init {
        require(host.isNotBlank()) { "host is blank" }
        require(port in 1..65535) { "port $port out of range" }
        require(connectTimeoutMs > 0 && publishTimeoutMs > 0) { "timeouts must be positive" }
        require(baseTopic.none { it == '+' || it == '#' || it == '\u0000' }) { "baseTopic must not contain wildcards" }
        require(!baseTopic.startsWith("$")) { "baseTopic must not start with \$ (reserved for broker topics)" }
    }

    /** Topic a batch named [batchName] is published to. */
    fun topicFor(batchName: String): String {
        val base = baseTopic.trimEnd('/')
        return if (base.isEmpty()) batchName else "$base/$batchName"
    }
}
