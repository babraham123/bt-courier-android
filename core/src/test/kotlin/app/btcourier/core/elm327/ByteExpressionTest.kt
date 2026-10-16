package app.btcourier.core.elm327

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource

/** Grammar and semantics of wican-fw `expression_parser.c` (commit `a2cf687`). */
class ByteExpressionTest {
    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    private fun eval(
        expression: String,
        vararg values: Int,
    ) = ByteExpression.evaluate(expression, bytes(*values))

    @ParameterizedTest(name = "{0} = {1}")
    @CsvSource(
        "7+3, 10",
        "7-3, 4",
        "7*3, 21",
        "7/2, 3.5",
        "12&10, 8",
        "12|10, 14",
        "12^10, 6",
        "3<<4, 48",
        "200>>3, 25",
        "0.25*8, 2",
        ".2*10, 2",
        "' 1 +  2 ', 3",
    )
    fun `every binary operator on numbers`(
        expression: String,
        expected: Double,
    ) {
        assertEquals(expected, eval(expression), 1e-12)
    }

    @ParameterizedTest(name = "{0} = {1}")
    @CsvSource(
        // * and / bind tighter than + and -.
        "2+3*4, 14",
        "20-8/2, 16",
        // + and - bind tighter than shifts.
        "1<<2+1, 8",
        "64>>1+1, 16",
        // Shifts bind tighter than &.
        "6&1<<1, 2",
        // & binds tighter than | and ^.
        "4|6&3, 6",
        "4^6&3, 6",
        // Equal precedence is left-associative.
        "10-4-3, 3",
        "64/4/2, 8",
        "3|1^1, 2",
        "1<<4>>2, 4",
    )
    fun `precedence and associativity`(
        expression: String,
        expected: Double,
    ) {
        assertEquals(expected, eval(expression), 1e-12)
    }

    @Test
    fun `parentheses override precedence and nest`() {
        assertEquals(20.0, eval("(2+3)*4"))
        assertEquals(1.0, eval("((10-4)-(3+2))"))
        assertEquals(36.0, eval("(((1+2)*(3+1))*3)"))
    }

    @Test
    fun `bitwise operators and shifts truncate operands to int`() {
        assertEquals(2.0, eval("2.9&3.7"))
        assertEquals(8.0, eval("1.5<<3.9"))
        assertEquals(3.0, eval("7.9>>1"))
    }

    @Test
    fun `B reads an unsigned byte, S a signed one`() {
        assertEquals(255.0, eval("B1", 0x00, 0xFF))
        assertEquals(-1.0, eval("S1", 0x00, 0xFF))
        assertEquals(127.0, eval("S0", 0x7F))
        assertEquals(-128.0, eval("S0", 0x80))
    }

    @Test
    fun `B with a bit selector reads one bit`() {
        val flags = 0b1010_0101
        assertEquals(1.0, eval("B0:0", flags))
        assertEquals(0.0, eval("B0:1", flags))
        assertEquals(1.0, eval("B0:7", flags))
        assertEquals(1.0, eval("B0:5", flags))
    }

    @Test
    fun `a B range is one unsigned big-endian number`() {
        assertEquals(0x1AF8.toDouble(), eval("[B1:B2]", 0x00, 0x1A, 0xF8))
        assertEquals(0xFFFFFFFFL.toDouble(), eval("[B0:B3]", 0xFF, 0xFF, 0xFF, 0xFF))
        assertEquals(0x12.toDouble(), eval("[B0:B0]", 0x12))
        assertEquals(6904.0 * 0.25, eval("[B3:B4]*0.25", 0x04, 0x41, 0x0C, 0x1A, 0xF8))
    }

    @Test
    fun `an S range is signed in the firmware's int8, int16 or int32 accumulator`() {
        assertEquals(-2.0, eval("[S0:S1]", 0xFF, 0xFE))
        assertEquals(-1.0, eval("[S0:S0]", 0xFF))
        assertEquals(-1.0, eval("[S0:S3]", 0xFF, 0xFF, 0xFF, 0xFF))
        // Three bytes go through int32, which a 24-bit value never makes negative.
        assertEquals(0xFFFFFF.toDouble(), eval("[S0:S2]", 0xFF, 0xFF, 0xFF))
    }

    @Test
    fun `real profile expressions`() {
        // EV6 HV_AH_CHARGED: 0x0001E240 = 123456 tenths.
        val counter =
            ByteArray(44).also { b ->
                listOf(0x00, 0x01, 0xE2, 0x40).forEachIndexed { i, v ->
                    b[40 + i] =
                        v.toByte()
                }
            }
        assertEquals(12345.6, ByteExpression.evaluate("((B40<<24)+(B41<<16)+(B42<<8)+B43)/10", counter), 1e-9)
        // EV6 TMP_A: 0x5A = 90 half-degrees, offset 40.
        val temp = ByteArray(13).also { it[12] = 0x5A }
        assertEquals(5.0, ByteExpression.evaluate("(B12/2)-40", temp))
        // EV6 HV_W: signed current times voltage.
        val power = ByteArray(21)
        power[17] = 0xFF.toByte()
        power[18] = 0x9C.toByte() // -100, i.e. -10.0 A
        power[19] = 0x1B.toByte()
        power[20] = 0x3A.toByte() // 6970, i.e. 697.0 V
        assertEquals(-6970.0, ByteExpression.evaluate("([B19:B20]/10)*((S17*256)+B18)/10", power), 1e-9)
    }

    @Test
    fun `a compiled expression evaluates against many answers`() {
        val speed = ByteExpression.compile("B3")
        assertEquals(50.0, speed.evaluate(bytes(0x03, 0x41, 0x0D, 50)))
        assertEquals(51.0, speed.evaluate(bytes(0x03, 0x41, 0x0D, 51)))
    }

    @Test
    fun `a byte past the end of the answer is a MissingByteException`() {
        val e = assertThrows<MissingByteException> { eval("B4", 1, 2, 3, 4) }
        assertEquals(4, e.index)
        assertEquals(4, e.available)
        assertThrows<MissingByteException> { eval("[B2:B4]", 1, 2, 3, 4) }
        assertThrows<MissingByteException> { eval("S9", 1) }
        assertThrows<MissingByteException> { eval("B9:1", 1) }
    }

    @Test
    fun `division by zero is an ArithmeticException`() {
        assertThrows<ArithmeticException> { eval("B0/B1", 5, 0) }
    }

    @ParameterizedTest(name = "''{0}''")
    @ValueSource(
        strings = [
            "",
            "   ",
            "-5",
            "B3*-1",
            "(-B3)",
            "B3+",
            "*B3",
            "B3 B4",
            "2(3)",
            "(B3",
            "B3)",
            "()",
            "B",
            "S",
            "B3:8",
            "B3:",
            "[B3:S4]",
            "[B4:B3]",
            "[B0:B8]",
            "[B3]",
            "1.2.3",
            "abs(B3)",
            "B3%2",
            "B3<2",
            "V",
        ],
    )
    fun `malformed expressions are rejected at compile time`(expression: String) {
        assertThrows<IllegalArgumentException> { ByteExpression.compile(expression) }
    }
}
