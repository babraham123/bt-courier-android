package app.btcourier.core.sink.mqtt

import io.moquette.broker.Server
import io.moquette.broker.config.MemoryConfig
import io.moquette.broker.security.IAuthenticator
import kotlinx.coroutines.CompletableDeferred
import java.io.DataInputStream
import java.io.IOException
import java.net.BindException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Properties
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/** Moquette on a loopback ephemeral port, plain TCP, no persistence. */
class EmbeddedBroker(
    authenticator: IAuthenticator? = null,
) : AutoCloseable {
    private val server: Server
    val port: Int

    init {
        val (server, port) = startOnFreePort(authenticator)
        this.server = server
        this.port = port
    }

    val connectedClients: Int get() = server.listConnectedClients().size

    override fun close() = server.stopServer()

    private companion object {
        const val BIND_ATTEMPTS = 5

        // Moquette records the port it bound for "0" from a Netty listener that can run after
        // startServer returns, so the broker gets a port probed free beforehand instead. Another
        // test fork can take that port in between, which fails the bind; a fresh port is tried then.
        fun startOnFreePort(authenticator: IAuthenticator?): Pair<Server, Int> {
            for (attempt in 1..BIND_ATTEMPTS) {
                val port = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { it.localPort }
                val server = Server()
                try {
                    server.startServer(config(port, authenticator), emptyList(), null, authenticator, null)
                    return server to port
                } catch (e: RuntimeException) {
                    if (e.cause !is BindException || attempt == BIND_ATTEMPTS) throw e
                    // A failed bind leaves the broker's event loops running.
                    runCatching { server.stopServer() }
                }
            }
            error("unreachable: the last attempt returns or throws")
        }

        fun config(
            port: Int,
            authenticator: IAuthenticator?,
        ) = MemoryConfig(
            Properties().apply {
                setProperty("host", "127.0.0.1")
                setProperty("port", port.toString())
                setProperty("websocket_port", "disabled")
                setProperty("persistence_enabled", "false")
                setProperty("telemetry_enabled", "false")
                setProperty("allow_anonymous", (authenticator == null).toString())
                // Moquette's default limit is 8 KB; real batches go up to 1 MB.
                setProperty("netty.mqtt.message_size", (2 * 1024 * 1024).toString())
            },
        )
    }
}

/**
 * A scripted broker speaking just enough MQTT to exercise paths a real broker won't produce on
 * demand: silence, malformed-for-v5 CONNACKs, chosen reason codes and CONNACK properties.
 *
 * @param connack reply to a CONNECT carrying the given protocol level (4 = 3.1.1, 5 = MQTT 5).
 * @param puback reply to a QoS 1 PUBLISH with the given packet id.
 */
