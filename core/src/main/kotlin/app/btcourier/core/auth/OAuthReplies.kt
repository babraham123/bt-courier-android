package app.btcourier.core.auth

import app.btcourier.core.wican.long
import app.btcourier.core.wican.parseObjectOrNull
import app.btcourier.core.wican.string
import kotlinx.serialization.json.JsonObject
import okhttp3.Response
import java.io.IOException

/** A provider reply that was neither a transport failure nor transient. */
internal sealed interface OAuthReply {
    class Ok(
        val body: JsonObject,
    ) : OAuthReply

    /** An RFC 6749 §5.2 error reply. */
    class Error(
        val error: String,
        val description: String?,
        val httpCode: Int,
    ) : OAuthReply {
        fun rejected() = OidcException.Rejected(error, description, httpCode)
    }
}

/**
 * The status and JSON object body of this response, closing it. 429 and 5xx throw [IOException]
 * so they are retried like a dropped connection. The body is never quoted in errors because a
 * token response carries secrets.
 */
internal fun Response.readJsonObject(endpoint: String): Pair<Int, JsonObject?> =
    use {
        if (code == 429 || code >= 500) throw IOException("$endpoint answered HTTP $code")
        code to parseObjectOrNull(body.string())
    }

internal fun Response.toOAuthReply(endpoint: String): OAuthReply {
    val (code, json) = readJsonObject(endpoint)
    if (code in 200..299) {
        return OAuthReply.Ok(
            json ?: throw OidcException.InvalidResponse("$endpoint answered HTTP $code without a JSON object"),
        )
    }
    val error =
        json?.string("error")
            ?: throw OidcException.InvalidResponse("$endpoint answered HTTP $code without an OAuth error code")
    return OAuthReply.Error(error, json.string("error_description"), code)
}

internal fun JsonObject.requireString(
    key: String,
    what: String,
): String = string(key)?.takeIf { it.isNotEmpty() } ?: throw OidcException.InvalidResponse("$what has no $key")

/**
 * Reads an RFC 6749 §5.1 access token response.
 *
 * @param requestedAtMs when the request went out; `expires_in` counts from the provider's issue
 *   time, so measuring from the send time errs towards refreshing early.
 * @param previous the set being refreshed. RFC 6749 §6 lets the provider omit `refresh_token`
 *   (keep the old one) and `scope` (unchanged).
 */
internal fun JsonObject.toTokenSet(
    requestedAtMs: Long,
    previous: TokenSet? = null,
): TokenSet {
    val what = "token response"
    val tokenType = string("token_type")
    if (tokenType != null && !tokenType.equals("Bearer", ignoreCase = true)) {
        throw OidcException.InvalidResponse("$what has token_type $tokenType, expected Bearer")
    }
    // expires_in is only RECOMMENDED; without it, assume a short life so the token is refreshed
    // regularly instead of being used until the resource server rejects it.
    val expiresInS = long("expires_in") ?: TokenSet.ASSUMED_LIFETIME_S
    return TokenSet(
        accessToken = requireString("access_token", what),
        refreshToken = string("refresh_token") ?: previous?.refreshToken,
        expiresAtMs = requestedAtMs + expiresInS * 1000,
        scope = string("scope") ?: previous?.scope,
    )
}
