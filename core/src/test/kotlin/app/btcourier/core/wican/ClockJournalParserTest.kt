package app.btcourier.core.wican

import app.btcourier.core.source.ClockEvent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ClockJournalParserTest {
    @Test
    fun `each journal event kind maps to its model with every field`() {
        val text = """
            {"ev":"boot","boot":7,"ts":20000,"up_ms":20000,"valid":false,"source":"none"}
            {"ev":"valid","boot":7,"ts_before":60000,"ts_after":1784000060000,"delta_ms":1783999999999,"source":"sntp","files":["dl_0000000001.jsonl","can_0000000001.mf4"]}
            {"ev":"rotate","boot":7,"ts":1784000060000,"stream":"params","from":"dl_0000000001.jsonl","to":"dl_1784000060.jsonl","why":"clock"}
            {"ev":"step","boot":7,"ts_before":1784000120000,"ts_after":1784000117500,"delta_ms":-2500,"source":"manual","files":["dl_1784000060.jsonl"]}
        """.trimIndent()

        assertEquals(
            listOf(
                ClockEvent.Boot(boot = 7, ts = 20_000, upMs = 20_000, valid = false, source = "none"),
                ClockEvent.Valid(
                    boot = 7,
                    tsBefore = 60_000,
                    tsAfter = 1_784_000_060_000,
                    // delta_ms is the device's measurement and is taken as written, not recomputed.
                    deltaMs = 1_783_999_999_999,
                    source = "sntp",
                    files = listOf("dl_0000000001.jsonl", "can_0000000001.mf4"),
                ),
                ClockEvent.Rotate(
                    boot = 7,
                    ts = 1_784_000_060_000,
                    stream = "params",
                    from = "dl_0000000001.jsonl",
                    to = "dl_1784000060.jsonl",
                    why = "clock",
                ),
                ClockEvent.Step(
                    boot = 7,
                    tsBefore = 1_784_000_120_000,
                    tsAfter = 1_784_000_117_500,
                    deltaMs = -2_500,
                    source = "manual",
                    files = listOf("dl_1784000060.jsonl"),
                ),
            ),
            ClockJournalParser.parse(text),
        )
    }

    @Test
    fun `the boot origin is the clock at the boot line minus uptime`() {
        val boot = ClockJournalParser.parse(
            """{"ev":"boot","boot":1,"ts":1784000009000,"up_ms":9000,"valid":true,"source":"rtc"}""",
        ).single() as ClockEvent.Boot

        assertEquals(1_784_000_000_000, boot.originMs)
    }

    @Test
    fun `garbage, cut-off, unknown and unplaceable lines are skipped and extra keys ignored`() {
        val text = listOf(
            "not json",
            """{"ev":"boot","boot":1,"ts":5000,"up_ms":5000,"valid":false,"source":"none","fw":"x"}""",
            """{"ev":"future_kind","boot":1}""",
            """{"ev":"step","boot":1,"ts_after":9000,"delta_ms":1000,"files":["a"]}""",
            "",
            """{"ev":"step","boot":1,"ts_before":8000,"ts_after":9000,"delta_ms":1000,"files":["a"]}""",
            """{"ev":"step","boot":1,"ts_before":9000,"ts_""",
        ).joinToString("\n")

        assertEquals(
            listOf(
                ClockEvent.Boot(1, 5_000, 5_000, false, "none"),
                ClockEvent.Step(1, 8_000, 9_000, 1_000, "", listOf("a")),
            ),
            ClockJournalParser.parse(text),
        )
    }
}
