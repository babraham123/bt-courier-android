package app.btcourier.data.sinkkind

import androidx.annotation.StringRes
import app.btcourier.core.sink.UploadTransport
import app.btcourier.data.AuthKind
import app.btcourier.data.SinkConfig
import app.btcourier.data.SinkCredentials
import app.btcourier.data.SinkEnvironment
import app.btcourier.data.TransportConfig
import app.btcourier.data.TransportKind

/**
 * Everything the app knows about one [TransportConfig] variant: which auth it can use, what the
 * editor shows for it, which values are valid, how the editor's values map to the config, and how
 * the config becomes an [UploadTransport]. The sink editor and [app.btcourier.data.buildSinks] are
 * generic over this interface; [module] picks the implementation for a [TransportKind].
 *
 * [validate] is the single source of truth for the editor: [toConfig] accepts exactly the values
 * it passes, and a config built from them is one the core transport accepts (each implementation
 * reuses core's own checks where core has them).
 */
interface SinkTransportKind {
    val kind: TransportKind

    @get:StringRes
    val label: Int

    /** The first is what a sink falls back to when the transport changes under an auth it can't use. */
    val allowedAuth: List<AuthKind>

    /** The editor renders these in order, generically. */
    val fields: List<TransportField>

    /** Kind-specific editor actions beyond plain fields. */
    val capabilities: List<TransportCapability> get() = emptyList()

    /** Values of a new sink of this kind. */
    fun defaults(): FieldValues

    /** @throws ClassCastException for another kind's config. */
    fun toValues(config: TransportConfig): FieldValues

    /** Problems by field key (or capability value key); empty when [toConfig] will succeed. */
    fun validate(values: FieldValues): Map<String, FieldProblem>

    /** @throws IllegalArgumentException (or a parse error) for values [validate] reports. */
    fun toConfig(values: FieldValues): TransportConfig

    /**
     * The values after the user changed [before] into [after]; lets one field follow another
     * (MQTT's port tracking its TLS default). Identity by default.
     */
    fun adjust(
        before: FieldValues,
        after: FieldValues,
    ): FieldValues = after

    /**
     * The transport for [config], whose transport is this kind's and whose auth is in
     * [allowedAuth]. A config that can't work yet (no SFTP key, no host-key pin) builds a
     * transport whose uploads fail with the reason.
     *
     * @throws IllegalArgumentException for values core refuses.
     * @throws java.security.GeneralSecurityException when a Keystore key can't be loaded.
     */
    suspend fun build(
        config: SinkConfig,
        credentials: SinkCredentials,
        env: SinkEnvironment,
    ): UploadTransport
}

/** The implementation of [this] kind; adding a [TransportKind] adds a branch here. */
val TransportKind.module: SinkTransportKind
    get() =
        when (this) {
            TransportKind.HTTP_PUT -> HttpPutKind
            TransportKind.HTTP_POST -> HttpPostKind
            TransportKind.SFTP -> SftpKind
            TransportKind.MQTT -> MqttKind
            TransportKind.LOCAL_FOLDER -> LocalFolderKind
        }

inline fun <reified C : TransportCapability> SinkTransportKind.capability(): C? =
    capabilities.firstNotNullOfOrNull {
        it as? C
    }

/**
 * One transport's editor values by key: text as typed, toggles as `true`/`false`, choices by
 * [ChoiceOption.value], and what capabilities set (a pinned host key, a picked folder). A missing
 * key reads as empty.
 */
class FieldValues(
    private val values: Map<String, String> = emptyMap(),
) {
    constructor(vararg pairs: Pair<String, String>) : this(mapOf(*pairs))

    operator fun get(key: String): String = values[key].orEmpty()

    fun flag(key: String): Boolean = get(key).toBoolean()

    fun orNull(key: String): String? = values[key]?.ifEmpty { null }

    /** [value] null removes the key. */
    fun with(
        key: String,
        value: String?,
    ): FieldValues =
        FieldValues(
            if (value ==
                null
            ) {
                values - key
            } else {
                values + (key to value)
            },
        )

    override fun equals(other: Any?) = other is FieldValues && other.values == values

    override fun hashCode() = values.hashCode()

    // Hosts, users and folder names are the user's; keys say enough for a log.
    override fun toString() = "FieldValues(${values.keys})"
}

/** What a field descriptor needs the editor to show; the editor picks the widget by subtype. */
sealed class TransportField(
    val key: String,
    @StringRes val label: Int,
    @StringRes val help: Int?,
    /** Shown only while this holds for the kind's current values. */
    val visibleWhen: (FieldValues) -> Boolean,
) {
    class Text(
        key: String,
        @StringRes label: Int,
        @StringRes help: Int? = null,
        val keyboard: FieldKeyboard = FieldKeyboard.TEXT,
        @StringRes val placeholder: Int? = null,
        visibleWhen: (FieldValues) -> Boolean = { true },
    ) : TransportField(key, label, help, visibleWhen)

    class Toggle(
        key: String,
        @StringRes label: Int,
        @StringRes help: Int? = null,
        visibleWhen: (FieldValues) -> Boolean = { true },
    ) : TransportField(key, label, help, visibleWhen)

    class Choice(
        key: String,
        @StringRes label: Int,
        val options: List<ChoiceOption>,
        @StringRes help: Int? = null,
        visibleWhen: (FieldValues) -> Boolean = { true },
    ) : TransportField(key, label, help, visibleWhen)
}

class ChoiceOption(
    val value: String,
    @StringRes val label: Int,
)

enum class FieldKeyboard { TEXT, URI, NUMBER }

sealed interface FieldProblem {
    /** Empty or unusable; the field is drawn as an error. */
    data object Invalid : FieldProblem

    /** A URL problem, explained under the field. */
    data class Url(
        val problem: SinkUrlProblem,
    ) : FieldProblem
}

/**
 * Kind-specific editor actions. The editor asks the kind for these instead of switching on the
 * kind, and only the screen switches on them, to render each.
 */
sealed interface TransportCapability {
    /**
     * The sink authenticates with its own Keystore key ([SinkCredentials.sftpPublicKey]): the
     * editor creates it once the kind is picked and shows the public half to install on the server.
     */
    data object ClientKey : TransportCapability

    /**
     * Trust on first use: the editor fetches the server's host key from the values under
     * [hostKey] and [portKey] and, once the user trusts it, keeps the fingerprint under
     * [fingerprintKey].
     */
    class HostKeyPin(
        val hostKey: String,
        val portKey: String,
        val fingerprintKey: String,
    ) : TransportCapability

    /**
     * A folder picked through the Storage Access Framework; its tree URI goes under [treeUriKey].
     * The screen takes the persistable grant, [SinkCredentials] releases it.
     */
    class FolderPick(
        val treeUriKey: String,
    ) : TransportCapability
}

internal fun FieldValues.portProblem(key: String): FieldProblem? =
    FieldProblem.Invalid.takeIf { get(key).trim().toIntOrNull() !in 1..65535 }

internal fun FieldValues.blankProblem(key: String): FieldProblem? = FieldProblem.Invalid.takeIf { get(key).isBlank() }

internal fun problemsOf(vararg entries: Pair<String, FieldProblem?>): Map<String, FieldProblem> =
    entries.mapNotNull { (key, problem) -> problem?.let { key to it } }.toMap()
