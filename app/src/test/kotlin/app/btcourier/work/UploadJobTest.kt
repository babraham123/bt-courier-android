package app.btcourier.work

import app.btcourier.core.sink.CompositeSink
import app.btcourier.core.sink.Jsonl
import app.btcourier.core.sink.LocalDirectoryUploadTransport
import app.btcourier.core.sink.Sink
import app.btcourier.core.sink.UploadResult
import app.btcourier.core.store.DefaultStore
import app.btcourier.data.AppSettings
import app.btcourier.data.HTTP_SINK_ID
import app.btcourier.data.InMemorySyncStatus
import app.btcourier.data.SecretStore
import app.btcourier.data.SinkAuth
import app.btcourier.data.SinkUpload
import app.btcourier.data.UploadResultKind
import app.btcourier.data.buildSinks
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.Base64
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import kotlin.io.path.listDirectoryEntries

/**
 * [UploadJob] over the app's store with real sinks (local directory, HTTP PUT against a local
 * server) and scripted ones for failures. Expected values follow the job's contract: every
 * pending batch reaches every sink, sinks fail independently, transient failures retry up to
 * [UploadJob.MAX_ATTEMPTS] rounds, permanent ones never, and nothing is lost either way.
 */
class UploadJobTest {
    @TempDir
    lateinit var dir: Path

    private val status = InMemorySyncStatus()
    private lateinit var server: MockWebServer
    private val client = OkHttpClient.Builder().callTimeout(10, TimeUnit.SECONDS).build()

    @BeforeEach
    fun startServer() {
        server = MockWebServer().apply { start() }
    }

    @AfterEach
    fun stopServer() = server.close()

    /** A store holding two sealed batches (one per device file), 3 rows each. */
    private suspend fun storeWithTwoBatches(sinks: Set<String>): DefaultStore = openStore(dir.resolve("store"), sinks).apply {
        appendPage("wican", "1:100", listOf(marker("dl_1.jsonl")) + (0 until 3).map { row(1_000L + it, it) })
        appendPage("wican", "2:100", listOf(marker("dl_2.jsonl")) + (3 until 6).map { row(2_000L + it, it) })
        seal(sealEverything, NOW)
    }

    private fun localSink(id: String) = CompositeSink(id, LocalDirectoryUploadTransport(dir.resolve(id)), Jsonl)

    private fun scriptedSink(id: String, vararg results: UploadResult) = CompositeSink(id, ScriptedUpload(*results), Jsonl)

    private fun job(store: DefaultStore, vararg sinks: Sink, threshold: Long = UploadJob.FOREGROUND_THRESHOLD_BYTES) =
        UploadJob(store, { sinks.toList() }, status, clock = { NOW }, foregroundThresholdBytes = threshold)

    @Test
    fun `every pending batch reaches every sink, then the store drops them`() = runTest {
        val store = storeWithTwoBatches(setOf("a", "b"))

        val outcome = job(store, localSink("a"), localSink("b")).run(attempt = 0)

        assertEquals(UploadOutcome.Delivered, outcome)
        assertEquals(2, dir.resolve("a").listDirectoryEntries().size)
        assertEquals(2, dir.resolve("b").listDirectoryEntries().size)
        assertEquals(0, store.counters().pendingBatches)
        val s = status.status.value
        assertEquals(UploadResultKind.DELIVERED, s.lastUpload)
        assertEquals(NOW, s.lastUploadAtMs)
        assertEquals(0, s.pendingBatches)
        assertNull(s.lastError)
    }

    @Test
    fun `a transient failure retries until the attempt cap, then gives up and keeps the batches`() = runTest {
        val store = storeWithTwoBatches(setOf("down"))
        val sink = scriptedSink("down", UploadResult.Retry("HTTP 503"))

        assertEquals(UploadOutcome.Retry, job(store, sink).run(attempt = 0))
        assertEquals(UploadResultKind.RETRYING, status.status.value.lastUpload)

        assertEquals(UploadOutcome.GaveUp, job(store, sink).run(attempt = UploadJob.MAX_ATTEMPTS - 1))

        val s = status.status.value
        assertEquals(UploadResultKind.GAVE_UP, s.lastUpload)
        assertEquals("gave up after ${UploadJob.MAX_ATTEMPTS} attempts: down: HTTP 503", s.lastError)
        assertEquals(2, store.pending("down").size)
        assertEquals(2, s.pendingBatches)
    }

