package app.btcourier.device

import androidx.annotation.StringRes
import app.btcourier.ble.DeviceFilterSpec
import app.btcourier.ble.GattProfile
import app.btcourier.core.source.Source
import app.btcourier.core.source.colmi.Battery
import app.btcourier.core.transport.GattLink
import app.btcourier.data.DeviceKind
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
    /** What the BLE link adapter subscribes to and writes, and whether pairing bonds first. */
    val profile: GattProfile

    /** What the companion-device scan offers in the chooser. */
    val filter: DeviceFilterSpec

    /**
     * Start of the store source id of each device of this kind (the address follows). It also
     * names the device's uploaded batches, so it never changes once rows are stored under it.
     */
    val sourcePrefix: String

    /** Whether the car head unit connecting or disconnecting syncs devices of this kind. */
    val syncsOnCarTrigger: Boolean

    /** The kind's name on the pair wizard and status screen. */
    @get:StringRes
    val label: Int

    val pairingText: PairingText

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
     * The pair wizard's "test connection" on the connected [link]: one cheap read that proves
     * the link carries this kind's protocol. Leaves the device's state alone. Closes nothing.
     *
     * @throws java.io.IOException when the device does not answer as expected.
     */
    suspend fun probe(link: GattLink): ProbeResult
}

/**
 * What a source may need from the app; each kind reads only its own part.
 *
 * @property clock the phone's wall clock, epoch ms.
 * @property zone the phone's zone, which the ring's clock is set to and its days are counted in.
 * @property pageLimit most rows per page the WiCAN logger is asked for.
 */
data class SourceOptions(
    val clock: () -> Long,
    val zone: ZoneId,
    val pageLimit: Int,
)

/** The pair wizard's per-kind copy, as string resources. */
data class PairingText(
    /** Under the kind's button on the first step. */
    @StringRes val help: Int,
    /** Paragraphs of the explain step, in order. */
    val explain: List<Int>,
    @StringRes val associate: Int,
    @StringRes val test: Int,
)

/** What a "test connection" learned. */
sealed interface ProbeResult {
    data class Wican(
        val firmwareVersion: String,
        val mtu: Int,
    ) : ProbeResult

    data class Ring(
        val battery: Battery,
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
        }
