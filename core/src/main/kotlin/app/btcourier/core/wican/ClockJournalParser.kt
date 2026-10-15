package app.btcourier.core.wican

import app.btcourier.core.source.ClockEvent
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Parses `/sd/logs/clock.jsonl` into [ClockEvent]s, keeping journal order (re-basing depends on it).
 *
 * Like wican-fw `tools/log_clock.py`, it is lenient: blank, malformed or cut-off lines are skipped
 * (the journal is appended to across power loss), and unknown keys are ignored. Unknown `ev`
 * kinds are skipped too, since nothing could act on them. A `valid`/`step` line without
 * `ts_before` is skipped because no row could be placed against it.
 */
object ClockJournalParser {
    fun parse(text: String): List<ClockEvent> =
        text.lineSequence().mapNotNull { line ->
            line.trim().takeIf { it.isNotEmpty() }?.let(::parseObjectOrNull)?.let(::eventOrNull)
        }.toList()

    private fun eventOrNull(o: JsonObject): ClockEvent? {
        val boot = o.long("boot")
        return when (o.string("ev")) {
            "boot" -> ClockEvent.Boot(
                boot = boot,
                ts = o.long("ts"),
                upMs = o.long("up_ms"),
                valid = o.boolean("valid") ?: false,
                source = o.string("source").orEmpty(),
            )
            "valid", "step" -> {
                val tsBefore = o.long("ts_before") ?: return null
                val tsAfter = o.long("ts_after")
                val deltaMs = o.long("delta_ms") ?: 0
                val source = o.string("source").orEmpty()
                val files = (o["files"] as? JsonArray).orEmpty()
                    .mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
                if (o.string("ev") == "valid") {
                    ClockEvent.Valid(boot, tsBefore, tsAfter, deltaMs, source, files)
                } else {
                    ClockEvent.Step(boot, tsBefore, tsAfter, deltaMs, source, files)
                }
            }
            "rotate" -> ClockEvent.Rotate(
                boot = boot,
                ts = o.long("ts"),
                stream = o.string("stream").orEmpty(),
                from = o.string("from").orEmpty(),
                to = o.string("to").orEmpty(),
                why = o.string("why").orEmpty(),
            )
            else -> null
        }
    }
}
