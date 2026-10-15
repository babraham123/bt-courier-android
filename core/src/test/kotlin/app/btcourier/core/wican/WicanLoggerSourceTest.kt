package app.btcourier.core.wican

import app.btcourier.core.source.ClockEvent
import app.btcourier.core.source.DeviceStatus
import app.btcourier.core.source.SourceException
import app.btcourier.core.testing.WicanLines.boot
import app.btcourier.core.testing.WicanLines.clockSet
import app.btcourier.core.testing.WicanLines.fileMarker
import app.btcourier.core.testing.WicanLines.meta
import app.btcourier.core.testing.WicanLines.row
import app.btcourier.core.transport.Response
import app.btcourier.core.transport.StreamingResponse
import app.btcourier.core.transport.Transport
import app.btcourier.core.transport.blehttp.BleHttpException
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** Paths and statuses from wican-fw `dev-v6` HTTP_API.md §6d/§6e6 and `data_logger_http.c`. */
class WicanLoggerSourceTest {
    /** Answers every request with [answer] and records the paths asked for. */
    private class CannedTransport(
        private val answer: (String) -> Response,
    ) : Transport {
        val paths = mutableListOf<String>()

        override suspend fun request(
            method: String,
            path: String,
            body: ByteArray?,
            headers: Map<String, String>,
        ): Response {
            assertEquals("GET", method)
            paths += path
            return answer(path)
        }

        override suspend fun stream(
            method: String,
            path: String,
            body: ByteArray?,
            headers: Map<String, String>,
        ): StreamingResponse = throw UnsupportedOperationException("the source buffers its responses")
    }

    private fun ok(
        body: String,
        contentType: String = "application/x-ndjson",
    ) = Response(200, mapOf("content-type" to contentType), body.encodeToByteArray())

    private fun status(
        code: Int,
        body: String = "",
    ) = Response(code, emptyMap(), body.encodeToByteArray())

    private fun export(vararg lines: String) = ok(lines.joinToString("\n", postfix = "\n"))

    private val file = "dl_1784000060.jsonl"

    @Test
    fun `the first pull omits since, so the device starts at its oldest file`() =
        runTest {
            val transport =
                CannedTransport {
                    export(
                        fileMarker(file),
                        row(1_784_000_061_000),
                        meta("1784000060:58", more = false),
                    )
                }

            val page = WicanLoggerSource(transport, limit = 250).pull(null)

            assertEquals(listOf("/api/logger/export?format=jsonl&stream=params&limit=250"), transport.paths)
            assertEquals(listOf(fileMarker(file), row(1_784_000_061_000)), page.lines)
            assertEquals("1784000060:58", page.nextCursor)
            assertEquals(false, page.more)
        }

    @Test
    fun `later pulls send the cursor verbatim as since`() =
        runTest {
            val transport = CannedTransport { export(fileMarker(file), row(1), meta("1784000060:8192", more = true)) }

            val page = WicanLoggerSource(transport, limit = 2000).pull("1784000060:4096")

            // Not percent-encoded: the device's query parser does not decode.
            assertEquals(
                listOf("/api/logger/export?format=jsonl&stream=params&since=1784000060:4096&limit=2000"),
                transport.paths,
            )
            assertEquals("1784000060:8192", page.nextCursor)
            assertEquals(true, page.more)
        }

    @Test
    fun `limit and cursor outside the device contract are rejected before any request`() =
        runTest {
            val transport = CannedTransport { error("no request expected") }
            assertThrows<IllegalArgumentException> { WicanLoggerSource(transport, limit = 0) }
            assertThrows<IllegalArgumentException> { WicanLoggerSource(transport, limit = 2001) }
            assertThrows<IllegalArgumentException> { WicanLoggerSource(transport).pull("1784000060:4096&limit=1") }
            assertTrue(transport.paths.isEmpty())
        }

    @Test
    fun `429 is Busy, other errors keep their status, a page without its cursor line is Truncated`() =
        runTest {
            val source = { r: Response -> WicanLoggerSource(CannedTransport { r }) }

            assertThrows<SourceException.Busy> { source(status(429)).pull(null) }
            val e =
                assertThrows<SourceException.DeviceError> {
                    source(
                        status(400, "params stream is not jsonl or csv"),
                    ).pull(null)
                }
            assertEquals(400, e.status)
            assertEquals("params stream is not jsonl or csv", e.detail)
            assertThrows<SourceException.Truncated> { source(export(fileMarker(file), row(1))).pull(null) }
            assertThrows<SourceException.Truncated> { source(ok("")).pull(null) }
        }

    @Test
    fun `a ble_http body cut short is Truncated, other link failures stay IOExceptions`() =
        runTest {
            val cut =
                WicanLoggerSource(
                    CannedTransport { throw BleHttpException.Truncated(expectedBytes = 100, receivedBytes = 50) },
                )
            val pull = assertThrows<SourceException.Truncated> { cut.pull(null) }
            assertTrue(pull.cause is BleHttpException.Truncated)
            assertEquals(
                WicanLoggerSource.JOURNAL_PATH,
                assertThrows<SourceException.Truncated> { cut.clockJournal() }.path,
            )

            val dropped = WicanLoggerSource(CannedTransport { throw BleHttpException.LinkLost() })
            assertThrows<BleHttpException.LinkLost> { dropped.pull(null) }
        }

