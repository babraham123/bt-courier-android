package app.btcourier.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.btcourier.data.AppSettings
import app.btcourier.data.SettingsRepository
import app.btcourier.data.SinkConfig
import app.btcourier.work.WorkControl
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The editable copy of the general settings; sinks are edited one at a time in the sink editor. */
data class SettingsForm(
    val uploadOverCellular: Boolean = false,
    val cacheCapMb: String = "",
    val periodicSync: Boolean = true,
    /** [AppSettings.maxBatchAgeMs]; applies from the next sync or upload, so nothing is rescheduled. */
    val maxBatchAgeMs: Long = 0,
    /** MAC of the car head unit picked from the bonded devices; null means no car trigger. */
    val carDeviceAddress: String? = null,
)

enum class CacheCapProblem { NOT_A_NUMBER, OUT_OF_RANGE }

data class SettingsUiState(
    val loaded: Boolean = false,
    val form: SettingsForm = SettingsForm(),
    /** As stored; enabling or disabling one is saved at once, apart from [form]. */
    val sinks: List<SinkConfig> = emptyList(),
    val capProblem: CacheCapProblem? = null,
    /** True after a save until the next edit. */
    val saved: Boolean = false,
)

/** Cache cap in decimal megabytes, the unit `SealPolicy`'s 200 MB default is written in. */
private const val MB = 1_000_000L

/** Below ~10 MB a single stalled sink would start evicting within one long drive. */
const val MIN_CACHE_CAP_MB = 10L

/**
 * The store rewrites its whole JSON index on every page append (~250 B per ~40 KB page): ~13 MB per
 * append at 2 GB, ~130 MB at 20 GB.
 */
const val MAX_CACHE_CAP_MB = 2_000L

fun validateCacheCap(text: String): CacheCapProblem? {
    val mb = text.trim().toLongOrNull() ?: return CacheCapProblem.NOT_A_NUMBER
    return if (mb in MIN_CACHE_CAP_MB..MAX_CACHE_CAP_MB) null else CacheCapProblem.OUT_OF_RANGE
}

class SettingsViewModel(
    private val settings: SettingsRepository,
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
            mutableState.value = SettingsUiState(loaded = true, form = current.toForm(), sinks = current.sinks)
        }
    }

    fun edit(transform: (SettingsForm) -> SettingsForm) {
        mutableState.update { it.copy(form = transform(it.form), saved = false) }
    }

    fun setSinkEnabled(
        id: String,
        enabled: Boolean,
    ) {
        viewModelScope.launch {
            var after: AppSettings? = null
            settings.update { s ->
                s.copy(sinks = s.sinks.map { if (it.id == id) it.copy(enabled = enabled) else it }).also { after = it }
            }
            val stored = checkNotNull(after)
            mutableState.update { it.copy(sinks = stored.sinks) }
            // A waiting upload would otherwise still deliver to, or skip, this sink until it ends.
            work.uploadNow(stored.uploadOverCellular, replace = true)
        }
    }

    fun save() {
        val form = mutableState.value.form
        val capProblem = validateCacheCap(form.cacheCapMb)
        mutableState.update { it.copy(capProblem = capProblem) }
        if (capProblem != null) return

        viewModelScope.launch {
            val before = settings.current()
            var after = before
            settings.update { stored ->
                stored
                    .copy(
                        uploadOverCellular = form.uploadOverCellular,
                        cacheCapBytes = form.cacheCapMb.trim().toLong() * MB,
                        periodicSync = form.periodicSync,
                        maxBatchAgeMs = form.maxBatchAgeMs,
                        carDeviceAddress = form.carDeviceAddress,
                    ).also { after = it }
            }
            // Re-enqueue so a waiting upload picks up the new network constraint at once.
            if (before.uploadOverCellular !=
                after.uploadOverCellular
            ) {
                work.uploadNow(after.uploadOverCellular, replace = true)
            }
            if (before.periodicSync != after.periodicSync) work.setPeriodicSync(after.periodicSync)
            mutableState.update { it.copy(form = after.toForm(), sinks = after.sinks, saved = true) }
        }
    }

    private fun AppSettings.toForm() =
        SettingsForm(
            uploadOverCellular = uploadOverCellular,
            cacheCapMb = (cacheCapBytes / MB).toString(),
            periodicSync = periodicSync,
            maxBatchAgeMs = maxBatchAgeMs,
            carDeviceAddress = carDeviceAddress,
        )
}
