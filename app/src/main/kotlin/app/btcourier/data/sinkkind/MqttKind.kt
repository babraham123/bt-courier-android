package app.btcourier.data.sinkkind

import app.btcourier.R
import app.btcourier.core.sink.mqtt.MqttConfig
import app.btcourier.core.sink.mqtt.MqttUploadTransport
import app.btcourier.data.AuthKind
import app.btcourier.data.SinkConfig
import app.btcourier.data.SinkCredentials
import app.btcourier.data.SinkEnvironment
import app.btcourier.data.TransportConfig
import app.btcourier.data.TransportKind
import app.btcourier.data.defaultMqttPort
import app.btcourier.data.newMqttClientId

/** Publishes each batch to `<base topic>/<batch name>`; brokers only know username/password. */
object MqttKind : SinkTransportKind {
    const val HOST = "host"
    const val TLS = "tls"
    const val PORT = "mqttPort"
    const val CLIENT_ID = "clientId"
    const val BASE_TOPIC = "baseTopic"

    override val kind = TransportKind.MQTT
    override val label = R.string.transport_mqtt
    override val allowedAuth = listOf(AuthKind.NONE, AuthKind.BASIC)
    override val fields =
        listOf(
            TransportField.Text(HOST, R.string.sink_host, keyboard = FieldKeyboard.URI),
            TransportField.Toggle(TLS, R.string.sink_tls, R.string.sink_tls_help),
            TransportField.Text(PORT, R.string.sink_port, keyboard = FieldKeyboard.NUMBER),
            TransportField.Text(CLIENT_ID, R.string.sink_client_id, R.string.sink_client_id_help),
            TransportField.Text(BASE_TOPIC, R.string.sink_base_topic, R.string.sink_base_topic_help),
        )

    /** A random client id, so two phones never kick each other off a broker. */
    override fun defaults() =
        FieldValues(
            TLS to "true",
            PORT to defaultMqttPort(true).toString(),
            CLIENT_ID to newMqttClientId(),
            BASE_TOPIC to "btcourier",
        )

    override fun toValues(config: TransportConfig): FieldValues {
        val t = config as TransportConfig.Mqtt
        return FieldValues(
            HOST to t.host,
            TLS to t.tls.toString(),
            PORT to t.port.toString(),
            CLIENT_ID to t.clientId,
            BASE_TOPIC to t.baseTopic,
        )
    }

    /**
     * Host, port and base topic are checked by [MqttConfig]'s own constructor, one field at a time
     * against otherwise valid values, so the editor flags exactly what the transport would refuse,
     * on the field at fault.
     */
    override fun validate(values: FieldValues): Map<String, FieldProblem> {
        val port = values[PORT].trim().toIntOrNull()
        return problemsOf(
            HOST to invalidUnless { MqttConfig(values[HOST].trim(), PROBE_CLIENT_ID, "") },
            PORT to
                if (port ==
                    null
                ) {
                    FieldProblem.Invalid
                } else {
                    invalidUnless { MqttConfig(PROBE_HOST, PROBE_CLIENT_ID, "", port = port) }
                },
            // Stricter than MqttConfig, which lets the broker assign an id: the form prefills a
            // unique one, so a blank is a slip, and a broker-assigned id changes every connection.
            CLIENT_ID to values.blankProblem(CLIENT_ID),
            BASE_TOPIC to invalidUnless { MqttConfig(PROBE_HOST, PROBE_CLIENT_ID, values[BASE_TOPIC].trim()) },
        )
    }

    override fun toConfig(values: FieldValues) =
        TransportConfig.Mqtt(
            host = values[HOST].trim(),
            port = values[PORT].trim().toInt(),
            tls = values.flag(TLS),
            clientId = values[CLIENT_ID].trim(),
            baseTopic = values[BASE_TOPIC].trim(),
        )

    /** Keeps the port on the protocol default when TLS flips while it was on the other one. */
    override fun adjust(
        before: FieldValues,
        after: FieldValues,
    ): FieldValues {
        val tls = after.flag(TLS)
        if (tls == before.flag(TLS) || after[PORT] != defaultMqttPort(!tls).toString()) return after
        return after.with(PORT, defaultMqttPort(tls).toString())
    }

    override suspend fun build(
        config: SinkConfig,
        credentials: SinkCredentials,
        env: SinkEnvironment,
    ): MqttUploadTransport {
        val t = config.transport as TransportConfig.Mqtt
        return MqttUploadTransport(
            MqttConfig(
                t.host.trim(),
                t.clientId,
                t.baseTopic.trim(),
                t.tls,
                t.port,
                credentials.mqttCredentials(config),
            ),
        )
    }

    private const val PROBE_HOST = "broker.invalid"
    private const val PROBE_CLIENT_ID = "probe"

    private inline fun invalidUnless(build: () -> MqttConfig): FieldProblem? =
        try {
            build()
            null
        } catch (_: IllegalArgumentException) {
            FieldProblem.Invalid
        }
}
