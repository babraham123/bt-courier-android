package app.btcourier.core.store

import kotlinx.serialization.Serializable

/**
 * Persistence behind [DefaultStore]: four tables (sources, pages, batches with acks, counters)
 * and a handful of atomic writes. All policy (dedupe, sealing, eviction) lives in
 * [DefaultStore]; implementations only have to make each write all-or-nothing.
 *
 * [DefaultStore] is the only writer and serialises its calls, so implementations need no
 * check-and-set operations. A Room implementation would map each multi-row write onto one
 * `@Transaction` DAO method.
 */
interface StoreDb {
    suspend fun source(sourceId: String): SourceState?

    /** True if any part of the page ending at [cursor] is still stored. */
    suspend fun hasPage(sourceId: String, cursor: String): Boolean

    /** Metadata of every stored page part, sealed or not, in insertion order ([PageMeta.seq]). */
    suspend fun pages(): List<PageMeta>

    suspend fun pageLines(seq: Long): List<String>

    /** Every stored batch, in creation order. */
    suspend fun batches(): List<BatchRow>

    suspend fun dropped(): Dropped

    /**
     * Inserts [pages] (possibly none) and upserts [source] in one atomic commit. This is the
     * page-plus-cursor transaction: after a crash either both are visible or neither is.
     */
    suspend fun insertPages(source: SourceState, pages: List<NewPage>)

    /** Atomically creates a batch and assigns it the unsealed pages [pageSeqs]. */
    suspend fun insertBatch(batch: NewBatch, pageSeqs: List<Long>): Long

    suspend fun addAck(batchId: Long, sinkId: String)

    /**
     * Atomically deletes [batchIds] (with their pages and acks) and the pages [pageSeqs], and adds
     * [dropped] to the eviction counters. Unknown ids are ignored.
     */
    suspend fun delete(batchIds: List<Long>, pageSeqs: List<Long>, dropped: Dropped = Dropped())
}

/**
 * Per-source progress.
 *
 * @property cursor nextCursor of the latest appended page. Kept apart from the pages so it
 *   survives their deletion (after upload) and eviction.
 * @property fileMarker the latest `{"_file":...}` line seen, so a batch that starts mid-file can
 *   still be prefixed with its file marker.
 */
@Serializable
data class SourceState(
    val sourceId: String,
    val cursor: String,
    val fileMarker: String? = null,
)

/**
 * A page part to insert. A pulled page is split before every new `{"_file":...}` marker, so a
 * batch can begin at a file boundary even when one page spans two files; all parts share the
 * page's cursor.
 *
 * @property bytes encoded size of [lines] (UTF-8 plus one newline each).
 * @property fileMarker marker in effect at the first line (the first line itself if it is one).
 * @property startsFile the part begins with a marker for a file other than the previous part's.
 */
data class NewPage(
    val sourceId: String,
    val cursor: String,
    val part: Int,
    val lines: List<String>,
    val bytes: Long,
    val receivedAtMs: Long,
    val fileMarker: String?,
    val startsFile: Boolean,
)

/** A stored page part without its lines. [batchId] is null while the part is unsealed. */
@Serializable
data class PageMeta(
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

data class NewBatch(
    val sourceId: String,
    val firstCursor: String,
    val sealedAtMs: Long,
)

@Serializable
data class BatchRow(
    val id: Long,
    val sourceId: String,
    val firstCursor: String,
    val sealedAtMs: Long,
    val acks: Set<String> = emptySet(),
)

/** Lines and bytes discarded by the byte cap. */
@Serializable
data class Dropped(
    val lines: Long = 0,
    val bytes: Long = 0,
) {
    operator fun plus(other: Dropped) = Dropped(lines + other.lines, bytes + other.bytes)
}
