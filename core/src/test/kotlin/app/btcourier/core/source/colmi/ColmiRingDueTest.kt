package app.btcourier.core.source.colmi

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.ZoneId
import kotlin.time.Duration.Companion.minutes

/**
 * [ColmiRingSource.nextDueAtMs]: an automatic sync is due the intraday interval (1 hour by default)
 * after the cursor's cut, in the phone's zone. A `YYYY-MM-DD@HH:MM` cursor's cut is that wall time;
 * a whole day `YYYY-MM-DD`'s is the start of the next day. Expected instants are written out as
 * offsets, not derived from the function's own steps.
 */
class ColmiRingDueTest {
    private fun ms(iso: String) = Instant.parse(iso).toEpochMilli()

    private val la = ZoneId.of("America/Los_Angeles")

    @Test
    fun `a cut cursor is due an hour after its cut`() {
        assertEquals(ms("2026-10-15T17:30:00Z"), ColmiRingSource.nextDueAtMs("2026-10-15@09:30", la), "10:30 PDT")
        assertEquals(ms("2026-10-15T10:30:00Z"), ColmiRingSource.nextDueAtMs("2026-10-15@09:30", ZoneId.of("UTC")))
        assertEquals(ms("2026-10-16T06:55:00Z"), ColmiRingSource.nextDueAtMs("2026-10-15@22:55", la), "past midnight")
    }

    @Test
    fun `a whole-day cursor is due an hour after the midnight that ends it`() {
        assertEquals(ms("2026-10-14T08:00:00Z"), ColmiRingSource.nextDueAtMs("2026-10-13", la), "2026-10-14 01:00 PDT")
        assertEquals(ms("2026-10-14T01:00:00Z"), ColmiRingSource.nextDueAtMs("2026-10-13", ZoneId.of("UTC")))
    }

    @Test
    fun `the interval is the caller's`() {
        assertEquals(
            ms("2026-10-15T17:00:00Z"),
            ColmiRingSource.nextDueAtMs("2026-10-15@09:30", la, intradayInterval = 30.minutes),
        )
        assertEquals(
            ms("2026-10-14T07:15:00Z"),
            ColmiRingSource.nextDueAtMs("2026-10-13", la, intradayInterval = 15.minutes),
        )
    }

    @Test
    fun `the day boundary is the phone zone's, so one cursor is due at different instants per zone`() {
        assertEquals(
            ms("2026-10-13T11:00:00Z"),
            ColmiRingSource.nextDueAtMs("2026-10-13", ZoneId.of("Pacific/Kiritimati")),
            "2026-10-14 01:00 at +14",
        )
        assertEquals(
            ms("2026-10-14T12:00:00Z"),
            ColmiRingSource.nextDueAtMs("2026-10-13", ZoneId.of("Pacific/Pago_Pago")),
            "2026-10-14 01:00 at -11",
        )
    }

    @Test
    fun `across a DST change the midnight carries the offset of its own day and the hour is elapsed time`() {
        // US clocks fall back on 2026-11-01 at 02:00 PDT: that day lasts 25 hours.
        assertEquals(ms("2026-11-01T08:00:00Z"), ColmiRingSource.nextDueAtMs("2026-10-31", la), "PDT midnight + 1 h")
        assertEquals(ms("2026-11-02T09:00:00Z"), ColmiRingSource.nextDueAtMs("2026-11-01", la), "PST midnight + 1 h")
        // A cut in the repeated hour is its first (PDT) occurrence.
        assertEquals(ms("2026-11-01T09:30:00Z"), ColmiRingSource.nextDueAtMs("2026-11-01@01:30", la))
        // And spring forward on 2026-03-08 at 02:00 PST, a 23-hour day.
        assertEquals(ms("2026-03-08T09:00:00Z"), ColmiRingSource.nextDueAtMs("2026-03-07", la), "PST midnight + 1 h")
        assertEquals(ms("2026-03-09T08:00:00Z"), ColmiRingSource.nextDueAtMs("2026-03-08", la), "PDT midnight + 1 h")
        // A cut in the skipped hour counts as an hour later, 03:30 PDT.
        assertEquals(ms("2026-03-08T11:30:00Z"), ColmiRingSource.nextDueAtMs("2026-03-08@02:30", la))
    }

    @Test
    fun `a day whose midnight a DST change skips counts from its first instant`() {
        // Brazil sprang forward at midnight on 2018-11-04: 00:00 became 01:00 (-02:00).
        assertEquals(
            ms("2018-11-04T04:00:00Z"),
            ColmiRingSource.nextDueAtMs("2018-11-03", ZoneId.of("America/Sao_Paulo")),
        )
    }

    @Test
    fun `no cursor, or one the source would reject, is always due`() {
        val utc = ZoneId.of("UTC")
        assertNull(ColmiRingSource.nextDueAtMs(null, utc))
        for (cursor in listOf(
            "1790000000:42",
            "2026-10-15@9:30",
            "2026-10-15@",
            "2026-10-15@24:00",
            "2026-10-15T09:30",
        )) {
            assertNull(ColmiRingSource.nextDueAtMs(cursor, utc), cursor)
        }
    }
}
