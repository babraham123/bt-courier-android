package app.btcourier.ui.pairing

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.btcourier.ble.PairedDeviceRepository
import app.btcourier.ble.PairingException
import app.btcourier.ble.pairingStep
import app.btcourier.data.DeviceConfig
import app.btcourier.data.DeviceKind
import app.btcourier.device.ProbeResult
import app.btcourier.device.support
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class PairingStep { ChooseKind, Explain, Associate, Bond, Test }

/**
 * @property address the associated device, upper-case; set from [PairingStep.Bond] on.
 * @property bondAttempt bumped by each retry, so the screen restarts bonding.
 */
data class PairingUiState(
    val step: PairingStep = PairingStep.ChooseKind,
    val kind: DeviceKind = DeviceKind.WICAN,
    val address: String? = null,
    val busy: Boolean = false,
    val error: String? = null,
    val probe: ProbeResult? = null,
    val bondAttempt: Int = 0,
)

/** The Android side of pairing that runs after the association; a seam for JVM tests. */
interface PairingPlatform {
    /** Starts CDM presence observation of [address]; false when it is not associated (any more). */
    fun observePresence(address: String): Boolean

    /**
     * Connects to [address] with [kind]'s GATT profile and runs its "test connection" probe;
     * [ProbeResult.Busy] when a sync holds the device.
     */
    suspend fun probe(
        address: String,
        kind: DeviceKind,
    ): ProbeResult
}

/**
 * Pair wizard: kind → explain → companion-device association → bond (system passkey dialog, only
 * for kinds whose profile [wants one][app.btcourier.ble.GattProfile.wantsBond]) → test
 * connection. The association is the persisted record of the paired device; the wizard adds its
 * [DeviceConfig] right after it, before presence observation can start a sync that needs the kind.
 *
 * The screen owns what needs an activity: the permission request, the companion chooser and the
 * bond dialog. It reports their outcomes here; error messages arrive already localised.
 */
class PairingViewModel(
    private val devices: PairedDeviceRepository,
    private val platform: PairingPlatform,
) : ViewModel() {
    private val mutableState = MutableStateFlow(PairingUiState())
    val state: StateFlow<PairingUiState> = mutableState.asStateFlow()

    fun chooseKind(kind: DeviceKind) =
        mutableState.update {
            it.copy(step = PairingStep.Explain, kind = kind, error = null)
        }

    fun permissionGranted() = mutableState.update { it.copy(step = PairingStep.Associate, error = null) }

    /** The chooser is being requested. */
    fun scanning() = mutableState.update { it.copy(busy = true, error = null) }

    /** Permission refused, no companion support, nothing picked, or a bond that failed. */
    fun failed(message: String?) = mutableState.update { it.copy(busy = false, error = message) }

    /** The user picked the device at [address] (advertised as [name]) in the companion chooser. */
    fun associated(
        address: String,
        name: String?,
    ) {
        val kind = state.value.kind
        val upper = address.uppercase()
        mutableState.update { it.copy(address = upper, busy = true, error = null) }
        viewModelScope.launch {
            try {
                pairingStep("Saving the device") { devices.remember(DeviceConfig(upper, kind, name)) }
            } catch (e: PairingException) {
                failed(e.message)
                return@launch
            }
            if (kind.support.profile.wantsBond) {
                mutableState.update { it.copy(step = PairingStep.Bond, busy = false) }
            } else {
                ready(upper)
            }
        }
    }

    fun bonding() = mutableState.update { it.copy(busy = true, error = null) }

    fun bonded() = state.value.address?.let(::ready)

    fun retryBond() = mutableState.update { it.copy(bondAttempt = it.bondAttempt + 1) }

    fun test() {
        val current = state.value
        val address = current.address ?: return
        mutableState.update { it.copy(busy = true, error = null, probe = null) }
        viewModelScope.launch {
            val next =
                try {
                    val result = pairingStep("Test connection") { platform.probe(address, current.kind) }
                    state.value.copy(probe = result)
                } catch (e: PairingException) {
                    state.value.copy(error = e.message)
                }
            mutableState.value = next.copy(busy = false)
        }
    }

    /**
     * From here on the system binds `PresenceService` whenever the device comes in range, which is
     * what starts background syncs. The association already exists, so a false return means the
     * user removed it meanwhile; pairing carries on and the status screen will not list it.
     */
    private fun ready(address: String) {
        platform.observePresence(address)
        mutableState.update { it.copy(step = PairingStep.Test, busy = false, error = null) }
    }
}
