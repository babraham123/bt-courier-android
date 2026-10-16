package app.btcourier.core.sink

import app.btcourier.core.store.Store
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Delivers [store]'s pending batches to sinks and owns retry policy: transports only classify one
 * attempt, this module decides when a sink may be tried again.
 *
 * [deliverPending] uploads a sink's pending batches oldest first, acking each, and stops at the
 * first result that is not [UploadResult.Acked], so a sink receives batches in order and one
 * sink's failure never touches another's. A [UploadResult.Retry] schedules the sink's next
 * attempt: [UploadResult.Retry.retryAfterMs] from now when the backend gave one (capped at
 * [MAX_RETRY_AFTER_MS]), else an exponential back-off over the sink's consecutive retries,
 * [INITIAL_BACKOFF_MS] doubling up to [MAX_BACKOFF_MS]. Until that time [deliverPending] sends
 * nothing to the sink and reports [DeliveryResult.Deferred], so a backend is never hit before it
 * asked to be, and the caller can tell a round that waited from one that failed. An Acked or
 * [UploadResult.Failed] result clears the schedule.
 *
 * Retry state is keyed by [Sink.id] and lives in this instance, not in the sinks, so callers may
 * rebuild sinks every round (fresh transports, fresh credentials) without losing it. Keep one
 * instance for the life of the process. It is persisted through [retryStates]: loaded on this
 * instance's first call (a constructor cannot suspend), saved whole on every change, so a
 * process restarted while a sink waits still waits for it, with its streak and reason.
 *
 * @param clock wall clock for the schedule; persisted due times are on it too.
 */
class Delivery(
    private val store: Store,
    private val clock: () -> Long = System::currentTimeMillis,
    private val retryStates: RetryStateStore = MemoryRetryStateStore(),
) {
    // Guards the schedules and orders their saves; never held across an upload.
    private val lock = Mutex()

    // Null until loaded from retryStates.
    private var schedules: Map<String, SinkRetryState>? = null

    /** One round for [sink]; see [DeliveryResult]. */
    suspend fun deliverPending(sink: Sink): DeliveryResult {
        var batch = store.pending(sink.id, limit = 1).firstOrNull() ?: return DeliveryResult.Delivered
        val waiting = lock.withLock { loaded()[sink.id] }
        if (waiting != null &&
            waiting.dueAtMs > clock()
        ) {
            return DeliveryResult.Deferred(waiting.reason, waiting.dueAtMs)
        }
        while (true) {
            val result = sink.upload(batch)
            record(sink.id, result)
            if (result != UploadResult.Acked) return DeliveryResult.Stopped(result)
            store.ack(batch.id, sink.id)
            batch = store.pending(sink.id, limit = 1).firstOrNull() ?: return DeliveryResult.Delivered
        }
    }

    /**
     * Drops [sinkId]'s schedule, so its next round tries it at once. For a sink whose configuration
     * changed or which was removed: the old schedule was earned by a target that no longer exists.
     */
    suspend fun forget(sinkId: String) = update(sinkId) { null }

    private suspend fun record(
        sinkId: String,
        result: UploadResult,
    ) = update(sinkId) { previous ->
        if (result !is UploadResult.Retry) return@update null
        val streak = (previous?.streak ?: 0) + 1
        val delay = result.retryAfterMs?.coerceIn(0, MAX_RETRY_AFTER_MS) ?: backoffMs(streak)
        SinkRetryState(streak, clock() + delay, result.reason)
    }

    /** Replaces [sinkId]'s schedule with [next] of it (null: none), saving only a change. */
    private suspend fun update(
        sinkId: String,
        next: (SinkRetryState?) -> SinkRetryState?,
    ) = lock.withLock {
        val current = loaded()
        val previous = current[sinkId]
        val updated = next(previous)
        if (updated == previous) return@withLock
        val all = if (updated == null) current - sinkId else current + (sinkId to updated)
        schedules = all
        retryStates.save(all)
    }

    /** The schedules, loading them on first use; call with [lock] held. */
    private suspend fun loaded(): Map<String, SinkRetryState> = schedules ?: retryStates.load().also { schedules = it }

    companion object {
        /**
         * WorkManager's default back-off delay, which the upload work retries with, so with no
         * hint a worker retry finds its sink due rather than still waiting.
         */
        const val INITIAL_BACKOFF_MS = 30_000L
        const val MAX_BACKOFF_MS = 60 * 60_000L

        /**
         * A far-future `Retry-After` is more likely a broken server than a real request to wait
         * for days, and would stall delivery silently.
         */
        const val MAX_RETRY_AFTER_MS = 24 * 60 * 60_000L

        // Enough to pass MAX_BACKOFF_MS; bounds the shift so it can't overflow.
        private const val MAX_DOUBLINGS = 16

        /** Wait after the [streak]th consecutive retry without a hint (1-based). */
        fun backoffMs(streak: Int): Long =
            (INITIAL_BACKOFF_MS shl (streak - 1).coerceIn(0, MAX_DOUBLINGS)).coerceAtMost(MAX_BACKOFF_MS)
    }
}

/** How a [Delivery.deliverPending] round for one sink ended. */
sealed interface DeliveryResult {
    /** Nothing is pending for the sink any more. */
    data object Delivered : DeliveryResult

    /** A batch was sent and the sink answered [result] (Retry or Failed); it and later batches stay pending. */
    data class Stopped(
        val result: UploadResult,
    ) : DeliveryResult

    /**
     * Nothing was sent: the sink is waiting out its schedule after [reason] and is due at
     * [dueAtMs] (the delivery's clock). Not an attempt, so a caller counting attempts must not
     * count it, and should come back at [dueAtMs] rather than on its own back-off.
     */
    data class Deferred(
        val reason: String,
        val dueAtMs: Long,
    ) : DeliveryResult
}
