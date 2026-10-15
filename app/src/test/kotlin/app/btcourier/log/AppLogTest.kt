package app.btcourier.log

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class AppLogTest {
    @TempDir
    lateinit var dir: File

    private fun log(
        capacity: Int = 3,
        file: File? = null,
    ) = AppLog(capacity, file, clock = { 0 })

    @Test
    fun `keeps only the newest entries, oldest first`() {
        val log = log()
        (1..5).forEach { log.info("t", "m$it") }

        assertEquals(listOf("m3", "m4", "m5"), log.lines().map { it.substringAfter("t: ") })
        assertEquals("1970-01-01T00:00:00Z I t: m3", log.lines().first())
    }

    @Test
    fun `a credentialed URL is redacted, the host and path kept`() {
        val log = log()
        log.warn("upload", "PUT https://courier:hunter22@sink.example.org/up/a.jsonl.gz failed")

        val line = log.lines().single()
        assertFalse(line.contains("hunter22"))
        assertFalse(line.contains("courier:"))
        assertTrue(line.contains("https://<redacted>@sink.example.org/up/a.jsonl.gz"))
    }

    @Test
    fun `auth header values and secret parameters are redacted, prose and addresses are not`() {
        assertEquals(
            "Authorization: Bearer <redacted>, Basic <redacted>",
            AppLog.redact("Authorization: Bearer eyJhbGciOiJIUzI1NiJ9.abc, Basic Y291cmllcjpodW50ZXIy"),
        )
        assertEquals("GET /x?token=<redacted>&page=2", AppLog.redact("GET /x?token=abc123&page=2"))
        assertEquals(
            "Basic auth failed for AA:BB:CC:DD:EE:FF",
            AppLog.redact("Basic auth failed for AA:BB:CC:DD:EE:FF"),
        )
    }

    @Test
    fun `entries are single lines of bounded length`() {
        val log = log()
        log.info("t", "first\nsecond\r\nthird")
        log.info("t", "x".repeat(10_000))

        val (multi, long) = log.lines()
        assertEquals("1970-01-01T00:00:00Z I t: first second third", multi)
        assertEquals(AppLog.MAX_MESSAGE_CHARS, long.substringAfter("t: ").length)
    }

    @Test
    fun `entries survive a new instance and the file stays bounded`() {
        val file = File(dir, "app.log")
        val first = log(file = file)
        (1..20).forEach { first.info("t", "m$it") }

        assertTrue(file.readLines().size <= 2 * 3)
        assertEquals(listOf("m18", "m19", "m20"), log(file = file).lines().map { it.substringAfter("t: ") })
    }

    @Test
    fun `the diagnostics export holds the log and the store listing`() {
        val store = File(dir, "store").apply { mkdirs() }
        File(store, "index.json").writeText("{}")
        File(store, "pages").mkdirs()
        File(store, "pages/1.json").writeText("12345")
        val log = log().apply { info("sync", "sync CAUGHT_UP") }

        val text = diagnosticsText(log, store, nowMs = 0)

        assertTrue(text.contains("I sync: sync CAUGHT_UP"))
        assertTrue(text.contains("2 index.json\n5 pages/1.json\ntotal: 2 files, 7 bytes\n"))
    }

    @Test
    fun `a long store listing is summarised`() {
        val store = File(dir, "store").apply { mkdirs() }
        (1..5).forEach { File(store, "$it.json").writeText("ab") }

        val listing = storeListing(store, maxEntries = 2)

        assertEquals("2 1.json\n2 2.json\n… 3 more files, 6 bytes\ntotal: 5 files, 10 bytes\n", listing)
    }
}
