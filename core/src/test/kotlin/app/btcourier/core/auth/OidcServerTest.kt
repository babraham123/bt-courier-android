package app.btcourier.core.auth

import app.btcourier.core.http.MockServerTest
import mockwebserver3.RecordedRequest
import java.net.URLDecoder

/** A [MockServerTest] posing as Authelia mounted under `/auth`, with the paths Authelia uses. */
abstract class OidcServerTest : MockServerTest() {
    protected val issuer get() = server.url("/auth").toString()

    protected val provider
        get() =
            OidcProviderMetadata(
                issuer = issuer,
                tokenEndpoint = server.url("/auth/api/oidc/token"),
                deviceAuthorizationEndpoint = server.url("/auth/api/oidc/device-authorization"),
            )

    protected val publicClient = OidcClient("bt-courier")

    protected fun respondJson(
        code: Int,
        json: String,
    ) = respond(code, json, "Content-Type" to "application/json")

    /** RFC 6749 §5.2 error reply. */
    protected fun respondError(
        error: String,
        description: String? = null,
        code: Int = 400,
    ) = respondJson(code, """{"error":"$error"${description?.let { ""","error_description":"$it"""" }.orEmpty()}}""")

    /** RFC 6749 §5.1 token reply; null fields are left out. */
    protected fun respondTokens(
        access: String,
        refresh: String?,
        expiresIn: Long?,
        scope: String? = null,
    ) {
        val fields =
            listOfNotNull(
                "\"access_token\":\"$access\"",
                "\"token_type\":\"Bearer\"",
                "\"id_token\":\"eyJhbGciOiJub25lIn0.e30.\"",
                refresh?.let { "\"refresh_token\":\"$it\"" },
                expiresIn?.let { "\"expires_in\":$it" },
                scope?.let { "\"scope\":\"$it\"" },
            )
        respondJson(200, fields.joinToString(",", "{", "}"))
    }

    /** The decoded `application/x-www-form-urlencoded` body. */
    protected fun RecordedRequest.form(): Map<String, String> {
        assertFormContentType()
        return body!!.utf8().split('&').filter { it.isNotEmpty() }.associate { pair ->
            val (k, v) = pair.split('=', limit = 2)
            URLDecoder.decode(k, Charsets.UTF_8) to URLDecoder.decode(v, Charsets.UTF_8)
        }
    }

    private fun RecordedRequest.assertFormContentType() {
        val type = headers["Content-Type"].orEmpty()
        check(type.startsWith("application/x-www-form-urlencoded")) { "not a form post: $type" }
    }
}

/** Records every save, as the Keystore-backed store would persist it. */
class RecordingTokenStore(
    private var stored: TokenSet? = null,
) : TokenStore {
    val saves = mutableListOf<TokenSet?>()

    @Synchronized
    override fun load() = stored

    @Synchronized
    override fun save(tokens: TokenSet?) {
        saves += tokens
        stored = tokens
    }
}
