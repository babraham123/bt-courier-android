package app.btcourier.data

import app.btcourier.core.auth.ClientAuthMethod
import app.btcourier.core.sink.Format
import app.btcourier.core.sink.Jsonl
import app.btcourier.core.sink.formats.Csv
import app.btcourier.core.sink.formats.PrometheusText
import app.btcourier.core.sink.http.HttpPostUploadTransport.BodyEncoding
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.security.SecureRandom

/**
 * One upload target: a transport, the format batches are encoded in, and how the transport
 * authenticates. Credentials are not here: [SinkCredentials] keeps them under the sink's [id].
 *
 * @property id the store's ack key for this sink, generated once by [newSinkId]. Renaming or
 *   re-pointing the sink keeps it, so batches the old target did not take go to the new one.
 * @property enabled a disabled sink is neither built nor waited for: the store drops batches
 *   once the enabled sinks have them.
 */
@Serializable
data class SinkConfig(
    val id: String,
    val name: String,
    val enabled: Boolean = true,
    val transport: TransportConfig,
    val format: FormatKind = FormatKind.JSONL,
    val auth: AuthConfig = AuthConfig.None,
)

/**
 * Where a sink sends batches; one variant per [TransportKind]. Persisted, so field and type names
 * are on-device data. What each variant may be paired with, how the editor shows it and how it
 * becomes a transport is its [app.btcourier.data.sinkkind.SinkTransportKind]'s business.
 */
@Serializable
sealed interface TransportConfig {
    val kind: TransportKind

    /** `PUT <url>/<batch name>`; [compress] false uploads the decompressed bytes. */
    @Serializable
    @SerialName("http_put")
    data class HttpPut(val url: String, val compress: Boolean = true) : TransportConfig {
        override val kind get() = TransportKind.HTTP_PUT
    }

    /** `POST <url>` with the batch name in a header (and the query with [nameQuery]). */
    @Serializable
    @SerialName("http_post")
    data class HttpPost(
        val url: String,
        val encoding: PostEncoding = PostEncoding.GZIP_FILE,
        val nameQuery: Boolean = false,
    ) : TransportConfig {
        override val kind get() = TransportKind.HTTP_POST
    }

    /**
     * Authenticates with the sink's Keystore key ([SinkCredentials.sftpKey]). Uploads fail until the
     * user has confirmed a [hostKeyFingerprint] (trust on first use).
     */
    @Serializable
    @SerialName("sftp")
    data class Sftp(
        val host: String,
        val port: Int = 22,
        val username: String,
        val remoteDir: String,
        val hostKeyFingerprint: String? = null,
    ) : TransportConfig {
        override val kind get() = TransportKind.SFTP
    }

    /**
     * Broker credentials are the sink's [AuthConfig.Basic] user plus its secret, so the username
     * has one home whichever transport uses it.
     */
    @Serializable
    @SerialName("mqtt")
    data class Mqtt(
        val host: String,
        val port: Int = defaultMqttPort(true),
        val tls: Boolean = true,
        val clientId: String,
        val baseTopic: String,
    ) : TransportConfig {
        override val kind get() = TransportKind.MQTT
    }

    /** A Storage Access Framework tree the app holds a persisted grant for. */
    @Serializable
    @SerialName("local_folder")
    data class LocalFolder(val treeUri: String) : TransportConfig {
        override val kind get() = TransportKind.LOCAL_FOLDER
    }
}

/** One per [TransportConfig] variant; its behaviour lives in [app.btcourier.data.sinkkind.module]. */
enum class TransportKind {
    HTTP_PUT,
    HTTP_POST,
    SFTP,
    MQTT,
    LOCAL_FOLDER,
}

fun defaultMqttPort(tls: Boolean) = if (tls) 8883 else 1883

/** App-side names for [BodyEncoding], so the persisted form does not depend on core's enum names. */
enum class PostEncoding(val core: BodyEncoding) {
    GZIP_FILE(BodyEncoding.GzipFile),
    CONTENT_ENCODING_GZIP(BodyEncoding.ContentEncodingGzip),
    IDENTITY(BodyEncoding.Identity),
}

enum class FormatKind(val format: Format) {
    JSONL(Jsonl),
    CSV(Csv),
    PROMETHEUS(PrometheusText),
}

@Serializable
sealed interface AuthConfig {
    val kind: AuthKind

    @Serializable
    @SerialName("none")
    data object None : AuthConfig {
        override val kind get() = AuthKind.NONE
    }

    /** [user] plus the sink's secret as password; for MQTT these are the broker credentials. */
    @Serializable
    @SerialName("basic")
    data class Basic(val user: String) : AuthConfig {
        override val kind get() = AuthKind.BASIC
    }

    /** The sink's secret as token. */
    @Serializable
    @SerialName("bearer")
    data object Bearer : AuthConfig {
        override val kind get() = AuthKind.BEARER
    }

    /**
     * A bearer token from the OIDC device flow, refreshed with the sink's stored token set. The
     * endpoints are discovered at sign-in and kept here so building the sink later needs no
     * network; they are null until the first sign-in.
     */
    @Serializable
    @SerialName("oidc")
    data class Oidc(
        val issuer: String,
        val clientId: String,
        val clientAuthMethod: OidcClientAuth = OidcClientAuth.NONE,
        val tokenEndpoint: String? = null,
        val deviceAuthorizationEndpoint: String? = null,
    ) : AuthConfig {
        override val kind get() = AuthKind.OIDC

        val signedInEndpoints: Boolean get() = tokenEndpoint != null && deviceAuthorizationEndpoint != null
    }
}

enum class AuthKind { NONE, BASIC, BEARER, OIDC }

/** App-side names for [ClientAuthMethod]; [NONE] is a public client without a secret. */
enum class OidcClientAuth(val core: ClientAuthMethod) {
    NONE(ClientAuthMethod.None),
    SECRET_BASIC(ClientAuthMethod.ClientSecretBasic),
    SECRET_POST(ClientAuthMethod.ClientSecretPost),
}

private val idRandom = SecureRandom()
private const val ID_ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789"

/** 10 base-36 characters: no collisions in practice, short enough for secret file names. */
fun newSinkId(): String = String(CharArray(10) { ID_ALPHABET[idRandom.nextInt(ID_ALPHABET.length)] })

/** Random suffix for a default MQTT client id, so two phones never kick each other off a broker. */
fun newMqttClientId(): String = "btcourier-" + newSinkId().take(8)

internal val sinkJson = Json {
    ignoreUnknownKeys = true
    // Defaults are written out so a later change to a default does not silently re-point a sink.
    encodeDefaults = true
}

private val sinkListSerializer = ListSerializer(SinkConfig.serializer())

fun encodeSinks(sinks: List<SinkConfig>): String = sinkJson.encodeToString(sinkListSerializer, sinks)

/** @throws IllegalArgumentException for malformed JSON (`SerializationException` is one). */
fun decodeSinks(json: String): List<SinkConfig> = sinkJson.decodeFromString(sinkListSerializer, json)
