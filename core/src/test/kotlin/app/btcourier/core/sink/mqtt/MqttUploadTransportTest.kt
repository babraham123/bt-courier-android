package app.btcourier.core.sink.mqtt

import app.btcourier.core.sink.Batch
import app.btcourier.core.sink.Gzip
import app.btcourier.core.sink.UploadResult
import app.btcourier.core.sink.RetryBackoff.Companion.INITIAL_BACKOFF_MS
import app.btcourier.core.sink.mqtt.StubBroker.Companion.maximumPacketSize
import app.btcourier.core.sink.mqtt.StubBroker.Companion.v3Connack
import app.btcourier.core.sink.mqtt.StubBroker.Companion.v3Puback
import app.btcourier.core.sink.mqtt.StubBroker.Companion.v5Connack
import app.btcourier.core.sink.mqtt.StubBroker.Companion.v5Disconnect
import app.btcourier.core.sink.mqtt.StubBroker.Companion.v5Puback
import com.hivemq.client.mqtt.datatypes.MqttQos
import com.hivemq.client.mqtt.mqtt3.Mqtt3Client
import com.hivemq.client.mqtt.mqtt3.message.publish.Mqtt3Publish
import io.moquette.broker.security.IAuthenticator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

/**
 * Moquette 0.17 speaks only MQTT 3.1.1 (it answers an MQTT 5 CONNECT with a 3.1.1 CONNACK
 * `unacceptable protocol version`), so MQTT 5 behaviour is checked against [StubBroker], which
 * records the PUBLISH bytes and properties exactly as the client put them on the wire.
 */
class MqttUploadTransportTest {
    private val content = "{\"_file\":\"1700000000.jsonl\"}\n{\"ts\":1}\n".repeat(2_000).encodeToByteArray()
    private val batch = Batch("7", Gzip.compress(content), "wican-1700000000_0-0123456789abcdef.jsonl.gz")
    private val topic = "btcourier/uploads/${batch.name}"

    private val resources = mutableListOf<AutoCloseable>()

    private fun <T : AutoCloseable> T.closedAfterTest(): T = also { resources += it }

    @AfterEach
    fun closeResources() = resources.asReversed().forEach { runCatching { it.close() } }

    private fun config(
        port: Int,
        version: MqttVersion = MqttVersion.V5,
        credentials: MqttCredentials? = null,
        clientId: String = "courier-test",
        fallbackToV311: Boolean = true,
        connectTimeoutMs: Long = 5_000,
        publishTimeoutMs: Long = 5_000,
    ) = MqttConfig(
        host = "127.0.0.1",
        clientId = clientId,
        baseTopic = "btcourier/uploads/",
        tls = false,
        port = port,
        credentials = credentials,
        version = version,
        fallbackToV311 = fallbackToV311,
        connectTimeoutMs = connectTimeoutMs,
        publishTimeoutMs = publishTimeoutMs,
    )

    private fun transport(config: MqttConfig) = MqttUploadTransport(config).closedAfterTest()

    private fun retry(result: UploadResult) = assertInstanceOf(UploadResult.Retry::class.java, result) { "got $result" }

    private fun failed(result: UploadResult) = assertInstanceOf(UploadResult.Failed::class.java, result) { "got $result" }

    private fun mqtt5Stub(puback: (Int) -> StubBroker.Reply = { v5Puback(it, 0) }) =
        StubBroker(connack = { v5Connack() }, puback = puback).closedAfterTest()

    /** A receiver with a QoS 1 subscription to everything under the base topic. */
    private inner class Subscriber(port: Int, credentials: MqttCredentials? = null) : AutoCloseable {
        private val messages = LinkedBlockingQueue<Mqtt3Publish>()
        private val client = Mqtt3Client.builder()
            .identifier("receiver")
            .serverHost("127.0.0.1")
            .serverPort(port)
            .buildBlocking()

        init {
            val connect = client.connectWith()
            credentials?.let { connect.simpleAuth().username(it.username).password(it.password!!.encodeToByteArray()).applySimpleAuth() }
            connect.send()
            client.toAsync().subscribeWith()
                .topicFilter("btcourier/uploads/#")
                .qos(MqttQos.AT_LEAST_ONCE)
                .callback { messages.add(it) }
                .send()
                .get(5, TimeUnit.SECONDS)
            closedAfterTest()
        }

        fun next(): Mqtt3Publish = messages.poll(5, TimeUnit.SECONDS) ?: fail("no message received")

        fun assertNoMore() = assertNull(messages.poll(300, TimeUnit.MILLISECONDS))

        override fun close() = client.disconnect()
    }

