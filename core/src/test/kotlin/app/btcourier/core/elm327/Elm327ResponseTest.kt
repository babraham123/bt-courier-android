package app.btcourier.core.elm327

import app.btcourier.core.elm327.Elm327Response.StatusKind
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource

/** ELM327 answer formats (ELM327 datasheet, `ATH1 ATS1`) and wican-fw `parse_elm327_response`. */
class Elm327ResponseTest {
    private fun hex(text: String) = text.split(' ').map { it.toInt(16).toByte() }.toByteArray()

    private fun data(text: String) = assertInstanceOf(Elm327Response.Data::class.java, Elm327Response.parse(text))

    @Test
    fun `a single frame keeps its PCI byte as B0`() {
        val answer = data("7E8 04 41 0D 32")
        assertEquals(listOf("7E8"), answer.headers)
        assertArrayEquals(hex("04 41 0D 32"), answer.bytes)
        assertEquals(0x32.toDouble(), ByteExpression.evaluate("B3", answer.bytes))
    }

    @Test
    fun `a multi-frame answer concatenates every line after its header, PCIs included`() {
        val answer =
            data(
                "7EC 10 0B 62 01 01 AA BB CC\n" +
                    "7EC 21 DD EE FF 11 22 AA AA",
            )
        assertEquals(listOf("7EC", "7EC"), answer.headers)
        assertArrayEquals(hex("10 0B 62 01 01 AA BB CC 21 DD EE FF 11 22 AA AA"), answer.bytes)
        // The second frame's PCI is B8; its first data byte B9.
        assertEquals(0x21.toDouble(), ByteExpression.evaluate("B8", answer.bytes))
        assertEquals(0xDD.toDouble(), ByteExpression.evaluate("B9", answer.bytes))
    }

    @Test
    fun `several ECUs answering are concatenated in line order`() {
        val answer = data("7E8 03 41 0D 32\n7E9 03 41 0D 33")
        assertEquals(listOf("7E8", "7E9"), answer.headers)
        assertArrayEquals(hex("03 41 0D 32 03 41 0D 33"), answer.bytes)
    }

    @Test
    fun `a 29-bit header with spaces is four 2-digit groups`() {
        val answer = data("18 DA F1 10 03 41 0D 32")
        assertEquals(listOf("18DAF110"), answer.headers)
        assertArrayEquals(hex("03 41 0D 32"), answer.bytes)
    }

    @Test
    fun `a 29-bit header may also be 8 digits`() {
        val answer = data("18DAF110 03 41 0D 32")
        assertEquals(listOf("18DAF110"), answer.headers)
        assertArrayEquals(hex("03 41 0D 32"), answer.bytes)
    }

    @Test
    fun `an adapter ignoring ATS1 is split by length - odd lines have an 11-bit header`() {
        val answer = data("7EC100B620101AABBCC\r7EC21DDEEFF1122AAAA")
        assertEquals(listOf("7EC", "7EC"), answer.headers)
        assertArrayEquals(hex("10 0B 62 01 01 AA BB CC 21 DD EE FF 11 22 AA AA"), answer.bytes)
    }

    @Test
    fun `an adapter ignoring ATS1 is split by length - even lines have a 29-bit header`() {
        val answer = data("18DAF11003410D32")
        assertEquals(listOf("18DAF110"), answer.headers)
        assertArrayEquals(hex("03 41 0D 32"), answer.bytes)
    }

    @Test
    fun `progress lines are skipped`() {
        assertArrayEquals(hex("03 41 0D 32"), data("SEARCHING...\n7E8 03 41 0D 32").bytes)
        assertArrayEquals(hex("03 41 0D 32"), data("BUS INIT: ...OK\n7E8 03 41 0D 32").bytes)
    }

    @Test
    fun `lowercase hex is accepted`() {
        assertArrayEquals(hex("03 41 0D 32"), data("7e8 03 41 0d 32").bytes)
    }

    @ParameterizedTest(name = "''{0}'' is {1}")
    @CsvSource(
        "NO DATA, NO_DATA",
        "?, UNKNOWN_COMMAND",
        "STOPPED, STOPPED",
        "UNABLE TO CONNECT, UNABLE_TO_CONNECT",
        "BUS INIT: ...ERROR, BUS_INIT_ERROR",
        "CAN ERROR, CAN_ERROR",
        "BUFFER FULL, BUFFER_FULL",
        "ERROR, ERROR",
        "BUS ERROR, ERROR",
        "DATA ERROR, ERROR",
        "<RX ERROR, ERROR",
        "FB ERROR, ERROR",
        "BUS BUSY, ERROR",
        "ACT ALERT, ERROR",
        "LV RESET, ERROR",
    )
    fun `status lines`(
        text: String,
        kind: StatusKind,
    ) {
        val status = assertInstanceOf(Elm327Response.Status::class.java, Elm327Response.parse(text))
        assertEquals(kind, status.kind)
    }

    @Test
    fun `a status line anywhere makes the whole answer that status`() {
        val searching = Elm327Response.parse("SEARCHING...\nUNABLE TO CONNECT")
        assertEquals(StatusKind.UNABLE_TO_CONNECT, assertInstanceOf(Elm327Response.Status::class.java, searching).kind)
        // Frames were lost, so the bytes before it are not the whole answer.
        val overflow = Elm327Response.parse("7E8 03 41 0D 32\nBUFFER FULL")
        assertEquals(StatusKind.BUFFER_FULL, assertInstanceOf(Elm327Response.Status::class.java, overflow).kind)
    }

    @Test
    fun `OK acknowledges an AT command`() {
        assertEquals(Elm327Response.Ok, Elm327Response.parse("OK"))
        assertEquals(Elm327Response.Ok, Elm327Response.parse("\nOK\n"))
    }

    @Test
    fun `segment-numbered lines mean headers are off and are not parsed as data`() {
        val text = "03E\n0: 62 01 01 AA BB CC\n1: DD EE FF 11 22 33 44"
        val answer = assertInstanceOf(Elm327Response.Unparseable::class.java, Elm327Response.parse(text))
        assertEquals("headers off (segment-numbered lines)", answer.reason)
    }

    @ParameterizedTest(name = "''{0}''")
    @ValueSource(
        strings = [
            "",
            "ELM327 v2.2",
            "12.6V",
            // Headers off, single frame: too short for a 29-bit header, wrong length for 11 bits.
            "41 0D 32",
            // Headers off, longer: 41 cannot start a 29-bit CAN identifier.
            "41 00 BE 3F A8 13",
            // A header without data.
            "7E8",
            "7E8 4",
            "7E8 03 41 0D 3",
            "7E8 03 41 0D ZZ",
            "FFF 03 41 0D 32",
        ],
    )
    fun `anything else is unparseable`(text: String) {
        assertInstanceOf(Elm327Response.Unparseable::class.java, Elm327Response.parse(text))
    }
}
