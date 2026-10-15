package app.btcourier.core.sink

/**
 * Moves a [Batch] to a backend (`HttpPut`, `LocalFile`, `Sftp`, ...). An adapter: it classifies
 * what one attempt did and keeps no retry state; when to try again is [Delivery]'s decision.
 */
interface UploadTransport {
    /**
     * Uploads [batch]. Must be idempotent: uploading the same batch twice leaves one object.
     * Network failures are reported as [UploadResult.Retry], not thrown.
     */
    suspend fun upload(batch: Batch): UploadResult
}

sealed interface UploadResult {
    /** The backend durably stored the batch; the store may ack it. */
    data object Acked : UploadResult

    /**
     * Transient failure; another attempt may succeed.
     *
     * @property retryAfterMs how long the backend asked us to wait (HTTP `Retry-After`), or null
     *   when it gave no hint and the caller's back-off applies.
     */
    data class Retry(
        val reason: String,
        val retryAfterMs: Long? = null,
    ) : UploadResult

    /** Permanent failure (e.g. 403); retrying without a config change will not help. */
    data class Failed(
        val reason: String,
    ) : UploadResult
}
