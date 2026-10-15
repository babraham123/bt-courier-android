package app.btcourier.core.lines

import app.btcourier.core.lines.StoredLine.Marker
import app.btcourier.core.lines.StoredLine.Other
import app.btcourier.core.lines.StoredLine.Row
import app.btcourier.core.testing.WicanLines.fileMarker
import app.btcourier.core.testing.WicanLines.row
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test

/**
 * The marker spec: a JSON object whose only key is `_file` with a string value, as wican-fw's
 * export handler (and so `ExportPageParser` and `FakeWicanLogger`) and `ColmiRingSource` write it.
 */
class StoredLineTest {
    @Test
    fun `markers as the devices write them name their file`() {
        for ((line, file) in listOf(
            """{"_file":"dl_1784000060.jsonl"}""" to "dl_1784000060.jsonl",
            fileMarker("dl_0000000001.jsonl") to "dl_0000000001.jsonl",
            """{"_file":"colmi/2026-10-14"}""" to "colmi/2026-10-14",
            """  { "_file" : "dl_1.jsonl" }  """ to "dl_1.jsonl",
            """{"_file":"we\"ird\\name"}""" to "we\"ird\\name",
        )) {
            assertEquals(Marker(file), StoredLine.classify(line), line)
            assertEquals(file, StoredLine.markerFile(line), line)
        }
    }

    @Test
    fun `the marker builder writes the device's bytes and escapes what JSON requires`() {
        assertEquals("""{"_file":"dl_1784000060.jsonl"}""", StoredLine.marker("dl_1784000060.jsonl"))
        assertEquals("""{"_file":"colmi/2026-10-14"}""", StoredLine.marker("colmi/2026-10-14"))
        val odd = "we\"ird\\name"
        assertEquals(Marker(odd), StoredLine.classify(StoredLine.marker(odd)))
    }

    @Test
    fun `a row that also carries _file is a row, whatever the key order`() {
        // The firmware never writes one; as data its values reach every output instead of vanishing.
        for (line in listOf(
            """{"ts":1,"_file":"dl_1.jsonl"}""",
            """{"_file":"dl_1.jsonl","ts":1,"param":"autopid.TEST","value":1}""",
        )) {
            assertInstanceOf(Row::class.java, StoredLine.classify(line), line)
            assertEquals(null, StoredLine.markerFile(line), line)
        }
    }

    @Test
    fun `a non-string _file is a row and anything that is not a JSON object is other`() {
        assertInstanceOf(Row::class.java, StoredLine.classify("""{"_file":5}"""))
        assertEquals(Row(Json.parseToJsonElement(row(5)).jsonObject), StoredLine.classify(row(5)))
        for (line in listOf("", "   ", "not json", """{"_file":"dl_1""", """["_file","x"]""", "\"_file\"")) {
            assertEquals(Other, StoredLine.classify(line), line)
            assertEquals(false, StoredLine.isMarker(line), line)
        }
    }

    @Test
    fun `walking lines attributes each to the latest marker, starting from the open file`() {
        val seen = mutableListOf<Pair<String, String?>>()
        val last =
            walkFiles(
                listOf(row(1), fileMarker("b"), row(2), "garbage", fileMarker("c")),
                openFile = "a",
            ) { line, _, file ->
                seen += line to file
            }

        assertEquals(
            listOf(row(1) to "a", fileMarker("b") to "b", row(2) to "b", "garbage" to "b", fileMarker("c") to "c"),
            seen,
        )
        assertEquals("c", last)
        assertEquals("a", walkFiles(listOf(row(3)), openFile = "a") { _, _, _ -> })
        assertEquals(null, walkFiles(emptyList()) { _, _, _ -> })
    }
}
