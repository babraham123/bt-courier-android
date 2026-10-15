package app.btcourier.data

import android.content.Context
import androidx.datastore.core.DataMigration
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import app.btcourier.core.store.SealPolicy
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * User settings. Whether a device is paired is not here: `CompanionDeviceManager` owns the
 * association, and [devices] only adds what it cannot store. Upload credentials are not here
 * either: [SinkCredentials] keeps them.
 *
 * @property sinks every configured upload target, enabled or not; empty keeps data on the phone.
 * @property uploadOverCellular false restricts uploads to unmetered networks.
 * @property cacheCapBytes [SealPolicy.maxStoreBytes].
 * @property periodicSync whether the 15-minute periodic sync is registered; presence and manual
 *   syncs run either way.
 * @property carDeviceAddress MAC of the car head unit whose ACL connect/disconnect triggers a sync
 *   (`CarTrigger`); null disables the car trigger.
 * @property devices kind and name of each associated device ([DeviceConfig]).
 */
data class AppSettings(
    val sinks: List<SinkConfig> = emptyList(),
    val uploadOverCellular: Boolean = false,
    val cacheCapBytes: Long = SealPolicy().maxStoreBytes,
    val periodicSync: Boolean = true,
    val carDeviceAddress: String? = null,
    val devices: List<DeviceConfig> = emptyList(),
) {
    val sealPolicy: SealPolicy get() = SealPolicy(maxStoreBytes = cacheCapBytes)
}

interface SettingsRepository {
    val settings: Flow<AppSettings>

    suspend fun current(): AppSettings = settings.first()

    suspend fun update(transform: (AppSettings) -> AppSettings)
}

/**
 * The app's settings store, with [LegacySinkMigration] run before the first read. Exactly one
 * instance may exist per file, so `AppGraph` holds it.
 */
fun settingsDataStore(
    context: Context,
    credentials: SinkCredentials,
): DataStore<Preferences> =
    PreferenceDataStoreFactory.create(migrations = listOf(LegacySinkMigration(credentials))) {
        context.applicationContext.preferencesDataStoreFile("settings")
    }

/**
 * [AppSettings] in Preferences DataStore. The sink list and the device list are one JSON value
 * each ([encodeSinks], [encodeDevices]), so editing either is a single atomic write.
 */
class DataStoreSettings(
    private val store: DataStore<Preferences>,
) : SettingsRepository {
    override val settings: Flow<AppSettings> = store.data.map(::read)

    override suspend fun update(transform: (AppSettings) -> AppSettings) {
        store.edit { prefs ->
            val next = transform(read(prefs))
            prefs[SettingsKeys.SINKS] = encodeSinks(next.sinks)
            prefs[SettingsKeys.CELLULAR] = next.uploadOverCellular
            prefs[SettingsKeys.CACHE_CAP] = next.cacheCapBytes
            prefs[SettingsKeys.PERIODIC_SYNC] = next.periodicSync
            next.carDeviceAddress?.let { prefs[SettingsKeys.CAR_DEVICE] = it } ?: prefs.remove(SettingsKeys.CAR_DEVICE)
            prefs[SettingsKeys.DEVICES] = encodeDevices(next.devices)
        }
    }

    private fun read(prefs: Preferences): AppSettings {
        val defaults = AppSettings()
        return AppSettings(
            sinks = prefs[SettingsKeys.SINKS]?.let(::decodeSinksOrEmpty) ?: defaults.sinks,
            uploadOverCellular = prefs[SettingsKeys.CELLULAR] ?: defaults.uploadOverCellular,
            cacheCapBytes = prefs[SettingsKeys.CACHE_CAP] ?: defaults.cacheCapBytes,
            periodicSync = prefs[SettingsKeys.PERIODIC_SYNC] ?: defaults.periodicSync,
            carDeviceAddress = prefs[SettingsKeys.CAR_DEVICE],
            devices = prefs[SettingsKeys.DEVICES]?.let(::decodeDevicesOrEmpty) ?: defaults.devices,
        )
    }

    // An unreadable list (written by a newer version with a transport this one lacks) reads as no
    // sinks rather than crashing every worker. With no sink ids the store deletes nothing, so the
    // data waits until the list is fixed.
    private fun decodeSinksOrEmpty(json: String): List<SinkConfig> =
        try {
            decodeSinks(json)
        } catch (_: IllegalArgumentException) {
            emptyList()
        }

    // Same for a device list from a newer version: its devices read as WiCANs, whose links then
    // fail to open, which touches no cursor.
    private fun decodeDevicesOrEmpty(json: String): List<DeviceConfig> =
        try {
            decodeDevices(json)
        } catch (_: IllegalArgumentException) {
            emptyList()
        }
}

