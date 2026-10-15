package app.btcourier.core.store

import app.btcourier.core.lines.StoredLine
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.nio.file.Path

/**
 * The [Store]: cursor dedupe, sealing, the ack lifecycle and the byte cap over one immutable
 * [StoreSnapshot]. Each call builds the next snapshot and hands it to [SnapshotPersistence] as one
 * commit, so every call is atomic: a crash leaves the state before or after it, never between.
 * Open it with [open] (a directory) or [inMemory].
 *
 * Every row lands in exactly one batch because pages are deduped by cursor before they are
 * stored, each page part is assigned to one batch at seal, and a batch's lines never change.
 *
 * Byte cap: when [SealPolicy.maxStoreBytes] is exceeded, the oldest sealed batches go first
 * (acked by no sink or only some), then the oldest unsealed pages. Cursors are never rewound, so
 * evicted data is lost, not re-pulled: the device may have deleted it already, and re-pulling
 * would only refill a cache that is full because sinks are not draining it. The loss is counted
 * in [StoreCounters.droppedLines] and [StoreCounters.droppedBytes]. The cap is enforced in
 * [seal], so a long sync can overshoot it until the next seal; callers seal after each sync.
 *
 * One instance per storage: the instance holds the committed snapshot and serialises writers with
 * an in-process lock, so a second instance on the same directory would overwrite its commits.
 *
 * @param clock stamps appended pages; their age drives [SealPolicy.maxBatchAgeMs].
 * @param sinkIds the sinks currently configured. A batch is deleted once all of them acked it;
 *   with none configured, nothing is deleted, so data waits for the first sink.
 */
