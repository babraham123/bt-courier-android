package app.btcourier.core.sink

/** Moves a [Batch] to a backend (`HttpPut`, `LocalFile`, `Sftp`, ...). */
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

    /** Transient failure; try again no sooner than [afterMs] from now (0 = use backoff). */
    data class Retry(val reason: String, val afterMs: Long = 0) : UploadResult

    /** Permanent failure (e.g. 403); retrying without a config change will not help. */
    data class Failed(val reason: String) : UploadResult
}
