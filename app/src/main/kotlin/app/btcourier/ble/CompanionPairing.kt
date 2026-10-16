package app.btcourier.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.companion.AssociationInfo
import android.companion.AssociationRequest
import android.companion.BluetoothLeDeviceFilter
import android.companion.CompanionDeviceManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.IntentSender
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import androidx.annotation.RequiresPermission
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.IOException
import java.util.regex.Pattern
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Builds the system's companion-device scan filter from the testable [DeviceFilterSpec]. */
fun DeviceFilterSpec.toAssociationRequest(): AssociationRequest {
    val name = Pattern.compile(namePattern.pattern)
    val services = serviceUuids.map { ScanFilter.Builder().setServiceUuid(ParcelUuid(it)).build() }
    // The chooser offers a device that matches any one filter, so AND is one filter per service
    // carrying the name too, and OR one filter per condition. For AND the service UUID sits in the
    // advertising PDU and the name in the scan response (legacy advertising), which the system's
    // active scan merges.
    val filters =
        when (match) {
            DeviceFilterSpec.Match.NAME_AND_SERVICE -> {
                services.map { service ->
                    BluetoothLeDeviceFilter
                        .Builder()
                        .setNamePattern(name)
                        .setScanFilter(service)
                        .build()
                }
            }

            DeviceFilterSpec.Match.NAME_OR_SERVICE -> {
                listOf(BluetoothLeDeviceFilter.Builder().setNamePattern(name).build()) +
                    services.map { BluetoothLeDeviceFilter.Builder().setScanFilter(it).build() }
            }
        }
    return AssociationRequest
        .Builder()
        .apply { filters.forEach { addDeviceFilter(it) } }
        // A list rather than a single-device prompt: a household may have more than one device.
        .setSingleDevice(false)
        .build()
}

/** Failure of the association or bonding step, with a message fit to show the user. */
class PairingException(
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause)

/**
 * Asks the system to scan for the device and returns the chooser to launch with
 * `ActivityResultContracts.StartIntentSenderForResult`; feed the result to [associatedDevice].
 */
suspend fun CompanionDeviceManager.requestAssociation(
    context: Context,
    spec: DeviceFilterSpec,
): IntentSender =
    suspendCancellableCoroutine { cont ->
        val callback =
            object : CompanionDeviceManager.Callback() {
                // Deprecated on API 33 for onAssociationPending, but the only callback below it.
                @Suppress("OVERRIDE_DEPRECATION")
                override fun onDeviceFound(intentSender: IntentSender) {
                    if (cont.isActive) cont.resume(intentSender)
                }

                override fun onAssociationPending(intentSender: IntentSender) {
                    if (cont.isActive) cont.resume(intentSender)
                }

                override fun onAssociationCreated(associationInfo: AssociationInfo) = Unit

                override fun onFailure(error: CharSequence?) {
                    if (cont.isActive) {
                        cont.resumeWithException(
                            PairingException(error?.toString() ?: "No device found"),
                        )
                    }
                }
            }
        val request = spec.toAssociationRequest()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            associate(request, ContextCompat.getMainExecutor(context), callback)
        } else {
            @Suppress("DEPRECATION")
            associate(request, callback, null)
        }
    }

/**
 * The device the user picked in the companion chooser, from its result intent. Prefers the
 * scanned [BluetoothDevice] (it carries the random address type the stack saw) and falls back to
 * the association's address.
 */
@SuppressLint("MissingPermission") // getRemoteDevice needs no permission; it only builds a handle.
fun associatedDevice(
    context: Context,
    result: Intent?,
): BluetoothDevice? {
    if (result == null) return null
    @Suppress("DEPRECATION")
    val extra = CompanionDeviceManager.EXTRA_DEVICE
    IntentCompat.getParcelableExtra(result, extra, ScanResult::class.java)?.let { return it.device }
    IntentCompat.getParcelableExtra(result, extra, BluetoothDevice::class.java)?.let { return it }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        val info =
            IntentCompat.getParcelableExtra(
                result,
                CompanionDeviceManager.EXTRA_ASSOCIATION,
                AssociationInfo::class.java,
            )
        val mac = info?.deviceMacAddress ?: return null
        return adapter(context)?.getRemoteDevice(mac.toString().uppercase())
    }
    return null
}

fun adapter(context: Context): BluetoothAdapter? = context.getSystemService(BluetoothManager::class.java)?.adapter

/**
 * Bonds with [device], suspending until the bond is made or refused. The system shows the
 * pairing dialog; the WiCAN is DisplayOnly with LE Secure Connections + MITM, so the user types
 * the dongle's 6-digit passkey (setting `ble_manager.passkey`).
 *
 * Returns at once if already bonded.
 */
@RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
suspend fun bond(
    context: Context,
    device: BluetoothDevice,
) {
    if (device.bondState == BluetoothDevice.BOND_BONDED) return
    suspendCancellableCoroutine { cont ->
        val receiver =
            object : BroadcastReceiver() {
                override fun onReceive(
                    ctx: Context,
                    intent: Intent,
                ) {
                    val changed =
                        IntentCompat.getParcelableExtra(
                            intent,
                            BluetoothDevice.EXTRA_DEVICE,
                            BluetoothDevice::class.java,
                        )
                    if (changed?.address != device.address) return
                    when (intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR)) {
                        BluetoothDevice.BOND_BONDED -> finish(null)

                        // BONDING → NONE: wrong passkey, dialog dismissed, or the dongle's pairing
                        // window is closed (it opens at boot).
                        BluetoothDevice.BOND_NONE -> finish(PairingException("Bonding failed or was cancelled"))
                    }
                }

                fun finish(error: Throwable?) {
                    runCatching { context.unregisterReceiver(this) }
                    if (!cont.isActive) return
                    if (error == null) cont.resume(Unit) else cont.resumeWithException(error)
                }
            }
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED),
            // Exported because only the Bluetooth stack can send this protected broadcast, and it
            // runs under its own uid, so a non-exported receiver never hears it. The address check
            // in onReceive still filters out other devices.
            ContextCompat.RECEIVER_EXPORTED,
        )
        cont.invokeOnCancellation { runCatching { context.unregisterReceiver(receiver) } }
        val started =
            try {
                device.createBond()
            } catch (e: SecurityException) {
                receiver.finish(PairingException("Bluetooth permission missing", e))
                return@suspendCancellableCoroutine
            }
        // false also covers "bonding already in progress"; then the broadcast still arrives.
        if (!started && device.bondState == BluetoothDevice.BOND_NONE) {
            receiver.finish(PairingException("Android refused to start bonding"))
        } else if (device.bondState == BluetoothDevice.BOND_BONDED) {
            receiver.finish(null)
        }
    }
}

/** Rethrows coroutine cancellation; everything else becomes a [PairingException]. */
internal inline fun <T> pairingStep(
    what: String,
    block: () -> T,
): T =
    try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: PairingException) {
        throw e
    } catch (e: Exception) {
        throw PairingException("$what failed: ${e.message ?: e::class.java.simpleName}", e)
    }

/** BLUETOOTH_CONNECT gates bonding, GATT, the bonded-device list and car ACL broadcasts (API 31+). */
fun hasBluetoothConnectPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
        PackageManager.PERMISSION_GRANTED
