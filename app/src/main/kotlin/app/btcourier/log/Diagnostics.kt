package app.btcourier.log

import java.io.File
import java.time.Instant

/**
 * The text the status screen exports: the [AppLog] followed by a listing of the store directory
 * (relative path and size of each file), which shows at a glance whether pages pile up or the
 * index is stale. File names are cursors and sequence numbers, never data.
 */
fun diagnosticsText(log: AppLog, storeDir: File, nowMs: Long): String = buildString {
    append("BT Courier diagnostics, ").append(Instant.ofEpochMilli(nowMs)).append("\n\n")
    append("== log ==\n")
    append(log.export())
    append("\n== store files ==\n")
    append(storeListing(storeDir))
}

/**
 * One `<size> <relative path>` line per file under [dir], sorted by path. Beyond [maxEntries]
 * files only a count and total are given, so a 200 MB backlog of small pages still fits a share
 * intent.
 */
fun storeListing(dir: File, maxEntries: Int = 1000): String {
    if (!dir.isDirectory) return "(no store directory)\n"
    val files = dir.walkTopDown().filter { it.isFile }.sortedBy { it.relativeTo(dir).invariantSeparatorsPath }.toList()
    return buildString {
        files.take(maxEntries).forEach { f ->
            append(f.length()).append(' ').append(f.relativeTo(dir).invariantSeparatorsPath).append('\n')
        }
        if (files.size > maxEntries) {
            val rest = files.drop(maxEntries)
            append("… ").append(rest.size).append(" more files, ").append(rest.sumOf { it.length() }).append(" bytes\n")
        }
        append("total: ").append(files.size).append(" files, ").append(files.sumOf { it.length() }).append(" bytes\n")
    }
}
