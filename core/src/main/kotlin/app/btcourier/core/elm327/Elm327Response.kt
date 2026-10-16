package app.btcourier.core.elm327

/**
 * One ELM327 answer (the text before its `>` prompt, as [Elm327Transport.command] returns it),
 * classified.
 *
 * Data follows wican-fw `autopid.c` `parse_elm327_response` with the adapter set to `ATH1 ATS1`:
 * each line is `<header> <bytes…>`, and the bytes after the header of every line are
 * concatenated in line order, PCI bytes included. So `B0` of a single-frame answer
 * `7E8 04 41 0D 32` is the PCI `04`, and a multi-frame answer's consecutive-frame PCIs (`21`,
 * `22`, …) sit at every eighth byte. Headers are 3 hex digits (11-bit CAN) or 29-bit, shown either
 * as four 2-digit groups (`18 DA F1 10`, what `ATS1` prints) or as 8 digits. An adapter that
 * ignores `ATS1` and prints no spaces is split by length: odd-length lines carry an 11-bit header,
 * even-length ones a 29-bit header.
 */
sealed interface Elm327Response {
    /**
     * Data lines. [bytes] is what [ByteExpression] indexes; [headers] has each line's header in
     * order, for telling several answering ECUs apart.
     */
    class Data(
        val headers: List<String>,
        val bytes: ByteArray,
    ) : Elm327Response {
        override fun toString(): String = "Data(headers=$headers, bytes=${bytes.toHex()})"
    }

    /** The adapter accepted an AT command. */
    data object Ok : Elm327Response

    /** A status line instead of data: the PID yields nothing this round. */
    data class Status(
        val kind: StatusKind,
        val line: String,
    ) : Elm327Response

    /**
     * Neither data in the expected format nor a known status: segment-prefixed lines (`0: 62 01`)
     * of an adapter running with headers off, stray text, a header without bytes.
     */
    data class Unparseable(
        val reason: String,
        val text: String,
    ) : Elm327Response

    enum class StatusKind {
        NO_DATA,
        UNKNOWN_COMMAND,
        STOPPED,
        UNABLE_TO_CONNECT,
        BUS_INIT_ERROR,
        CAN_ERROR,
        BUFFER_FULL,

        /** `ERROR`, `BUS ERROR`, `DATA ERROR`, `<RX ERROR`, `FB ERROR`, `BUS BUSY`, `ACT ALERT`, `LV RESET`. */
        ERROR,
    }

    companion object {
        private val segmentPrefix = Regex("""^[0-9A-Fa-f]{1,2}:""")
        private val hexPair = Regex("""[0-9A-Fa-f]{2}""")
        private val hexDigits = Regex("""[0-9A-Fa-f]+""")
        private val errorLines = setOf("ERROR", "BUS BUSY", "ACT ALERT", "LV RESET")

        fun parse(text: String): Elm327Response {
            val lines =
                text
                    .split('\r', '\n')
                    .map { it.trim() }
                    .filter { it.isNotEmpty() && !isProgress(it) }
            if (lines.isEmpty()) return Unparseable("empty answer", text)
            lines.firstNotNullOfOrNull(::status)?.let { return it }
            if (lines.size == 1 && lines[0].equals("OK", ignoreCase = true)) return Ok

            // With headers off a multi-frame answer is a byte count line then `0:`, `1:`, ... lines; the
            // count line alone would read as a header without data, so this check comes first.
            if (lines.any {
                    segmentPrefix.containsMatchIn(
                        it,
                    )
                }
            ) {
                return Unparseable("headers off (segment-numbered lines)", text)
            }
            val headers = mutableListOf<String>()
            val bytes = mutableListOf<Byte>()
            for (line in lines) {
                val (header, data) = splitLine(line) ?: return Unparseable("not a data line: '$line'", text)
                headers += header
                data.forEach { bytes += it.toInt(16).toByte() }
            }
            return Data(headers, bytes.toByteArray())
        }

        /** Lines the adapter prints while working, before the answer proper. */
        private fun isProgress(line: String): Boolean {
            val upper = line.uppercase()
            return upper.startsWith("SEARCHING") || (upper.startsWith("BUS INIT") && "ERROR" !in upper)
        }

        private fun status(line: String): Status? {
            val upper = line.uppercase()
            val kind =
                when {
                    upper == "NO DATA" -> StatusKind.NO_DATA
                    upper == "?" -> StatusKind.UNKNOWN_COMMAND
                    upper == "STOPPED" -> StatusKind.STOPPED
                    upper.startsWith("UNABLE TO CONNECT") -> StatusKind.UNABLE_TO_CONNECT
                    upper.startsWith("BUS INIT") -> StatusKind.BUS_INIT_ERROR
                    upper.startsWith("CAN ERROR") -> StatusKind.CAN_ERROR
                    upper.startsWith("BUFFER FULL") -> StatusKind.BUFFER_FULL
                    upper in errorLines || upper.endsWith(" ERROR") || upper.endsWith("<RX ERROR") -> StatusKind.ERROR
                    else -> return null
                }
            return Status(kind, line)
        }

        /** The header and the byte pairs of one data line, or null when it is not one. */
        private fun splitLine(line: String): Pair<String, List<String>>? {
            val tokens = line.split(' ').filter { it.isNotEmpty() }
            if (tokens.any { !hexDigits.matches(it) }) return null
            val (header, data) =
                if (tokens.size == 1) {
                    unspaced(tokens[0]) ?: return null
                } else {
                    when (tokens[0].length) {
                        3, 8 -> tokens[0] to tokens.drop(1)
                        2 -> if (tokens.size > 4) tokens.take(4).joinToString("") to tokens.drop(4) else return null
                        else -> return null
                    }
                }
            if (data.isEmpty() || data.any { !hexPair.matches(it) } || !isCanId(header)) return null
            return header.uppercase() to data
        }

        private fun unspaced(line: String): Pair<String, List<String>>? {
            val headerLength = if (line.length % 2 == 1) 3 else 8
            if (line.length <= headerLength) return null
            return line.take(headerLength) to line.drop(headerLength).chunked(2)
        }

        /**
         * Whether [header] can be a CAN identifier: at most 0x7FF for 11 bits, 0x1FFFFFFF for 29. It
         * also keeps a headers-off line such as `41 00 BE 3F A8 13` from passing for a 29-bit header.
         */
        private fun isCanId(header: String): Boolean {
            val id = header.toLongOrNull(16) ?: return false
            return if (header.length == 3) id <= 0x7FF else id <= 0x1FFFFFFF
        }

        private fun ByteArray.toHex(): String = joinToString(" ") { "%02X".format(it) }
    }
}