internal object SettingsKeys {
    val SINKS = stringPreferencesKey("sinks")
    val CELLULAR = booleanPreferencesKey("upload_over_cellular")
    val CACHE_CAP = longPreferencesKey("cache_cap_bytes")
    val PERIODIC_SYNC = booleanPreferencesKey("periodic_sync")
    val CAR_DEVICE = stringPreferencesKey("car_device_address")
    val DEVICES = stringPreferencesKey("devices")

    // The single HTTP PUT sink of earlier versions; read only by LegacySinkMigration.
    val LEGACY_URL = stringPreferencesKey("sink_url")
    val LEGACY_AUTH = stringPreferencesKey("sink_auth")
    val LEGACY_USER = stringPreferencesKey("sink_user")
    val LEGACY_COMPRESS = booleanPreferencesKey("sink_compress")
    val LEGACY = listOf(LEGACY_URL, LEGACY_AUTH, LEGACY_USER, LEGACY_COMPRESS)
}

/**
 * Turns the flat single-sink settings of earlier versions into a one-element [AppSettings.sinks]
 * and moves its secret to that sink ([SinkCredentials.adoptLegacySecret]). DataStore runs it
 * before any read completes, so nothing ever sees the half-migrated state; its secret half runs
 * first and is idempotent, so a crash between the two halves is repaired on the next start.
 *
 * The migrated sink keeps id [LEGACY_SINK_ID], the ack key the old single sink used, so batches it
 * had not acked yet still go to it.
 */
class LegacySinkMigration(
    private val credentials: SinkCredentials,
) : DataMigration<Preferences> {
    override suspend fun shouldMigrate(currentData: Preferences): Boolean =
        SettingsKeys.LEGACY.any { it in currentData }

    override suspend fun migrate(currentData: Preferences): Preferences {
        val sink = legacySink(currentData)
        if (sink != null && currentData[SettingsKeys.SINKS] == null) credentials.adoptLegacySecret(LEGACY_SINK_ID)
        return currentData
            .toMutablePreferences()
            .apply {
                if (sink != null &&
                    this[SettingsKeys.SINKS] == null
                ) {
                    this[SettingsKeys.SINKS] = encodeSinks(listOf(sink))
                }
                SettingsKeys.LEGACY.forEach { remove(it) }
            }.toPreferences()
    }

    override suspend fun cleanUp() {
        // A secret left without a sink to use it (no URL was set) is deleted rather than kept forever.
        credentials.dropLegacySecret()
    }

    private fun legacySink(prefs: Preferences): SinkConfig? {
        val url = prefs[SettingsKeys.LEGACY_URL]?.trim().orEmpty()
        if (url.isEmpty()) return null
        val auth =
            when (prefs[SettingsKeys.LEGACY_AUTH]) {
                "BASIC" -> AuthConfig.Basic(prefs[SettingsKeys.LEGACY_USER].orEmpty())
                "BEARER" -> AuthConfig.Bearer
                else -> AuthConfig.None
            }
        return SinkConfig(
            id = LEGACY_SINK_ID,
            name = LEGACY_SINK_NAME,
            transport = TransportConfig.HttpPut(url, compress = prefs[SettingsKeys.LEGACY_COMPRESS] ?: true),
            format = FormatKind.JSONL,
            auth = auth,
        )
    }

    companion object {
        const val LEGACY_SINK_ID = "http"
        const val LEGACY_SINK_NAME = "HTTP PUT"
    }
}