class DefaultStore internal constructor(
    private val persistence: SnapshotPersistence,
    private val clock: () -> Long,
    private val sinkIds: () -> Set<String>,
) : Store {
    companion object {
        /** Opens (or creates) the store in [dir], discarding anything a crash left uncommitted. */
        fun open(dir: Path, clock: () -> Long = System::currentTimeMillis, sinkIds: () -> Set<String>): DefaultStore =
            DefaultStore(FilePersistence(dir), clock, sinkIds)

        /** A volatile store, for tests and a sink-less dry run. */
        fun inMemory(clock: () -> Long = System::currentTimeMillis, sinkIds: () -> Set<String>): DefaultStore =
            DefaultStore(MemoryPersistence(), clock, sinkIds)
    }

    private val mutex = Mutex()

    // Replaced only by commit, so it always equals what persistence last committed.
    private var snapshot = persistence.load()

    override suspend fun appendPage(sourceId: String, cursor: String, lines: List<String>): Boolean = mutex.withLock {
        val s = snapshot
        val state = s.sources[sourceId]
        // The current cursor is checked too because its page may already be uploaded and deleted.
        if (state?.cursor == cursor || s.pages.any { it.sourceId == sourceId && it.cursor == cursor }) {
            return@withLock false
        }
        var marker = state?.fileMarker
        val now = clock()
        val parts = splitAtNewFiles(lines, marker)
        val metas = parts.mapIndexed { i, part ->
            val startsFile = part.newMarker != null
            if (startsFile) marker = part.newMarker
            PageMeta(
                seq = s.nextPageSeq + i,
                sourceId = sourceId,
                cursor = cursor,
                part = i,
                lineCount = part.lines.size,
                bytes = part.lines.sumOf(::encodedSize),
                receivedAtMs = now,
                fileMarker = marker,
                startsFile = startsFile,
            )
        }
        // Repeated markers for the current file don't split, but the latest text is kept.
        val lastMarker = lines.lastOrNull(StoredLine::isMarker) ?: marker
        val next = s.copy(
            sources = s.sources + (sourceId to SourceState(sourceId, cursor, lastMarker)),
            pages = s.pages + metas,
            nextPageSeq = s.nextPageSeq + metas.size,
        )
        commit(next, added = metas.zip(parts).associate { (meta, part) -> meta.seq to part.lines })
        true
    }

    override suspend fun cursor(sourceId: String): String? = mutex.withLock { snapshot.sources[sourceId]?.cursor }

    override suspend fun openFile(sourceId: String): String? =
        mutex.withLock { snapshot.sources[sourceId]?.fileMarker?.let(StoredLine::markerFile) }

    override suspend fun seal(policy: SealPolicy, nowMs: Long): List<String> = mutex.withLock {
        val s = snapshot
        val runs = s.pages.filter { it.batchId == null }.groupBy { it.sourceId }.values
            .flatMap { closedRuns(it, policy, nowMs) }
            // Batch ids then follow data age across sources, which is what eviction walks.
            .sortedBy { it.first().seq }
        val batchOfPage = HashMap<Long, Long>()
        val sealed = runs.mapIndexed { i, run ->
            val id = s.nextBatchId + i
            run.forEach { batchOfPage[it.seq] = id }
            BatchRow(id, run.first().sourceId, run.first().cursor, nowMs)
        }
        val next = s.copy(
            pages = s.pages.map { page -> batchOfPage[page.seq]?.let { page.copy(batchId = it) } ?: page },
            batches = s.batches + sealed,
            nextBatchId = s.nextBatchId + sealed.size,
        )
        commit(next.pruned().evicted(policy.maxStoreBytes))
        sealed.map { it.id.toString() }
    }

    override suspend fun pending(sinkId: String, limit: Int): List<SealedBatch> = mutex.withLock {
        val s = snapshot
        val pagesByBatch = s.pages.filter { it.batchId != null }.groupBy { it.batchId!! }
        s.batches
            .filter { sinkId !in it.acks }
            .sortedBy { pagesByBatch[it.id]?.first()?.seq ?: Long.MAX_VALUE }
            .take(limit)
            .map { batch ->
                val pages = pagesByBatch[batch.id].orEmpty()
                val lines = pages.flatMap { persistence.lines(it.seq) }
                val first = pages.firstOrNull()
                // A batch cut by size or age starts mid-file; restating the marker keeps every
                // batch self-describing (plan: "each batch starts with the _file marker").
                val prefix = first?.fileMarker?.takeIf { lines.firstOrNull()?.let(StoredLine::isMarker) != true }
                SealedBatch(
                    id = batch.id.toString(),
                    sourceId = batch.sourceId,
                    firstCursor = batch.firstCursor,
                    lines = listOfNotNull(prefix) + lines,
                    sealedAtMs = batch.sealedAtMs,
                )
            }
    }

    override suspend fun ack(batchId: String, sinkId: String) = mutex.withLock {
        val id = batchId.toLongOrNull() ?: return@withLock
        val s = snapshot
        commit(s.copy(batches = s.batches.map { if (it.id == id) it.copy(acks = it.acks + sinkId) else it }).pruned())
    }

    override suspend fun counters(): StoreCounters = mutex.withLock {
        val s = snapshot
        StoreCounters(
            bytesStored = s.pages.sumOf { it.bytes },
            pendingBatches = s.batches.size,
            droppedLines = s.dropped.lines,
            droppedBytes = s.dropped.bytes,
        )
    }

    /**
     * Makes [next] the committed snapshot, storing [added] page lines and dropping the lines of
     * pages [next] no longer has. A no-op when nothing changed.
     */
    private suspend fun commit(next: StoreSnapshot, added: Map<Long, List<String>> = emptyMap()) {
        val prev = snapshot
        if (next == prev) return
        val kept = next.pages.mapTo(HashSet()) { it.seq }
        val removed = prev.pages.map { it.seq }.filterNot(kept::contains)
        // Non-cancellable so a caller cancelled mid-commit can't skip `snapshot = next` after the
        // commit reached disk; a stale snapshot would be written back by the next commit.
        withContext(NonCancellable) {
            persistence.commit(next, added, removed)
            snapshot = next
        }
    }

    /**
     * Splits one source's unsealed parts (in order) into runs and returns the complete ones: a run
     * ends before a part that starts a new file, or once it reaches the byte limit; the trailing
     * open run is complete only when its oldest part is old enough.
     */
    private fun closedRuns(parts: List<PageMeta>, policy: SealPolicy, nowMs: Long): List<List<PageMeta>> {
        val closed = mutableListOf<List<PageMeta>>()
        var run = mutableListOf<PageMeta>()
        var runBytes = 0L
        for (part in parts) {
            if (policy.onFileBoundary && part.startsFile && run.isNotEmpty()) {
                closed += run
                run = mutableListOf()
                runBytes = 0
            }
            run += part
            runBytes += part.bytes
            if (runBytes >= policy.maxBatchBytes) {
                closed += run
                run = mutableListOf()
                runBytes = 0
            }
        }
        if (run.isNotEmpty() && nowMs - run.first().receivedAtMs >= policy.maxBatchAgeMs) closed += run
        return closed
    }

    /** Without the batches every configured sink has acked. */
    private fun StoreSnapshot.pruned(): StoreSnapshot {
        val sinks = sinkIds()
        if (sinks.isEmpty()) return this
        return without(batches.filter { it.acks.containsAll(sinks) }.mapTo(HashSet()) { it.id })
    }
}

