package app.btcourier.ble

import app.btcourier.core.transport.GattLink
import com.juul.kable.AndroidPeripheral
import com.juul.kable.Peripheral
import com.juul.kable.State
import com.juul.kable.Transport
import com.juul.kable.characteristicOf
import com.juul.kable.toIdentifier
import com.juul.kable.write
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.toKotlinUuid

/** Connection state of a [KableGattLink], without Kable or Android types. */
sealed interface LinkState {
    data object Connecting : LinkState

    data object Connected : LinkState

    data object Disconnecting : LinkState

    data class Disconnected(
        val reason: String? = null,
    ) : LinkState
}

internal fun State.toLinkState(): LinkState =
    when (this) {
        is State.Connecting -> LinkState.Connecting
        is State.Connected -> LinkState.Connected
        State.Disconnecting -> LinkState.Disconnecting
        is State.Disconnected -> LinkState.Disconnected(status?.toString())
    }

/**
 * [GattLink] over Kable to the characteristics of a [GattProfile]: notifications from
 * [GattProfile.notify], writes to [GattProfile.write].
 *
 * One instance is one connection: once the link drops or is [closed][close] it stays dead and
 * the caller connects again, which also matches how the WiCAN tunnel resets all state on
 * reconnect.
 *
 * A device whose profile [wants a bond][GattProfile.wantsBond] must already be bonded (pair
 * wizard); an unbonded connect still works but the first encrypted access (the CCCD write) then
 * raises the system pairing dialog.
 */
@OptIn(ExperimentalUuidApi::class)
class KableGattLink private constructor(
    private val peripheral: AndroidPeripheral,
    private val profile: GattProfile,
) : GattLink {
    private val closed = AtomicBoolean(false)

    // Whether the profile's write characteristic declares write with response; false until
    // discovery, which only a profile with WriteTypeRule.AS_ASKED_IF_SUPPORTED consults.
    @Volatile
    private var writeWithResponseSupported = false

    // Unbounded so a slow consumer never loses a PDU here. The WiCAN's 16 KB credit window bounds
    // what can pile up, and its frame counter would catch a loss elsewhere anyway; the ring only
    // sends what it is asked for.
    private val pending = Channel<ByteArray>(Channel.UNLIMITED)

    override val notifications: Flow<ByteArray> = pending.receiveAsFlow()

    override val mtu: Int
        get() = peripheral.mtu.value ?: GattProfile.DEFAULT_MTU

    /** Live connection state; reads `Disconnected` after a drop or [close]. */
    val state: Flow<LinkState> = peripheral.state.map { it.toLinkState() }

    val currentState: LinkState
        get() = peripheral.state.value.toLinkState()

    private suspend fun open() {
        val connection = peripheral.connect()
        // connect() returns after service discovery, so the declared properties are known here.
        writeWithResponseSupported =
            peripheral.services.value
                ?.firstOrNull { it.serviceUuid == profile.service.toKotlinUuid() }
                ?.characteristics
                ?.firstOrNull { it.characteristicUuid == profile.write.toKotlinUuid() }
                ?.properties
                ?.write == true
        // Shorter connection interval: more connection events per second for frames written with
        // response and for multi-packet answers. Only a hint; the controller may refuse it.
        peripheral.requestConnectionPriority(AndroidPeripheral.Priority.High)

        val subscribed = CompletableDeferred<Unit>()
        // Launched in the connection's scope, which Kable cancels when the link drops; Kable's own
        // observations would otherwise survive the drop and resume on a reconnect, but the
        // GattLink contract is that notifications complete with the link.
        connection.launch {
            var failure: Throwable? = null
            try {
                peripheral
                    .observe(characteristic(profile.notify)) { subscribed.complete(Unit) }
                    .collect { pending.send(it) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                failure = e as? IOException ?: IOException("notify observation failed", e)
            } finally {
                subscribed.completeExceptionally(failure ?: IOException("link dropped before the notify subscription"))
                pending.close(failure)
            }
        }
        // The source sends its first request right after this returns; a response arriving
        // before the CCCD write would be lost, so wait for the subscription.
        subscribed.await()
    }

    override suspend fun write(
        bytes: ByteArray,
        withResponse: Boolean,
    ) {
        if (closed.get()) throw IOException("link closed")
        require(bytes.size <= mtu - 3) { "PDU of ${bytes.size} bytes exceeds MTU payload ${mtu - 3}" }
        val route = profile.routeWrite(withResponse, writeWithResponseSupported)
        // Kable serialises GATT operations behind a FIFO mutex, so concurrent writers (request
        // body vs CREDIT) reach the air in call order, and each write suspends until the stack's
        // onCharacteristicWrite.
        try {
            peripheral.write(characteristic(route.characteristic), bytes, route.writeType)
        } catch (e: CancellationException) {
            // Kable reports a dropped connection as a CancellationException wrapping
            // NotConnectedException; only our caller's own cancellation should propagate as such.
            if (currentCoroutineContext().isActive) throw IOException("link lost during write", e)
            throw e
        } catch (e: IOException) {
            throw e
        } catch (e: Exception) {
            // GattRequestRejectedException (an IllegalStateException): stack busy or link gone.
            throw IOException("GATT write rejected", e)
        }
    }

    /** Disconnects and releases the `BluetoothGatt`. Idempotent and non-blocking. */
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        pending.close()
        // Cancelling the peripheral's scope closes the BluetoothGatt, which drops the link.
        peripheral.close()
    }

    private fun characteristic(uuid: UUID) = characteristicOf(profile.service.toKotlinUuid(), uuid.toKotlinUuid())

    companion object {
        /**
         * Connects to the device at [address] (as recorded by the CDM association), negotiates
         * the profile's MTU and subscribes to its notify characteristic.
         *
         * @throws IOException if the link cannot be established within [connectTimeout].
         */
        suspend fun connect(
            address: String,
            profile: GattProfile,
            connectTimeout: Duration = 30.seconds,
        ): KableGattLink {
            val peripheral =
                Peripheral(address.toIdentifier()) {
                    transport = Transport.Le
                    if (profile.mtu > GattProfile.DEFAULT_MTU) {
                        onServicesDiscovered {
                            // Before observations start, so the first notification already uses the
                            // large MTU. A refusal leaves the default 23, which the codec still handles.
                            try {
                                requestMtu(profile.mtu)
                            } catch (e: IOException) {
                                // Keep the default MTU.
                            } catch (e: IllegalStateException) {
                                // GattRequestRejectedException: keep the default MTU.
                            }
                        }
                    }
                } as AndroidPeripheral
            // Kable writes ENABLE_NOTIFICATION_VALUE (0x0001) when a characteristic offers notify,
            // which both profiles' characteristics do; for the WiCAN's FFF3 that is the fast
            // credit-based mode BleHttpTransport implements.
            val link = KableGattLink(peripheral, profile)
            try {
                withTimeout(connectTimeout) { link.open() }
            } catch (e: Throwable) {
                link.close()
                if (e is IOException) throw e
                if (e is CancellationException && currentCoroutineContext().isActive) {
                    throw IOException("connect to device timed out or was dropped", e)
                }
                if (e is CancellationException) throw e
                throw IOException("connect to device failed", e)
            }
            return link
        }
    }
}
