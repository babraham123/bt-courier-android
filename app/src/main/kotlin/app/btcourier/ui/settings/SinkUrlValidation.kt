package app.btcourier.ui.settings

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

enum class SinkUrlProblem {
    /** Not an http(s) URL. */
    INVALID,

    /** `user:pass@` in the URL; credentials go in the username and password fields. */
    CREDENTIALS_IN_URL,

    /** Objects are named by appending to the path, which a query or fragment would break. */
    QUERY_OR_FRAGMENT,

    /** Plain http; the app ships without cleartext traffic, so it could never connect. */
    CLEARTEXT,
}

/**
 * Checks a sink base URL before it is saved; blank is valid and means "no sink".
 *
 * https is required for every host, loopback and private addresses included. Over http the Basic
 * password or token and the uploaded data would travel in clear, and a self-hosted server on the
 * user's own network is reachable over https through a VPN anyway, so the app ships without a
 * cleartext exception (the manifest keeps the targetSdk default) rather than with a hole an F-Droid
 * audience would rightly object to. Refusing http here explains the failure at save time instead
 * of at the first upload.
 */
fun validateSinkUrl(raw: String): SinkUrlProblem? {
    val text = raw.trim()
    if (text.isEmpty()) return null
    val url = text.toHttpUrlOrNull() ?: return SinkUrlProblem.INVALID
    if (url.username.isNotEmpty() || url.password.isNotEmpty()) return SinkUrlProblem.CREDENTIALS_IN_URL
    if (url.query != null || url.fragment != null) return SinkUrlProblem.QUERY_OR_FRAGMENT
    return if (url.isHttps) null else SinkUrlProblem.CLEARTEXT
}
