package app.btcourier.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.btcourier.data.AppSettings
import app.btcourier.data.SecretStore
import app.btcourier.data.SettingsRepository
import app.btcourier.data.SinkAuth
import app.btcourier.ui.WorkControl
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The editable copy of the settings. [secretInput] is only what the user typed this time: the
 * stored secret is never read back into the UI, so it can't be shown or leak through state.
 */
data class SettingsForm(
    val sinkUrl: String = "",
    val sinkAuth: SinkAuth = SinkAuth.NONE,
    val sinkUser: String = "",
    val secretInput: String = "",
    val sinkCompress: Boolean = true,
    val uploadOverCellular: Boolean = false,
    val cacheCapMb: String = "",
    val periodicSync: Boolean = true,
    /** MAC of the car head unit picked from the bonded devices; null means no car trigger. */
    val carDeviceAddress: String? = null,
)

enum class CacheCapProblem { NOT_A_NUMBER, OUT_OF_RANGE }

data class SettingsUiState(
    val loaded: Boolean = false,
    val form: SettingsForm = SettingsForm(),
    val hasStoredSecret: Boolean = false,
    val urlProblem: SinkUrlProblem? = null,
    val capProblem: CacheCapProblem? = null,
    /** True after a save until the next edit. */
    val saved: Boolean = false,
)

/** Cache cap in decimal megabytes, the unit `SealPolicy`'s 200 MB default is written in. */
private const val MB = 1_000_000L

/** Below ~10 MB a single stalled sink would start evicting within one long drive. */
const val MIN_CACHE_CAP_MB = 10L
const val MAX_CACHE_CAP_MB = 20_000L

fun validateCacheCap(text: String): CacheCapProblem? {
    val mb = text.trim().toLongOrNull() ?: return CacheCapProblem.NOT_A_NUMBER
    return if (mb in MIN_CACHE_CAP_MB..MAX_CACHE_CAP_MB) null else CacheCapProblem.OUT_OF_RANGE
}

class SettingsViewModel(
    private val settings: SettingsRepository,
    private val secrets: SecretStore,
    private val work: WorkControl,
) : ViewModel() {
    private val mutableState = MutableStateFlow(SettingsUiState())
    val state: StateFlow<SettingsUiState> = mutableState.asStateFlow()

    init {
        reload()
    }

    /** Discards unsaved edits and reads the stored settings again. */
    fun reload() {
        viewModelScope.launch {
            val current = settings.current()
            val hasSecret = secrets.get(SecretStore.UPLOAD_SECRET) != null
            mutableState.value = SettingsUiState(loaded = true, form = current.toForm(), hasStoredSecret = hasSecret)
        }
    }

    fun edit(transform: (SettingsForm) -> SettingsForm) {
        mutableState.update { it.copy(form = transform(it.form), saved = false) }
    }

    fun clearSecret() {
        viewModelScope.launch {
            secrets.put(SecretStore.UPLOAD_SECRET, null)
            mutableState.update { it.copy(hasStoredSecret = false) }
            // The running upload would otherwise keep using the old credentials until it ends.
            work.uploadNow(settings.current().uploadOverCellular, replace = true)
        }
    }

    fun save() {
        val form = mutableState.value.form
        val urlProblem = validateSinkUrl(form.sinkUrl)
        val capProblem = validateCacheCap(form.cacheCapMb)
        mutableState.update { it.copy(urlProblem = urlProblem, capProblem = capProblem) }
        if (urlProblem != null || capProblem != null) return

        viewModelScope.launch {
            val before = settings.current()
            var after = before
            settings.update { stored ->
                stored.copy(
                    sinkUrl = form.sinkUrl.trim(),
                    sinkAuth = form.sinkAuth,
                    sinkUser = form.sinkUser.trim(),
                    sinkCompress = form.sinkCompress,
                    uploadOverCellular = form.uploadOverCellular,
                    cacheCapBytes = form.cacheCapMb.trim().toLong() * MB,
                    periodicSync = form.periodicSync,
                    carDeviceAddress = form.carDeviceAddress,
                ).also { after = it }
            }
            val secretChanged = form.secretInput.isNotEmpty() && form.sinkAuth != SinkAuth.NONE
            if (secretChanged) secrets.put(SecretStore.UPLOAD_SECRET, form.secretInput)

            // Re-enqueue so a waiting upload picks up the new network constraint or sink at once.
            if (secretChanged || sinkOrNetworkChanged(before, after)) {
                work.uploadNow(after.uploadOverCellular, replace = true)
            }
            if (before.periodicSync != after.periodicSync) work.setPeriodicSync(after.periodicSync)

            mutableState.update {
                it.copy(
                    form = after.toForm(),
                    hasStoredSecret = it.hasStoredSecret || secretChanged,
                    saved = true,
                )
            }
        }
    }

    private fun sinkOrNetworkChanged(a: AppSettings, b: AppSettings) =
        a.sinkUrl != b.sinkUrl || a.sinkAuth != b.sinkAuth || a.sinkUser != b.sinkUser ||
            a.sinkCompress != b.sinkCompress || a.uploadOverCellular != b.uploadOverCellular

    private fun AppSettings.toForm() = SettingsForm(
        sinkUrl = sinkUrl,
        sinkAuth = sinkAuth,
        sinkUser = sinkUser,
        sinkCompress = sinkCompress,
        uploadOverCellular = uploadOverCellular,
        cacheCapMb = (cacheCapBytes / MB).toString(),
        periodicSync = periodicSync,
        carDeviceAddress = carDeviceAddress,
    )
}
