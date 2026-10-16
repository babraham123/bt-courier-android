package app.btcourier.core.elm327

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

/** Row value formatting of [Elm327Source]: whole numbers bare, at most 6 decimals, never an exponent. */
class Elm327RowsTest {
    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource(
        "8355.0, 8355",
        "-40.0, -40",
        "0.0, 0",
        "-0.0, 0",
        "12.6, 12.6",
        "12345.6, 12345.6",
        "0.333333333, 0.333333",
        "0.6666666666, 0.666667",
        "0.0000001, 0",
        "0.000001, 0.000001",
        "1.0E20, 100000000000000000000",
        "1.5E-3, 0.0015",
    )
    fun `JSON numbers`(
        value: Double,
        expected: String,
    ) {
        assertEquals(expected, Elm327Source.jsonNumber(value).toString())
    }

    @Test
    fun `ATRV answers volts`() {
        assertEquals(12.6, Elm327Source.parseVoltage("12.6V"))
        assertEquals(12.0, Elm327Source.parseVoltage("12V"))
        assertEquals(14.1, Elm327Source.parseVoltage("14.1 V"))
        assertNull(Elm327Source.parseVoltage("?"))
        assertNull(Elm327Source.parseVoltage(""))
    }
}
