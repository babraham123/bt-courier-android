package app.btcourier.core.sink

import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.concurrent.atomic.AtomicInteger

/**
 * Retry delays for one transport instance: `Retry-After` when the server sent a usable one,
 * else exponential back-off over consecutive retries from [INITIAL_BACKOFF_MS], doubling, capped at
 * [MAX_BACKOFF_MS]. [reset] on any non-retry result.
 *
 * @param nowMs wall clock, for `Retry-After` dates.
 */
class RetryBackoff(private val nowMs: () -> Long = System::currentTimeMillis) {
    private val consecutive = AtomicInteger(0)

    fun reset() = consecutive.set(0)

    fun retry(reason: String, retryAfterMs: Long?): UploadResult.Retry {
        val attempt = consecutive.getAndIncrement()
        val backoff = (INITIAL_BACKOFF_MS shl attempt.coerceAtMost(MAX_DOUBLINGS)).coerceAtMost(MAX_BACKOFF_MS)
        return UploadResult.Retry(reason, retryAfterMs ?: backoff)
    }

    /** RFC 9110 §10.2.3: delay-seconds or an HTTP-date; null if absent or unparseable. */
    fun retryAfterMs(header: String?): Long? {
        val value = header?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val ms = value.toLongOrNull()?.let { it * 1000 }
            ?: try {
                ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() - nowMs()
            } catch (_: DateTimeParseException) {
                return null
            }
        // A past date means "now"; a far-future one is more likely a broken server than a real
        // request to wait for days, and would stall delivery silently.
        return ms.coerceIn(0, MAX_RETRY_AFTER_MS)
    }

    companion object {
        const val INITIAL_BACKOFF_MS = 30_000L
        const val MAX_BACKOFF_MS = 60 * 60_000L
        const val MAX_RETRY_AFTER_MS = 24 * 60 * 60_000L

        // Enough to pass MAX_BACKOFF_MS; bounds the shift so it can't overflow.
        private const val MAX_DOUBLINGS = 16
    }
}
