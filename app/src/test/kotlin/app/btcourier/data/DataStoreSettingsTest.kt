package app.btcourier.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import app.btcourier.work.MapSecretStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/** [DataStoreSettings] over a real Preferences DataStore file, including the legacy migration. */
class DataStoreSettingsTest {
    @TempDir
    lateinit var dir: File

    private val scopes = mutableListOf<CoroutineScope>()
    private val secrets = MapSecretStore()

    private val file get() = File(dir, "settings.preferences_pb")

    /** A store over [file]; each needs its own scope, cancelled before another opens the file. */
    private fun open(migrate: Boolean): DataStore<Preferences> {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob()).also { scopes += it }
        val migrations = if (migrate) listOf(LegacySinkMigration(testCredentials(secrets))) else emptyList()
        return PreferenceDataStoreFactory.create(migrations = migrations, scope = scope) { file }
    }

    private fun closeAll() = scopes.forEach { it.cancel() }.also { scopes.clear() }

    @AfterEach
    fun tearDown() = closeAll()

    /** Writes what an earlier version stored, then closes that store. */
    private fun writeLegacy(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) =
        runBlocking {
            open(migrate = false).edit(block)
            closeAll()
        }

    @Test
    fun `every field and the sink list round trip through the file`() =
        runBlocking {
            val sinks =
                listOf(
                    SinkConfig(
                        "a1",
                        "Archive",
                        transport = TransportConfig.HttpPut("https://sink.invalid/up/"),
                        auth = AuthConfig.Basic("courier"),
                    ),
                    SinkConfig(
                        "b2",
                        "Broker",
                        enabled = false,
                        transport = TransportConfig.Mqtt("mqtt.invalid", clientId = "btcourier-1", baseTopic = "t"),
                    ),
                )
            val devices =
                listOf(
                    DeviceConfig("AA:BB:CC:DD:EE:01", DeviceKind.WICAN, "WiC_000000000000"),
                    DeviceConfig("AA:BB:CC:DD:EE:02", DeviceKind.COLMI_RING),
                )
            val expected =
                AppSettings(
                    sinks,
                    uploadOverCellular = true,
                    cacheCapBytes = 50_000_000,
                    periodicSync = false,
                    carDeviceAddress = "AA:BB:CC:DD:EE:0A",
                    devices = devices,
                )
            DataStoreSettings(open(migrate = true)).update { expected }
            closeAll()

            assertEquals(expected, DataStoreSettings(open(migrate = true)).current())
        }

    @Test
    fun `a legacy Basic HTTP sink becomes sink http with its secret moved and the old keys removed`() =
        runBlocking {
            writeLegacy {
                it[SettingsKeys.LEGACY_URL] = " https://sink.invalid/ingest/ "
                it[SettingsKeys.LEGACY_AUTH] = "BASIC"
                it[SettingsKeys.LEGACY_USER] = "courier"
                it[SettingsKeys.LEGACY_COMPRESS] = false
                it[SettingsKeys.CELLULAR] = true
            }
            secrets.put(StoredNames.LEGACY_SECRET, "s3cret")
            val store = open(migrate = true)

            val settings = DataStoreSettings(store).current()

            assertEquals(
                listOf(
                    SinkConfig(
                        id = "http",
                        name = "HTTP PUT",
                        enabled = true,
                        transport = TransportConfig.HttpPut("https://sink.invalid/ingest/", compress = false),
                        format = FormatKind.JSONL,
                        auth = AuthConfig.Basic("courier"),
                    ),
                ),
                settings.sinks,
            )
            assertEquals(true, settings.uploadOverCellular, "unrelated settings are kept")
            assertEquals("s3cret", secrets.get(StoredNames.secret("http")))
            assertNull(secrets.get(StoredNames.LEGACY_SECRET))
            val prefs = store.data.first()
            assertFalse(SettingsKeys.LEGACY.any { it in prefs }, "legacy keys are gone")
            assertEquals(setOf("http"), configuredSinkIds(settings))
        }

    @Test
    fun `legacy bearer and no-auth sinks keep their auth`() =
        runBlocking {
            writeLegacy {
                it[SettingsKeys.LEGACY_URL] = "https://sink.invalid/"
                it[SettingsKeys.LEGACY_AUTH] = "BEARER"
            }
            assertEquals(
                AuthConfig.Bearer,
                DataStoreSettings(open(migrate = true))
                    .current()
                    .sinks
                    .single()
                    .auth,
            )
            closeAll()
            file.delete()

            writeLegacy { it[SettingsKeys.LEGACY_URL] = "https://sink.invalid/" }
            val sink = DataStoreSettings(open(migrate = true)).current().sinks.single()
            assertEquals(AuthConfig.None, sink.auth)
            assertEquals(TransportConfig.HttpPut("https://sink.invalid/", compress = true), sink.transport)
        }

    @Test
    fun `a blank legacy URL migrates to no sinks and drops the orphaned secret`() =
        runBlocking {
            writeLegacy {
                it[SettingsKeys.LEGACY_URL] = " "
                it[SettingsKeys.LEGACY_AUTH] = "BEARER"
            }
            secrets.put(StoredNames.LEGACY_SECRET, "stale")

            val store = open(migrate = true)

            assertEquals(emptyList<SinkConfig>(), DataStoreSettings(store).current().sinks)
            assertEquals(emptySet<String>(), secrets.names)
            assertFalse(SettingsKeys.LEGACY.any { it in store.data.first() })
        }

    @Test
    fun `a secret already moved by an interrupted migration is not overwritten`() =
        runBlocking {
            writeLegacy {
                it[SettingsKeys.LEGACY_URL] = "https://sink.invalid/"
                it[SettingsKeys.LEGACY_AUTH] = "BEARER"
            }
            secrets.put(StoredNames.secret("http"), "moved")
            secrets.put(StoredNames.LEGACY_SECRET, "moved")

            DataStoreSettings(open(migrate = true)).current()

            assertEquals(
                mapOf(StoredNames.secret("http") to "moved"),
                secrets.names.associateWith {
                    runBlocking { secrets.get(it) }
                },
            )
        }

    @Test
    fun `the device list is one JSON value with kinds by name`() =
        runBlocking {
            val store = open(migrate = true)
            DataStoreSettings(store).update {
                it.copy(devices = listOf(DeviceConfig("AA:BB:CC:DD:EE:02", DeviceKind.COLMI_RING, "R02_0000")))
            }

            assertEquals(
                """[{"address":"AA:BB:CC:DD:EE:02","kind":"COLMI_RING","name":"R02_0000"}]""",
                store.data.first()[SettingsKeys.DEVICES],
            )
            assertEquals(
                listOf(DeviceConfig("AA:BB:CC:DD:EE:01", DeviceKind.WICAN)),
                decodeDevices("""[{"address":"AA:BB:CC:DD:EE:01","kind":"WICAN"}]"""),
            )
        }

    @Test
    fun `settings without a device list, or with an unreadable one, read as no entries, so every association is a WiCAN`() =
        runBlocking {
            writeLegacy { it[SettingsKeys.CELLULAR] = true }
            assertEquals(emptyList<DeviceConfig>(), DataStoreSettings(open(migrate = true)).current().devices)
            closeAll()

            writeLegacy { it[SettingsKeys.DEVICES] = """[{"address":"AA:BB:CC:DD:EE:01","kind":"TOASTER"}]""" }
            val devices = DataStoreSettings(open(migrate = true)).current().devices
            assertEquals(emptyList<DeviceConfig>(), devices)
            assertEquals(DeviceKind.WICAN, devices.kindOf("AA:BB:CC:DD:EE:01"))
        }

    @Test
    fun `an unreadable sink list reads as no sinks instead of failing`() =
        runBlocking {
            writeLegacy { it[SettingsKeys.SINKS] = """[{"id":"a","transport":{"type":"carrier_pigeon"}}]""" }

            assertEquals(emptyList<SinkConfig>(), DataStoreSettings(open(migrate = true)).current().sinks)
        }
}
