package app.btcourier.core.elm327

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive

/**
 * One car's polling plan from wican-fw's `vehicle_profiles.json`.
 *
 * @property init AT commands sent once after the adapter is configured, e.g. `ATSH7E4`.
 * @property pids polled in this order every round.
 */
data class VehicleProfile(
    val carModel: String,
    val init: List<String>,
    val pids: List<Pid>,
)

/**
 * One request of a round.
 *
 * @property command what goes to the adapter, e.g. `220101`; a trailing digit after a mode 01
 *   request (`010C1`) is the ELM327's "answers to wait for" count and is sent as is.
 * @property init AT commands that select the ECU for this request (`ATSH7E4`); empty when the
 *   adapter state of the previous request applies.
 */
data class Pid(
    val command: String,
    val init: List<String>,
    val parameters: List<Parameter>,
)

/**
 * A value decoded from a [Pid]'s answer.
 *
 * @property name the row is `autopid.<name>`, as the WiCAN logger names it.
 * @property expression a [ByteExpression] source.
 * @property unit display unit, e.g. `km/h`; empty when the profile gives none.
 * @property deviceClass Home Assistant device class (`temperature`, `battery`, `none`).
 * @property min values below are dropped, as wican-fw does; null for no bound.
 * @property max values above are dropped; null for no bound.
 */
data class Parameter(
    val name: String,
    val expression: String,
    val unit: String = "",
    val deviceClass: String = "none",
    val min: Double? = null,
    val max: Double? = null,
)

/**
 * The profile set shipped in core resources (`app/btcourier/core/elm327/vehicle_profiles.json`,
 * see the README next to it), loaded on first use.
 */
object VehicleProfiles {
    /** wican-fw's generic mode 01 profile (RPM, speed, temperatures, fuel, throttle, ...). */
    const val GENERIC_MODEL: String = "AAA: Generic"

    private const val RESOURCE = "/app/btcourier/core/elm327/vehicle_profiles.json"

    private val json = Json { ignoreUnknownKeys = true }

    private val profiles: List<VehicleProfile> by lazy {
        val text =
            checkNotNull(VehicleProfiles::class.java.getResourceAsStream(RESOURCE)) { "missing resource $RESOURCE" }
                .use { it.readBytes().decodeToString() }
        json.decodeFromString<ProfileFile>(text).cars.map { it.toProfile() }
    }

    /** Every profile, in file order. */
    fun all(): List<VehicleProfile> = profiles

    /** The profile named exactly [carModel], or null. */
    fun byModel(carModel: String): VehicleProfile? = profiles.firstOrNull { it.carModel == carModel }

    /** For cars without a profile of their own: standard mode 01 PIDs over CAN 11-bit 500 kbit/s. */
    val GENERIC: VehicleProfile get() = checkNotNull(byModel(GENERIC_MODEL)) { "$GENERIC_MODEL missing from $RESOURCE" }

    /** `ATSH7E4;ATST96;` → `[ATSH7E4, ATST96]`. */
    internal fun splitCommands(commands: String?): List<String> =
        commands
            .orEmpty()
            .split(';')
            .map { it.trim() }
            .filter { it.isNotEmpty() }

    @Serializable
    private class ProfileFile(
        val cars: List<CarEntry>,
    )

    @Serializable
    private class CarEntry(
        @SerialName("car_model") val carModel: String,
        val init: String = "",
        val pids: List<PidEntry> = emptyList(),
    ) {
        fun toProfile() = VehicleProfile(carModel, splitCommands(init), pids.map { it.toPid() })
    }

    @Serializable
    private class PidEntry(
        val pid: String,
        @SerialName("pid_init") val pidInit: String? = null,
        val parameters: List<ParameterEntry> = emptyList(),
    ) {
        fun toPid() = Pid(pid, splitCommands(pidInit), parameters.map { it.toParameter() })
    }

    // min and max are strings in the file ("0", "-40"); numbers are accepted too.
    @Serializable
    private class ParameterEntry(
        val name: String,
        val expression: String,
        val unit: String? = null,
        @SerialName("class") val deviceClass: String? = null,
        val min: JsonPrimitive? = null,
        val max: JsonPrimitive? = null,
    ) {
        fun toParameter() =
            Parameter(
                name = name,
                expression = expression,
                unit = unit.orEmpty(),
                deviceClass = deviceClass ?: "none",
                min = min?.content?.trim()?.toDoubleOrNull(),
                max = max?.content?.trim()?.toDoubleOrNull(),
            )
    }
}
