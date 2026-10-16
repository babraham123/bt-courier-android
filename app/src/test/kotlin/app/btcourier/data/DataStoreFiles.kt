package app.btcourier.data

import androidx.datastore.core.DataMigration
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import java.io.File

/**
 * Opens Preferences DataStores over one file in turn, as app versions do across restarts. A file
 * may have one active store at a time, and DataStore releases it only once the store's scope has
 * completed: cancelling alone is asynchronous and lets the next [open] race it on a slow runner, so
 * [closeAll] joins every scope before returning.
 */
class DataStoreFiles {
    private val scopes = mutableListOf<CoroutineScope>()

    fun open(
        file: File,
        migrations: List<DataMigration<Preferences>> = emptyList(),
    ): DataStore<Preferences> {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob()).also { scopes += it }
        return PreferenceDataStoreFactory.create(migrations = migrations, scope = scope) { file }
    }

    suspend fun closeAll() {
        scopes.forEach {
            it.coroutineContext.job.cancel()
            it.coroutineContext.job.join()
        }
        scopes.clear()
    }

    /** For `@AfterEach`. */
    fun closeAllBlocking() = runBlocking { closeAll() }
}
