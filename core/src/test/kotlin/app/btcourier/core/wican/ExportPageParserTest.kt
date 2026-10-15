package app.btcourier.core.wican

import app.btcourier.core.source.Page
import app.btcourier.core.testing.WicanLines.fileMarker
import app.btcourier.core.testing.WicanLines.meta
import app.btcourier.core.testing.WicanLines.row
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class ExportPageParserTest {
    @Test
    fun `rows and file markers are kept verbatim with marker positions and the trailing cursor`() {
        val lines = listOf(
            fileMarker("dl_0000000001.jsonl"),
            row(25_000, value = "0.500000"),
            row(26_000),
            fileMarker("dl_1784000060.jsonl"),
            row(1_784_000_061_000),
        )
        val body = (lines + meta("1784000060:4096", more = true)).joinToString("\n", postfix = "\n")

        val page = ExportPageParser.parse(body)

        assertEquals(lines, page.lines)
        assertEquals(
            listOf(FileMarker(0, "dl_0000000001.jsonl"), FileMarker(3, "dl_1784000060.jsonl")),
            page.files,
        )
        assertEquals("1784000060:4096", page.cursor)
        assertEquals(true, page.more)
        assertEquals(Page(lines, "1784000060:4096", true), page.toPage())
    }

    @Test
    fun `a page with only the meta line is an empty final page`() {
        val page = ExportPageParser.parse(meta("1784000060:8192", more = false) + "\n")

        assertEquals(emptyList<String>(), page.lines)
        assertEquals(emptyList<FileMarker>(), page.files)
        assertEquals("1784000060:8192", page.cursor)
        assertFalse(page.more)
    }

    @Test
    fun `CRLF endings and blank lines do not change the rows`() {
        val body = fileMarker("dl_1.jsonl") + "\r\n\r\n" + row(1) + "\r\n" + meta("1:10", false) + "\r\n"

        val page = ExportPageParser.parse(body)

        assertEquals(listOf(fileMarker("dl_1.jsonl"), row(1)), page.lines)
    }

    @Test
    fun `a page without the trailing meta line is rejected as truncated`() {
        val body = listOf(fileMarker("dl_1.jsonl"), row(1), row(2)).joinToString("\n")

        assertThrows<ExportPageException.Truncated> { ExportPageParser.parse(body) }
    }

    @Test
    fun `a page cut inside the meta line is rejected as truncated`() {
        val body = row(1) + "\n" + meta("1:10", false).dropLast(5)

        assertThrows<ExportPageException.Truncated> { ExportPageParser.parse(body) }
    }

    @Test
    fun `an empty body is rejected as truncated`() {
        assertThrows<ExportPageException.Truncated> { ExportPageParser.parse("") }
    }

    @Test
    fun `a meta line before the end is rejected`() {
        val body = listOf(row(1), meta("1:10", true), row(2), meta("1:20", false)).joinToString("\n")

        val e = assertThrows<ExportPageException.MisplacedMeta> { ExportPageParser.parse(body) }
        assertEquals("export cursor line at 1 is not the last line", e.message)
    }
}
