package app.btcourier.ui

import app.btcourier.core.elm327.VehicleProfiles
import app.btcourier.ui.pairing.vehicleProfileModels
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * The pair wizard's vehicle profile list: generic first, then the car models alphabetically,
 * narrowed to names containing the search text in any case; generic is also found by the name the
 * picker shows for it.
 */
class VehicleProfilePickerTest {
    private val generic = VehicleProfiles.GENERIC_MODEL
    private val label = "Generic (standard OBD-II)"
    private val models = listOf("Kia: EV6", generic, "bmw: i3", "Hyundai: Ioniq 5", "Kia: EV6 77kWh")

    @Test
    fun `without a search every model is listed, generic first and the rest alphabetically ignoring case`() {
        assertEquals(
            listOf(generic, "bmw: i3", "Hyundai: Ioniq 5", "Kia: EV6", "Kia: EV6 77kWh"),
            vehicleProfileModels("", label, models),
        )
        assertEquals(vehicleProfileModels("", label, models), vehicleProfileModels("  ", label, models))
    }

    @Test
    fun `a search keeps the names containing it in any case`() {
        assertEquals(listOf("Kia: EV6", "Kia: EV6 77kWh"), vehicleProfileModels("ev6", label, models))
        assertEquals(listOf("bmw: i3"), vehicleProfileModels("BMW", label, models))
        assertEquals(emptyList<String>(), vehicleProfileModels("Tesla", label, models))
    }

    @Test
    fun `generic is found by its model name or by the name the picker shows`() {
        assertEquals(listOf(generic), vehicleProfileModels("generic", label, models))
        assertEquals(listOf(generic), vehicleProfileModels("standard obd", label, models))
    }

    @Test
    fun `the shipped profiles are all listed once, generic first`() {
        val listed = vehicleProfileModels("", label)

        assertEquals(generic, listed.first())
        assertEquals(VehicleProfiles.all().map { it.carModel }.sorted(), listed.sorted())
    }
}
