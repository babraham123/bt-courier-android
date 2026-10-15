package app.btcourier.core.store

/**
 * Durable local cache between sources and sinks.
 *
 * Pages are keyed by cursor so a re-pulled page is a no-op; sealed batches are immutable so a
 * retried upload is byte-identical. [DefaultStore] implements it over a [StoreDb]: [FileStoreDb] in
 * the app and on the JVM, [InMemoryStoreDb] in tests.
 */
interface Store {
    /**
     * Stores [lines] as the page that ends at [cursor] and advances [sourceId]'s cursor in the
     * same transaction, so a crash never leaves one without the other.
     *
     * @return false if a page with this cursor was already stored (nothing changes).
     */
    suspend fun appendPage(sourceId: String, cursor: String, lines: List<String>): Boolean

    /** Last persisted cursor for [sourceId], or null if nothing was pulled yet. */
    suspend fun cursor(sourceId: String): String?

    /**
     * Seals unsealed lines into batches wherever [policy] says a batch is complete, then enforces
     * [SealPolicy.maxStoreBytes].
     *
     * @param nowMs wall-clock time, injected so the age rule is testable.
     * @return ids of the newly sealed batches.
     */
    suspend fun seal(policy: SealPolicy, nowMs: Long): List<String>

    /**
     * Sealed batches that [sinkId] has not acknowledged yet, oldest first, at most [limit] of
     * them. The limit exists because each batch carries its lines in memory.
     */
    suspend fun pending(sinkId: String, limit: Int = Int.MAX_VALUE): List<SealedBatch>

    /** Records that [sinkId] durably received [batchId]; acked by every sink, it may be deleted. */
    suspend fun ack(batchId: String, sinkId: String)

    /** Size and eviction counters for the status screen. */
    suspend fun counters(): StoreCounters
}

/**
 * When an open batch is sealed: at a `{"_file":...}` boundary (if [onFileBoundary]), once it
 * reaches [maxBatchBytes], or once its oldest line is [maxBatchAgeMs] old. Whichever comes first.
 */
data class SealPolicy(
    val onFileBoundary: Boolean = true,
    val maxBatchBytes: Long = 1_000_000,
    val maxBatchAgeMs: Long = 30 * 60 * 1000,
    /** Total cache cap; oldest data is evicted beyond it and counted in [StoreCounters]. */
    val maxStoreBytes: Long = 200_000_000,
)

/**
 * An immutable, sealed run of lines from one source, in device order.
 *
 * @property firstCursor cursor of the first page in the batch; names the uploaded object.
 */
data class SealedBatch(
    val id: String,
    val sourceId: String,
    val firstCursor: String,
    val lines: List<String>,
    val sealedAtMs: Long,
)

data class StoreCounters(
    /** Encoded size of every stored line, sealed or not; what [SealPolicy.maxStoreBytes] caps. */
    val bytesStored: Long,
    /** Sealed batches not yet acked by every sink. */
    val pendingBatches: Int,
    /** Lines discarded by the byte cap before every sink acked them. */
    val droppedLines: Long,
    val droppedBytes: Long,
)
