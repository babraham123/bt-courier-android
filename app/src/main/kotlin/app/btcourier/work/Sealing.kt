package app.btcourier.work

import app.btcourier.core.store.SealPolicy
import app.btcourier.core.store.Store
import app.btcourier.core.store.StoreCounters

/**
 * What one seal of the store did.
 *
 * @property sealed ids of the batches it created.
 * @property counters the store's counters right after it.
 * @property dueAtMs when the policy's age rule closes the lines it left open; null when it left
 *   none, which is always the case with an age of 0.
 */
data class SealResult(
    val sealed: List<String>,
    val counters: StoreCounters,
    val dueAtMs: Long?,
)

/** Seals what [policy] closes at [nowMs]; the one seal a sync's end and a drive session round share. */
suspend fun Store.sealNow(
    policy: SealPolicy,
    nowMs: Long,
): SealResult {
    val sealed = seal(policy, nowMs)
    val counters = counters()
    return SealResult(sealed, counters, counters.oldestUnsealedAtMs?.let { it + policy.maxBatchAgeMs })
}

/**
 * The upload rule after a seal, whoever sealed ([SyncWork] after a sync, [DriveSession] after a
 * round):
 *
 * - [newBatches]: an upload at once, KEEP, so an upload already waiting delivers them too.
 * - [sealDueAtMs]: an upload for when the age rule closes the lines left open ([FollowUp.SEAL]);
 *   that upload's own seal closes them, so they do not wait for the next sync or round. A newer
 *   follow-up replaces the previous one.
 *
 * Both can apply at once: a seal that closed one file and left the next half-pulled.
 */
fun WorkControl.uploadAfterSeal(
    newBatches: Boolean,
    sealDueAtMs: Long?,
    uploadOverCellular: Boolean,
    nowMs: Long,
) {
    if (newBatches) uploadNow(uploadOverCellular, replace = false)
    sealDueAtMs?.let { uploadAt(it - nowMs, uploadOverCellular, FollowUp.SEAL) }
}
