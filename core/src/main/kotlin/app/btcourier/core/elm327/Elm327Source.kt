package app.btcourier.core.elm327

import app.btcourier.core.source.ClockEvent
import app.btcourier.core.source.Page
import app.btcourier.core.source.Source
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Live [Source] over an ELM327 adapter polling one [VehicleProfile], as wican-fw's autopid does.
 * The adapter keeps nothing, so everything comes through [subscribe]; rows carry the phone's clock.
 *
 * Each collection of [subscribe]:
 *
 * 1. resets the adapter (`ATZ`, whose answer is the version string) and configures the answer
 *    format the parser needs: `ATE0 ATL0 ATS1 ATH1 ATST96`;
 * 2. sends the profile's `init`;
 * 3. polls in rounds, one every [pollInterval] from the start of the previous (back to back when a
 *    round takes longer): per PID its `pid_init` when it differs from the last one sent (the
 *    profile `init` counting as the first), the request, and one row per decoded parameter,
 *    `{"ts","param":"autopid.<NAME>","value"}`; then `ATRV`, `{"ts","param":"elm327.voltage","value"}`.
 *
 * Each round is one emission. A round that decoded no parameter, as with the car off when every
 * request answers a status line, emits an empty list without the voltage row, so the collector's
 * idle rule sees the car as gone. The first non-empty emission of a collection starts with
 * `{"ts","param":"elm327.adapter","value":{"version":"ELM327 v2.2"}}`.
 *
 * A PID answering a status line, or a parameter whose expression fails (a byte past the answer's
 * end, division by zero) or whose value is outside the profile's min/max, yields no row and the
 * round goes on. Transport failures ([java.io.IOException]) end the flow. One collector at a time:
 * collections share the adapter.
 *
 * Init commands that would break the answer format (`ATE1`, `ATH0`, `ATS0`) are skipped, as
 * wican-fw does.
 *
 * @param clock the phone's wall clock, for row timestamps and the round cadence.
 */
class Elm327Source(
    private val transport: Elm327Transport,
    private val profile: VehicleProfile,
    private val pollInterval: Duration = DEFAULT_POLL_INTERVAL,
    private val clock: () -> Long = System::currentTimeMillis,
) : Source {
    private val pids =
        profile.pids.map { pid ->
            CompiledPid(pid, pid.parameters.mapNotNull { p -> compileOrNull(p.expression)?.let { p to it } })
        }

    override suspend fun pull(cursor: String?): Page = Page(emptyList(), cursor ?: LIVE_CURSOR, more = false)

    override suspend fun clockJournal(): List<ClockEvent> = emptyList()

    override fun subscribe(): Flow<List<String>> =
        flow {
            val version =
                transport
                    .command("ATZ")
                    .lines()
                    .lastOrNull { it.isNotBlank() }
                    ?.trim()
                    .orEmpty()
            val versionTs = clock()
            for (command in SETUP) transport.command(command)
            send(profile.init)
            var lastInit = profile.init
            var adapterRow: String? = row(versionTs, ADAPTER_PARAM, buildJsonObject { put("version", version) })

            while (true) {
                val start = clock()
                val rows = mutableListOf<String>()
                for (compiled in pids) {
                    val init = compiled.pid.init
                    if (init.isNotEmpty() && init != lastInit) {
                        send(init)
                        lastInit = init
                    }
                    val answer = Elm327Response.parse(transport.command(compiled.pid.command))
                    val ts = clock()
                    if (answer is Elm327Response.Data) rows += decode(compiled, answer.bytes, ts)
                }
                val voltage = parseVoltage(transport.command("ATRV"))
                if (rows.isEmpty()) {
                    emit(emptyList())
                } else {
                    voltage?.let { rows += row(clock(), VOLTAGE_PARAM, jsonNumber(it)) }
                    emit(listOfNotNull(adapterRow) + rows)
                    adapterRow = null
                }
                val elapsed = (clock() - start).coerceIn(0, pollInterval.inWholeMilliseconds)
                delay(pollInterval - elapsed.milliseconds)
            }
        }

    private suspend fun send(commands: List<String>) {
        for (command in commands) {
            if (command.uppercase().replace(" ", "") !in FORMAT_BREAKERS) transport.command(command)
        }
    }

    private fun decode(
        compiled: CompiledPid,
        bytes: ByteArray,
        ts: Long,
    ): List<String> =
        compiled.parameters.mapNotNull { (parameter, expression) ->
            val value =
                try {
                    expression.evaluate(bytes)
                } catch (_: MissingByteException) {
                    return@mapNotNull null
                } catch (_: ArithmeticException) {
                    return@mapNotNull null
                }
            val inRange =
                value.isFinite() &&
                    (parameter.min == null || value >= parameter.min) &&
                    (parameter.max == null || value <= parameter.max)
            if (inRange) row(ts, AUTOPID_PREFIX + parameter.name, jsonNumber(value)) else null
        }

    private class CompiledPid(
        val pid: Pid,
        val parameters: List<Pair<Parameter, ByteExpression>>,
    )

    companion object {
        val DEFAULT_POLL_INTERVAL: Duration = 2.seconds

        /** [pull]'s cursor when none was given: there is no history to page through. */
        const val LIVE_CURSOR: String = "live"

        const val AUTOPID_PREFIX: String = "autopid."
        const val VOLTAGE_PARAM: String = "elm327.voltage"
        const val ADAPTER_PARAM: String = "elm327.adapter"

        /** Echo off, no linefeeds, spaces and headers on (the parser's format), CAN timeout 150 x 4 ms. */
        val SETUP: List<String> = listOf("ATE0", "ATL0", "ATS1", "ATH1", "ATST96")

        private val FORMAT_BREAKERS = setOf("ATE1", "ATH0", "ATS0")

        private const val MAX_DECIMALS = 6

        private val voltageShape = Regex("""(\d+(?:\.\d+)?)\s*V""", RegexOption.IGNORE_CASE)

        /** `12.6V` → 12.6; null for anything else (`?` from an adapter without a voltage pin). */
        internal fun parseVoltage(answer: String): Double? =
            voltageShape
                .find(answer)
                ?.groupValues
                ?.get(1)
                ?.toDoubleOrNull()

        /**
         * A JSON number for [value]: whole values without a fraction (`8355`, not `8355.0`), others
         * rounded to [MAX_DECIMALS] places, never in exponent notation.
         */
        internal fun jsonNumber(value: Double): JsonPrimitive {
            val decimal = BigDecimal.valueOf(value).setScale(MAX_DECIMALS, RoundingMode.HALF_UP).stripTrailingZeros()
            return if (decimal.scale() <= 0) JsonPrimitive(decimal.toBigInteger()) else JsonPrimitive(decimal)
        }

        private fun row(
            ts: Long,
            param: String,
            value: JsonElement,
        ): String =
            buildJsonObject {
                put("ts", ts)
                put("param", param)
                put("value", value)
            }.toString()

        /** Profile expressions are compiled once; one the grammar rejects never yields a row. */
        private fun compileOrNull(expression: String): ByteExpression? =
            try {
                ByteExpression.compile(expression)
            } catch (_: IllegalArgumentException) {
                null
            }
    }
}
