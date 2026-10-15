package app.btcourier.work

import app.btcourier.core.sink.Batch
import app.btcourier.core.sink.CompositeSink
import app.btcourier.core.sink.Jsonl
import app.btcourier.core.sink.LocalDirectoryUploadTransport
import app.btcourier.core.sink.Sink
import app.btcourier.core.sink.UploadResult
import app.btcourier.core.sink.UploadTransport
import app.btcourier.core.store.DefaultStore
import app.btcourier.core.store.SealPolicy
import app.btcourier.data.AppSettings
import app.btcourier.data.AuthConfig
import app.btcourier.data.InMemorySyncStatus
import app.btcourier.data.SinkConfig
import app.btcourier.data.SinkEnvironment
import app.btcourier.data.SinkUpload
import app.btcourier.data.StoredNames
import app.btcourier.data.TransportConfig
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
import java.io.Closeable
import java.nio.file.Path
import java.util.Base64
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import kotlin.io.path.listDirectoryEntries

/**
 * [UploadJob] over the app's store with real sinks (local directory, HTTP PUT against a local
 * server) and scripted ones for failures. Expected values follow the job's contract: every
 * pending batch reaches every sink, sinks fail independently, transient failures retry up to
 * [UploadJob.MAX_ATTEMPTS] rounds, permanent ones never, a round that only waited for a sink's
 * `Retry-After` is no attempt but asks for a follow-up when the sink is due, and nothing is lost
 * either way.
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
    private suspend fun storeWithTwoBatches(sinks: Set<String>): DefaultStore =
        openStore(dir.resolve("store"), sinks).apply {
            appendPage("wican", "1:100", listOf(marker("dl_1.jsonl")) + (0 until 3).map { row(1_000L + it, it) })
            appendPage("wican", "2:100", listOf(marker("dl_2.jsonl")) + (3 until 6).map { row(2_000L + it, it) })
            seal(sealEverything, NOW)
        }

    private fun localSink(id: String) = CompositeSink(id, LocalDirectoryUploadTransport(dir.resolve(id)), Jsonl)

    private fun scriptedSink(
        id: String,
        vararg results: UploadResult,
    ) = CompositeSink(id, ScriptedUpload(*results), Jsonl)

    private fun job(
        store: DefaultStore,
        vararg sinks: Sink,
        threshold: Long = UploadJob.FOREGROUND_THRESHOLD_BYTES,
    ) = UploadJob(store, { sinks.toList() }, status, clock = { NOW }, foregroundThresholdBytes = threshold)

    @Test
    fun `every pending batch reaches every sink, then the store drops them`() =
        runTest {
            val store = storeWithTwoBatches(setOf("a", "b"))

            val outcome = job(store, localSink("a"), localSink("b")).run(attempt = 0, sealEverything)

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
    fun `a transient failure retries until the attempt cap, then gives up and keeps the batches`() =
        runTest {
            val store = storeWithTwoBatches(setOf("down"))
            val sink = scriptedSink("down", UploadResult.Retry("HTTP 503"))

            assertEquals(UploadOutcome.Retry, job(store, sink).run(attempt = 0, sealEverything))
            assertEquals(UploadResultKind.RETRYING, status.status.value.lastUpload)

            assertEquals(
                UploadOutcome.GaveUp,
                job(store, sink).run(attempt = UploadJob.MAX_ATTEMPTS - 1, sealEverything),
            )

            val s = status.status.value
            assertEquals(UploadResultKind.GAVE_UP, s.lastUpload)
            assertEquals("gave up after ${UploadJob.MAX_ATTEMPTS} attempts: down: HTTP 503", s.lastError)
            assertEquals(2, store.pending("down").size)
            assertEquals(2, s.pendingBatches)
        }

    @Test
    fun `a permanent failure is not retried and does not hold back the other sinks`() =
        runTest {
            val store = storeWithTwoBatches(setOf("refused", "ok"))

            val outcome =
                job(
                    store,
                    scriptedSink("refused", UploadResult.Failed("HTTP 403")),
                    localSink("ok"),
                ).run(attempt = 0, sealEverything)

            assertEquals(UploadOutcome.Failed, outcome)
            assertEquals(2, dir.resolve("ok").listDirectoryEntries().size, "the healthy sink got everything")
            assertEquals(2, store.pending("refused").size, "the refused batches stay for a config fix")
            assertEquals(UploadResultKind.FAILED, status.status.value.lastUpload)
            assertEquals("refused: HTTP 403", status.status.value.lastError)
            assertEquals(
                mapOf(
                    "refused" to SinkUpload(NOW, UploadResultKind.FAILED),
                    "ok" to SinkUpload(NOW, UploadResultKind.DELIVERED),
                ),
                status.status.value.lastUploadBySink,
            )
        }

    @Test
    fun `with no sink configured nothing is uploaded or dropped`() =
        runTest {
            val store = storeWithTwoBatches(emptySet())

            assertEquals(UploadOutcome.NoSink, job(store).run(attempt = 0, sealEverything))

            assertEquals(UploadResultKind.NO_SINK, status.status.value.lastUpload)
            assertEquals(2, store.counters().pendingBatches)
        }

    @Test
    fun `only a backlog at the threshold promotes the upload to the foreground`() =
        runTest {
            val store = storeWithTwoBatches(setOf("a"))
            val bytes = store.counters().bytesStored
            var promotions = 0

            job(
                store,
                scriptedSink("a", UploadResult.Retry("x")),
                threshold = bytes + 1,
            ).run(0, sealEverything) { promotions++ }
            assertEquals(0, promotions)

            job(store, localSink("a"), threshold = bytes).run(0, sealEverything) { promotions++ }
            assertEquals(1, promotions)
        }

    @Test
    fun `a round first seals rows the age rule has closed, so they go out without a sync`() =
        runTest {
            var now = NOW
            val store = openStore(dir.resolve("store"), setOf("a")) { now }
            val rows = (0 until 3).map { row(1_000L + it, it) }
            store.appendPage("wican", "1:100", listOf(marker("dl_1.jsonl")) + rows)
            val upload = ScriptedUpload(UploadResult.Acked)
            val job = UploadJob(store, { listOf(CompositeSink("a", upload, Jsonl)) }, status, clock = { now })

            assertEquals(UploadOutcome.Delivered, job.run(attempt = 0, SealPolicy()))
            assertEquals(0, upload.uploads, "younger than the age rule, so the batch is still open")

            now += 30 * MINUTE
            assertEquals(UploadOutcome.Delivered, job.run(attempt = 0, SealPolicy()))

            assertEquals(rows, upload.ackedRows)
            assertEquals(0, store.counters().pendingBatches)
        }

    private fun env() = SinkEnvironment(client, { error("no folders here") }, InMemorySshKeys())

    @Test
    fun `configured HTTP PUT sink uploads with Basic auth from the secret store`() =
        runTest {
            val store = storeWithTwoBatches(setOf("put"))
            repeat(2) { server.enqueue(MockResponse.Builder().code(201).build()) }
            val sink =
                SinkConfig(
                    "put",
                    "Put",
                    transport = TransportConfig.HttpPut(server.url("/ingest/").toString()),
                    auth = AuthConfig.Basic("courier"),
                )
            val secrets = MapSecretStore(StoredNames.secret("put") to "s3cret")

            val outcome =
                UploadJob(store, {
                    buildSinks(AppSettings(sinks = listOf(sink)), secrets, env())
                }, status, clock = { NOW }).run(0, sealEverything)

            assertEquals(UploadOutcome.Delivered, outcome)
            val requests = List(2) { checkNotNull(server.takeRequest(5, TimeUnit.SECONDS)) }
            val basic = "Basic " + Base64.getEncoder().encodeToString("courier:s3cret".toByteArray())
            assertTrue(requests.all { it.method == "PUT" && it.headers["Authorization"] == basic })
            assertTrue(requests.all { it.target.startsWith("/ingest/wican-") && it.target.endsWith(".jsonl.gz") })
            val firstObject =
                GZIPInputStream(
                    requests[0].body!!.toByteArray().inputStream(),
                ).readBytes().decodeToString()
            assertEquals(
                listOf(marker("dl_1.jsonl")) +
                    (0 until 3).map {
                        row(1_000L + it, it)
                    },
                firstObject.lines().filter(String::isNotEmpty),
            )
        }

    @Test
    fun `a sink is not contacted before its Retry-After, although sinks are rebuilt every round`() =
        runTest {
            val store = storeWithTwoBatches(setOf("put"))
            server.enqueue(
                MockResponse
                    .Builder()
                    .code(429)
                    .setHeader("Retry-After", "120")
                    .build(),
            )
            repeat(2) { server.enqueue(MockResponse.Builder().code(201).build()) }
            val settings =
                AppSettings(
                    sinks =
                        listOf(
                            SinkConfig(
                                "put",
                                "Put",
                                transport = TransportConfig.HttpPut(server.url("/ingest/").toString()),
                            ),
                        ),
                )
            var now = NOW
            val job = UploadJob(store, { buildSinks(settings, MapSecretStore(), env()) }, status, clock = { now })

            assertEquals(UploadOutcome.Retry, job.run(attempt = 0, sealEverything))
            now += 119_000
            assertEquals(
                UploadOutcome.Deferred(1_000),
                job.run(attempt = 1, sealEverything),
                "a waiting round asks for a follow-up when the sink is due",
            )

            assertEquals(1, server.requestCount, "the waiting round sent nothing")
            val s = status.status.value
            assertEquals(UploadResultKind.RETRYING, s.lastUpload)
            assertEquals(mapOf("put" to SinkUpload(now, UploadResultKind.RETRYING)), s.lastUploadBySink)
            assertEquals(
                "put: HTTP 429 for ${server.takeRequest(
                    5,
                    TimeUnit.SECONDS,
                )!!.target.substringAfterLast('/')} (next attempt in 1 s)",
                s.lastError,
            )

            now += 1_000
            assertEquals(UploadOutcome.Delivered, job.run(attempt = 0, sealEverything))
            assertEquals(3, server.requestCount)
            assertEquals(0, store.counters().pendingBatches)
        }

    @Test
    fun `a Retry-After beyond the attempt window does not burn the budget, the round is deferred rather than given up`() =
        runTest {
            val store = storeWithTwoBatches(setOf("slow", "down"))
            val slow = scriptedSink("slow", UploadResult.Retry("HTTP 503", retryAfterMs = 2 * HOUR), UploadResult.Acked)
            val down = scriptedSink("down", UploadResult.Retry("HTTP 503"))
            var now = NOW
            val job = UploadJob(store, { listOf(slow, down) }, status, clock = { now })

            assertEquals(
                UploadOutcome.Retry,
                job.run(attempt = 0, sealEverything),
                "a sink answered Retry, so WorkManager's back-off applies",
            )
            now += MINUTE
            assertEquals(
                UploadOutcome.Deferred(2 * HOUR - MINUTE),
                job.run(attempt = UploadJob.MAX_ATTEMPTS - 1, sealEverything),
                "with the budget used up but a sink still waiting, its due time beats giving up",
            )
            assertEquals(UploadResultKind.RETRYING, status.status.value.lastUpload)
            assertEquals(
                mapOf(
                    "slow" to SinkUpload(now, UploadResultKind.RETRYING),
                    "down" to SinkUpload(now, UploadResultKind.RETRYING),
                ),
                status.status.value.lastUploadBySink,
            )

            now += 2 * HOUR - MINUTE
            assertEquals(
                UploadOutcome.Retry,
                job.run(attempt = 0, sealEverything),
                "the follow-up attempts the slow sink and the down one is still down",
            )
            assertEquals(0, store.pending("slow").size)
            assertEquals(2, store.pending("down").size)
        }

    @Test
    fun `a permanent failure beside a waiting sink still gets the follow-up`() =
        runTest {
            val store = storeWithTwoBatches(setOf("slow", "refused"))
            val slow = scriptedSink("slow", UploadResult.Retry("HTTP 503", retryAfterMs = HOUR))
            var now = NOW
            val job =
                UploadJob(
                    store,
                    { listOf(slow, scriptedSink("refused", UploadResult.Failed("HTTP 403"))) },
                    status,
                    clock = { now },
                )
            job.run(attempt = 0, sealEverything)

            now += MINUTE
            assertEquals(UploadOutcome.Deferred(HOUR - MINUTE), job.run(attempt = 1, sealEverything))
            assertEquals(UploadResultKind.RETRYING, status.status.value.lastUpload)
            assertEquals(
                UploadResultKind.FAILED,
                status.status.value.lastUploadBySink
                    .getValue("refused")
                    .result,
            )
        }

    @Test
    fun `an unparseable sink URL fails that sink instead of crashing the worker, and keeps its batches`() =
        runTest {
            val store = storeWithTwoBatches(setOf("bad"))
            val settings =
                AppSettings(sinks = listOf(SinkConfig("bad", "Bad", transport = TransportConfig.HttpPut("not a url"))))

            val outcome =
                UploadJob(store, {
                    buildSinks(settings, MapSecretStore(), env())
                }, status, clock = { NOW }).run(0, sealEverything)

            assertEquals(UploadOutcome.Failed, outcome)
            assertTrue(
                status.status.value.lastError!!
                    .startsWith("bad: invalid sink configuration"),
                status.status.value.lastError,
            )
            assertEquals(2, store.counters().pendingBatches)
        }

    @Test
    fun `no sinks, or only disabled ones, means no sink`() =
        runTest {
            assertEquals(emptyList<Sink>(), buildSinks(AppSettings(), MapSecretStore(), env()))
            val disabled =
                SinkConfig("off", "Off", enabled = false, transport = TransportConfig.HttpPut("https://sink.invalid/"))
            assertEquals(emptyList<Sink>(), buildSinks(AppSettings(sinks = listOf(disabled)), MapSecretStore(), env()))
        }

    @Test
    fun `closeable transports are closed after the round, also when it fails`() =
        runTest {
            val store = storeWithTwoBatches(setOf("c"))
            val transport =
                object : UploadTransport, Closeable {
                    var closed = 0

                    override suspend fun upload(batch: Batch): UploadResult = UploadResult.Failed("refused")

                    override fun close() {
                        closed++
                    }
                }

            job(store, CompositeSink("c", transport, Jsonl)).run(attempt = 0, sealEverything)

            assertEquals(1, transport.closed)
        }
}
