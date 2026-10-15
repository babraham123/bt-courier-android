package app.btcourier.core.auth

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Tokens from a device flow or refresh. The ID token is not kept: nothing here identifies the
 * user, the access token is what uploads carry.
 *
 * `toString()` masks both tokens.
 */
@Serializable
data class TokenSet(
    val accessToken: String,
    val refreshToken: String?,
    val expiresAtMs: Long,
    val scope: String? = null,
) {
    /** JSON for a [TokenStore] that persists strings. */
    fun encode(): String = Json.encodeToString(serializer(), this)

    override fun toString() =
        "TokenSet(accessToken=***, refreshToken=${refreshToken?.let { "***" }}, expiresAtMs=$expiresAtMs, scope=$scope)"

    companion object {
        internal const val ASSUMED_LIFETIME_S = 300L

        /** Throws [IllegalArgumentException] without the parser's message, which quotes the input. */
        fun decode(json: String): TokenSet =
            try {
                Json.decodeFromString(serializer(), json)
            } catch (_: IllegalArgumentException) {
                throw IllegalArgumentException("stored token set is malformed")
            }
    }
}

/**
 * Durable home of the current [TokenSet], backed in the app by the Keystore-encrypted secret
 * store. Both calls may block; [RefreshingTokenSource] calls them under its lock.
 */
interface TokenStore {
    fun load(): TokenSet?

    /** Replaces the stored set; null signs out. */
    fun save(tokens: TokenSet?)
}
