package app.btcourier.core.store

import kotlinx.serialization.Serializable

/**
 * The seam under [DefaultStore]: where committed [StoreSnapshot]s and page lines live. Adapters:
 * [FilePersistence] (the app and the JVM) and [MemoryPersistence] (tests, a sink-less dry run).
 *
 * [DefaultStore] is the only writer, serialises its calls and keeps the current snapshot itself,
 * so an adapter only has to make each [commit] all-or-nothing and hand the last one back on [load].
 */
internal interface SnapshotPersistence {
    /** The last committed snapshot (empty if none), once per [DefaultStore], before any commit. */
    fun load(): StoreSnapshot

    /**
     * Durably replaces the committed state with [next], storing [added] page lines and dropping
     * [removed] ones. Must be all-or-nothing: a crash midway leaves the previous state.
     */
    suspend fun commit(next: StoreSnapshot, added: Map<Long, List<String>>, removed: Collection<Long>)

    /** Lines of the committed page [seq]. */
    suspend fun lines(seq: Long): List<String>
}

/** Volatile adapter; outlives the [DefaultStore]s opened on it, which is how tests "restart". */
internal class MemoryPersistence : SnapshotPersistence {
    private var committed = StoreSnapshot()
    private val lines = HashMap<Long, List<String>>()

    override fun load(): StoreSnapshot = committed

    override suspend fun commit(next: StoreSnapshot, added: Map<Long, List<String>>, removed: Collection<Long>) {
        lines.putAll(added)
        removed.forEach(lines::remove)
        committed = next
    }

    override suspend fun lines(seq: Long): List<String> = lines.getValue(seq)
}

/**
 * Everything the store knows except page lines, small enough to rewrite whole on each commit.
 *
 * This is the `index.json` format of existing installs: field names and defaults are part of it
 * (defaults are not encoded), so renaming or re-defaulting a field breaks stores already on disk.
 */
@Serializable
internal data class StoreSnapshot(
    val sources: Map<String, SourceState> = emptyMap(),
    /** Every stored page part, sealed or not, in insertion ([PageMeta.seq]) order. */
    val pages: List<PageMeta> = emptyList(),
    /** Every stored batch, in creation order. */
    val batches: List<BatchRow> = emptyList(),
    val dropped: Dropped = Dropped(),
    // Never reused, even after deletes, so a stale file or row can't alias a new page or batch.
    val nextPageSeq: Long = 1,
    val nextBatchId: Long = 1,
)

/**
 * Per-source progress.
 *
 * @property cursor nextCursor of the latest appended page. Kept apart from the pages so it
 *   survives their deletion (after upload) and eviction.
 * @property fileMarker the latest `{"_file":...}` line stored, so a batch that starts mid-file can
 *   still be prefixed with its file marker and a sync can resume in that file.
 */
@Serializable
internal data class SourceState(
    val sourceId: String,
    val cursor: String,
    val fileMarker: String? = null,
)

/**
 * A stored page part without its lines. A pulled page is split before every new `{"_file":...}`
 * marker, so a batch can begin at a file boundary even when one page spans two files; all parts
 * share the page's cursor.
 *
 * @property bytes encoded size of the lines (UTF-8 plus one newline each).
 * @property fileMarker marker in effect at the first line (the first line itself if it is one).
 * @property startsFile the part begins with a marker for a file other than the previous part's.
 * @property batchId null while the part is unsealed.
 */
@Serializable
internal data class PageMeta(
    val seq: Long,
    val sourceId: String,
    val cursor: String,
    val part: Int,
    val lineCount: Int,
    val bytes: Long,
    val receivedAtMs: Long,
    val fileMarker: String?,
    val startsFile: Boolean,
    val batchId: Long? = null,
)

@Serializable
internal data class BatchRow(
    val id: Long,
    val sourceId: String,
    val firstCursor: String,
    val sealedAtMs: Long,
    val acks: Set<String> = emptySet(),
)

/** Lines and bytes discarded by the byte cap. */
@Serializable
internal data class Dropped(
    val lines: Long = 0,
    val bytes: Long = 0,
) {
    operator fun plus(other: Dropped) = Dropped(lines + other.lines, bytes + other.bytes)
}
