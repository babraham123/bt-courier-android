package app.btcourier.core.sink.mqtt

import app.btcourier.core.sink.Batch
import app.btcourier.core.sink.UploadResult
import app.btcourier.core.sink.UploadTransport
import app.btcourier.core.sink.RetryBackoff
import com.hivemq.client.mqtt.MqttClientSslConfig
import com.hivemq.client.mqtt.MqttClientTransportConfig
import com.hivemq.client.mqtt.datatypes.MqttQos
import com.hivemq.client.mqtt.exceptions.MqttDecodeException
import com.hivemq.client.mqtt.exceptions.MqttEncodeException
import com.hivemq.client.mqtt.exceptions.MqttSessionExpiredException
import com.hivemq.client.mqtt.mqtt3.Mqtt3Client
import com.hivemq.client.mqtt.mqtt3.exceptions.Mqtt3ConnAckException
import com.hivemq.client.mqtt.mqtt3.message.auth.Mqtt3SimpleAuth
import com.hivemq.client.mqtt.mqtt3.message.connect.Mqtt3Connect
import com.hivemq.client.mqtt.mqtt3.message.connect.connack.Mqtt3ConnAckReturnCode
import com.hivemq.client.mqtt.mqtt3.message.publish.Mqtt3Publish
import com.hivemq.client.mqtt.mqtt5.Mqtt5Client
import com.hivemq.client.mqtt.mqtt5.datatypes.Mqtt5UserProperties
import com.hivemq.client.mqtt.mqtt5.datatypes.Mqtt5UserProperty
import com.hivemq.client.mqtt.mqtt5.exceptions.Mqtt5ConnAckException
import com.hivemq.client.mqtt.mqtt5.exceptions.Mqtt5DisconnectException
import com.hivemq.client.mqtt.mqtt5.exceptions.Mqtt5PubAckException
import com.hivemq.client.mqtt.mqtt5.message.auth.Mqtt5SimpleAuth
import com.hivemq.client.mqtt.mqtt5.message.connect.Mqtt5Connect
import com.hivemq.client.mqtt.mqtt5.message.connect.connack.Mqtt5ConnAckReasonCode
import com.hivemq.client.mqtt.mqtt5.message.disconnect.Mqtt5DisconnectReasonCode
import com.hivemq.client.mqtt.mqtt5.message.publish.Mqtt5Publish
import com.hivemq.client.mqtt.mqtt5.message.publish.Mqtt5PublishResult
import com.hivemq.client.mqtt.mqtt5.message.publish.puback.Mqtt5PubAckReasonCode
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.future.await
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit.MILLISECONDS
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Publishes each batch as one MQTT message to `<baseTopic>/<batch.name>` at QoS 1 with retain
 * off. The payload is the stored gzip bytes unchanged; batches are at most about 1 MB, so no
 * chunking is needed. Over MQTT 5 the message also carries `content-type` (`application/gzip`
 * for `.gz` names) and the user property `name=<batch.name>`.
 *
 * Idempotency is the subscriber's job: MQTT has no "store under this key" operation, so
 * uploading a batch twice (a retry after a lost PUBACK, or a re-send) delivers two messages with
 * the same topic and identical bytes. The batch name in the topic embeds a content hash, so a
 * subscriber that writes each message to a file named after the last topic level, or keeps the
 * names it has seen, dedupes for free.
 *
 * Retain is off, so a message only reaches subscriptions that exist when it is published. A
 * receiver that may be offline should hold a persistent session (MQTT 5 session expiry > 0, or
 * 3.1.1 clean session off) with a QoS 1 subscription so the broker queues for it.
 *
 * Each [upload] opens its own connection (clean start, no session kept) and closes it before
 * returning, so nothing stays open between batches and a failed attempt never leaves state
 * behind. Uploads on one instance are serialised: concurrent connections with one client id
 * would disconnect each other.
 *
 * Result mapping:
 * - PUBACK received: [UploadResult.Acked]. Nothing earlier counts; QoS 1 makes the broker
 *   responsible for the message only once it acknowledges it.
 * - MQTT 5 PUBACK `no matching subscribers`: [UploadResult.Retry]. The broker dropped the message
 *   because no subscription, persistent or live, would receive it; acking would lose the batch.
 *   MQTT 3.1.1 cannot report this.
 * - Connection refused, DNS or network errors, connection closed, connect or publish timeout,
 *   and transient broker answers (server unavailable or busy, quota or rate exceeded):
 *   [UploadResult.Retry]. After a publish timeout the message may or may not have arrived; the
 *   retry can duplicate it, which the subscriber dedupes as above.
 * - A client id or credentials the client refuses to send (e.g. longer than MQTT allows), CONNACK
 *   refusing the credentials, client id or protocol, PUBACK `not authorized` or
 *   `topic name invalid`, a payload above the broker's advertised maximum packet size (MQTT 5),
 *   or a broker DISCONNECT for `packet too large`: [UploadResult.Failed]; these need a config
 *   change. A 3.1.1 broker that rejects a large payload just closes the connection, which is
 *   indistinguishable from a network drop and therefore retried.
 *
 * [UploadResult.Retry.afterMs] is the HTTP transports' exponential back-off ([RetryBackoff]) over
 * this instance's consecutive retries, reset by any non-retry result.
 *
 * [close] aborts an in-flight upload (it returns [UploadResult.Retry]) and makes later uploads
 * return [UploadResult.Failed]. It is idempotent.
 */
