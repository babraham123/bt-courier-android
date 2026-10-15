package app.btcourier.work

import app.btcourier.core.sink.Batch
import app.btcourier.core.sink.UploadResult
import app.btcourier.core.sink.UploadTransport
import app.btcourier.core.store.DefaultStore
import app.btcourier.core.store.SealPolicy
import app.btcourier.core.store.Store
import app.btcourier.data.SecretStore
import app.btcourier.data.SshKeys
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import java.nio.file.Path
import java.util.zip.GZIPInputStream

/** Wall clock shared by store, source and jobs, so age-based sealing is deterministic. */
const val NOW = 1_790_000_000_000L

/** Seals every stored line at the end of a sync, so each test sees deliverable batches. */
val sealEverything = SealPolicy(maxBatchAgeMs = 0)

/** Synthetic data row; no real vehicle data. */
fun row(ts: Long, value: Int) = """{"ts":$ts,"param":"autopid.TEST","value":$value}"""

fun marker(file: String) = """{"_file":"$file"}"""

fun isMarker(line: String) = line.startsWith("""{"_file":""")

/** Minutes, for advancing a test clock past [SealPolicy.maxBatchAgeMs]. */
const val MINUTE = 60_000L
const val HOUR = 60 * MINUTE

/** The app's store as the app builds it: [DefaultStore.open] on a directory. */
fun openStore(dir: Path, sinks: Set<String>, clock: () -> Long = { NOW }): DefaultStore =
    DefaultStore.open(dir, clock = clock, sinkIds = { sinks })

/** Data rows (markers dropped) of every batch [sinkId] has not acked yet. */
suspend fun Store.pendingRows(sinkId: String): List<String> =
    pending(sinkId).flatMap { it.lines }.filterNot(::isMarker)

/**
 * Answers each upload with the next of [results], repeating the last one, and keeps the data rows
 * of every acked batch. Expects gzipped [app.btcourier.core.sink.Jsonl] batches.
 */
class ScriptedUpload(private vararg val results: UploadResult) : UploadTransport {
    var uploads = 0
        private set

    val ackedRows = mutableListOf<String>()

    override suspend fun upload(batch: Batch): UploadResult = results[minOf(uploads++, results.lastIndex)].also {
        if (it == UploadResult.Acked) ackedRows += GZIPInputStream(batch.bytes.inputStream()).readBytes().decodeToString().lines().filter { l -> l.isNotEmpty() && !isMarker(l) }
    }
}

class MapSecretStore(vararg entries: Pair<String, String>) : SecretStore {
    private val map = mutableMapOf(*entries)

    /** Names currently stored. */
    val names: Set<String> get() = map.keys.toSet()

    override suspend fun get(name: String): String? = map[name]

    override suspend fun put(name: String, value: String?) {
        if (value == null) map.remove(name) else map[name] = value
    }
}

/** [SshKeys] with software P-256 keys, standing in for AndroidKeyStore on the JVM. */
class InMemorySshKeys : SshKeys {
    val keys = mutableMapOf<String, KeyPair>()

    override fun load(alias: String): KeyPair? = keys[alias]

    override fun loadOrCreate(alias: String): KeyPair = keys.getOrPut(alias) {
        KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
    }

    override fun delete(alias: String) {
        keys.remove(alias)
    }
}
