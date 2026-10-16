package app.btcourier.ble

import app.btcourier.core.transport.GattLink
import app.btcourier.data.DeviceKind
import app.btcourier.data.SettingsRepository
import app.btcourier.device.support
import app.btcourier.work.LinkFactory
import java.io.IOException

/** Connects to a device and opens the profile picked from its discovered services; a seam for JVM tests. */
fun interface GattConnector {
    /** @throws IOException if the device cannot be reached or [resolveProfile] finds no fit. */
    suspend fun connect(
        address: String,
        mtu: Int,
        resolveProfile: (List<DiscoveredService>) -> GattProfile,
    ): GattLink
}

/**
 * The production [LinkFactory]. A kind with a fixed profile opens it. A kind whose layout varies
 * by model ([app.btcourier.device.DeviceSupport.gattDiscovery]) opens the layout stored in the
 * device's [app.btcourier.data.DeviceConfig.gatt]; a device without one (its first link, normally
 * the pair wizard's test) is searched, and the pick is stored once the link is up, so a device
 * whose test was skipped still gets one from its first session.
 *
 * @param settings read per open, since the graph builds it after this factory.
 * @param log takes one line per discovery: the picked layout and the MTU the device granted, which
 *   is what a hardware test needs to see.
 */
class KableLinkFactory(
    private val settings: () -> SettingsRepository,
    private val connector: GattConnector =
        GattConnector { address, mtu, resolve -> KableGattLink.connect(address, mtu, resolveProfile = resolve) },
    private val log: (String) -> Unit = {},
) : LinkFactory {
    override suspend fun open(
        address: String,
        kind: DeviceKind,
    ): GattLink {
        val support = kind.support
        val discovery =
            support.gattDiscovery ?: return connector.connect(address, support.profile.mtu) { support.profile }
        val stored =
            settings()
                .current()
                .devices
                .firstOrNull { it.address.equals(address, ignoreCase = true) }
                ?.gatt
        if (stored != null) return connector.connect(address, discovery.mtu) { discovery.profile(stored) }

        var found: GattLayout? = null
        val link =
            connector.connect(address, discovery.mtu) { services ->
                val layout =
                    discovery.choose(services)
                        ?: throw IOException(
                            "no service with a notify and a write characteristic among ${services.size}",
                        )
                found = layout
                discovery.profile(layout)
            }
        val layout = checkNotNull(found)
        log(
            "$kind GATT layout: service ${layout.service}, notify ${layout.notify}, write ${layout.write}, " +
                "bonded ${layout.bonded}, mtu ${link.mtu}",
        )
        store(address, layout)
        return link
    }

    // Only an entry still without a layout takes it: a device forgotten meanwhile is not brought back.
    private suspend fun store(
        address: String,
        layout: GattLayout,
    ) {
        try {
            settings().update { current ->
                current.copy(
                    devices =
                        current.devices.map { device ->
                            val takes = device.address.equals(address, ignoreCase = true) && device.gatt == null
                            if (takes) device.copy(gatt = layout) else device
                        },
                )
            }
        } catch (e: IOException) {
            // The link is up either way; the next one searches again.
        }
    }
}
