package app.btcourier.core.sink.formats

import app.btcourier.core.sink.Format
import app.btcourier.core.sink.Jsonl
import app.btcourier.core.sink.encodeBatch
import app.btcourier.core.store.SealedBatch
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.zip.GZIPInputStream

class FormatsTest {
    private val soc = """{"ts":1700000000123,"param":"autopid.SOC","value":81.50,"ts_raw":12345,"ts_quality":"journal"}"""
    private val unknownTs = """{"ts":null,"param":"autopid.HV V","value":-3,"ts_raw":12,"ts_quality":"unknown"}"""
    private val quoted = """{"ts":1700000100000,"param":"name,with \"quotes\"","value":1e3,"ts_raw":5,"ts_quality":"anchor"}"""
    private val textValue = """{"ts":1700000100001,"param":"autopid.GEAR","value":"P","ts_raw":6,"ts_quality":"device"}"""

    /** Two device files, garbage between them, and every kind of row each format must handle. */
    private val lines = listOf(
        """{"_file":"dl_1700000000.jsonl"}""",
        soc,
        unknownTs,
        "not json",
        "[1,2]",
        """{"_file":"dl_1700000100.jsonl"}""",
        quoted,
        textValue,
    )

    private val source = "wican:AA:BB:CC:DD:EE:01"

    private fun gunzip(bytes: ByteArray) = GZIPInputStream(bytes.inputStream()).readBytes()

    /** Through [encodeBatch], as a sink would: the name carries the extension and the bytes decode back. */
    private fun roundTrip(format: Format, lines: List<String> = this.lines, sourceId: String = source): String {
        val batch = encodeBatch(SealedBatch("7", sourceId, "1700000000:0", lines, sealedAtMs = 0), format)
        assert(batch.name.endsWith(".${format.extension}.gz")) { batch.name }
        val content = gunzip(batch.bytes)
        assertArrayEquals(format.encode(lines, sourceId), content, "deterministic")
        return content.decodeToString()
    }

    @Test
    fun `csv has a fixed header, one record per data row, and the file from the latest marker`() {
        assertEquals(
            "ts,ts_quality,ts_raw,param,value,file,source\n" +
                "1700000000123,journal,12345,autopid.SOC,81.50,dl_1700000000.jsonl,$source\n" +
                ",unknown,12,autopid.HV V,-3,dl_1700000000.jsonl,$source\n" +
                "1700000100000,anchor,5,\"name,with \"\"quotes\"\"\",1e3,dl_1700000100.jsonl,$source\n" +
                "1700000100001,device,6,autopid.GEAR,P,dl_1700000100.jsonl,$source\n",
            roundTrip(Csv),
        )
    }

    @Test
    fun `csv leaves file empty before any marker, drops extra keys, and quotes CR and LF`() {
        val rows = listOf(
            """{"param":"x","value":1,"extra":true}""",
            """{"ts":1,"param":"multi\r\nline","value":{"a":1}}""",
        )
        assertEquals(
            "ts,ts_quality,ts_raw,param,value,file,source\n" +
                ",,,x,1,,$source\n" +
                "1,,,\"multi\r\nline\",\"{\"\"a\"\":1}\",,$source\n",
            roundTrip(Csv, rows),
        )
    }

    @Test
    fun `an empty batch is just the csv header`() {
        assertEquals("ts,ts_quality,ts_raw,param,value,file,source\n", roundTrip(Csv, emptyList()))
    }

    @Test
    fun `prometheus emits timestamped samples and comments out rows it cannot stamp`() {
        assertEquals(
            """
            autopid_SOC{source="$source",file="dl_1700000000.jsonl",ts_quality="journal"} 81.50 1700000000123
            # dropped: $unknownTs
            # dropped: not json
            # dropped: [1,2]
            name_with__quotes_{source="$source",file="dl_1700000100.jsonl",ts_quality="anchor"} 1e3 1700000100000
            # dropped: $textValue

            """.trimIndent(),
            roundTrip(PrometheusText),
        )
    }

    @Test
    fun `prometheus prefixes digit-led names, escapes label values, and drops rows without a param`() {
        val rows = listOf(
            """{"ts":5,"param":"0x0C:rpm","value":800}""",
            """{"_file":"we\"ird\\name"}""",
            """{"ts":6,"param":"autopid.SOC","value":1,"ts_quality":"device"}""",
            """{"ts":7,"value":1}""",
            """{"ts":1.5e3,"param":"p","value":1}""",
        )
        assertEquals(
            "_0x0C:rpm{source=\"$source\",file=\"\",ts_quality=\"\"} 800 5\n" +
                "autopid_SOC{source=\"$source\",file=\"we\\\"ird\\\\name\",ts_quality=\"device\"} 1 6\n" +
                "# dropped: ${rows[3]}\n" +
                "# dropped: ${rows[4]}\n",
            roundTrip(PrometheusText, rows),
        )
    }

    @Test
    fun `two devices of one kind with identical rows produce distinguishable tabular output`() {
        val ring1 = "colmi:AA:BB:CC:DD:EE:01"
        val ring2 = "colmi:AA:BB:CC:DD:EE:02"
        val day = listOf("""{"_file":"colmi/2026-10-14"}""", """{"ts":1760400000000,"param":"hr","value":61,"ts_quality":"device"}""")

        assertEquals(
            "hr{source=\"$ring1\",file=\"colmi/2026-10-14\",ts_quality=\"device\"} 61 1760400000000\n",
            roundTrip(PrometheusText, day, ring1),
        )
        assertEquals(
            "hr{source=\"$ring2\",file=\"colmi/2026-10-14\",ts_quality=\"device\"} 61 1760400000000\n",
            roundTrip(PrometheusText, day, ring2),
        )
        assertEquals(
            "ts,ts_quality,ts_raw,param,value,file,source\n1760400000000,device,,hr,61,colmi/2026-10-14,$ring2\n",
            roundTrip(Csv, day, ring2),
        )
    }

    @Test
    fun `the source id is escaped like any other label or field`() {
        val odd = "x\"y,z\\w"
        val row = listOf("""{"ts":1,"param":"p","value":2}""")

        assertEquals("p{source=\"x\\\"y,z\\\\w\",file=\"\",ts_quality=\"\"} 2 1\n", roundTrip(PrometheusText, row, odd))
        assertEquals("ts,ts_quality,ts_raw,param,value,file,source\n1,,,p,2,,\"x\"\"y,z\\w\"\n", roundTrip(Csv, row, odd))
    }

    @Test
    fun `jsonl leaves the stored lines untouched whatever the source`() {
        assertEquals(lines.joinToString("") { "$it\n" }, roundTrip(Jsonl, lines, "wican:AA:BB:CC:DD:EE:01"))
        assertEquals(roundTrip(Jsonl, lines, "wican:AA:BB:CC:DD:EE:01"), roundTrip(Jsonl, lines, "colmi:AA:BB:CC:DD:EE:02"))
    }
}