class MqttUploadTransport(private val config: MqttConfig) : UploadTransport, Closeable {
    private val mutex = Mutex()
    private val closed = AtomicBoolean(false)
    private val backoff = RetryBackoff()

    @Volatile private var active: Connection? = null

    @Volatile private var version = config.version

    override suspend fun upload(batch: Batch): UploadResult {
        val result = mutex.withLock {
            if (closed.get()) UploadResult.Failed("MQTT transport is closed") else deliver(batch)
        }
        if (result !is UploadResult.Retry) {
            backoff.reset()
            return result
        }
        return backoff.retry(result.reason, null)
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) active?.disconnect()
    }

    private suspend fun deliver(batch: Batch): UploadResult {
        val topic = config.topicFor(batch.name)
        if (version == MqttVersion.V3_1_1) return session({ V3Connection() }, topic, batch).result
        val v5 = session({ V5Connection() }, topic, batch)
        if (!v5.protocolRejected || !config.fallbackToV311) return v5.result
        val v3 = session({ V3Connection() }, topic, batch)
        // Later batches skip the MQTT 5 attempt the broker is known to refuse.
        if (v3.connected) version = MqttVersion.V3_1_1
        return v3.result
    }

    private class Attempt(val result: UploadResult, val connected: Boolean, val protocolRejected: Boolean = false)

    private suspend fun session(newConnection: () -> Connection, topic: String, batch: Batch): Attempt {
        val conn: Connection
        val connecting: CompletableFuture<*>
        try {
            conn = newConnection().also { active = it }
            connecting = conn.connect()
        } catch (e: RuntimeException) {
            active = null
            return Attempt(rejectedConfig(e, batch.name), connected = false)
        }
        try {
            if (closed.get()) return Attempt(UploadResult.Retry("MQTT transport closed during upload"), connected = false)
            try {
                // A copy, so the guard timeout never completes the client's own future (see release()).
                connecting.copy().orTimeout(connectGuardMs, MILLISECONDS).await()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                return Attempt(classify(e, batch.name), connected = false, protocolRejected = isProtocolRejection(e))
            }
            if (closed.get()) return Attempt(UploadResult.Retry("MQTT transport closed during upload"), connected = true)
            val result = try {
                conn.publish(topic, batch).copy().orTimeout(config.publishTimeoutMs, MILLISECONDS).await()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                classify(e, batch.name)
            }
            return Attempt(result, connected = true)
        } finally {
            active = null
            withContext(NonCancellable) { release(conn, connecting) }
        }
    }

    /**
     * HiveMQ validates the client id when the client is built and the credentials while CONNECT is
     * built, throwing synchronously before anything reaches the network. The same config fails the
     * same way every time, so this sink fails instead of aborting the upload round. The client's
     * message is left out: it can quote the rejected username or a 64 KB client id.
     */
    private fun rejectedConfig(e: RuntimeException, name: String): UploadResult {
        if (e is CancellationException || (e !is IllegalArgumentException && e !is IllegalStateException)) throw e
        return UploadResult.Failed("MQTT upload of $name: client rejected the client id or credentials (${e.javaClass.simpleName})")
    }

    /**
     * Disconnects once the connect attempt has settled. Disconnecting mid-handshake would fail and
     * let a late CONNACK leave an orphaned connection open; waiting on the client's own future
     * (not the timed-out copy) closes it whenever the attempt ends.
     */
    private suspend fun release(conn: Connection, connecting: CompletableFuture<*>) {
        val disconnected = connecting.handle { _, _ -> }.thenCompose { conn.disconnect() }
        if (!connecting.isDone) return
        try {
            disconnected.copy().orTimeout(DISCONNECT_TIMEOUT_MS, MILLISECONDS).await()
        } catch (_: Exception) {
            // Not connected (connect failed) or the broker went away: nothing left to close.
        }
    }

    // Each of TCP connect, TLS handshake and CONNACK has its own client-side timeout; this only
    // guards against the client never settling the attempt.
    private val connectGuardMs = 3 * config.connectTimeoutMs + DISCONNECT_TIMEOUT_MS

    private fun classify(error: Throwable, name: String): UploadResult {
        val e = unwrap(error)
        val what = "MQTT upload of $name"
        return when (e) {
            is TimeoutException -> UploadResult.Retry("$what timed out")
            is Mqtt5ConnAckException -> e.mqttMessage.reasonCode.let {
                if (it in transientConnAck) UploadResult.Retry("$what: CONNACK $it") else UploadResult.Failed("$what: CONNACK $it")
            }
            is Mqtt3ConnAckException -> e.mqttMessage.returnCode.let {
                if (it == Mqtt3ConnAckReturnCode.SERVER_UNAVAILABLE) UploadResult.Retry("$what: CONNACK $it") else UploadResult.Failed("$what: CONNACK $it")
            }
            is Mqtt5PubAckException -> e.mqttMessage.reasonCode.let {
                if (it in permanentPubAck) UploadResult.Failed("$what: PUBACK $it") else UploadResult.Retry("$what: PUBACK $it")
            }
            is Mqtt5DisconnectException if e.sentByServer -> e.mqttMessage.reasonCode.let {
                val reason = e.mqttMessage.reasonString.map { s -> " ($s)" }.orElse("")
                if (it in permanentDisconnect) UploadResult.Failed("$what: DISCONNECT $it$reason") else UploadResult.Retry("$what: DISCONNECT $it$reason")
            }
            // The broker answered with bytes that aren't valid in the negotiated protocol (e.g. a
            // 3.1.1 broker's CONNACK to an MQTT 5 CONNECT); it will do so again.
            is Exception if e.cause is MqttDecodeException -> UploadResult.Failed("$what: ${e.message}")
            // The client refuses to encode a PUBLISH above the broker's maximum packet size; the
            // same bytes will be refused again.
            is MqttEncodeException -> UploadResult.Failed("$what: ${e.message}")
            is IllegalArgumentException -> UploadResult.Failed("$what: ${e.message}")
            else -> UploadResult.Retry("$what: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    /**
     * A broker without MQTT 5 answers a v5 CONNECT with a 3.1.1 CONNACK `unacceptable protocol
     * version`, which the v5 client cannot decode (Moquette, older Mosquitto), or with a v5
     * CONNACK saying the same.
     */
    private fun isProtocolRejection(error: Throwable): Boolean =
        when (val e = unwrap(error)) {
            is Mqtt5ConnAckException -> e.mqttMessage.reasonCode == Mqtt5ConnAckReasonCode.UNSUPPORTED_PROTOCOL_VERSION
            is Mqtt5DisconnectException -> !e.sentByServer && e.cause is MqttDecodeException
            else -> false
        }

    // HiveMQ raises the same type for a DISCONNECT it sends itself (CONNACK timeout, decode error)
    // as for one the broker sent; only the latter carries the broker's verdict. This message is
    // the client's fixed marker for the broker-sent case.
    private val Mqtt5DisconnectException.sentByServer get() = message == "Server sent DISCONNECT."

    /** Futures wrap the real error, and in-flight publishes report a lost connection as an expired session. */
    private fun unwrap(error: Throwable): Throwable {
        var e = error
        while ((e is CompletionException || e is ExecutionException || e is MqttSessionExpiredException) && e.cause != null) {
            e = e.cause!!
        }
        return e
    }

    private fun transportConfig(): MqttClientTransportConfig {
        val builder = MqttClientTransportConfig.builder()
            .serverHost(config.host)
            .serverPort(config.port)
            .socketConnectTimeout(config.connectTimeoutMs, MILLISECONDS)
            .mqttConnectTimeout(config.connectTimeoutMs, MILLISECONDS)
        if (config.tls) {
            builder.sslConfig(
                MqttClientSslConfig.builder()
                    .trustManagerFactory(config.trustManagerFactory)
                    .handshakeTimeout(config.connectTimeoutMs, MILLISECONDS)
                    .build(),
            )
        }
        return builder.build()
    }

    private fun contentTypeOf(name: String) = if (name.endsWith(".gz")) "application/gzip" else "application/octet-stream"

    /** One broker connection in one protocol version; used for a single upload, then disconnected. */
    private interface Connection {
        fun connect(): CompletableFuture<*>

        fun publish(topic: String, batch: Batch): CompletableFuture<UploadResult>

        fun disconnect(): CompletableFuture<Void>
    }

    private inner class V5Connection : Connection {
        private val client = Mqtt5Client.builder()
            .identifier(config.clientId)
            .transportConfig(transportConfig())
            .buildAsync()

        override fun connect(): CompletableFuture<*> {
            val connect = Mqtt5Connect.builder().cleanStart(true).sessionExpiryInterval(0).keepAlive(KEEP_ALIVE_S)
            config.credentials?.let { c ->
                val auth = Mqtt5SimpleAuth.builder().username(c.username)
                c.password?.let { auth.password(it.encodeToByteArray()) }
                connect.simpleAuth(auth.build())
            }
            return client.connect(connect.build())
        }

        override fun publish(topic: String, batch: Batch): CompletableFuture<UploadResult> {
            val publish = Mqtt5Publish.builder()
                .topic(topic)
                .qos(MqttQos.AT_LEAST_ONCE)
                .retain(false)
                .payload(batch.bytes)
                .contentType(contentTypeOf(batch.name))
                .userProperties(Mqtt5UserProperties.of(Mqtt5UserProperty.of("name", batch.name)))
                .build()
            return client.publish(publish).thenApply { result -> resultOf(result, batch.name) }
        }

        private fun resultOf(result: Mqtt5PublishResult, name: String): UploadResult {
            result.error.orElse(null)?.let { return classify(it, name) }
            val code = (result as? Mqtt5PublishResult.Mqtt5Qos1Result)?.pubAck?.reasonCode
            return if (code == Mqtt5PubAckReasonCode.NO_MATCHING_SUBSCRIBERS) {
                UploadResult.Retry("MQTT upload of $name: no subscriber would receive it (PUBACK $code)")
            } else {
                UploadResult.Acked
            }
        }

        override fun disconnect(): CompletableFuture<Void> = client.disconnect()
    }

    private inner class V3Connection : Connection {
        private val client = Mqtt3Client.builder()
            .identifier(config.clientId)
            .transportConfig(transportConfig())
            .buildAsync()

        override fun connect(): CompletableFuture<*> {
            val connect = Mqtt3Connect.builder().cleanSession(true).keepAlive(KEEP_ALIVE_S)
            config.credentials?.let { c ->
                val auth = Mqtt3SimpleAuth.builder().username(c.username)
                c.password?.let { auth.password(it.encodeToByteArray()) }
                connect.simpleAuth(auth.build())
            }
            return client.connect(connect.build())
        }

        override fun publish(topic: String, batch: Batch): CompletableFuture<UploadResult> {
            val publish = Mqtt3Publish.builder()
                .topic(topic)
                .qos(MqttQos.AT_LEAST_ONCE)
                .retain(false)
                .payload(batch.bytes)
                .build()
            return client.publish(publish).thenApply { UploadResult.Acked }
        }

        override fun disconnect(): CompletableFuture<Void> = client.disconnect()
    }

    private companion object {
        const val KEEP_ALIVE_S = 60
        const val DISCONNECT_TIMEOUT_MS = 5_000L

        val transientConnAck = setOf(
            Mqtt5ConnAckReasonCode.UNSPECIFIED_ERROR,
            Mqtt5ConnAckReasonCode.IMPLEMENTATION_SPECIFIC_ERROR,
            Mqtt5ConnAckReasonCode.SERVER_UNAVAILABLE,
            Mqtt5ConnAckReasonCode.SERVER_BUSY,
            Mqtt5ConnAckReasonCode.QUOTA_EXCEEDED,
            Mqtt5ConnAckReasonCode.CONNECTION_RATE_EXCEEDED,
        )

        val permanentPubAck = setOf(
            Mqtt5PubAckReasonCode.NOT_AUTHORIZED,
            Mqtt5PubAckReasonCode.TOPIC_NAME_INVALID,
            Mqtt5PubAckReasonCode.PAYLOAD_FORMAT_INVALID,
        )

        val permanentDisconnect = setOf(
            Mqtt5DisconnectReasonCode.MALFORMED_PACKET,
            Mqtt5DisconnectReasonCode.PROTOCOL_ERROR,
            Mqtt5DisconnectReasonCode.NOT_AUTHORIZED,
            Mqtt5DisconnectReasonCode.BAD_AUTHENTICATION_METHOD,
            Mqtt5DisconnectReasonCode.TOPIC_NAME_INVALID,
            Mqtt5DisconnectReasonCode.TOPIC_ALIAS_INVALID,
            Mqtt5DisconnectReasonCode.PACKET_TOO_LARGE,
            Mqtt5DisconnectReasonCode.PAYLOAD_FORMAT_INVALID,
            Mqtt5DisconnectReasonCode.RETAIN_NOT_SUPPORTED,
            Mqtt5DisconnectReasonCode.QOS_NOT_SUPPORTED,
            Mqtt5DisconnectReasonCode.USE_ANOTHER_SERVER,
            Mqtt5DisconnectReasonCode.SERVER_MOVED,
        )
    }
}
