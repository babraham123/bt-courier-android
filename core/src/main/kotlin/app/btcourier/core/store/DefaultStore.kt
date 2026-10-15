package app.btcourier.core.store

import app.btcourier.core.lines.StoredLine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * [Store] policy over any [StoreDb]: cursor dedupe, sealing, ack lifecycle and the byte cap.
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
 * @param clock stamps appended pages; their age drives [SealPolicy.maxBatchAgeMs].
 * @param sinkIds the sinks currently configured. A batch is deleted once all of them acked it;
 *   with none configured, nothing is deleted, so data waits for the first sink.
 */
class DefaultStore(
    private val db: StoreDb,
    private val clock: () -> Long = System::currentTimeMillis,
    private val sinkIds: () -> Set<String>,
) : Store {
    // StoreDb promises atomic writes, not atomic read-modify-write; one writer at a time covers that.
    private val mutex = Mutex()

    override suspend fun appendPage(sourceId: String, cursor: String, lines: List<String>): Boolean = mutex.withLock {
        val state = db.source(sourceId)
        // The current cursor is checked too because its page may already be uploaded and deleted.
        if (state?.cursor == cursor || db.hasPage(sourceId, cursor)) return@withLock false
        var marker = state?.fileMarker
        val now = clock()
        val parts = splitAtNewFiles(lines, marker).mapIndexed { i, part ->
            val startsFile = part.newMarker != null
            if (startsFile) marker = part.newMarker
            NewPage(
                sourceId = sourceId,
                cursor = cursor,
                part = i,
                lines = part.lines,
                bytes = part.lines.sumOf(::encodedSize),
                receivedAtMs = now,
                fileMarker = marker,
                startsFile = startsFile,
            )
        }
        // Repeated markers for the current file don't split, but the latest text is kept.
        val lastMarker = lines.lastOrNull(StoredLine::isMarker) ?: marker
        db.insertPages(SourceState(sourceId, cursor, lastMarker), parts)
        true
    }

    override suspend fun cursor(sourceId: String): String? = db.source(sourceId)?.cursor

    override suspend fun seal(policy: SealPolicy, nowMs: Long): List<String> = mutex.withLock {
        val runs = db.pages().filter { it.batchId == null }.groupBy { it.sourceId }.values
            .flatMap { closedRuns(it, policy, nowMs) }
            // Batch ids then follow data age across sources, which is what eviction walks.
            .sortedBy { it.first().seq }
        val sealed = runs.map { run ->
            db.insertBatch(NewBatch(run.first().sourceId, run.first().cursor, nowMs), run.map { it.seq })
        }
        prune()
        evict(policy.maxStoreBytes)
        sealed.map(Long::toString)
    }

    override suspend fun pending(sinkId: String, limit: Int): List<SealedBatch> = mutex.withLock {
        val pagesByBatch = db.pages().filter { it.batchId != null }.groupBy { it.batchId!! }
        db.batches()
            .filter { sinkId !in it.acks }
            .sortedBy { pagesByBatch[it.id]?.first()?.seq ?: Long.MAX_VALUE }
            .take(limit)
            .map { batch ->
                val pages = pagesByBatch[batch.id].orEmpty()
                val lines = pages.flatMap { db.pageLines(it.seq) }
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
        db.addAck(id, sinkId)
        prune()
    }

    override suspend fun counters(): StoreCounters = mutex.withLock {
        val dropped = db.dropped()
        StoreCounters(
            bytesStored = db.pages().sumOf { it.bytes },
            pendingBatches = db.batches().size,
            droppedLines = dropped.lines,
            droppedBytes = dropped.bytes,
        )
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

    /** Deletes batches every configured sink has acked. */
    private suspend fun prune() {
        val sinks = sinkIds()
        if (sinks.isEmpty()) return
        val done = db.batches().filter { it.acks.containsAll(sinks) }.map { it.id }
        if (done.isNotEmpty()) db.delete(done, emptyList())
    }

    private suspend fun evict(capBytes: Long) {
        val pages = db.pages()
        var total = pages.sumOf { it.bytes }
        if (total <= capBytes) return
        val batchIds = mutableListOf<Long>()
        val pageSeqs = mutableListOf<Long>()
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
        db.delete(batchIds, pageSeqs, dropped)
    }
}

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
