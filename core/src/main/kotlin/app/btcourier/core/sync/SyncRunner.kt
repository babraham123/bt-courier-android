package app.btcourier.core.sync

import app.btcourier.core.normalize.Anchor
import app.btcourier.core.normalize.JournalNormalizer
import app.btcourier.core.normalize.Normalizer
import app.btcourier.core.source.ClockEvent
import app.btcourier.core.source.Source
import app.btcourier.core.source.SourceException
import app.btcourier.core.store.SealPolicy
import app.btcourier.core.store.Store
import app.btcourier.core.transport.blehttp.BleHttpException
import app.btcourier.core.wican.fileMarkerName
import kotlinx.coroutines.delay
import java.io.IOException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * One sync of one source into the store (plan, "Sync loop"), on an already connected transport:
 *
 * 1. Device status, for the [Anchor] (phone vs device clock).
 * 2. Clock journal.
 * 3. Pull from the store's cursor until `more:false`, normalising each page and storing it with
 *    its cursor in one transaction.
 * 4. Seal, whatever the outcome, so every persisted page can reach the sinks.
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
 * Failures end the sync cleanly: the store's cursor stays at the last page persisted, so the next
 * sync resumes there, and a page that was pulled but not persisted is pulled again.
 *
 * @param busyBackoff waits before each retry of a call answered 429; once they are used up the
 *   sync stops with [StopReason.Busy] and the scheduler tries again later.
 * @param clock wall time for [Store.seal]'s age rule.
 */
class SyncRunner(
    private val sourceId: String,
    private val source: Source,
    private val store: Store,
    private val normalizer: Normalizer = JournalNormalizer(),
    private val sealPolicy: SealPolicy = SealPolicy(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val busyBackoff: List<Duration> = listOf(1.seconds, 2.seconds, 4.seconds),
) {
    suspend fun run(): SyncReport {
        val progress = Progress()
        val stoppedBecause = try {
            pullAll(progress)
        } catch (e: SourceException.Busy) {
            StopReason.Busy
        } catch (e: SourceException.Truncated) {
            StopReason.Truncated(e)
        } catch (e: SourceException) {
            StopReason.DeviceError(e)
        } catch (e: BleHttpException.Truncated) {
            StopReason.Truncated(e)
        } catch (e: IOException) {
            StopReason.LinkLost(e)
        }
        val sealed = store.seal(sealPolicy, clock())
        return SyncReport(progress.pages, progress.linesStored, progress.duplicates, stoppedBecause, sealed)
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
        var openFile: String? = null
        while (true) {
            val cursor = store.cursor(sourceId)
            val page = retryBusy { source.pull(cursor) }
            progress.pages++
            val normalized = normalize(page.lines, journal, anchor, openFile)
            openFile = normalized.lastFile
            if (normalized.rows == 0) {
                // Markers alone are not worth a stored page (they would seal into a batch with no
                // rows), but the cursor still has to move past them.
                store.appendPage(sourceId, page.nextCursor, emptyList())
            } else if (store.appendPage(sourceId, page.nextCursor, normalized.lines)) {
                progress.linesStored += normalized.rows
            } else {
                progress.duplicates++
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

    private class Normalized(val lines: List<String>, val rows: Int, val lastFile: String?)

    /**
     * Normalises the rows of [lines] file by file and keeps the `_file` markers in place: the
     * normaliser drops them, but the store splits batches on them.
     */
    private fun normalize(lines: List<String>, journal: List<ClockEvent>, anchor: Anchor?, openFile: String?): Normalized {
        val out = ArrayList<String>(lines.size)
        var rows = 0
        var file = openFile
        val segment = mutableListOf<String>()
        fun flush() {
            if (segment.isEmpty()) return
            normalizer.fix(segment, journal, anchor, file).forEach {
                out += it.line
                rows++
            }
            segment.clear()
        }
        for (line in lines) {
            val marker = fileMarkerName(line)
            if (marker == null) {
                segment += line
            } else {
                flush()
                out += line
                file = marker
            }
        }
        flush()
        return Normalized(out, rows, file)
    }
}

/**
 * @property pagesPulled export responses received, including an empty final one.
 * @property linesStored data rows newly stored (markers not counted).
 * @property duplicates pages with rows that the store already had (a re-pull after a lost ack
 *   or a crash between persisting and returning).
 * @property sealed ids of the batches this sync's seal created, whatever the outcome. Not the
 *   change in pending batches: the same seal may evict old batches at the cache cap, and an
 *   upload running meanwhile may drain some.
 */
data class SyncReport(
    val pagesPulled: Int,
    val linesStored: Long,
    val duplicates: Int,
    val stoppedBecause: StopReason,
    val sealed: List<String>,
)

sealed interface StopReason {
    /** The device said `more:false`. */
    data object CaughtUp : StopReason

    /** The device kept answering `more:true` without moving its cursor. */
    data class NoProgress(val cursor: String) : StopReason

    /** The link failed (dropped, frame lost, timed out); resume on the next connection. */
    data class LinkLost(val cause: IOException) : StopReason

    /** A response was cut short; the page was not stored and is pulled again next time. */
    data class Truncated(val cause: Exception) : StopReason

    /** 429 on every attempt; try again later. */
    data object Busy : StopReason

    /** The device refused (HTTP error) or answered something unusable; needs a look, not a retry loop. */
    data class DeviceError(val cause: SourceException) : StopReason
}
