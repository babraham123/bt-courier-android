package app.btcourier.data.sinkkind

import app.btcourier.core.http.parseBaseUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

enum class SinkUrlProblem {
    /** Not an http(s) URL, or blank where one is required. */
    INVALID,

    /** `user:pass@` in the URL; credentials go in the username and password fields. */
    CREDENTIALS_IN_URL,

    /**
     * PUT objects are named by appending to the path, which a query would break; a fragment is
     * never sent, so it can only be a mistake.
     */
    QUERY_OR_FRAGMENT,

    /** Plain http; the app ships without cleartext traffic, so it could never connect. */
    CLEARTEXT,
}

/**
 * Checks a sink or OIDC issuer URL before it is saved. Builds on core's [parseBaseUrl], so a URL
 * accepted here is one the HTTP transports accept.
 *
 * https is required for every host, loopback and private addresses included. Over http the Basic
 * password or token and the uploaded data would travel in clear, and a self-hosted server on the
 * user's own network is reachable over https through a VPN anyway, so the app ships without a
 * cleartext exception (the manifest keeps the targetSdk default) rather than with a hole an F-Droid
 * audience would rightly object to. Refusing http here explains the failure at save time instead
 * of at the first upload.
 *
 * @param allowQuery true for a POST endpoint, whose query (e.g. VictoriaMetrics `extra_label=`) is
 *   sent as is.
 * @param required false lets a blank URL pass.
 */
fun validateSinkUrl(raw: String, allowQuery: Boolean = false, required: Boolean = false): SinkUrlProblem? {
    val text = raw.trim()
    if (text.isEmpty()) return SinkUrlProblem.INVALID.takeIf { required }
    val url = try {
        parseBaseUrl(text)
    } catch (_: IllegalArgumentException) {
        // parseBaseUrl refuses both; only a URL that parses can carry credentials.
        return if (text.toHttpUrlOrNull() == null) SinkUrlProblem.INVALID else SinkUrlProblem.CREDENTIALS_IN_URL
    }
    if ((url.query != null && !allowQuery) || url.fragment != null) return SinkUrlProblem.QUERY_OR_FRAGMENT
    return if (url.isHttps) null else SinkUrlProblem.CLEARTEXT
}