    @Test
    fun `a permanent failure is not retried and does not hold back the other sinks`() = runTest {
        val store = storeWithTwoBatches(setOf("refused", "ok"))

        val outcome = job(store, scriptedSink("refused", UploadResult.Failed("HTTP 403")), localSink("ok")).run(attempt = 0)

        assertEquals(UploadOutcome.Failed, outcome)
        assertEquals(2, dir.resolve("ok").listDirectoryEntries().size, "the healthy sink got everything")
        assertEquals(2, store.pending("refused").size, "the refused batches stay for a config fix")
        assertEquals(UploadResultKind.FAILED, status.status.value.lastUpload)
        assertEquals("refused: HTTP 403", status.status.value.lastError)
        assertEquals(
            mapOf("refused" to SinkUpload(NOW, UploadResultKind.FAILED), "ok" to SinkUpload(NOW, UploadResultKind.DELIVERED)),
            status.status.value.lastUploadBySink,
        )
    }

    @Test
    fun `with no sink configured nothing is uploaded or dropped`() = runTest {
        val store = storeWithTwoBatches(emptySet())

        assertEquals(UploadOutcome.NoSink, job(store).run(attempt = 0))

        assertEquals(UploadResultKind.NO_SINK, status.status.value.lastUpload)
        assertEquals(2, store.counters().pendingBatches)
    }

    @Test
    fun `only a backlog at the threshold promotes the upload to the foreground`() = runTest {
        val store = storeWithTwoBatches(setOf("a"))
        val bytes = store.counters().bytesStored
        var promotions = 0

        job(store, scriptedSink("a", UploadResult.Retry("x")), threshold = bytes + 1).run(0) { promotions++ }
        assertEquals(0, promotions)

        job(store, localSink("a"), threshold = bytes).run(0) { promotions++ }
        assertEquals(1, promotions)
    }

    @Test
    fun `configured HTTP PUT sink uploads with Basic auth from the secret store`() = runTest {
        val store = storeWithTwoBatches(setOf(HTTP_SINK_ID))
        repeat(2) { server.enqueue(MockResponse.Builder().code(201).build()) }
        val settings = AppSettings(sinkUrl = server.url("/ingest/").toString(), sinkAuth = SinkAuth.BASIC, sinkUser = "courier")
        val secrets = MapSecretStore(SecretStore.UPLOAD_SECRET to "s3cret")

        val outcome = UploadJob(store, { buildSinks(settings, secrets, client) }, status, clock = { NOW }).run(0)

        assertEquals(UploadOutcome.Delivered, outcome)
        val requests = List(2) { checkNotNull(server.takeRequest(5, TimeUnit.SECONDS)) }
        val basic = "Basic " + Base64.getEncoder().encodeToString("courier:s3cret".toByteArray())
        assertTrue(requests.all { it.method == "PUT" && it.headers["Authorization"] == basic })
        assertTrue(requests.all { it.target.startsWith("/ingest/wican-") && it.target.endsWith(".jsonl.gz") })
        val firstObject = GZIPInputStream(requests[0].body!!.toByteArray().inputStream()).readBytes().decodeToString()
        assertEquals(listOf(marker("dl_1.jsonl")) + (0 until 3).map { row(1_000L + it, it) }, firstObject.lines().filter(String::isNotEmpty))
    }

    @Test
    fun `an unparseable sink URL fails the upload instead of crashing the worker`() = runTest {
        val store = storeWithTwoBatches(setOf(HTTP_SINK_ID))
        val settings = AppSettings(sinkUrl = "not a url")

        val outcome = UploadJob(store, { buildSinks(settings, MapSecretStore(), client) }, status, clock = { NOW }).run(0)

        assertEquals(UploadOutcome.Failed, outcome)
        assertTrue(status.status.value.lastError!!.startsWith("invalid sink configuration"))
        assertEquals(2, store.counters().pendingBatches)
    }

    @Test
    fun `a blank sink URL means no sink`() = runTest {
        assertEquals(emptyList<Sink>(), buildSinks(AppSettings(sinkUrl = " "), MapSecretStore(), client))
    }
}
