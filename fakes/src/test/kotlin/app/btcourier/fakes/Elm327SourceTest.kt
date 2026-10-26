package app.btcourier.fakes

import app.btcourier.core.elm327.Elm327Exception
import app.btcourier.core.elm327.Elm327Source
import app.btcourier.core.elm327.Elm327Transport
import app.btcourier.core.elm327.Parameter
import app.btcourier.core.elm327.Pid
import app.btcourier.core.elm327.VehicleProfile
import app.btcourier.core.elm327.VehicleProfiles
import app.btcourier.core.source.Page
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** [Elm327Source] against [FakeElm327Adapter]: ticket 0002 and wican-fw autopid behaviour. */
class Elm327SourceTest {
    private val ev6 = VehicleProfiles.byModel("Kia: EV6")!!

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun TestScope.source(
        adapter: FakeElm327Adapter,
        profile: VehicleProfile,
        pollInterval: Duration = Elm327Source.DEFAULT_POLL_INTERVAL,
    ): Elm327Source {
        val transport = Elm327Transport(adapter.link, backgroundScope)
        return Elm327Source(transport, profile, pollInterval, clock = { PHONE_EPOCH + testScheduler.currentTime })
    }

    private fun List<String>.params(): List<String> = map { paramShape.find(it)!!.groupValues[1] }

    /** The `value` of the row named [param], as its JSON text. */
    private fun List<String>.value(param: String): String =
        single { paramShape.find(it)!!.groupValues[1] == param }.let { valueShape.find(it)!!.groupValues[1] }

    private suspend fun <T> Flow<T>.failure(): Throwable? =
        try {
            collect {}
            null
        } catch (e: IOException) {
            e
        }

    private fun profile(
        init: List<String>,
        vararg pids: Pid,
    ) = VehicleProfile("Test", init, pids.toList())

    private fun pid(
        command: String,
        init: List<String> = emptyList(),
        vararg parameters: Parameter,
    ) = Pid(command, init, parameters.toList().ifEmpty { listOf(Parameter(command, "B3")) })

    @Test
    fun `setup, profile init and a round reach the link in order, pid_init only when it changes`() =
        runTest {
            val adapter = FakeElm327Adapter()
            source(adapter, ev6).subscribe().take(1).toList()
            // Kia: EV6 in vehicle_profiles.json: init ATSH7E4;ATST96; then per PID its pid_init.
            val expected =
                listOf("ATZ", "ATE0", "ATL0", "ATS1", "ATH1", "ATST96") +
                    listOf("ATSH7E4", "ATST96") +
                    listOf("ATSH7B3", "220100", "ATSH7E4", "220105", "220101") +
                    listOf("ATSH7E5", "22E011", "ATSH7A0", "22C00B", "ATSH7C6", "22B002", "ATSH730", "22F010") +
                    listOf("ATRV")
            assertEquals(expected, adapter.commands)
            assertEquals(
                expected.joinToString("") { "$it\r" },
                adapter.link.writes.joinToString("") { it.decodeToString() },
            )
        }

    @Test
    fun `pid_init is compared with the last one sent, the profile init counting first, across rounds`() =
        runTest {
            val adapter = FakeElm327Adapter()
            val x = listOf("ATSH7E0")
            val y = listOf("ATSH7E1")
            val profile = profile(x, pid("01", x), pid("02", x), pid("03"), pid("04", x), pid("05", y))
            source(adapter, profile).subscribe().take(2).toList()
            val rounds = adapter.commands.drop(6)
            assertEquals(
                listOf("ATSH7E0") + listOf("01", "02", "03", "04", "ATSH7E1", "05", "ATRV") +
                    listOf("ATSH7E0", "01", "02", "03", "04", "ATSH7E1", "05", "ATRV"),
                rounds,
            )
        }

    @Test
    fun `init commands that break the answer format are skipped`() =
        runTest {
            val adapter = FakeElm327Adapter()
            val profile = profile(listOf("ATH0", "ATS0", "AT E1", "ATSP6"), pid("010D1", listOf("ATS0", "ATSH7DF")))
            source(adapter, profile).subscribe().take(1).toList()
            assertEquals(
                listOf("ATZ", "ATE0", "ATL0", "ATS1", "ATH1", "ATST96", "ATSP6", "ATSH7DF", "010D1", "ATRV"),
                adapter.commands,
            )
        }