/** Without the oldest data beyond [capBytes]: sealed batches first, then unsealed pages. */
private fun StoreSnapshot.evicted(capBytes: Long): StoreSnapshot {
    var total = pages.sumOf { it.bytes }
    if (total <= capBytes) return this
    val batchIds = HashSet<Long>()
    val pageSeqs = HashSet<Long>()
    var dropped = Dropped()
    val (sealed, unsealed) = pages.partition { it.batchId != null }
    // Pages are in seq order, so grouping keeps batches ordered by their oldest data.
    for ((batchId, batchPages) in sealed.groupBy { it.batchId!! }) {
        if (total <= capBytes) break
        val bytes = batchPages.sumOf { it.bytes }
        batchIds += batchId
        dropped += Dropped(batchPages.sumOf { it.lineCount.toLong() }, bytes)
        total -= bytes
    }
    for (page in unsealed) {
        if (total <= capBytes) break
        pageSeqs += page.seq
        dropped += Dropped(page.lineCount.toLong(), page.bytes)
        total -= page.bytes
    }
    return without(batchIds, pageSeqs, dropped)
}

/** Without [batchIds] (with their pages and acks) and the pages [pageSeqs], counting [dropped]. */
private fun StoreSnapshot.without(
    batchIds: Set<Long>,
    pageSeqs: Set<Long> = emptySet(),
    dropped: Dropped = Dropped(),
): StoreSnapshot = copy(
    pages = pages.filterNot { it.seq in pageSeqs || it.batchId in batchIds },
    batches = batches.filterNot { it.id in batchIds },
    dropped = this.dropped + dropped,
)

/** Bytes a line occupies once encoded as JSONL: UTF-8 plus the newline. */
internal fun encodedSize(line: String): Long = line.encodeToByteArray().size + 1L

private class Part(val lines: List<String>, val newMarker: String?)

/**
 * Splits [lines] before each [StoredLine.Marker] that names a file other than the one in effect,
 * starting from [currentMarker]. A marker repeating the current file (e.g. restated at the top of
 * every export page) does not split, so it can't shrink batches to one page each.
 */
private fun splitAtNewFiles(lines: List<String>, currentMarker: String?): List<Part> {
    val parts = mutableListOf<Part>()
    var currentFile = currentMarker?.let(StoredLine::markerFile)
    var run = mutableListOf<String>()
    var runMarker: String? = null
    for (line in lines) {
        val file = StoredLine.markerFile(line)
        if (file != null && file != currentFile) {
            if (run.isNotEmpty()) parts += Part(run, runMarker)
            run = mutableListOf()
            runMarker = line
            currentFile = file
        }
        run += line
    }
    if (run.isNotEmpty()) parts += Part(run, runMarker)
    return parts
}
