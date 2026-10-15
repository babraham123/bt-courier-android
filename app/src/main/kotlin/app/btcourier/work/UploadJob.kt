package app.btcourier.work

import app.btcourier.core.sink.Delivery
import app.btcourier.core.sink.DeliveryResult
import app.btcourier.core.sink.Sink
import app.btcourier.core.sink.UploadResult
import app.btcourier.core.store.SealPolicy
import app.btcourier.core.store.Store
import app.btcourier.data.SinkUpload
import app.btcourier.data.SyncStatusRepository
import app.btcourier.data.UploadResultKind
import app.btcourier.log.AppLog
import java.io.Closeable

/**
 * One delivery round of every pending batch to every configured sink, independent of WorkManager.
 * Each sink is delivered to on its own, so one that is down does not hold back the others. The
 * round seals the store first: otherwise rows whose batch only the age rule closes would wait
 * for the next sync that reaches the device.
 *
 * Retries are bounded: a round in which a sink answered [UploadResult.Retry] asks for another
 * attempt until [maxAttempts] rounds have run, then gives up ([UploadOutcome.GaveUp]);
 * [UploadResult.Failed] is never retried. Either way the batches stay in the store and the next
 * upload (after the next sync that seals new batches, or a manual one) tries them again. Within a
 * round, [delivery] decides whether a sink is due; one still waiting (its server's `Retry-After`,
 * or back-off) is not contacted. Such a round is no attempt: when nothing else asks for a retry it
 * ends [UploadOutcome.Deferred], asking for one follow-up when the earliest waiting sink is due, so
 * a long `Retry-After` neither burns the attempt budget nor strands the backlog until the next sync.
 *
 * @param sinks builds the configured sinks; may throw [IllegalArgumentException] for a bad URL.
 *   Transports that are [Closeable] are closed when the round ends.
 * @param foregroundThresholdBytes stored bytes from which [run] calls its `onLargeBacklog`.
 * @param delivery holds per-sink retry state across rounds, so one job must serve every round.
 */
class UploadJob(
    private val store: Store,
    private val sinks: suspend () -> List<Sink>,
    private val status: SyncStatusRepository,
    private val clock: () -> Long = System::currentTimeMillis,
    private val maxAttempts: Int = MAX_ATTEMPTS,
    private val foregroundThresholdBytes: Long = FOREGROUND_THRESHOLD_BYTES,
    private val log: AppLog = AppLog(),
    private val delivery: Delivery = Delivery(store, clock),
) {
    /**
     * @param attempt 0-based index of this round among consecutive retries.
     * @param sealPolicy what to seal before delivering.
     * @param onLargeBacklog runs before delivery when the backlog is large enough that the upload
     *   may outlast a background job's time limit; the worker promotes itself to a foreground service.
     */
    suspend fun run(attempt: Int, sealPolicy: SealPolicy, onLargeBacklog: suspend () -> Unit = {}): UploadOutcome {
        store.seal(sealPolicy, clock())
        val configured = try {
            sinks()
        } catch (e: IllegalArgumentException) {
            record(UploadResultKind.FAILED, "invalid sink configuration: ${e.message}")
            return UploadOutcome.Failed
        }
        return try {
            deliver(configured, attempt, onLargeBacklog)
        } finally {
            configured.forEach { (it.transport as? Closeable)?.close() }
        }
    }

    /** Lets [sinkId]'s next round try it at once, after it was edited or deleted ([Delivery.forget]). */
    fun forgetRetries(sinkId: String) = delivery.forget(sinkId)

    private suspend fun deliver(configured: List<Sink>, attempt: Int, onLargeBacklog: suspend () -> Unit): UploadOutcome {
        if (configured.isEmpty()) {
            record(UploadResultKind.NO_SINK, null, bySink = emptyMap())
            return UploadOutcome.NoSink
        }
        if (store.counters().bytesStored >= foregroundThresholdBytes) onLargeBacklog()

        val now = clock()
        val results = configured.map { sink -> sink.id to delivery.deliverPending(sink) }
        val stops = results.filter { (_, result) -> result != DeliveryResult.Delivered }
        val attempted = stops.any { (_, result) -> result is DeliveryResult.Stopped && result.result is UploadResult.Retry }
        val dueAtMs = stops.mapNotNull { (_, result) -> (result as? DeliveryResult.Deferred)?.dueAtMs }.minOrNull()
        val error = stops.takeIf { it.isNotEmpty() }?.joinToString("; ") { (id, result) -> "$id: ${reason(result, now)}" }
        val (kind, outcome) = when {
            stops.isEmpty() -> UploadResultKind.DELIVERED to UploadOutcome.Delivered
            attempted && attempt + 1 < maxAttempts -> UploadResultKind.RETRYING to UploadOutcome.Retry
            dueAtMs != null -> UploadResultKind.RETRYING to UploadOutcome.Deferred((dueAtMs - now).coerceAtLeast(0))
            attempted -> UploadResultKind.GAVE_UP to UploadOutcome.GaveUp
            else -> UploadResultKind.FAILED to UploadOutcome.Failed
        }
        val message = if (kind == UploadResultKind.GAVE_UP) "gave up after $maxAttempts attempts: $error" else error
        val bySink = results.associate { (id, result) ->
            val sinkKind = when (result) {
                DeliveryResult.Delivered -> UploadResultKind.DELIVERED
                is DeliveryResult.Deferred -> UploadResultKind.RETRYING
                is DeliveryResult.Stopped -> when (result.result) {
                    is UploadResult.Failed -> UploadResultKind.FAILED
                    else -> if (kind == UploadResultKind.GAVE_UP) UploadResultKind.GAVE_UP else UploadResultKind.RETRYING
                }
            }
            id to SinkUpload(now, sinkKind)
        }
        record(kind, message, bySink)
        return outcome
    }

    private fun reason(result: DeliveryResult, now: Long): String = when (result) {
        DeliveryResult.Delivered -> "delivered"
        is DeliveryResult.Deferred -> "${result.reason} (next attempt in ${(result.dueAtMs - now + 999) / 1000} s)"
        is DeliveryResult.Stopped -> when (val r = result.result) {
            is UploadResult.Retry -> r.reason
            is UploadResult.Failed -> r.reason
            UploadResult.Acked -> "acked"
        }
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

    /**
     * No sink was attempted and at least one is waiting out its schedule: nothing to retry now,
     * but a follow-up upload is due in [delayMs]. Also the end of a run whose attempts are used
     * up while a sink still waits, since its due time is the better guess than giving up.
     */
    data class Deferred(val delayMs: Long) : UploadOutcome

    data object GaveUp : UploadOutcome

    /** A sink refused permanently, or the sink configuration is invalid. */
    data object Failed : UploadOutcome

    data object NoSink : UploadOutcome
}