class StubBroker(
    private val connack: (level: Int) -> Reply,
    private val puback: (packetId: Int) -> Reply = { Reply.Silent },
) : AutoCloseable {
    class Reply(
        val bytes: ByteArray?,
        val thenClose: Boolean = false,
    ) {
        companion object {
            val Silent = Reply(null)
        }
    }

    private val server = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
    private val sockets = CopyOnWriteArrayList<Socket>()

    val port: Int = server.localPort
    val protocolLevels = CopyOnWriteArrayList<Int>()
    val published = CopyOnWriteArrayList<Published>()

    /** Completes when the first PUBLISH has been recorded in [published]. */
    val firstPublish = CompletableDeferred<Unit>()
    val openConnections = AtomicInteger()

    init {
        thread(isDaemon = true, name = "stub-broker-accept") {
            while (!server.isClosed) {
                val socket =
                    try {
                        server.accept()
                    } catch (_: IOException) {
                        break
                    }
                sockets += socket
                openConnections.incrementAndGet()
                thread(isDaemon = true, name = "stub-broker-conn") {
                    try {
                        socket.use { serve(it) }
                    } catch (_: IOException) {
                        // Client went away.
                    } finally {
                        openConnections.decrementAndGet()
                    }
                }
            }
        }
    }

    /** A PUBLISH as it arrived on the wire; [contentType] and [userProperties] only exist in MQTT 5. */
    class Published(
        val topic: String,
        val qos: Int,
        val retain: Boolean,
        val payload: ByteArray,
        val contentType: String?,
        val userProperties: List<Pair<String, String>>,
    )

    private fun serve(socket: Socket) {
        val input = DataInputStream(socket.getInputStream())
        val output = socket.getOutputStream()
        var level = 0
        while (true) {
            val header = input.read()
            if (header < 0) return
            val body = ByteArray(readVarInt(input)).also { input.readFully(it) }
            val reply =
                when (header shr 4) {
                    CONNECT -> {
                        val nameLength = u16(body, 0)
                        level = body[2 + nameLength].toInt()
                        protocolLevels += level
                        connack(level)
                    }

                    PUBLISH -> {
                        val qos = (header shr 1) and 3
                        val reader = Reader(body)
                        val topic = reader.string()
                        val packetId = if (qos > 0) reader.u16() else 0
                        var contentType: String? = null
                        val userProperties = mutableListOf<Pair<String, String>>()
                        if (level == 5) {
                            val end = reader.varInt() + reader.at
                            while (reader.at < end) {
                                when (val id = reader.byte()) {
                                    0x01 -> reader.byte()
                                    0x03 -> contentType = reader.string()
                                    0x26 -> userProperties += reader.string() to reader.string()
                                    else -> error("unexpected PUBLISH property $id")
                                }
                            }
                        }
                        published +=
                            Published(
                                topic,
                                qos,
                                header and 1 == 1,
                                body.copyOfRange(reader.at, body.size),
                                contentType,
                                userProperties,
                            )
                        firstPublish.complete(Unit)
                        if (qos == 0) Reply.Silent else puback(packetId)
                    }

                    PINGREQ -> {
                        Reply(byteArrayOf(0xD0.toByte(), 0))
                    }

                    DISCONNECT -> {
                        return
                    }

                    else -> {
                        Reply.Silent
                    }
                }
            reply.bytes?.let {
                output.write(it)
                output.flush()
            }
            if (reply.thenClose) return
        }
    }

    /** Waits until every accepted connection has been closed. */
    fun awaitNoConnections(timeoutMs: Long = 5_000): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (openConnections.get() > 0 && System.currentTimeMillis() < deadline) Thread.sleep(20)
        return openConnections.get() == 0
    }

    override fun close() {
        server.close()
        sockets.forEach { runCatching { it.close() } }
    }

    companion object {
        private const val CONNECT = 1
        private const val PUBLISH = 3
        private const val PINGREQ = 12
        private const val DISCONNECT = 14

        fun v3Connack(
            returnCode: Int,
            thenClose: Boolean = returnCode != 0,
        ) = Reply(byteArrayOf(0x20, 0x02, 0x00, returnCode.toByte()), thenClose)

        fun v5Connack(
            reasonCode: Int = 0,
            properties: ByteArray = ByteArray(0),
        ) = Reply(
            packet(0x20, byteArrayOf(0x00, reasonCode.toByte(), properties.size.toByte()) + properties),
            reasonCode != 0,
        )

        fun maximumPacketSize(bytes: Int) = byteArrayOf(0x27) + int32(bytes)

        fun v3Puback(packetId: Int) = Reply(packet(0x40, u16(packetId)))

        fun v5Puback(
            packetId: Int,
            reasonCode: Int,
        ) = Reply(
            packet(
                0x40,
                u16(packetId) + byteArrayOf(reasonCode.toByte(), 0),
            ),
        )

        fun v5Disconnect(reasonCode: Int) = Reply(packet(0xE0, byteArrayOf(reasonCode.toByte(), 0)), thenClose = true)

        private fun packet(
            type: Int,
            body: ByteArray,
        ): ByteArray {
            require(body.size < 128)
            return byteArrayOf(type.toByte(), body.size.toByte()) + body
        }

        private fun u16(value: Int) = byteArrayOf((value shr 8).toByte(), value.toByte())

        private fun int32(value: Int) =
            byteArrayOf((value shr 24).toByte(), (value shr 16).toByte(), (value shr 8).toByte(), value.toByte())

        private fun u16(
            bytes: ByteArray,
            at: Int,
        ) = ((bytes[at].toInt() and 0xFF) shl 8) or (bytes[at + 1].toInt() and 0xFF)

        private class Reader(
            private val bytes: ByteArray,
        ) {
            var at = 0

            fun byte() = bytes[at++].toInt() and 0xFF

            fun u16() = u16(bytes, at).also { at += 2 }

            fun string(): String {
                val length = u16()
                return String(bytes, at, length).also { at += length }
            }

            fun varInt(): Int {
                var value = 0
                var shift = 0
                while (true) {
                    val b = byte()
                    value = value or ((b and 0x7F) shl shift)
                    if (b and 0x80 == 0) return value
                    shift += 7
                }
            }
        }

        private fun readVarInt(input: DataInputStream): Int {
            var value = 0
            var shift = 0
            while (true) {
                val b = input.readUnsignedByte()
                value = value or ((b and 0x7F) shl shift)
                if (b and 0x80 == 0) return value
                shift += 7
            }
        }
    }
}
