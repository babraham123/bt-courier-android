package app.btcourier.core.auth

import okhttp3.OkHttpClient
import java.io.IOException

/**
 * A [TokenSource] over the [TokenSet] in [store], refreshed with the `refresh_token` grant
 * (RFC 6749 §6) once the access token is within [skewMs] of expiry.
 *
 * Concurrency: [token] is blocking because `Auth.applyTo` is, and transports call it on
 * `Dispatchers.IO`. A plain monitor around a synchronous OkHttp call makes concurrent callers wait
 * for the one refresh in flight and then reuse its result. `runBlocking` with a coroutine mutex
 * would park the thread just the same, and could deadlock if a caller ever held the only thread of
 * a dispatcher the inner coroutine needed. The refresh is bounded by the client's timeouts, not by
 * coroutine cancellation.
 *
 * Rotation: Authelia issues a new refresh token on every refresh and invalidates the old one, so
 * each new set is saved before [token] returns. If saving throws, the new set is kept in memory and
 * saving is retried on the next call, since the old refresh token is already dead.
 *
 * Failures:
 * - [OidcException.ReauthorizationRequired]: nothing stored, an expired access token with no
 *   refresh token, or `invalid_grant`. On `invalid_grant` the store is cleared, so later calls
 *   fail fast without asking the provider.
 * - [OidcException.Rejected] / [OidcException.InvalidResponse]: client misconfiguration or a
 *   broken provider.
 * - [IOException]: network failure, 429 or 5xx. While the current access token has not actually
 *   expired (only entered the skew window) it is returned instead, and the refresh is retried on
 *   the next call.
 */
class RefreshingTokenSource(
    private val provider: OidcProviderMetadata,
    private val client: OidcClient,
    private val http: OkHttpClient,
    private val store: TokenStore,
    private val skewMs: Long = DEFAULT_SKEW_MS,
    private val nowMs: () -> Long = System::currentTimeMillis,
) : TokenSource {
    private val lock = Any()
    private var loaded = false
    private var current: TokenSet? = null
    private var unsaved = false

    override fun token(): String = synchronized(lock) {
        if (!loaded) {
            current = store.load()
            loaded = true
        }
        persist()
        val tokens = current ?: throw OidcException.ReauthorizationRequired("not signed in")
        if (nowMs() < tokens.expiresAtMs - skewMs) return tokens.accessToken
        val refreshToken = tokens.refreshToken
            ?: throw OidcException.ReauthorizationRequired("the access token expired and no refresh token was issued")
        val fresh = try {
            refresh(tokens, refreshToken)
        } catch (e: IOException) {
            if (nowMs() < tokens.expiresAtMs) return tokens.accessToken
            throw e
        }
        adopt(fresh)
        fresh.accessToken
    }

    private fun refresh(tokens: TokenSet, refreshToken: String): TokenSet {
        val fields = mapOf("grant_type" to "refresh_token", "refresh_token" to refreshToken)
        val requestedAt = nowMs()
        val reply = http.newCall(client.post(provider.tokenEndpoint, fields)).execute().toOAuthReply("token endpoint")
        return when (reply) {
            is OAuthReply.Ok -> reply.body.toTokenSet(requestedAt, previous = tokens)
            is OAuthReply.Error -> {
                if (reply.error != "invalid_grant") throw reply.rejected()
                adopt(null)
                throw OidcException.ReauthorizationRequired(
                    "the provider refused the refresh token" + reply.description?.let { " ($it)" }.orEmpty(),
                )
            }
        }
    }

    private fun adopt(tokens: TokenSet?) {
        current = tokens
        unsaved = true
        persist()
    }

    private fun persist() {
        if (!unsaved) return
        store.save(current)
        unsaved = false
    }

    override fun toString() = "RefreshingTokenSource(issuer=${provider.issuer}, client=$client)"

    companion object {
        const val DEFAULT_SKEW_MS = 30_000L
    }
}
