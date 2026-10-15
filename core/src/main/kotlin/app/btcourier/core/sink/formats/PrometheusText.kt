package app.btcourier.core.sink.formats

import app.btcourier.core.sink.Format
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/**
 * Prometheus text exposition with explicit timestamps, as VictoriaMetrics
 * `/api/v1/import/prometheus` ingests it. One sample per data row:
 *
 * ```
 * autopid_SOC{source="wican:AA:BB:CC:DD:EE:01",file="dl_1700000000.jsonl",ts_quality="journal"} 81.5 1700000000123
 * ```
 *
 * - Metric name: `param` with every char outside `[a-zA-Z0-9_:]` replaced by `_`, and a leading
 *   `_` added if it would start with a digit.
 * - Labels, always all three, in this order: `source` (the batch's source id), `file` (the latest
 *   `_file` marker, "" before any) and `ts_quality` (as stored, "" if absent). Values escape `\`,
 *   `"` and LF. Without `source`, two devices of one kind writing the same file name would emit
 *   colliding series and the receiver would keep only one sample per timestamp.
 * - Value: `value`'s JSON number literal verbatim (JSON number syntax is a subset of what
 *   Prometheus parses). Timestamp: `ts` in ms.
 * - `_file` marker lines emit nothing.
 * - Every other line that can't be a sample (null or non-integer `ts`, missing `param`, a
 *   non-numeric `value`, not a JSON object) becomes `# dropped: <line>`. Omitting the timestamp
 *   would make the receiver stamp the row with ingest time, which is wrong data; a comment keeps
 *   the row accounted for in the batch bytes, and the parser ignores it.
 *
 * Samples keep input order and there are no `# TYPE` lines, so same-metric samples are not grouped
 * the way a strict Prometheus scrape parser wants; import endpoints don't require that.
 */
object PrometheusText : Format {
    override val extension = "prom"

    private val invalidNameChar = Regex("[^a-zA-Z0-9_:]")

    override fun encode(
        lines: List<String>,
        sourceId: String,
    ): ByteArray {
        val source = label(sourceId)
        val sb = StringBuilder(lines.sumOf { it.length + source.length + 26 })
        forEachRow(lines) { file, row, line ->
            val sample = row?.let { sample(it, source, file.orEmpty()) }
            if (sample != null) sb.append(sample) else sb.append("# dropped: ").append(line)
            sb.append('\n')
        }
        return sb.toString().encodeToByteArray()
    }

    /** [source] arrives already escaped; it is the same for every row. */
    private fun sample(
        row: JsonObject,
        source: String,
        file: String,
    ): String? {
        val ts = (row["ts"] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull ?: return null
        val value = (row["value"] as? JsonPrimitive)?.takeIf { !it.isString && it.doubleOrNull != null } ?: return null
        val param = row.text("param").takeIf { it.isNotEmpty() } ?: return null
        val name = invalidNameChar.replace(param, "_").let { if (it[0].isDigit()) "_$it" else it }
        return "$name{source=\"$source\",file=\"${label(
            file,
        )}\",ts_quality=\"${label(row.text("ts_quality"))}\"} ${value.content} $ts"
    }

    private fun label(s: String) = s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
}