    @Test
    fun `the EV6 BMS answer decodes SOC and the cumulative counters`() =
        runTest {
            val adapter = FakeElm327Adapter(mapOf(Ev6Fixture.COMMAND to Ev6Fixture.RESPONSE))
            val rows =
                source(adapter, ev6)
                    .subscribe()
                    .take(1)
                    .toList()
                    .single()

            assertEquals(Ev6Fixture.SOC, rows.value("autopid.SOC").toDouble())
            assertEquals(Ev6Fixture.LV_V, rows.value("autopid.LV_V").toDouble())
            assertEquals(Ev6Fixture.HV_AH_CHARGED, rows.value("autopid.HV_AH_CHARGED").toDouble())
            assertEquals(Ev6Fixture.HV_AH_DISCHARGED, rows.value("autopid.HV_AH_DISCHARGED").toDouble())
            assertEquals(Ev6Fixture.KWH_CHARGED, rows.value("autopid.KWH_CHARGED").toDouble())
            // A whole value is written without a fraction.
            assertEquals("8355", rows.value("autopid.KWH_DISCHARGED"))
            // HV_V is 0 in the fixture, below the profile's min of 200, so it is dropped.
            assertTrue("autopid.HV_V" !in rows.params())
        }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `rows are phone-stamped, adapter first and voltage last`() =
        runTest {
            val adapter = FakeElm327Adapter(mapOf("010D1" to "7E8 03 41 0D 32"))
            val rows =
                source(adapter, profile(emptyList(), pid("010D1")))
                    .subscribe()
                    .take(1)
                    .toList()
                    .single()
            val ts = PHONE_EPOCH + testScheduler.currentTime
            assertEquals(
                listOf(
                    """{"ts":$ts,"param":"elm327.adapter","value":{"version":"ELM327 v2.2"}}""",
                    """{"ts":$ts,"param":"autopid.010D1","value":50}""",
                    """{"ts":$ts,"param":"elm327.voltage","value":12.6}""",
                ),
                rows,
            )
        }

    @Test
    fun `elm327 adapter comes once per collection`() =
        runTest {
            val adapter = FakeElm327Adapter(mapOf(Ev6Fixture.COMMAND to Ev6Fixture.RESPONSE))
            val source = source(adapter, ev6)
            val rounds = source.subscribe().take(3).toList()
            assertEquals(listOf(1, 0, 0), rounds.map { r -> r.params().count { it == "elm327.adapter" } })
            assertEquals(listOf(1, 1, 1), rounds.map { r -> r.params().count { it == "elm327.voltage" } })
            assertEquals(
                "elm327.adapter",
                source
                    .subscribe()
                    .take(1)
                    .toList()
                    .single()
                    .params()
                    .first(),
            )
        }

    @Test
    fun `a status answer or a failing expression yields no row and the round goes on`() =
        runTest {
            val adapter =
                FakeElm327Adapter(
                    mapOf(
                        // Too short for B3.
                        "010D1" to "7E8 02 41 0D",
                        "010C1" to "7E8 04 41 0C 1A F8",
                        "01051" to "7E8 03 41 05 7B",
                    ),
                )
            val rows =
                source(adapter, VehicleProfiles.GENERIC)
                    .subscribe()
                    .take(1)
                    .toList()
                    .single()
            assertEquals(
                listOf("elm327.adapter", "autopid.ENGINE_RPM", "autopid.COOLANT_TMP", "elm327.voltage"),
                rows.params(),
            )
            assertEquals("1726", rows.value("autopid.ENGINE_RPM"))
            assertEquals("83", rows.value("autopid.COOLANT_TMP"))
        }

    @Test
    fun `values outside the parameter's min and max are dropped`() =
        runTest {
            val adapter = FakeElm327Adapter(mapOf("01" to "7E8 03 41 01 64"))
            val bounded =
                pid(
                    "01",
                    emptyList(),
                    Parameter("IN", "B3", min = 100.0, max = 100.0),
                    Parameter("LOW", "B3", min = 101.0),
                    Parameter("HIGH", "B3", max = 99.0),
                )
            val rows =
                source(adapter, profile(emptyList(), bounded))
                    .subscribe()
                    .take(1)
                    .toList()
                    .single()
            assertEquals(listOf("elm327.adapter", "autopid.IN", "elm327.voltage"), rows.params())
        }