    @Test
    fun `a CSV-engine logger is reported as unsupported`() =
        runTest {
            val csv = ok("ts_ms,param,value\n1,autopid.TEST,1\n" + meta("1784000060:40", false) + "\n", "text/plain")
            assertThrows<SourceException.Unsupported> { WicanLoggerSource(CannedTransport { csv }).pull(null) }
        }

    @Test
    fun `a cursor reset to offset 0 of the file the page just finished moves on to the next file`() =
        runTest {
            // The handler's answer when the line budget runs out on the last line of a file that has
            // a newer sibling: the finished file at offset 0, more:false.
            val transport =
                CannedTransport { export(fileMarker("dl_100.jsonl"), row(1), row(2), meta("100:0", more = false)) }

            val page = WicanLoggerSource(transport, limit = 2).pull("100:0")

            assertEquals("101:0", page.nextCursor)
            assertEquals(true, page.more)
            assertEquals(listOf(fileMarker("dl_100.jsonl"), row(1), row(2)), page.lines)
        }

    @Test
    fun `offset 0 is kept when the page carries no rows of that file`() =
        runTest {
            // An empty newest file, and a cursor naming a file with no rows on the page.
            val empty = CannedTransport { export(fileMarker("dl_100.jsonl"), meta("100:0", more = false)) }
            assertEquals("100:0", WicanLoggerSource(empty).pull(null).nextCursor)
            val other =
                CannedTransport {
                    export(
                        fileMarker("dl_99.jsonl"),
                        row(1),
                        fileMarker("dl_100.jsonl"),
                        meta("100:0", false),
                    )
                }
            val page = WicanLoggerSource(other).pull("99:0")
            assertEquals("100:0", page.nextCursor)
            assertEquals(false, page.more)
        }

    @Test
    fun `the clock journal is downloaded from the SD card and parsed`() =
        runTest {
            val journal =
                listOf(
                    boot(3, ts = 20_000, upMs = 20_000, valid = false, source = "none"),
                    clockSet("valid", 3, tsBefore = 95_000, tsAfter = 1_784_000_000_000, source = "sntp", file),
                ).joinToString("\n", postfix = "\n")
            val transport = CannedTransport { ok(journal, "application/octet-stream") }

            val events = WicanLoggerSource(transport).clockJournal()

            assertEquals(listOf("/api/fs/download?path=/sd/logs/clock.jsonl"), transport.paths)
            assertEquals(2, events.size)
            val set = events[1] as ClockEvent.Valid
            assertEquals(1_784_000_000_000 - 95_000, set.deltaMs)
            assertEquals(listOf(file), set.files)
        }

    @Test
    fun `no journal on the card is an empty journal, other failures are errors`() =
        runTest {
            assertEquals(
                emptyList<ClockEvent>(),
                WicanLoggerSource(
                    CannedTransport {
                        status(404, "file not found")
                    },
                ).clockJournal(),
            )
            assertThrows<SourceException.Busy> { WicanLoggerSource(CannedTransport { status(429) }).clockJournal() }
            val e =
                assertThrows<SourceException.DeviceError> {
                    WicanLoggerSource(CannedTransport { status(409, "file in use") }).clockJournal()
                }
            assertEquals(409, e.status)
        }

    @Test
    fun `device status reads the rtc wall clock, pairing it with the round trip midpoint`() =
        runTest {
            val transport =
                CannedTransport {
                    ok(
                        """{"time":"2026-07-08T00:00:00Z","valid":true,"rtc":null}""",
                        "application/json",
                    )
                }
            val phone = ArrayDeque(listOf(5_000L, 5_300L))

            val status = WicanLoggerSource(transport, phoneClock = { phone.removeFirst() }).deviceStatus()

            assertEquals(listOf("/api/rtc"), transport.paths)
            // Whole seconds on the wire; mid-second is the unbiased estimate.
            assertEquals(
                DeviceStatus(deviceEpochMs = 1_783_468_800_500, phoneEpochMs = 5_150, clockValid = true),
                status,
            )
        }

    @Test
    fun `an unset device clock reads as 1970 plus uptime and not valid`() =
        runTest {
            val transport =
                CannedTransport { ok("""{"time":"1970-01-01T00:01:40Z","valid":false}""", "application/json") }

            val status = WicanLoggerSource(transport, phoneClock = { 0 }).deviceStatus()

            assertEquals(100_500, status.deviceEpochMs)
            assertEquals(false, status.clockValid)
            assertThrows<SourceException.Unsupported> {
                WicanLoggerSource(CannedTransport { ok("""{"valid":true}""") }).deviceStatus()
            }
        }
}
