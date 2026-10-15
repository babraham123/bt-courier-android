package app.btcourier.core.auth

import app.btcourier.core.http.append
import app.btcourier.core.http.await
import app.btcourier.core.http.parseBaseUrl
import app.btcourier.core.wican.string
import kotlinx.serialization.json.JsonObject
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

/** The parts of an OpenID Provider's metadata (OIDC Discovery §3) this app uses. */
data class OidcProviderMetadata(
    val issuer: String,
    val tokenEndpoint: HttpUrl,
    val deviceAuthorizationEndpoint: HttpUrl,
    val revocationEndpoint: HttpUrl? = null,
    val endSessionEndpoint: HttpUrl? = null,
)

/** Fetches `<issuer>/.well-known/openid-configuration` (OIDC Discovery §4). */
class OidcDiscovery(private val http: OkHttpClient) {
    /**
     * @param issuer the issuer URL as the user typed it, e.g. `https://auth.example.com`.
     * @throws OidcException.IssuerMismatch if the document is for another issuer.
     * @throws OidcException.InvalidMetadata if the document is unusable, including a provider
     *   without the device authorization grant.
     * @throws java.io.IOException on network failure, 429 or 5xx.
     */
    suspend fun discover(issuer: String): OidcProviderMetadata {
        val url = parseBaseUrl(issuer).append(WELL_KNOWN_PATH)
        val (code, json) = http.newCall(Request.Builder().url(url).build()).await().readJsonObject("discovery endpoint")
        if (code !in 200..299) throw OidcException.InvalidMetadata("$url answered HTTP $code")
        if (json == null) throw OidcException.InvalidMetadata("$url did not return a JSON object")
        return parse(issuer, json)
    }

    private fun parse(expectedIssuer: String, json: JsonObject): OidcProviderMetadata {
        val issuer = json.string("issuer") ?: throw OidcException.InvalidMetadata("discovery document has no issuer")
        // Discovery requires an identical issuer; only a trailing '/' is forgiven, since users
        // type one and providers differ on whether their issuer carries it.
        if (issuer.trimEnd('/') != expectedIssuer.trimEnd('/')) {
            throw OidcException.IssuerMismatch(expectedIssuer, issuer)
        }
        return OidcProviderMetadata(
            issuer = issuer,
            tokenEndpoint = json.endpoint("token_endpoint")
                ?: throw OidcException.InvalidMetadata("discovery document has no token_endpoint"),
            deviceAuthorizationEndpoint = json.endpoint("device_authorization_endpoint")
                ?: throw OidcException.InvalidMetadata(
                    "provider has no device_authorization_endpoint; enable the device authorization grant for it",
                ),
            revocationEndpoint = json.endpoint("revocation_endpoint"),
            endSessionEndpoint = json.endpoint("end_session_endpoint"),
        )
    }

    private fun JsonObject.endpoint(key: String): HttpUrl? {
        val value = string(key) ?: return null
        return value.toHttpUrlOrNull() ?: throw OidcException.InvalidMetadata("$key is not an http(s) URL: $value")
    }

    companion object {
        const val WELL_KNOWN_PATH = "/.well-known/openid-configuration"
    }
}
