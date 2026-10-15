package app.btcourier.work

import kotlinx.coroutines.sync.Mutex
import java.util.concurrent.ConcurrentHashMap

/**
 * One [Mutex] per device address (case-insensitive), created on first use. Whoever opens a link
 * to a device holds its lock for the link's lifetime, and a caller that finds it held backs off
 * rather than waiting: a device serves one link at a time.
 */
class DeviceLocks {
    private val locks = ConcurrentHashMap<String, Mutex>()

    fun of(address: String): Mutex = locks.getOrPut(address.uppercase()) { Mutex() }

    companion object {
        /** The instance every link opener in the app process shares: syncs and the pair wizard's probe. */
        val process = DeviceLocks()
    }
}