    @Test
    fun `with the car off every round emits an empty list, and the adapter row waits for data`() =
        runTest {
            val adapter = FakeElm327Adapter(mapOf(Ev6Fixture.COMMAND to Ev6Fixture.RESPONSE))
            adapter.carOff = true
            val rounds = mutableListOf<List<String>>()
            source(adapter, ev6).subscribe().take(4).collect {
                rounds += it
                if (rounds.size == 2) adapter.carOff = false
            }
            assertEquals(emptyList<String>(), rounds[0])
            assertEquals(emptyList<String>(), rounds[1])
            assertEquals("elm327.adapter", rounds[2].params().first())
            assertTrue("elm327.adapter" !in rounds[3].params())
        }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `rounds start every poll interval`() =
        runTest {
            val adapter = FakeElm327Adapter(mapOf("010D1" to "7E8 03 41 0D 32"), scope = backgroundScope)
            adapter.delays["010D1"] = 500.milliseconds
            val ends = mutableListOf<Long>()
            source(adapter, profile(emptyList(), pid("010D1")), 2.seconds).subscribe().take(3).collect {
                ends += testScheduler.currentTime
            }
            assertEquals(listOf(500L, 2500L, 4500L), ends)
        }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `a round longer than the poll interval is followed at once`() =
        runTest {
            val adapter = FakeElm327Adapter(mapOf("010D1" to "7E8 03 41 0D 32"), scope = backgroundScope)
            adapter.delays["010D1"] = 3.seconds
            val ends = mutableListOf<Long>()
            source(adapter, profile(emptyList(), pid("010D1")), 2.seconds).subscribe().take(3).collect {
                ends += testScheduler.currentTime
            }
            assertEquals(listOf(3000L, 6000L, 9000L), ends)
        }

    @Test
    fun `a dropped link ends the flow with an IOException`() =
        runTest {
            val adapter = FakeElm327Adapter()
            var emissions = 0
            launch {
                delay(1.seconds)
                adapter.drop()
            }
            val failure = source(adapter, ev6).subscribe().onEach { emissions++ }.failure()
            assertInstanceOf(Elm327Exception.LinkLost::class.java, failure)
            assertEquals(1, emissions)
        }

    @Test
    fun `an adapter that stops answering ends the flow with a PromptTimeout`() =
        runTest {
            val adapter = FakeElm327Adapter()
            adapter.silent += "220105"
            assertInstanceOf(Elm327Exception.PromptTimeout::class.java, source(adapter, ev6).subscribe().failure())
        }

    @Test
    fun `cancelling mid-round stops cleanly and a new collection starts over`() =
        runTest {
            val adapter = FakeElm327Adapter(mapOf("010D1" to "7E8 03 41 0D 32"), scope = backgroundScope)
            adapter.delays["010D1"] = 1.seconds
            val source = source(adapter, profile(emptyList(), pid("010D1")))
            val job = launch { source.subscribe().collect {} }
            delay(500.milliseconds)
            job.cancel()
            job.join()
            assertEquals("010D1", adapter.commands.last())

            adapter.delays.clear()
            val rows =
                source
                    .subscribe()
                    .take(1)
                    .toList()
                    .single()
            assertEquals("50", rows.value("autopid.010D1"))
        }

    @Test
    fun `nothing to pull, no journal, no device clock`() =
        runTest {
            val source = source(FakeElm327Adapter(), ev6)
            assertEquals(Page(emptyList(), "live", more = false), source.pull(null))
            assertEquals(Page(emptyList(), "session:1:2", more = false), source.pull("session:1:2"))
            assertEquals(emptyList<Any>(), source.clockJournal())
            assertNull(source.deviceStatus())
        }

    private companion object {
        /** An arbitrary phone clock at virtual time 0. */
        const val PHONE_EPOCH = 1_784_000_000_000L

        val paramShape = Regex(""""param":"([^"]+)"""")
        val valueShape = Regex(""""value":(.*)}$""")
    }
}
