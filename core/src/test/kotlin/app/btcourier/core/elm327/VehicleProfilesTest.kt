package app.btcourier.core.elm327

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The vendored wican-fw `vehicle_profiles.json` (commit `a2cf687`, see its README). */
class VehicleProfilesTest {
    private val all = VehicleProfiles.all()

    private fun ev6Bms(): Pid = VehicleProfiles.byModel("Kia: EV6")!!.pids.first { it.command == "220101" }

    @Test
    fun `the file loads every profile`() {
        assertEquals(82, all.size)
        assertTrue(all.all { it.carModel.isNotBlank() && it.pids.isNotEmpty() })
        assertTrue(all.flatMap { it.pids }.all { it.command.isNotBlank() && it.parameters.isNotEmpty() })
    }

    @Test
    fun `every expression compiles`() {
        val failures =
            all.flatMap { profile ->
                profile.pids.flatMap { pid ->
                    pid.parameters.mapNotNull { p ->
                        runCatching { ByteExpression.compile(p.expression) }
                            .exceptionOrNull()
                            ?.let { "${profile.carModel} ${pid.command} ${p.name}: ${it.message}" }
                    }
                }
            }
        assertEquals(emptyList<String>(), failures)
    }

    @Test
    fun `init strings are split on semicolons into single commands`() {
        val commands = all.flatMap { profile -> profile.init + profile.pids.flatMap { it.init } }
        assertTrue(commands.isNotEmpty())
        assertTrue(commands.all { it.isNotBlank() && ';' !in it && it == it.trim() }) { commands.toString() }

        val ev6 = VehicleProfiles.byModel("Kia: EV6")!!
        assertEquals(listOf("ATSH7E4", "ATST96"), ev6.init)
        assertEquals(listOf("ATSH7B3"), ev6.pids.first { it.command == "220100" }.init)
        // A PID without pid_init has no commands of its own.
        assertEquals(
            emptyList<String>(),
            VehicleProfiles.GENERIC.pids
                .first()
                .init,
        )
    }

    @Test
    fun `optional keys default and min-max parse from strings`() {
        val bms = ev6Bms().parameters.associateBy { it.name }
        val soc = bms.getValue("SOC")
        assertEquals("B10/2", soc.expression)
        assertEquals(0.0, soc.min)
        assertEquals(100.0, soc.max)
        assertEquals(-10100.0, bms.getValue("DM_R_RPM").min)
        assertNull(bms.getValue("LV_V").min)
        assertNull(bms.getValue("LV_V").max)
        assertEquals("V", bms.getValue("LV_V").unit)
        assertEquals("battery", bms.getValue("LV_V").deviceClass)
    }

    @Test
    fun `the EV6 BMS answer is indexed with the consecutive frames' PCI bytes skipped`() {
        // In a multi-frame answer B0, B8, B16, ... are PCI bytes; no value can live there.
        val indexes =
            ev6Bms().parameters.flatMap { p ->
                Regex("""[BS](\d+)""").findAll(p.expression).map { it.groupValues[1].toInt() }.toList()
            }
        assertEquals(emptyList<Int>(), indexes.filter { it % 8 == 0 })

        val expressions = ev6Bms().parameters.associate { it.name to it.expression }
        assertEquals("((B39<<24)+(B41<<16)+(B42<<8)+B43)/10", expressions["HV_AH_CHARGED"])
        assertEquals("((B44<<24)+(B45<<16)+(B46<<8)+B47)/10", expressions["HV_AH_DISCHARGED"])
        assertEquals("((B49<<24)+(B50<<16)+(B51<<8)+B52)/10", expressions["KWH_CHARGED"])
        assertEquals("((B53<<24)+(B54<<16)+(B55<<8)+B57)/10", expressions["KWH_DISCHARGED"])
    }

    @Test
    fun `byModel finds a profile by exact name only`() {
        assertEquals("Kia: EV6", VehicleProfiles.byModel("Kia: EV6")?.carModel)
        assertNull(VehicleProfiles.byModel("kia: ev6"))
        assertNull(VehicleProfiles.byModel("Nonexistent"))
    }

    @Test
    fun `the generic profile polls standard mode 01 PIDs`() {
        val generic = VehicleProfiles.GENERIC
        assertEquals(VehicleProfiles.GENERIC_MODEL, generic.carModel)
        val byName = generic.pids.flatMap { pid -> pid.parameters.map { it.name to pid.command } }.toMap()
        // Mode 01 PIDs (SAE J1979) with the ELM327's one-answer suffix.
        assertEquals("010D1", byName["SPEED"])
        assertEquals("010C1", byName["ENGINE_RPM"])
        assertEquals("01051", byName["COOLANT_TMP"])
        assertEquals("010F1", byName["INTAKE_AIR_TMP"])
        assertEquals("01111", byName["THROTTLE"])
        assertEquals("012F1", byName["FUEL"])
        // B0 is the PCI, B1 the 41 echo, B2 the PID, so speed is B3: 7E8 03 41 0D 32 is 50 km/h.
        val speed =
            generic.pids
                .first { it.command == "010D1" }
                .parameters
                .single()
        assertEquals(50.0, ByteExpression.evaluate(speed.expression, byteArrayOf(0x03, 0x41, 0x0D, 0x32)))
        // RPM is (256 A + B) / 4: 1A F8 is 1726 rpm.
        val rpm =
            generic.pids
                .first { it.command == "010C1" }
                .parameters
                .single()
        assertEquals(
            1726.0,
            ByteExpression.evaluate(rpm.expression, byteArrayOf(0x04, 0x41, 0x0C, 0x1A, 0xF8.toByte())),
        )
    }
}
