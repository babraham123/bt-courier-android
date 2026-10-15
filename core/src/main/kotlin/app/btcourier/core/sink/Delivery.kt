package app.btcourier.core.sink

import app.btcourier.core.store.Store
import java.util.concurrent.ConcurrentHashMap

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
 * nothing to the sink and reports a Retry, so a backend is never hit before it asked to be.
 * An Acked or [UploadResult.Failed] result clears the schedule.
 *
 * Retry state is keyed by [Sink.id] and lives in this instance, not in the sinks, so callers may
 * rebuild sinks every round (fresh transports, fresh credentials) without losing it. Keep one
 * instance for the life of the process. The state is not persisted: after a restart every sink
 * is due.
 *
 * @param clock wall clock for the schedule.
 */
class Delivery(private val store: Store, private val clock: () -> Long = System::currentTimeMillis) {
    private class Backoff(val streak: Int, val nextAttemptAtMs: Long, val reason: String)

    private val backoffs = ConcurrentHashMap<String, Backoff>()

    /**
     * One round for [sink].
     *
     * @return null if nothing is pending for it any more, else the result that stopped delivery.
     *   A sink still waiting out its schedule gets a [UploadResult.Retry] naming the last reason,
     *   with [UploadResult.Retry.retryAfterMs] set to the time left, and nothing is sent.
     */
    suspend fun deliverPending(sink: Sink): UploadResult? {
        var batch = store.pending(sink.id, limit = 1).firstOrNull() ?: return null
        backoffs[sink.id]?.let { waiting ->
            val left = waiting.nextAttemptAtMs - clock()
            if (left > 0) return UploadResult.Retry("${waiting.reason} (next attempt in ${(left + 999) / 1000} s)", left)
        }
        while (true) {
            val result = sink.upload(batch)
            record(sink.id, result)
            if (result != UploadResult.Acked) return result
            store.ack(batch.id, sink.id)
            batch = store.pending(sink.id, limit = 1).firstOrNull() ?: return null
        }
    }

    private fun record(sinkId: String, result: UploadResult) {
        if (result !is UploadResult.Retry) {
            backoffs.remove(sinkId)
            return
        }
        backoffs.compute(sinkId) { _, previous ->
            val streak = (previous?.streak ?: 0) + 1
            val delay = result.retryAfterMs?.coerceIn(0, MAX_RETRY_AFTER_MS) ?: backoffMs(streak)
            Backoff(streak, clock() + delay, result.reason)
        }
    }

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
