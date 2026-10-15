package app.btcourier.work

import app.btcourier.core.sink.Sink
import app.btcourier.core.sink.UploadResult
import app.btcourier.core.sink.deliverPending
import app.btcourier.core.store.Store
import app.btcourier.data.SinkUpload
import app.btcourier.data.SyncStatusRepository
import app.btcourier.data.UploadResultKind
import app.btcourier.log.AppLog

/**
 * One delivery round of every pending batch to every configured sink, independent of WorkManager.
 * Each sink is delivered to on its own, so one that is down does not hold back the others.
 *
 * Retries are bounded: a round that ends in [UploadResult.Retry] asks for another attempt until
 * [maxAttempts] rounds have run, then gives up ([UploadOutcome.GaveUp]); [UploadResult.Failed]
 * is never retried. Either way the batches stay in the store and the next upload (after the next
 * sync that seals new batches, or a manual one) tries them again.
 *
 * @param sinks builds the configured sinks; may throw [IllegalArgumentException] for a bad URL.
 * @param foregroundThresholdBytes stored bytes from which [run] calls its `onLargeBacklog`.
 */
class UploadJob(
    private val store: Store,
    private val sinks: suspend () -> List<Sink>,
    private val status: SyncStatusRepository,
    private val clock: () -> Long = System::currentTimeMillis,
    private val maxAttempts: Int = MAX_ATTEMPTS,
    private val foregroundThresholdBytes: Long = FOREGROUND_THRESHOLD_BYTES,
    private val log: AppLog = AppLog(),
) {
    /**
     * @param attempt 0-based index of this round among consecutive retries.
     * @param onLargeBacklog runs before delivery when the backlog is large enough that the upload
     *   may outlast a background job's time limit; the worker promotes itself to a foreground service.
     */
    suspend fun run(attempt: Int, onLargeBacklog: suspend () -> Unit = {}): UploadOutcome {
        val configured = try {
            sinks()
        } catch (e: IllegalArgumentException) {
            record(UploadResultKind.FAILED, "invalid sink configuration: ${e.message}")
            return UploadOutcome.Failed
        }
        if (configured.isEmpty()) {
            record(UploadResultKind.NO_SINK, null, bySink = emptyMap())
            return UploadOutcome.NoSink
        }
        if (store.counters().bytesStored >= foregroundThresholdBytes) onLargeBacklog()

        val results = configured.map { sink -> sink.id to deliverPending(store, sink) }
        val stops = results.mapNotNull { (id, result) -> result?.let { id to it } }
        val retries = stops.filter { it.second is UploadResult.Retry }
        val error = stops.takeIf { it.isNotEmpty() }?.joinToString("; ") { (id, result) -> "$id: ${reason(result)}" }
        val (kind, outcome) = when {
            stops.isEmpty() -> UploadResultKind.DELIVERED to UploadOutcome.Delivered
            retries.isEmpty() -> UploadResultKind.FAILED to UploadOutcome.Failed
            attempt + 1 >= maxAttempts -> UploadResultKind.GAVE_UP to UploadOutcome.GaveUp
            else -> UploadResultKind.RETRYING to UploadOutcome.Retry
        }
        val message = if (kind == UploadResultKind.GAVE_UP) "gave up after $maxAttempts attempts: $error" else error
        val now = clock()
        val bySink = results.associate { (id, result) ->
            val sinkKind = when (result) {
                null, UploadResult.Acked -> UploadResultKind.DELIVERED
                is UploadResult.Failed -> UploadResultKind.FAILED
                is UploadResult.Retry -> if (kind == UploadResultKind.GAVE_UP) UploadResultKind.GAVE_UP else UploadResultKind.RETRYING
            }
            id to SinkUpload(now, sinkKind)
        }
        record(kind, message, bySink)
        return outcome
    }

    private fun reason(result: UploadResult): String = when (result) {
        is UploadResult.Retry -> result.reason
        is UploadResult.Failed -> result.reason
        UploadResult.Acked -> "acked"
    }

    /** @param bySink replaces the per-sink outcomes; null keeps the previous ones. */
    private suspend fun record(kind: UploadResultKind, error: String?, bySink: Map<String, SinkUpload>? = null) {
        val counters = store.counters()
        val now = clock()
        status.update {
            it.copy(lastUploadAtMs = now, lastUpload = kind, lastUploadBySink = bySink ?: it.lastUploadBySink)
                .withCounters(counters)
                .withError(error, now)
        }
        val sinks = bySink?.entries?.joinToString { (id, s) -> "$id ${s.result}" }
        val line = "upload $kind" + (sinks?.let { " [$it]" } ?: "") + ", ${counters.pendingBatches} batches pending" +
            (error?.let { ": $it" } ?: "")
        if (error == null) log.info(TAG, line) else log.warn(TAG, line)
    }

    companion object {
        /**
         * Rounds per upload work, counting the first. With WorkManager's exponential back-off from
         * 30 s that spans about an hour, long enough to ride out a server restart or a flaky
         * hotspot without retrying a dead endpoint all day.
         */
        const val MAX_ATTEMPTS = 8

        private const val TAG = "upload"

        /**
         * 8 MB of stored (uncompressed) lines: gzip makes that ~1-2 MB on the wire, about a
         * minute on a slow uplink. Below it an upload fits an expedited job comfortably; above
         * it, a 200 MB backlog could take long enough that the job is stopped mid-way, so the
         * worker runs as a `dataSync` foreground service instead.
         */
        const val FOREGROUND_THRESHOLD_BYTES = 8_000_000L
    }
}

sealed interface UploadOutcome {
    data object Delivered : UploadOutcome

    /** A sink answered a transient failure and attempts remain. */
    data object Retry : UploadOutcome

    data object GaveUp : UploadOutcome

    /** A sink refused permanently, or the sink configuration is invalid. */
    data object Failed : UploadOutcome

    data object NoSink : UploadOutcome
}
