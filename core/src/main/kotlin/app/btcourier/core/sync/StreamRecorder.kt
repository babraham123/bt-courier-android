package app.btcourier.core.sync

import app.btcourier.core.lines.StoredLine
import app.btcourier.core.normalize.JournalNormalizer
import app.btcourier.core.normalize.Normalizer
import app.btcourier.core.store.Store

/**
 * Keeps one session of a stream source's emissions ([app.btcourier.core.source.Source.subscribe])
 * in the store, so they seal, dedupe and upload exactly like pulled pages:
 *
 * - Each emission with rows is one page, under the cursor `session:<start ms>:<n>`, with n
 *   counting the session's pages from 1. A stream keeps nothing on the device, so the cursor
 *   only has to be unique; nothing ever resumes from it.
 * - The session's first page opens its file, `{"_file":"<kind>/<start ms>"}`, where the kind is
 *   [sourceId] up to its first colon (`elm327:AA:…` gives `elm327/1712345678901`): batches then
 *   split between sessions and carry the session in their name.
 * - Rows pass through [normalizer] as a pull without a clock journal or anchor would: a
 *   phone-stamped row keeps its `ts` and gets `ts_raw` and `ts_quality` `device`, the same fields
 *   a pulled row gets.
 *
 * An emission without rows (the source polled and decoded nothing) stores nothing and uses no
 * page number. One instance per session, fed by one collector.
 *
 * @param clock the phone's wall clock, epoch ms; read once, for the session's start.
 */
class StreamRecorder(
    private val sourceId: String,
    private val store: Store,
    clock: () -> Long,
    private val normalizer: Normalizer = JournalNormalizer(),
) {
    /** When the session started, phone epoch ms; names its file and cursors. */
    val startMs: Long = clock()

    /** The file the session's rows are stored under. */
    val file: String = "${sourceId.substringBefore(':')}/$startMs"

    private var pages = 0
    private var openFile: String? = null

    /** Stores [rows], one emission, and returns how many rows were newly stored. */
    suspend fun record(rows: List<String>): Int {
        if (rows.isEmpty()) return 0
        val page = if (openFile == null) listOf(StoredLine.marker(file)) + rows else rows
        val fixed = normalizer.fix(page, journal = emptyList(), anchor = null, openFile = openFile)
        if (fixed.rows == 0) return 0
        // Counted even if the store already has the cursor, so one collision cannot stall the session.
        pages++
        if (!store.appendPage(sourceId, "session:$startMs:$pages", fixed.lines)) return 0
        openFile = fixed.lastFile
        return fixed.rows
    }
}
