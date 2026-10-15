package app.btcourier.core.sink.formats

import app.btcourier.core.sink.Format

/**
 * RFC 4180 CSV with the header `ts,ts_quality,ts_raw,param,value,file,source` and one record per
 * data row.
 *
 * - `_file` marker lines emit no record; they set `file` for the records after them (empty before
 *   the first marker).
 * - `source` is the batch's source id on every record, so tables merged from several devices
 *   (whose `file` values can coincide) keep each row's origin.
 * - Fields are the row's JSON values as written (see [text]): null or absent is an empty field,
 *   so an unknown `ts` is empty. Keys outside the header are dropped; the header is fixed so every
 *   batch from every source loads into one table.
 * - A field containing `,`, `"`, CR or LF is quoted, with `"` doubled; nothing else is quoted.
 * - Lines that are not JSON objects are skipped. The store only holds what the source emitted, so
 *   such a line is device garbage with no column to put it in, and the Jsonl sink still has it.
 * - Records end in `\n`, not RFC 4180's CRLF: it matches the other formats, and every consumer
 *   in reach (pandas, DuckDB, sqlite, VictoriaMetrics `/api/v1/import/csv`) accepts it.
 */
object Csv : Format {
    override val extension = "csv"

    private val columns = listOf("ts", "ts_quality", "ts_raw", "param", "value")

    override fun encode(
        lines: List<String>,
        sourceId: String,
    ): ByteArray {
        val source = field(sourceId)
        val sb = StringBuilder(lines.sumOf { it.length + source.length + 2 } + 64)
        sb.append(columns.joinToString(",")).append(",file,source\n")
        forEachRow(lines) { file, row, _ ->
            if (row == null) return@forEachRow
            for (c in columns) sb.append(field(row.text(c))).append(',')
            sb
                .append(field(file.orEmpty()))
                .append(',')
                .append(source)
                .append('\n')
        }
        return sb.toString().encodeToByteArray()
    }

    private fun field(s: String): String =
        if (s.any { it == ',' || it == '"' || it == '\r' || it == '\n' }) "\"" + s.replace("\"", "\"\"") + "\"" else s
}
