package app.btcourier.core.store

import app.btcourier.core.lines.StoredLine
import java.nio.file.Path

/** Synthetic data row; no real vehicle data. */
fun row(i: Int): String = """{"ts":${1_700_000_000_000L + i},"param":"SPEED","value":$i}"""

fun rows(range: IntRange): List<String> = range.map(::row)

fun marker(file: String): String = StoredLine.marker(file)

/** Spec: a line costs its UTF-8 bytes plus the JSONL newline. */
fun sizeOf(lines: List<String>): Long = lines.sumOf { it.toByteArray(Charsets.UTF_8).size + 1L }

/** The [SnapshotPersistence] adapters every store test runs against. */
enum class DbKind { MEMORY, FILE }

/** A [DefaultStore] with a settable clock that can be reopened on the same storage. */
class StoreHarness(
    private val kind: DbKind,
    private val dir: Path,
    var sinks: Set<String> = setOf("a"),
) {
    var now = 1_000_000L
    private val memory = MemoryPersistence()
    var store: DefaultStore = open()
        private set

    /** Simulates an app restart; in-memory storage survives only within the harness. */
    fun reopen(beforeCommit: () -> Unit = {}): DefaultStore = open(beforeCommit).also { store = it }

    private fun open(beforeCommit: () -> Unit = {}): DefaultStore {
        val persistence = when (kind) {
            DbKind.FILE -> FilePersistence(dir, beforeCommit)
            DbKind.MEMORY -> memory
        }
        return DefaultStore(persistence, clock = { now }, sinkIds = { sinks })
    }
}

/** Seals with only the rule under test effectively enabled. */
val neverByAgeOrSize = SealPolicy(maxBatchBytes = Long.MAX_VALUE, maxBatchAgeMs = Long.MAX_VALUE)

/** Lines of every pending batch for [sinkId], minus marker lines restated at batch starts. */
suspend fun Store.pendingRows(sinkId: String): List<List<String>> =
    pending(sinkId).map { b -> b.lines.filterNot(StoredLine::isMarker) }
