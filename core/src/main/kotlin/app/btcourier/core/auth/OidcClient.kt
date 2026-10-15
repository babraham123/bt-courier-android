package app.btcourier.core.auth

import okhttp3.Credentials
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.Request
import java.net.URLEncoder

/** How the client authenticates to the token and device authorization endpoints (RFC 6749 §2.3). */
enum class ClientAuthMethod(val wireName: String) {
    /** Public client: `client_id` in the form, no secret (Authelia `token_endpoint_auth_method: none`). */
    None("none"),

    /** `client_id` and `client_secret` in the form body. */
    ClientSecretPost("client_secret_post"),

    /** HTTP Basic with the form-urlencoded id and secret (RFC 6749 §2.3.1). */
    ClientSecretBasic("client_secret_basic"),
}

/**
 * The app's registration at the provider. `toString()` leaves out the secret so a client can sit
 * in config objects that reach logs.
 */
class OidcClient(
    val clientId: String,
    private val clientSecret: String? = null,
    val authMethod: ClientAuthMethod =
        if (clientSecret == null) ClientAuthMethod.None else ClientAuthMethod.ClientSecretBasic,
) {
    init {
        require(clientId.isNotEmpty()) { "client_id is empty" }
        require((authMethod == ClientAuthMethod.None) == (clientSecret == null)) {
            "a client secret goes with client_secret_post or client_secret_basic, and only with those"
        }
    }

    /** A form POST to [url] carrying [fields] and this client's credentials. */
    internal fun post(url: HttpUrl, fields: Map<String, String>): Request {
        val form = FormBody.Builder()
        fields.forEach(form::add)
        val request = Request.Builder().url(url).header("Accept", "application/json")
        when (authMethod) {
            ClientAuthMethod.None -> form.add("client_id", clientId)
            ClientAuthMethod.ClientSecretPost -> form.add("client_id", clientId).add("client_secret", clientSecret!!)
            // RFC 6749 §2.3.1 encodes both parts as application/x-www-form-urlencoded before Basic,
            // so a secret containing ':' or non-ASCII survives.
            ClientAuthMethod.ClientSecretBasic ->
                request.header("Authorization", Credentials.basic(formEncode(clientId), formEncode(clientSecret!!)))
        }
        return request.post(form.build()).build()
    }

    override fun toString() = "OidcClient(clientId=$clientId, authMethod=${authMethod.wireName})"

    // The (String, Charset) overload is API 33; the app's minimum is 31.
    private fun formEncode(s: String) = URLEncoder.encode(s, Charsets.UTF_8.name())
}
