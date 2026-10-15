package app.btcourier.ui.pairing

import app.btcourier.data.DeviceKind
import app.btcourier.device.ProbeResult
import app.btcourier.device.support
import app.btcourier.work.DeviceLocks
import app.btcourier.work.LinkFactory

/**
 * The pair wizard's "test connection" as one more link opener beside the sync: it opens the link
 * through the same [links] and holds the device's entry in the same [locks] while it does. Presence
 * observation starts before the test, so a sync can be triggered meanwhile; whichever comes second
 * backs off instead of opening a second link to the device.
 */
class PairingProbe(private val links: LinkFactory, private val locks: DeviceLocks) {
    /**
     * Connects to [address], runs [kind]'s probe and closes the link. [ProbeResult.Busy] without
     * opening anything when the device's lock is held.
     *
     * @throws java.io.IOException when the device cannot be reached or does not answer as expected.
     */
    suspend fun run(address: String, kind: DeviceKind): ProbeResult {
        val lock = locks.of(address)
        if (!lock.tryLock()) return ProbeResult.Busy
        try {
            val link = links.open(address.uppercase(), kind)
            try {
                return kind.support.probe(link)
            } finally {
                link.close()
            }
        } finally {
            lock.unlock()
        }
    }
}
