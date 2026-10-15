package app.btcourier.core.sink

/** Serializes a sealed batch's lines into a file format (`Jsonl`, `Csv`, ...). */
interface Format {
    /** Extension without compression suffix, e.g. `jsonl`. */
    val extension: String

    /** Must be deterministic: equal input gives byte-identical output. */
    fun encode(lines: List<String>): ByteArray
}

/**
 * JSON Lines: each stored line verbatim, each followed by `\n`.
 *
 * This is also the "raw" format: there is no separate `Raw`. A format only sees the lines the
 * store holds, so whether a sink gets device-original or normalised rows is decided before the
 * store (by running a Normalizer or not), and a `Raw` encoder would emit the same bytes under a
 * name that promises something it can't deliver. If raw rows must reach one sink while another
 * gets normalised ones, the store has to keep both, and that is a store change, not a format.
 */
object Jsonl : Format {
    override val extension = "jsonl"

    override fun encode(lines: List<String>): ByteArray {
        val sb = StringBuilder(lines.sumOf { it.length + 1 })
        for (line in lines) sb.append(line).append('\n')
        return sb.toString().encodeToByteArray()
    }
}