    @ParameterizedTest
    @EnumSource(MqttVersion::class)
    fun `a 3_1_1 broker delivers the exact batch bytes at QoS 1 without retain to base topic slash batch name`(version: MqttVersion) = runTest {
        val broker = EmbeddedBroker().closedAfterTest()
        val receiver = Subscriber(broker.port)

        assertEquals(UploadResult.Acked, transport(config(broker.port, version)).upload(batch))

        val message = receiver.next()
        assertEquals(topic, message.topic.toString())
        assertArrayEquals(batch.bytes, message.payloadAsBytes)
        assertEquals(MqttQos.AT_LEAST_ONCE, message.qos)
        assertFalse(message.isRetain)
        receiver.assertNoMore()
        assertEquals(1, broker.connectedClients, "only the receiver stays connected")
    }

    @Test
    fun `MQTT 5 publishes carry QoS 1, no retain, the exact bytes, content type and the batch name`() = runTest {
        val stub = mqtt5Stub()

        assertEquals(UploadResult.Acked, transport(config(stub.port)).upload(batch))

        val message = stub.published.single()
        assertEquals(topic, message.topic)
        assertEquals(1, message.qos)
        assertFalse(message.retain)
        assertArrayEquals(batch.bytes, message.payload)
        assertEquals("application/gzip", message.contentType)
        assertEquals(listOf("name" to batch.name), message.userProperties)
        assertEquals(listOf(5), stub.protocolLevels)
        assertTrue(stub.awaitNoConnections(), "connection left open after an upload")
    }

    @Test
    fun `uploading the same batch twice delivers two identical messages that the receiver dedupes by topic`() = runTest {
        val broker = EmbeddedBroker().closedAfterTest()
        val receiver = Subscriber(broker.port)
        val transport = transport(config(broker.port))

        assertEquals(UploadResult.Acked, transport.upload(batch))
        assertEquals(UploadResult.Acked, transport.upload(batch))

        val first = receiver.next()
        val second = receiver.next()
        assertEquals(topic, first.topic.toString())
        assertEquals(first.topic, second.topic)
        assertArrayEquals(first.payloadAsBytes, second.payloadAsBytes)
    }

    @ParameterizedTest
    @EnumSource(MqttVersion::class)
    fun `wrong or missing credentials fail permanently and right ones are accepted`(version: MqttVersion) = runTest {
        val good = MqttCredentials("courier", "s3cret")
        val broker = EmbeddedBroker(IAuthenticator { _, user, password -> user == "courier" && password?.decodeToString() == "s3cret" })
            .closedAfterTest()
        val receiver = Subscriber(broker.port, good)

        failed(transport(config(broker.port, version, MqttCredentials("courier", "wrong"))).upload(batch))
        failed(transport(config(broker.port, version)).upload(batch))
        assertEquals(UploadResult.Acked, transport(config(broker.port, version, good)).upload(batch))
        assertEquals(topic, receiver.next().topic.toString())
        receiver.assertNoMore()
    }

    @Test
    fun `MQTT 5 CONNACK - bad credentials fail, server unavailable retries`() = runTest {
        val badCredentials = StubBroker(connack = { v5Connack(0x86) }).closedAfterTest()
        val unavailable = StubBroker(connack = { v5Connack(0x88) }).closedAfterTest()

        failed(transport(config(badCredentials.port, credentials = MqttCredentials("courier", "wrong"))).upload(batch))
        retry(transport(config(unavailable.port)).upload(batch))
        assertEquals(listOf(5), badCredentials.protocolLevels, "a refusal is not a reason to fall back to 3.1.1")
    }

    @Test
    fun `an unreachable broker is retried with the HTTP transports' exponential back-off`() = runTest {
        val port = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { it.localPort }
        val transport = transport(config(port))

        assertEquals(INITIAL_BACKOFF_MS, retry(transport.upload(batch)).afterMs)
        assertEquals(2 * INITIAL_BACKOFF_MS, retry(transport.upload(batch)).afterMs)
    }

    @Test
    fun `a missing PUBACK times out as a retry, closes the connection, and success resets the back-off`() = runTest {
        val publishes = AtomicInteger()
        val stub = mqtt5Stub(puback = { id -> if (publishes.incrementAndGet() == 2) v5Puback(id, 0) else StubBroker.Reply.Silent })
        val transport = transport(config(stub.port, publishTimeoutMs = 300))

        assertEquals(INITIAL_BACKOFF_MS, retry(transport.upload(batch)).afterMs)
        assertTrue(stub.awaitNoConnections(), "connection left open after a publish timeout")
        assertEquals(UploadResult.Acked, transport.upload(batch))
        assertEquals(INITIAL_BACKOFF_MS, retry(transport.upload(batch)).afterMs)
        assertEquals(listOf(topic, topic, topic), stub.published.map { it.topic })
        assertTrue(stub.awaitNoConnections())
    }

    @Test
    fun `a broker that never answers CONNECT times out as a retry without falling back`() = runTest {
        val stub = StubBroker(connack = { StubBroker.Reply.Silent }).closedAfterTest()

        retry(transport(config(stub.port, connectTimeoutMs = 300)).upload(batch))
        assertTrue(stub.awaitNoConnections(), "connection left open after a connect timeout")
        assertEquals(listOf(5), stub.protocolLevels)
        assertEquals(emptyList<StubBroker.Published>(), stub.published)
    }

