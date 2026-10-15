package app.btcourier.core.http

import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import java.util.Base64
import java.util.concurrent.TimeUnit

/** Shared lifecycle for tests that talk to a local [MockWebServer]. */
abstract class MockServerTest {
    protected lateinit var server: MockWebServer

    // Short timeouts: every exchange is local, and a hang should fail the test, not stall it.
    protected val client: OkHttpClient =
        OkHttpClient
            .Builder()
            .callTimeout(10, TimeUnit.SECONDS)
            .build()

    @BeforeEach
    fun startServer() {
        server = MockWebServer()
        server.start()
    }

    @AfterEach
    fun stopServer() {
        server.close()
    }

    /** Base URL of the server with a path [prefix], as a user would configure it. */
    protected fun baseUrl(prefix: String = "") = server.url(prefix).toString()

    protected fun respond(
        code: Int,
        body: String = "",
        vararg headers: Pair<String, String>,
    ) {
        server.enqueue(
            MockResponse
                .Builder()
                .code(code)
                .body(body)
                .apply {
                    headers.forEach { (k, v) -> setHeader(k, v) }
                }.build(),
        )
    }

    protected fun takeRequest(): RecordedRequest =
        checkNotNull(server.takeRequest(5, TimeUnit.SECONDS)) { "server received no request" }

    protected fun basicHeader(
        user: String,
        password: String,
    ) = "Basic " + Base64.getEncoder().encodeToString("$user:$password".toByteArray(Charsets.UTF_8))
}
