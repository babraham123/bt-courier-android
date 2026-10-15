package app.btcourier.log

import java.io.File
import java.io.IOException
import java.time.Instant

/**
 * Bounded diagnostics log that the user can export from the status screen. Keeps the newest
 * [capacity] entries.
 *
 * Mirrored to [file] (when given) because syncs and uploads mostly run in a background process
 * that is gone by the time the user opens the app; the file is trimmed back to [capacity] lines
 * once it holds twice that, so it stays bounded without rewriting it on every entry.
 *
 * Every message goes through [redact] first: the export is meant to be shared, so credentials
 * must never reach it. Device addresses and cursors are fine to log.
 */
class AppLog(
    private val capacity: Int = DEFAULT_CAPACITY,
    private val file: File? = null,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    init {
        require(capacity > 0) { "capacity must be positive" }
    }

    private val entries = ArrayDeque<String>()
    private var linesInFile = 0
    private var loaded = false

    fun info(tag: String, message: String) = add("I", tag, message)

    fun warn(tag: String, message: String) = add("W", tag, message)

    /** The retained entries, oldest first. */
    @Synchronized
    fun lines(): List<String> {
        load()
        return entries.toList()
    }

    /** The retained entries as text, one per line. */
    fun export(): String = lines().joinToString(separator = "\n", postfix = "\n")

    @Synchronized
    private fun add(level: String, tag: String, message: String) {
        load()
        // One entry per line, so a multi-line exception message can't forge further entries.
        val text = redact(message).replace(Regex("\\s*[\\r\\n]+\\s*"), " ").take(MAX_MESSAGE_CHARS)
        val line = "${Instant.ofEpochMilli(clock())} $level $tag: $text"
        entries.addLast(line)
        while (entries.size > capacity) entries.removeFirst()
        persist(line)
    }

    private fun load() {
        if (loaded) return
        loaded = true
        val f = file ?: return
        try {
            if (!f.exists()) return
            val stored = f.readLines()
            linesInFile = stored.size
            stored.takeLast(capacity).forEach(entries::addLast)
        } catch (_: IOException) {
            // A diagnostics log is never worth failing a sync over.
        }
    }

    private fun persist(line: String) {
        val f = file ?: return
        try {
            f.parentFile?.mkdirs()
            if (linesInFile + 1 > 2 * capacity) {
                val tmp = File(f.parentFile, "${f.name}.tmp")
                tmp.writeText(entries.joinToString(separator = "\n", postfix = "\n"))
                if (!tmp.renameTo(f)) throw IOException("could not replace log file")
                linesInFile = entries.size
            } else {
                f.appendText(line + "\n")
                linesInFile++
            }
        } catch (_: IOException) {
            // Same as load: keep the in-memory copy and carry on.
        }
    }

    companion object {
        const val DEFAULT_CAPACITY = 500

        /** Keeps a full export far below the ~1 MB binder limit of a share intent. */
        const val MAX_MESSAGE_CHARS = 300

        private const val REDACTED = "<redacted>"

        // scheme://user:pass@host → scheme://<redacted>@host
        private val URL_USERINFO = Regex("""(?i)\b([a-z][a-z0-9+.-]*://)[^\s/?#@]+@""")

        // Authorization header values; the length floor spares prose such as "Basic auth".
        private val AUTH_SCHEME = Regex("""(?i)\b(Basic|Bearer)\s+[A-Za-z0-9._~+/=-]{8,}""")

        private val SECRET_PARAM = Regex(
            """(?i)\b(password|passwd|pwd|pass|token|access_token|refresh_token|secret|api_?key)=[^&\s]+""",
        )

        /** Strips credentials from [message]: URL user info, auth header values, secret query params. */
        fun redact(message: String): String =
            message
                .replace(URL_USERINFO) { "${it.groupValues[1]}$REDACTED@" }
                .replace(AUTH_SCHEME) { "${it.groupValues[1]} $REDACTED" }
                .replace(SECRET_PARAM) { "${it.groupValues[1]}=$REDACTED" }
    }
}
