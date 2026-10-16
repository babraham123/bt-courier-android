package app.btcourier.device

import app.btcourier.core.elm327.Elm327Source
import app.btcourier.core.elm327.VehicleProfiles
import app.btcourier.core.source.Page
import app.btcourier.data.DeviceKind
import app.btcourier.fakes.Ev6Fixture
import app.btcourier.fakes.FakeElm327Adapter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.IOException
import java.time.ZoneOffset

/**
 * The ELM327 kind against the scripted adapter (ticket 0002): the probe answers the adapter's
 * `ATZ` version and `ATRV` voltage and fails on anything else; `withSource` polls the device's
 * vehicle profile through the real [Elm327Source], the generic one when none or an unknown one is
 * stored; a sync's `pull` stores nothing.
 */
class Elm327SupportTest {
    private val elm327 = DeviceKind.ELM327.support
    private val clockMs = 1_790_000_000_000L

    private fun options(vehicleProfile: String?) =
        SourceOptions(clock = { clockMs }, zone = ZoneOffset.UTC, pageLimit = 7, vehicleProfile = vehicleProfile)

    @Test
    fun `the probe resets the adapter for its version and reads the battery voltage`() =
        runTest {
            val adapter = FakeElm327Adapter()
            adapter.version = "ELM327 v2.3"
            adapter.voltage = "12.4V"

            assertEquals(ProbeResult.Elm327("ELM327 v2.3", 12.4), elm327.probe(adapter.link, PairingChoices()))
            assertEquals(listOf("ATZ", "ATRV"), adapter.commands)
            assertFalse(adapter.link.closed)
        }

    @Test
    fun `a status line instead of a version or a voltage fails the probe`() =
        runTest {
            val noVersion = FakeElm327Adapter().apply { version = "?" }
            assertThrows<IOException> { elm327.probe(noVersion.link, PairingChoices()) }

            val noVoltage = FakeElm327Adapter().apply { voltage = "?" }
            assertThrows<IOException> { elm327.probe(noVoltage.link, PairingChoices()) }
        }

    @Test
    fun `an adapter that never prompts fails the probe`() =
        runTest {
            val adapter = FakeElm327Adapter().apply { silent += "ATZ" }

            assertThrows<IOException> { elm327.probe(adapter.link, PairingChoices()) }
        }

    @Test
    fun `the source polls the stored vehicle profile and decodes the EV6 battery answer`() =
        runTest {
            val adapter = FakeElm327Adapter(mapOf(Ev6Fixture.COMMAND to Ev6Fixture.RESPONSE))

            val rows =
                elm327.withSource(adapter.link, options("Kia: EV6")) { source ->
                    assertInstanceOf(Elm327Source::class.java, source)
                    source.subscribe().first { it.isNotEmpty() }
                }

            val values =
                rows
                    .map { Json.parseToJsonElement(it).jsonObject }
                    .filter {
                        it
                            .getValue("param")
                            .jsonPrimitive.content
                            .startsWith("autopid.")
                    }.associate {
                        it.getValue("param").jsonPrimitive.content to
                            it
                                .getValue("value")
                                .jsonPrimitive.content
                                .toDouble()
                    }
            assertEquals(Ev6Fixture.SOC, values["autopid.SOC"])
            assertEquals(Ev6Fixture.HV_AH_CHARGED, values["autopid.HV_AH_CHARGED"])
            assertEquals(Ev6Fixture.HV_AH_DISCHARGED, values["autopid.HV_AH_DISCHARGED"])
            assertEquals(Ev6Fixture.KWH_CHARGED, values["autopid.KWH_CHARGED"])
            assertEquals(Ev6Fixture.KWH_DISCHARGED, values["autopid.KWH_DISCHARGED"])
            assertEquals(
                setOf(clockMs),
                rows
                    .map {
                        Json
                            .parseToJsonElement(it)
                            .jsonObject
                            .getValue("ts")
                            .jsonPrimitive.long
                    }.toSet(),
            )
            assertFalse(adapter.link.closed, "the caller owns the link")
        }

    @Test
    fun `no stored profile, or one the shipped profiles lack, polls the generic profile`() =
        runTest {
            val genericFirstPid =
                VehicleProfiles.GENERIC.pids
                    .first()
                    .command
            for (stored in listOf(null, "Nonexistent: Car")) {
                val adapter = FakeElm327Adapter()
                adapter.carOff = true

                elm327.withSource(adapter.link, options(stored)) { it.subscribe().first() }

                assertEquals(genericFirstPid, adapter.commands.first { !it.startsWith("AT") }, "stored $stored")
            }
            assertEquals(VehicleProfiles.GENERIC, Elm327Support.vehicleProfile(null))
            assertEquals("Kia: EV6", Elm327Support.vehicleProfile("Kia: EV6").carModel)
        }

    @Test
    fun `a sync's pull sends nothing and stores nothing`() =
        runTest {
            val adapter = FakeElm327Adapter()

            val page = elm327.withSource(adapter.link, options(null)) { it.pull("live") }

            assertEquals(Page(emptyList(), "live", more = false), page)
            assertEquals(emptyList<String>(), adapter.commands)
        }
}
