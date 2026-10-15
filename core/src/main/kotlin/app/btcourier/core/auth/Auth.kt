package app.btcourier.core.auth

import okhttp3.Credentials
import okhttp3.Request

/**
 * Credentials an HTTP transport attaches to each request.
 *
 * None of the variants reveal secrets in `toString()`, so an [Auth] can sit in a config object
 * that ends up in logs or crash reports.
 */
sealed interface Auth {
    /**
     * Adds this credential to [builder]. May block (a refreshing [TokenSource] may hit the
     * network), so transports call it off the main thread.
     */
    fun applyTo(builder: Request.Builder)

    data object None : Auth {
        override fun applyTo(builder: Request.Builder) = Unit
    }

    /** RFC 7617, encoded as UTF-8 so non-ASCII passwords survive (OkHttp defaults to Latin-1). */
    class Basic(
        private val user: String,
        private val password: String,
    ) : Auth {
        override fun applyTo(builder: Request.Builder) {
            builder.header("Authorization", Credentials.basic(user, password, Charsets.UTF_8))
        }

        override fun toString() = "Basic(***)"
    }

    /**
     * RFC 6750 bearer token, read from [tokens] on every request so a refreshing source (OIDC)
     * can rotate it without callers rebuilding transports.
     */
    class Bearer(
        private val tokens: TokenSource,
    ) : Auth {
        constructor(token: String) : this(TokenSource { token })

        override fun applyTo(builder: Request.Builder) {
            builder.header("Authorization", "Bearer ${tokens.token()}")
        }

        override fun toString() = "Bearer(***)"
    }
}

/** Supplies the current bearer token; called once per request and may block to refresh. */
fun interface TokenSource {
    fun token(): String
}
