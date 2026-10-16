package app.btcourier.device

import androidx.annotation.StringRes
import app.btcourier.ble.DeviceFilterSpec
import app.btcourier.ble.GattDiscovery
import app.btcourier.ble.GattProfile
import app.btcourier.core.source.Source
import app.btcourier.core.source.colmi.AutoHeartRate
import app.btcourier.core.source.colmi.Battery
import app.btcourier.core.transport.GattLink
import app.btcourier.data.DeviceKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import java.time.ZoneId

/**
 * Everything the app does differently per [DeviceKind], behind one interface: how to find and
 * connect to the device, where its rows go in the store, which triggers sync it, and how to talk
 * to it once a link is up. Callers get the implementation for a kind from [support] and never
 * switch on the kind themselves; adding a kind means one new adapter and one line in [support].
 *
 * Adapters hold no state, so one instance per kind serves every device of that kind.
 */
interface DeviceSupport {
    /**
     * What the BLE link adapter subscribes to and writes, and whether pairing bonds first. For a
     * kind with [gattDiscovery] only the bond flag applies: each device opens its own layout.
     */
    val profile: GattProfile

    /**
     * How each device's GATT layout is found, for a kind whose layout varies by model; null when
     * every device of the kind has [profile]'s.
     */
    val gattDiscovery: GattDiscovery? get() = null

    /** What the companion-device scan offers in the chooser. */
    val filter: DeviceFilterSpec

    /**
     * Start of the store source id of each device of this kind (the address follows). It also
     * names the device's uploaded batches, so it never changes once rows are stored under it.
     */
    val sourcePrefix: String

    /**
     * Whether the car head unit connecting or disconnecting concerns devices of this kind: a sync
     * for a pulled kind, a drive session start or stop for a streaming one.
     */
    val syncsOnCarTrigger: Boolean

    /** How a drive session (`DriveSession`) keeps a link to a device of this kind; see [LiveMode]. */
    val liveMode: LiveMode

    /** The kind's name on the pair wizard and status screen. */
    @get:StringRes
    val label: Int

    val pairingText: PairingText

    /**
     * The earliest phone time, epoch ms, at which a sync from the device's stored [cursor] could
     * store anything, judged from the cursor and [zone] alone, before any link is opened; null
     * when it always could. Automatic syncs before then skip connecting, which spares a device
     * that only grows its history at set times a link it would answer with an empty page.
     */
    fun nextDueAtMs(
        cursor: String?,
        zone: ZoneId,
    ): Long?

    /**
     * Opens this kind's [Source] on the connected [link] and runs [block] with it. Whatever the
     * source needs for its lifetime (the WiCAN tunnel's notification reader) lives exactly as
     * long as [block]. Closes nothing: the caller owns [link].
     */
    suspend fun <T> withSource(
        link: GattLink,
        options: SourceOptions,
        block: suspend (Source) -> T,
    ): T

    /**
     * The pair wizard's "test connection" on the connected [link]: a cheap read that proves the
     * link carries this kind's protocol, then the kind's part of [choices]. Leaves the device's
     * state alone unless [choices] asks for a change. Closes nothing.
     *
     * @throws java.io.IOException when the device does not answer as expected.
     */
    suspend fun probe(
        link: GattLink,
        choices: PairingChoices,
    ): ProbeResult
}

/** How a drive session gets rows from a device it holds a link to. */
enum class LiveMode {
    /** Never in a drive session: the device's history grows too slowly to be worth a held link. */
    NONE,

    /** The session runs `SyncRunner` from the stored cursor on the open link every pull interval. */
    PULL,

    /** The session collects [Source.subscribe] and stores each emission; the source sets the pace. */
    STREAM,
}

/**
 * What the user chose on the pair wizard's explain step, applied by [DeviceSupport.probe]; each
 * kind reads only its own part, and a kind offers a choice by its [PairingText].
 *
 * @property autoHeartRate the ring: turn its own heart-rate logging on before reading the setting
 *   back. False only reads it, leaving the setting the vendor app made.
 * @property vehicleProfile an ELM327's vehicle profile, stored with the device
 *   ([DeviceConfig.vehicleProfile][app.btcourier.data.DeviceConfig.vehicleProfile]); null for the
 *   generic one.
 */
data class PairingChoices(
    val autoHeartRate: Boolean = true,
    val vehicleProfile: String? = null,
)

/**
 * What a source may need from the app; each kind reads only its own part.
 *
 * @property clock the phone's wall clock, epoch ms.
 * @property zone the phone's zone, which the ring's clock is set to and its days are counted in.
 * @property pageLimit most rows per page the WiCAN logger is asked for.
 * @property vehicleProfile the ELM327's stored vehicle profile
 *   ([PairedDevice.vehicleProfile][app.btcourier.ble.PairedDevice.vehicleProfile]); null for the
 *   generic one.
 */
data class SourceOptions(
    val clock: () -> Long,
    val zone: ZoneId,
    val pageLimit: Int,
    val vehicleProfile: String? = null,
)

/** The pair wizard's per-kind copy, as string resources. */
data class PairingText(
    /** Under the kind's button on the first step. */
    @StringRes val help: Int,
    /** Paragraphs of the explain step, in order. */
    val explain: List<Int>,
    @StringRes val associate: Int,
    @StringRes val test: Int,
    /** The explain step's checkbox for [PairingChoices.autoHeartRate]; null when not offered. */
    val autoHeartRateChoice: PairingChoiceText? = null,
    /** The explain step's picker for [PairingChoices.vehicleProfile]; null when not offered. */
    val vehicleProfileChoice: PairingChoiceText? = null,
)

/** A checkbox on the explain step: its [label] and one line of [help] under it. */
data class PairingChoiceText(
    @StringRes val label: Int,
    @StringRes val help: Int,
)

/** What a "test connection" learned. */
sealed interface ProbeResult {
    data class Wican(
        val firmwareVersion: String,
        val mtu: Int,
    ) : ProbeResult

    /**
     * @property autoHeartRate the logging setting read back, or null when the ring did not answer in time.
     * @property firmware the Device Information Service's firmware revision, null when not exposed.
     * @property hardware its hardware revision, null when not exposed.
     */
    data class Ring(
        val battery: Battery,
        val autoHeartRate: AutoHeartRate?,
        val firmware: String?,
        val hardware: String?,
    ) : ProbeResult

    /**
     * @property version the adapter's answer to `ATZ`, e.g. `ELM327 v2.2`.
     * @property voltageV the car battery voltage the adapter measures (`ATRV`).
     */
    data class Elm327(
        val version: String,
        val voltageV: Double,
    ) : ProbeResult

    /** A sync (or another probe) holds the device's link; nothing was opened. */
    data object Busy : ProbeResult
}

/** The one place that maps a kind to its implementation. */
val DeviceKind.support: DeviceSupport
    get() =
        when (this) {
            DeviceKind.WICAN -> WicanSupport
            DeviceKind.COLMI_RING -> ColmiRingSupport
            DeviceKind.ELM327 -> Elm327Support
        }

/**
 * Runs [block] with a scope for a transport's notification reader, which must outlive single
 * requests but not [block]. A plain child job rather than a supervisor: an unexpected failure in
 * the reader then surfaces from here instead of crashing the process.
 */
internal suspend fun <T> withReaderScope(block: suspend (CoroutineScope) -> T): T =
    coroutineScope {
        val reader = Job(coroutineContext[Job])
        try {
            block(CoroutineScope(coroutineContext + reader))
        } finally {
            reader.cancel()
        }
    }
