package app.btcourier.core.auth

import mockwebserver3.MockResponse
import okhttp3.Request
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class RefreshingTokenSourceTest : OidcServerTest() {
    private val t0 = 1_800_000_000_000L
    private var now = t0
    private val skew = RefreshingTokenSource.DEFAULT_SKEW_MS

    private fun source(
        store: TokenStore,
        client: OidcClient = publicClient,
    ) = RefreshingTokenSource(provider, client, this.client, store, nowMs = { now })

    /** A set whose access token is inside the refresh window but not yet expired. */
    private fun nearlyExpired(refresh: String? = "rt-old") =
        TokenSet("at-old", refresh, t0 + skew - 1, "openid offline_access")

    private fun refreshForm(refreshToken: String) =
        mapOf("grant_type" to "refresh_token", "refresh_token" to refreshToken, "client_id" to "bt-courier")

    @Test
    fun `a token outside the skew window is used as is`() {
        val store = RecordingTokenStore(TokenSet("at-1", "rt-1", t0 + skew + 1))
        assertEquals("at-1", source(store).token())
        assertEquals(0, server.requestCount)
        assertEquals(emptyList<TokenSet?>(), store.saves)
    }

    @Test
    fun `refreshes near expiry, persists the rotated token, and uses it for the next refresh`() {
        val store = RecordingTokenStore(nearlyExpired())
        val source = source(store)
        respondTokens("at-2", "rt-2", expiresIn = 3600)

        val header =
            Request
                .Builder()
                .url(
                    server.url("/"),
                ).also(Auth.Bearer(source)::applyTo)
                .build()
                .header("Authorization")

        assertEquals("Bearer at-2", header)
        assertEquals(refreshForm("rt-old"), takeRequest().form())
        val rotated = TokenSet("at-2", "rt-2", t0 + 3_600_000, "openid offline_access")
        assertEquals(listOf(rotated), store.saves, "saved before token() returned")
        assertEquals("at-2", source.token())
        assertEquals(1, server.requestCount)

        // RFC 6749 §6: without a new refresh_token the current one stays valid.
        now = rotated.expiresAtMs - skew
        respondTokens("at-3", null, expiresIn = 3600, scope = "openid")
        assertEquals("at-3", source.token())
        assertEquals(refreshForm("rt-2"), takeRequest().form())
        assertEquals(TokenSet("at-3", "rt-2", now + 3_600_000, "openid"), store.saves.last())
    }

    @Test
    fun `concurrent callers share one refresh`() {
        val store = RecordingTokenStore(nearlyExpired())
        val source = source(store)
        // A slow reply keeps the refresh in flight while every caller arrives.
        server.enqueue(
            MockResponse
                .Builder()
                .code(200)
                .setHeader("Content-Type", "application/json")
                .body("""{"access_token":"at-2","token_type":"Bearer","refresh_token":"rt-2","expires_in":3600}""")
                .headersDelay(300, TimeUnit.MILLISECONDS)
                .build(),
        )
        val callers = 8
        val pool = Executors.newFixedThreadPool(callers)
        val go = CountDownLatch(1)

        val results =
            try {
                val futures =
                    (1..callers).map {
                        pool.submit<String> {
                            go.await()
                            source.token()
                        }
                    }
                go.countDown()
                futures.map { it.get(10, TimeUnit.SECONDS) }
            } finally {
                pool.shutdownNow()
            }

        assertEquals(List(callers) { "at-2" }, results)
        assertEquals(1, server.requestCount)
        assertEquals(1, store.saves.size)
    }

    @Test
    fun `invalid_grant requires sign-in, clears the store, and later calls fail without a request`() {
        val store = RecordingTokenStore(nearlyExpired())
        val source = source(store)
        respondError("invalid_grant", "The refresh token has been revoked")

        val e = assertThrows<OidcException.ReauthorizationRequired> { source.token() }

        assertEquals(listOf<TokenSet?>(null), store.saves)
        assertThrows<OidcException.ReauthorizationRequired> { source.token() }
        assertEquals(1, server.requestCount)
        assertFalse("rt-old" in e.message.orEmpty())
    }

    @Test
    fun `no stored tokens, or an expired token without a refresh token, requires sign-in`() {
        assertThrows<OidcException.ReauthorizationRequired> { source(RecordingTokenStore()).token() }
        assertThrows<OidcException.ReauthorizationRequired> {
            source(RecordingTokenStore(nearlyExpired(refresh = null))).token()
        }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `a transient refresh failure keeps a still-valid token and surfaces as IOException once it expired`() {
        val source = source(RecordingTokenStore(nearlyExpired()))
        respond(503)
        respond(503)

        assertEquals("at-old", source.token())
        now = t0 + skew
        assertThrows<IOException> { source.token() }
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `a rejected client is not mistaken for a lost grant`() {
        val store = RecordingTokenStore(nearlyExpired())
        respondError("invalid_client", "Client authentication failed", code = 401)

        val e = assertThrows<OidcException.Rejected> { source(store).token() }

        assertEquals("invalid_client", e.error)
        assertEquals(emptyList<TokenSet?>(), store.saves)
    }

    @Test
    fun `a store that fails to save is retried before the next token is handed out`() {
        var failNext = true
        val saved = mutableListOf<TokenSet?>()
        val flaky =
            object : TokenStore {
                override fun load() = nearlyExpired()

                override fun save(tokens: TokenSet?) {
                    if (failNext) {
                        failNext = false
                        throw IOException("keystore busy")
                    }
                    saved += tokens
                }
            }
        val source = source(flaky)
        respondTokens("at-2", "rt-2", expiresIn = 3600)

        assertThrows<IOException> { source.token() }
        assertEquals("at-2", source.token())
        assertEquals(listOf<TokenSet?>(TokenSet("at-2", "rt-2", t0 + 3_600_000, "openid offline_access")), saved)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `secrets stay out of toString and error messages`() {
        val client = OidcClient("bt-courier", "s3cret-client", ClientAuthMethod.ClientSecretPost)
        val source = source(RecordingTokenStore(nearlyExpired(refresh = "rt-s3cret")), client)
        respondError("invalid_client")

        val e = assertThrows<OidcException.Rejected> { source.token() }

        assertEquals(
            mapOf(
                "grant_type" to "refresh_token",
                "refresh_token" to "rt-s3cret",
                "client_id" to "bt-courier",
                "client_secret" to "s3cret-client",
            ),
            takeRequest().form(),
        )
        for (s in listOf(client.toString(), source.toString(), e.message.orEmpty(), nearlyExpired().toString())) {
            assertFalse("s3cret" in s || "at-old" in s, s)
        }
        val malformed = assertThrows<IllegalArgumentException> { TokenSet.decode("""{"accessToken":"at-s3cret"""") }
        assertFalse("s3cret" in malformed.message.orEmpty())
        assertEquals(nearlyExpired(), TokenSet.decode(nearlyExpired().encode()))
    }
}
