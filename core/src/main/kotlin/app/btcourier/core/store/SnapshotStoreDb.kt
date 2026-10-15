package app.btcourier.core.store

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

/** Every table except page lines, small enough to rewrite whole on each commit. */
@Serializable
internal data class DbSnapshot(
    val sources: Map<String, SourceState> = emptyMap(),
    val pages: List<PageMeta> = emptyList(),
    val batches: List<BatchRow> = emptyList(),
    val dropped: Dropped = Dropped(),
    // Never reused, even after deletes, so a stale file or row can't alias a new page or batch.
    val nextPageSeq: Long = 1,
    val nextBatchId: Long = 1,
)

/** Where a [SnapshotStoreDb] keeps committed snapshots and page lines. */
internal interface SnapshotPersistence {
    /**
     * Durably replaces the committed state with [next], storing [added] page lines and dropping
     * [removed] ones. Must be all-or-nothing: a crash midway leaves the previous state.
     */
    suspend fun commit(next: DbSnapshot, added: Map<Long, List<String>>, removed: Collection<Long>)

    suspend fun lines(seq: Long): List<String>
}

/**
 * [StoreDb] over an immutable [DbSnapshot]: each write builds the next snapshot and hands it to
 * [persistence] as one commit, which makes every write atomic as long as the commit is.
 */
internal class SnapshotStoreDb(
    initial: DbSnapshot,
    private val persistence: SnapshotPersistence,
) : StoreDb {
    private val mutex = Mutex()
    private var snapshot = initial

    override suspend fun source(sourceId: String): SourceState? = mutex.withLock { snapshot.sources[sourceId] }

    override suspend fun hasPage(sourceId: String, cursor: String): Boolean =
        mutex.withLock { snapshot.pages.any { it.sourceId == sourceId && it.cursor == cursor } }

    override suspend fun pages(): List<PageMeta> = mutex.withLock { snapshot.pages }

    override suspend fun pageLines(seq: Long): List<String> = mutex.withLock {
        require(snapshot.pages.any { it.seq == seq }) { "no page $seq" }
        persistence.lines(seq)
    }

    override suspend fun batches(): List<BatchRow> = mutex.withLock { snapshot.batches }

    override suspend fun dropped(): Dropped = mutex.withLock { snapshot.dropped }

    override suspend fun insertPages(source: SourceState, pages: List<NewPage>): Unit = mutex.withLock {
        val s = snapshot
        val metas = pages.mapIndexed { i, p ->
            PageMeta(
                seq = s.nextPageSeq + i,
                sourceId = p.sourceId,
                cursor = p.cursor,
                part = p.part,
                lineCount = p.lines.size,
                bytes = p.bytes,
                receivedAtMs = p.receivedAtMs,
                fileMarker = p.fileMarker,
                startsFile = p.startsFile,
            )
        }
        val next = s.copy(
            sources = s.sources + (source.sourceId to source),
            pages = s.pages + metas,
            nextPageSeq = s.nextPageSeq + pages.size,
        )
        commit(next, metas.zip(pages).associate { (m, p) -> m.seq to p.lines }, emptyList())
    }

    override suspend fun insertBatch(batch: NewBatch, pageSeqs: List<Long>): Long = mutex.withLock {
        val s = snapshot
        val id = s.nextBatchId
        val seqs = pageSeqs.toSet()
        val next = s.copy(
            pages = s.pages.map { if (it.seq in seqs) it.copy(batchId = id) else it },
            batches = s.batches + BatchRow(id, batch.sourceId, batch.firstCursor, batch.sealedAtMs),
            nextBatchId = id + 1,
        )
        commit(next, emptyMap(), emptyList())
        id
    }

    override suspend fun addAck(batchId: Long, sinkId: String): Unit = mutex.withLock {
        val s = snapshot
        val next = s.copy(batches = s.batches.map { if (it.id == batchId) it.copy(acks = it.acks + sinkId) else it })
        if (next != s) commit(next, emptyMap(), emptyList())
    }

    override suspend fun delete(batchIds: List<Long>, pageSeqs: List<Long>, dropped: Dropped): Unit = mutex.withLock {
        val s = snapshot
        val ids = batchIds.toSet()
        val seqs = pageSeqs.toSet()
        val (removed, kept) = s.pages.partition { it.seq in seqs || it.batchId in ids }
        val next = s.copy(
            pages = kept,
            batches = s.batches.filterNot { it.id in ids },
            dropped = s.dropped + dropped,
        )
        if (next != s) commit(next, emptyMap(), removed.map { it.seq })
    }

    // Non-cancellable so a caller cancelled mid-commit can't skip `snapshot = next` after the
    // commit reached disk; a stale snapshot would be written back by the next mutation.
    private suspend fun commit(next: DbSnapshot, added: Map<Long, List<String>>, removed: Collection<Long>) =
        withContext(NonCancellable) {
            persistence.commit(next, added, removed)
            snapshot = next
        }
}

/** Volatile [StoreDb] for tests and for a sink-less dry run. */
class InMemoryStoreDb private constructor(db: SnapshotStoreDb) : StoreDb by db {
    constructor() : this(SnapshotStoreDb(DbSnapshot(), MemoryPersistence()))

    private class MemoryPersistence : SnapshotPersistence {
        private val lines = HashMap<Long, List<String>>()

        override suspend fun commit(next: DbSnapshot, added: Map<Long, List<String>>, removed: Collection<Long>) {
            lines.putAll(added)
            removed.forEach(lines::remove)
        }

        override suspend fun lines(seq: Long): List<String> = lines.getValue(seq)
    }
}
