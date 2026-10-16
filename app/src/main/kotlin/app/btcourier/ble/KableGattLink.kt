package app.btcourier.ble

import app.btcourier.core.transport.ChannelUnavailableException
import app.btcourier.core.transport.CharacteristicUnavailableException
import app.btcourier.core.transport.GattChannel
import app.btcourier.core.transport.GattLink
import com.juul.kable.AndroidPeripheral
import com.juul.kable.Characteristic
import com.juul.kable.GattStatusException
import com.juul.kable.Peripheral
import com.juul.kable.State
import com.juul.kable.Transport
import com.juul.kable.characteristicOf
import com.juul.kable.indicate
import com.juul.kable.notify
import com.juul.kable.read
import com.juul.kable.toIdentifier
import com.juul.kable.write
import com.juul.kable.writeWithoutResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
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
import kotlin.uuid.toJavaUuid
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
 * [GattLink] over Kable to the characteristics of a [GattProfile]: primary notifications from
 * [GattProfile.notify], primary writes to [GattProfile.write], and, when the profile declares
 * a [secondary][GattProfile.secondary] channel, that channel's pair as well. Only a declared
 * secondary channel is subscribed to. [read] reaches any discovered characteristic by UUID.
 *
 * One instance is one connection: once the link drops or is [closed][close] it stays dead and
 * the caller connects again, which also matches how the WiCAN tunnel resets all state on
 * reconnect.
 *
 * A device whose profile [wants a bond][GattProfile.wantsBond] must already be bonded (pair
 * wizard); an unbonded connect still works but the first encrypted access (the CCCD write) then
 * raises the system pairing dialog.
 *
 * The profile is picked once services are discovered, so a kind whose layout varies by model
 * opens whichever one the device turns out to expose.
 */
