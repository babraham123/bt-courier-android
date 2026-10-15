package app.btcourier.core.sync

import app.btcourier.core.normalize.Anchor
import app.btcourier.core.normalize.JournalNormalizer
import app.btcourier.core.normalize.Normalizer
import app.btcourier.core.source.Source
import app.btcourier.core.source.SourceException
import app.btcourier.core.store.Store
import kotlinx.coroutines.delay
import java.io.IOException
import kotlin.time.Duration

/**
 * The loop of one sync of one source into the store (plan, "Sync loop"), on an already connected
 * transport:
 *
 * 1. Device status, for the [Anchor] (phone vs device clock).
 * 2. Clock journal.
 * 3. Pull from the store's cursor until `more:false`; the [Normalizer] turns each page into the
 *    lines to store, carrying the open file from page to page, and the store keeps them with the
 *    page's cursor in one transaction.
 *
 * Sealing is the caller's, after the loop and whatever its outcome, so a sync that never reaches
 * its device seals through the same code as one that does.
 *
 * The journal is fetched before the loop, not after it, because the store keeps normalised lines:
 * every page must be re-based the moment it is stored, and the device trims the journal to its
 * newest 32 KB, so a later sync might no longer have the events an old file needs. The cost: rows
 * pulled before the clock event that re-bases them is journaled are stored without it. That is a
 * clock set during the few seconds of a sync, or (the real case) rows pulled while the device
 * still had no clock at all: the set that would re-base them happens after they left. Those rows
 * keep `anchor` quality from this sync's anchor, which is right as long as the device did not
 * reboot between writing them and the sync. Re-basing at seal time instead would need the store
 * to keep raw lines.
 *
 * The first page starts in the file the store last left open for the source ([Store.openFile]),
 * so a page that resumes mid-file without restating its marker is still re-based as that file.
 *
 * Failures end the sync cleanly: the store's cursor stays at the last page persisted, so the next
 * sync resumes there, and a page that was pulled but not persisted is pulled again.
 *
 * @param busyBackoff waits before each retry of a call answered 429, on the same link; once they
 *   are used up the sync stops with [StopReason.Busy]. The caller's retry policy sets them; none
 *   by default.
 */
class SyncRunner(
    private val sourceId: String,
    private val source: Source,
    private val store: Store,
    private val normalizer: Normalizer = JournalNormalizer(),
    private val busyBackoff: List<Duration> = emptyList(),
) {
    suspend fun run(): SyncReport {
        val progress = Progress()
        val stoppedBecause =
            try {
                pullAll(progress)
            } catch (e: SourceException.Busy) {
                StopReason.Busy
            } catch (e: SourceException.Truncated) {
                StopReason.Truncated(e)
            } catch (e: SourceException) {
                StopReason.DeviceError(e)
            } catch (e: IOException) {
                StopReason.LinkLost(e)
            }
        return SyncReport(progress.pages, progress.linesStored, progress.duplicates, stoppedBecause)
    }

    private class Progress {
        var pages = 0
        var linesStored = 0L
        var duplicates = 0
    }

    private suspend fun pullAll(progress: Progress): StopReason {
        val status = retryBusy { source.deviceStatus() }
        val anchor = status?.let { Anchor(phoneEpochMs = it.phoneEpochMs, deviceEpochMs = it.deviceEpochMs) }
        val journal = retryBusy { source.clockJournal() }
        var openFile = store.openFile(sourceId)
        while (true) {
            val cursor = store.cursor(sourceId)
            val page = retryBusy { source.pull(cursor) }
            progress.pages++
            val fixed = normalizer.fix(page.lines, journal, anchor, openFile)
            openFile = fixed.lastFile
            when {
                // A page without rows stores nothing, but the cursor still has to move past it.
                store.appendPage(sourceId, page.nextCursor, fixed.lines) -> progress.linesStored += fixed.rows

                fixed.rows > 0 -> progress.duplicates++
            }
            if (!page.more) return StopReason.CaughtUp
            // The device promised more but did not move; asking again would loop forever.
            if (page.nextCursor == cursor) return StopReason.NoProgress(page.nextCursor)
        }
    }

    private suspend fun <T> retryBusy(call: suspend () -> T): T {
        for (wait in busyBackoff) {
            try {
                return call()
            } catch (_: SourceException.Busy) {
                delay(wait)
            }
        }
        return call()
    }
}

/**
 * @property pagesPulled export responses received, including an empty final one.
 * @property linesStored data rows newly stored (markers not counted).
 * @property duplicates pages with rows that the store already had (a re-pull after a lost ack
 *   or a crash between persisting and returning).
 */
data class SyncReport(
    val pagesPulled: Int,
    val linesStored: Long,
    val duplicates: Int,
    val stoppedBecause: StopReason,
)

sealed interface StopReason {
    /** The device said `more:false`. */
    data object CaughtUp : StopReason

    /** The device kept answering `more:true` without moving its cursor. */
    data class NoProgress(
        val cursor: String,
    ) : StopReason

    /** The link failed (dropped, frame lost, timed out); resume on the next connection. */
    data class LinkLost(
        val cause: IOException,
    ) : StopReason

    /** A response was cut short; the page was not stored and is pulled again next time. */
    data class Truncated(
        val cause: SourceException.Truncated,
    ) : StopReason

    /** 429 on every attempt; try again later. */
    data object Busy : StopReason

    /** The device refused (HTTP error) or answered something unusable; needs a look, not a retry loop. */
    data class DeviceError(
        val cause: SourceException,
    ) : StopReason
}
