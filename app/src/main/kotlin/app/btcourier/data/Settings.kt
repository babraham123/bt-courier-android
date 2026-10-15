package app.btcourier.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.btcourier.core.store.SealPolicy
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * User settings. The paired device is not here: `CompanionDeviceManager` owns the association.
 * Upload secrets are not here either: they live in [SecretStore].
 *
 * @property sinkUrl base URL for HTTP PUT uploads; blank means no sink is configured.
 * @property uploadOverCellular false restricts uploads to unmetered networks.
 * @property cacheCapBytes [SealPolicy.maxStoreBytes].
 * @property periodicSync whether the 15-minute periodic sync is registered; presence and manual
 *   syncs run either way.
 * @property carDeviceAddress MAC of the car head unit whose ACL connect/disconnect triggers a sync
 *   (`CarTrigger`); null disables the car trigger.
 */
data class AppSettings(
    val sinkUrl: String = "",
    val sinkAuth: SinkAuth = SinkAuth.NONE,
    val sinkUser: String = "",
    val sinkCompress: Boolean = true,
    val uploadOverCellular: Boolean = false,
    val cacheCapBytes: Long = SealPolicy().maxStoreBytes,
    val periodicSync: Boolean = true,
    val carDeviceAddress: String? = null,
) {
    val sealPolicy: SealPolicy get() = SealPolicy(maxStoreBytes = cacheCapBytes)
}

enum class SinkAuth {
    NONE,

    /** [sinkUser][AppSettings.sinkUser] plus the [SecretStore.UPLOAD_SECRET] password. */
    BASIC,

    /** The [SecretStore.UPLOAD_SECRET] token. */
    BEARER,
}

interface SettingsRepository {
    val settings: Flow<AppSettings>

    suspend fun current(): AppSettings = settings.first()

    suspend fun update(transform: (AppSettings) -> AppSettings)
}

private val Context.settingsStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class DataStoreSettings(context: Context) : SettingsRepository {
    private val store = context.applicationContext.settingsStore

    override val settings: Flow<AppSettings> = store.data.map(::read)

    override suspend fun update(transform: (AppSettings) -> AppSettings) {
        store.edit { prefs ->
            val next = transform(read(prefs))
            prefs[SINK_URL] = next.sinkUrl
            prefs[SINK_AUTH] = next.sinkAuth.name
            prefs[SINK_USER] = next.sinkUser
            prefs[SINK_COMPRESS] = next.sinkCompress
            prefs[CELLULAR] = next.uploadOverCellular
            prefs[CACHE_CAP] = next.cacheCapBytes
            prefs[PERIODIC_SYNC] = next.periodicSync
            next.carDeviceAddress?.let { prefs[CAR_DEVICE] = it } ?: prefs.remove(CAR_DEVICE)
        }
    }

    private fun read(prefs: Preferences): AppSettings {
        val defaults = AppSettings()
        return AppSettings(
            sinkUrl = prefs[SINK_URL] ?: defaults.sinkUrl,
            sinkAuth = prefs[SINK_AUTH]?.let { name -> SinkAuth.entries.firstOrNull { it.name == name } } ?: defaults.sinkAuth,
            sinkUser = prefs[SINK_USER] ?: defaults.sinkUser,
            sinkCompress = prefs[SINK_COMPRESS] ?: defaults.sinkCompress,
            uploadOverCellular = prefs[CELLULAR] ?: defaults.uploadOverCellular,
            cacheCapBytes = prefs[CACHE_CAP] ?: defaults.cacheCapBytes,
            periodicSync = prefs[PERIODIC_SYNC] ?: defaults.periodicSync,
            carDeviceAddress = prefs[CAR_DEVICE],
        )
    }

    private companion object {
        val SINK_URL = stringPreferencesKey("sink_url")
        val SINK_AUTH = stringPreferencesKey("sink_auth")
        val SINK_USER = stringPreferencesKey("sink_user")
        val SINK_COMPRESS = booleanPreferencesKey("sink_compress")
        val CELLULAR = booleanPreferencesKey("upload_over_cellular")
        val CACHE_CAP = longPreferencesKey("cache_cap_bytes")
        val PERIODIC_SYNC = booleanPreferencesKey("periodic_sync")
        val CAR_DEVICE = stringPreferencesKey("car_device_address")
    }
}
