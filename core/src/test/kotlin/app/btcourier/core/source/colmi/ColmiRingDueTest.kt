package app.btcourier.core.source.colmi

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.ZoneId

/**
 * [ColmiRingSource.nextDueAtMs]: the cursor is the last whole day pulled, and the day after it is
 * pulled once it is whole, i.e. from the start of the second day after the cursor in the phone's
 * zone. Expected instants are written out as offsets, not derived from the function's own steps.
 */
class ColmiRingDueTest {
    private fun ms(iso: String) = Instant.parse(iso).toEpochMilli()

    @Test
    fun `due at local midnight two days after the cursor day`() {
        assertEquals(
            ms("2026-10-15T07:00:00Z"),
            ColmiRingSource.nextDueAtMs("2026-10-13", ZoneId.of("America/Los_Angeles")),
            "2026-10-15 00:00 PDT",
        )
        assertEquals(ms("2026-10-15T00:00:00Z"), ColmiRingSource.nextDueAtMs("2026-10-13", ZoneId.of("UTC")))
    }

    @Test
    fun `the day boundary is the phone zone's, so one cursor is due at different instants per zone`() {
        assertEquals(
            ms("2026-10-14T10:00:00Z"),
            ColmiRingSource.nextDueAtMs("2026-10-13", ZoneId.of("Pacific/Kiritimati")),
            "2026-10-15 00:00 at +14",
        )
        assertEquals(
            ms("2026-10-15T11:00:00Z"),
            ColmiRingSource.nextDueAtMs("2026-10-13", ZoneId.of("Pacific/Pago_Pago")),
            "2026-10-15 00:00 at -11",
        )
    }

    @Test
    fun `across a DST change the due midnight carries the offset of its own day`() {
        val la = ZoneId.of("America/Los_Angeles")
        // US clocks fall back on 2026-11-01: that day lasts 25 hours.
        assertEquals(ms("2026-11-01T07:00:00Z"), ColmiRingSource.nextDueAtMs("2026-10-30", la), "PDT midnight")
        assertEquals(ms("2026-11-02T08:00:00Z"), ColmiRingSource.nextDueAtMs("2026-10-31", la), "PST midnight")
        // And spring forward on 2026-03-08, a 23-hour day.
        assertEquals(ms("2026-03-08T08:00:00Z"), ColmiRingSource.nextDueAtMs("2026-03-06", la), "PST midnight")
        assertEquals(ms("2026-03-09T07:00:00Z"), ColmiRingSource.nextDueAtMs("2026-03-07", la), "PDT midnight")
    }

    @Test
    fun `a day whose midnight a DST change skips is due from its first instant`() {
        // Brazil sprang forward at midnight on 2018-11-04: 00:00 became 01:00 (-02:00).
        assertEquals(
            ms("2018-11-04T03:00:00Z"),
            ColmiRingSource.nextDueAtMs("2018-11-02", ZoneId.of("America/Sao_Paulo")),
        )
    }

    @Test
    fun `no cursor, or one the source would reject, is always due`() {
        assertNull(ColmiRingSource.nextDueAtMs(null, ZoneId.of("UTC")))
        assertNull(ColmiRingSource.nextDueAtMs("1790000000:42", ZoneId.of("UTC")))
    }
}