    @Test
    fun `a payload above the broker's maximum packet size fails without being sent`() = runTest {
        val stub = StubBroker(connack = { v5Connack(properties = maximumPacketSize(1024)) }).closedAfterTest()
        val big = batch.copy(bytes = Random(1).nextBytes(4096))

        failed(transport(config(stub.port)).upload(big))
        assertEquals(emptyList<StubBroker.Published>(), stub.published)
        assertTrue(stub.awaitNoConnections())
    }

    @Test
    fun `a broker DISCONNECT for packet too large fails`() = runTest {
        val stub = mqtt5Stub(puback = { v5Disconnect(0x95) })
        failed(transport(config(stub.port)).upload(batch))
    }

    @Test
    fun `MQTT 5 PUBACK - no matching subscribers retries, not authorized fails`() = runTest {
        val noSubscribers = mqtt5Stub(puback = { v5Puback(it, 0x10) })
        val notAuthorized = mqtt5Stub(puback = { v5Puback(it, 0x87) })

        retry(transport(config(noSubscribers.port)).upload(batch))
        failed(transport(config(notAuthorized.port)).upload(batch))
    }

    @Test
    fun `a broker without MQTT 5 is retried with 3_1_1, which is then used for later batches`() = runTest {
        val stub = StubBroker(
            connack = { level -> if (level == 5) v3Connack(1) else v3Connack(0) },
            puback = { v3Puback(it) },
        ).closedAfterTest()
        val transport = transport(config(stub.port))

        assertEquals(UploadResult.Acked, transport.upload(batch))
        assertEquals(UploadResult.Acked, transport.upload(batch))
        assertEquals(listOf(5, 4, 4), stub.protocolLevels)
        assertEquals(listOf(topic, topic), stub.published.map { it.topic })
        assertArrayEquals(batch.bytes, stub.published.last().payload)
    }

    @Test
    fun `without fallback a broker without MQTT 5 fails`() = runTest {
        val stub = StubBroker(connack = { level -> if (level == 5) v3Connack(1) else v3Connack(0) }).closedAfterTest()

        failed(transport(config(stub.port, fallbackToV311 = false)).upload(batch))
        assertEquals(listOf(5), stub.protocolLevels)
    }

    @Test
    fun `close aborts an in-flight upload, is idempotent, and refuses later uploads`() = runTest {
        val stub = mqtt5Stub(puback = { StubBroker.Reply.Silent })
        val transport = transport(config(stub.port, publishTimeoutMs = 60_000))

        val pending = async(Dispatchers.Default) { transport.upload(batch) }
        withContext(Dispatchers.Default) {
            withTimeout(10_000) {
                select {
                    stub.firstPublish.onAwait {}
                    pending.onAwait { fail("upload ended before publishing: $it") }
                }
            }
        }
        transport.close()
        transport.close()

        retry(withContext(Dispatchers.Default) { withTimeout(10_000) { pending.await() } })
        assertTrue(stub.awaitNoConnections(), "connection left open after close")
        failed(transport.upload(batch))
        assertEquals(1, stub.protocolLevels.size)
    }

    @ParameterizedTest
    @EnumSource(MqttVersion::class)
    fun `a client id or password the MQTT client rejects fails the upload without reaching the broker or leaking it`(version: MqttVersion) = runTest {
        val stub = mqtt5Stub()
        // MQTT strings and binary fields are length-prefixed with 16 bits; MqttConfig doesn't check that.
        val tooLong = "x".repeat(65_536)
        val longId = config(stub.port, version, clientId = tooLong)
        val longPassword = config(stub.port, version, credentials = MqttCredentials("courier", tooLong))

        for (config in listOf(longId, longPassword)) {
            val result = failed(transport(config).upload(batch))
            assertFalse(tooLong in result.reason, "reason quotes the rejected value")
        }
        assertEquals(emptyList<Int>(), stub.protocolLevels)
    }

    @Test
    fun `config defaults to TLS on 8883, hides credentials, and validates the base topic`() {
        val config = MqttConfig("broker.example", "phone-1", "cars/", credentials = MqttCredentials("courier", "hunter2"))

        assertTrue(config.tls)
        assertEquals(8883, config.port)
        assertEquals(1883, MqttConfig("broker.example", "phone-1", "cars", tls = false).port)
        assertEquals(MqttVersion.V5, config.version)
        assertFalse("hunter2" in config.toString())
        assertFalse("courier" in config.toString())
        assertEquals("cars/${batch.name}", config.topicFor(batch.name))
        assertEquals(batch.name, config.copy(baseTopic = "").topicFor(batch.name))
        assertThrows(IllegalArgumentException::class.java) { config.copy(baseTopic = "cars/+") }
        assertThrows(IllegalArgumentException::class.java) { config.copy(baseTopic = "cars/#") }
        assertThrows(IllegalArgumentException::class.java) { config.copy(baseTopic = "\$SYS/cars") }
    }
}