@OptIn(ExperimentalUuidApi::class)
class KableGattLink private constructor(
    private val peripheral: AndroidPeripheral,
    private val resolveProfile: (List<DiscoveredService>) -> GattProfile,
) : GattLink {
    // Set by open() before the link is handed out.
    @Volatile
    private lateinit var profile: GattProfile

    private val closed = AtomicBoolean(false)

    // Whether each channel's write characteristic declares write with response; false until
    // discovery, which only a profile with WriteTypeRule.AS_ASKED_IF_SUPPORTED consults.
    @Volatile
    private var writeWithResponseSupported = false

    @Volatile
    private var secondaryWriteWithResponseSupported = false

    // Unbounded so a slow consumer never loses a PDU here. The WiCAN's 16 KB credit window bounds
    // what can pile up, and its frame counter would catch a loss elsewhere anyway; the ring only
    // sends what it is asked for.
    private val pending = Channel<ByteArray>(Channel.UNLIMITED)
    private val secondaryPending = Channel<ByteArray>(Channel.UNLIMITED)

    override val notifications: Flow<ByteArray> = pending.receiveAsFlow()
    private val secondaryNotifications: Flow<ByteArray> = secondaryPending.receiveAsFlow()

    // The profile declares no secondary channel, or the device turned out not to expose it.
    @Volatile
    private var secondaryMissing = true

    override val mtu: Int
        get() = peripheral.mtu.value ?: GattProfile.DEFAULT_MTU

    /** Live connection state; reads `Disconnected` after a drop or [close]. */
    val state: Flow<LinkState> = peripheral.state.map { it.toLinkState() }

    val currentState: LinkState
        get() = peripheral.state.value.toLinkState()

    private suspend fun open() {
        val connection = peripheral.connect()
        // connect() returns after service discovery, so the services and their declared properties
        // are known here.
        profile = resolveProfile(discoveredServices())
        secondaryMissing = profile.secondary == null
        writeWithResponseSupported = declaresWriteWithResponse(profile.service, profile.write)
        profile.secondary?.let { secondaryWriteWithResponseSupported = declaresWriteWithResponse(it.service, it.write) }
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
                    .observe(characteristic(profile.service, profile.notify)) { subscribed.complete(Unit) }
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
        // A model whose discovered services lack the secondary one is known to lack the channel
        // before any subscription is tried.
        val secondary =
            profile.secondary?.takeIf { spec ->
                peripheral.services.value?.any {
                    it.serviceUuid ==
                        spec.service.toKotlinUuid()
                } ==
                    true
            }
        if (secondary == null) {
            secondaryMissing = true
            secondaryPending.close(ChannelUnavailableException(GattChannel.SECONDARY))
        }
        val secondarySettled = secondary?.let { observeSecondary(connection, it) }
        // The source sends its first request right after this returns; a response arriving
        // before the CCCD write would be lost, so wait for the subscription.
        subscribed.await()
        secondarySettled?.await()
    }

    /**
     * Subscribes to the secondary channel's notify characteristic into [secondaryPending]; the
     * result completes once subscribed or failed. A failure before the subscription means the
     * device lacks the channel (Kable finds no such characteristic): the connection carries on
     * with the primary channel alone, and the secondary one reports [ChannelUnavailableException].
     */
    private fun observeSecondary(
        connection: CoroutineScope,
        spec: GattProfile.ChannelSpec,
    ): Deferred<Unit> {
        val settled = CompletableDeferred<Unit>()
        connection.launch {
            var failure: Throwable? = null
            try {
                peripheral
                    .observe(characteristic(spec.service, spec.notify)) { settled.complete(Unit) }
                    .collect { secondaryPending.send(it) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                failure =
                    if (settled.isCompleted) {
                        e as? IOException ?: IOException("secondary notify observation failed", e)
                    } else {
                        secondaryMissing = true
                        ChannelUnavailableException(GattChannel.SECONDARY, e)
                    }
            } finally {
                settled.complete(Unit)
                secondaryPending.close(failure)
            }
        }
        return settled
    }

    private fun discoveredServices(): List<DiscoveredService> =
        peripheral.services.value.orEmpty().map { service ->
            DiscoveredService(
                service.serviceUuid.toJavaUuid(),
                service.characteristics.map {
                    DiscoveredCharacteristic(
                        it.characteristicUuid.toJavaUuid(),
                        notify = it.properties.notify || it.properties.indicate,
                        write = it.properties.write || it.properties.writeWithoutResponse,
                    )
                },
            )
        }

    private fun declaresWriteWithResponse(
        service: UUID,
        write: UUID,
    ): Boolean = discoveredProperties(service, write)?.write == true

    /** What the discovered [characteristic] of [service] declares; null when discovery did not find it. */
    private fun discoveredProperties(
        service: UUID,
        characteristic: UUID,
    ): Characteristic.Properties? =
        peripheral.services.value
            ?.firstOrNull { it.serviceUuid == service.toKotlinUuid() }
            ?.characteristics
            ?.firstOrNull { it.characteristicUuid == characteristic.toKotlinUuid() }
            ?.properties

    override fun notifications(channel: GattChannel): Flow<ByteArray> =
        when {
            channel == GattChannel.PRIMARY -> notifications

            profile.secondary == null -> flow { throw ChannelUnavailableException(channel) }

            // Closed with ChannelUnavailableException when the device lacks the channel.
            else -> secondaryNotifications
        }

    override suspend fun write(
        bytes: ByteArray,
        withResponse: Boolean,
    ) = write(bytes, withResponse, GattChannel.PRIMARY)

    override suspend fun write(
        bytes: ByteArray,
        withResponse: Boolean,
        channel: GattChannel,
    ) {
        if (closed.get()) throw IOException("link closed")
        require(bytes.size <= mtu - 3) { "PDU of ${bytes.size} bytes exceeds MTU payload ${mtu - 3}" }
        if (channel == GattChannel.SECONDARY && secondaryMissing) throw ChannelUnavailableException(channel)
        val supported =
            if (channel ==
                GattChannel.PRIMARY
            ) {
                writeWithResponseSupported
            } else {
                secondaryWriteWithResponseSupported
            }
        // Only an undeclared secondary channel has no route, and secondaryMissing covers that.
        val target = checkNotNull(profile.routeWrite(withResponse, supported, channel))
        // Kable serialises GATT operations behind a FIFO mutex, so concurrent writers (request
        // body vs CREDIT) reach the air in call order, and each write suspends until the stack's
        // onCharacteristicWrite.
        gattOperation("write", { ChannelUnavailableException(channel, it) }) {
            peripheral.write(characteristic(target.service, target.route.characteristic), bytes, target.route.writeType)
        }
    }

    override suspend fun read(
        service: UUID,
        characteristic: UUID,
    ): ByteArray {
        if (closed.get()) throw IOException("link closed")
        // Android refuses to read a characteristic without the read property, which Kable would
        // report like a busy stack; an error status (read not permitted, insufficient
        // authentication) likewise means this link cannot have the value, not that it dropped.
        if (discoveredProperties(service, characteristic)?.read != true) {
            throw CharacteristicUnavailableException(service, characteristic)
        }
        return try {
            gattOperation("read", { CharacteristicUnavailableException(service, characteristic, it) }) {
                peripheral.read(characteristic(service, characteristic))
            }
        } catch (e: GattStatusException) {
            throw CharacteristicUnavailableException(service, characteristic, e)
        }
    }

    /**
     * Runs one Kable GATT [operation] and maps its failures to the [GattLink] contract: a dropped
     * link or a rejected request is an [IOException], a characteristic the device does not expose
     * is [missing]'s exception.
     */
    private suspend inline fun <T> gattOperation(
        what: String,
        missing: (NoSuchElementException) -> IOException,
        operation: () -> T,
    ): T =
        try {
            operation()
        } catch (e: CancellationException) {
            // Kable reports a dropped connection as a CancellationException wrapping
            // NotConnectedException; only our caller's own cancellation should propagate as such.
            if (currentCoroutineContext().isActive) throw IOException("link lost during $what", e)
            throw e
        } catch (e: IOException) {
            throw e
        } catch (e: NoSuchElementException) {
            // Kable's lookup of a characteristic the device does not expose.
            throw missing(e)
        } catch (e: Exception) {
            // GattRequestRejectedException (an IllegalStateException): stack busy or link gone.
            throw IOException("GATT $what rejected", e)
        }

    /** Disconnects and releases the `BluetoothGatt`. Idempotent and non-blocking. */
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        pending.close()
        secondaryPending.close()
        // Cancelling the peripheral's scope closes the BluetoothGatt, which drops the link.
        peripheral.close()
    }

    private fun characteristic(
        service: UUID,
        uuid: UUID,
    ) = characteristicOf(service.toKotlinUuid(), uuid.toKotlinUuid())

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
        ): KableGattLink = connect(address, profile.mtu, connectTimeout) { profile }

        /**
         * Connects to the device at [address], asks for [mtu] (none at [GattProfile.DEFAULT_MTU] or
         * less), and opens the profile [resolveProfile] picks from the discovered services.
         *
         * @throws IOException if the link cannot be established within [connectTimeout], or as
         *   [resolveProfile] throws it when no service fits.
         */
        suspend fun connect(
            address: String,
            mtu: Int,
            connectTimeout: Duration = 30.seconds,
            resolveProfile: (List<DiscoveredService>) -> GattProfile,
        ): KableGattLink {
            val peripheral =
                Peripheral(address.toIdentifier()) {
                    transport = Transport.Le
                    if (mtu > GattProfile.DEFAULT_MTU) {
                        onServicesDiscovered {
                            // Before observations start, so the first notification already uses the
                            // large MTU. A refusal leaves the default 23, which the codecs still handle.
                            try {
                                requestMtu(mtu)
                            } catch (e: IOException) {
                                // Keep the default MTU.
                            } catch (e: IllegalStateException) {
                                // GattRequestRejectedException: keep the default MTU.
                            }
                        }
                    }
                } as AndroidPeripheral
            // Kable writes ENABLE_NOTIFICATION_VALUE (0x0001) when a characteristic offers notify,
            // which every profile's characteristics do; for the WiCAN's FFF3 that is the fast
            // credit-based mode BleHttpTransport implements.
            val link = KableGattLink(peripheral, resolveProfile)
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
