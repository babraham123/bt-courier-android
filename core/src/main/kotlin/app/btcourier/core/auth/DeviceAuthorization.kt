package app.btcourier.core.auth

import app.btcourier.core.http.await
import app.btcourier.core.wican.long
import app.btcourier.core.wican.string
import kotlinx.coroutines.delay
import okhttp3.OkHttpClient
import java.io.IOException

/**
 * An RFC 8628 §3.2 device authorization response. Show [userCode] and [verificationUri] (or
 * [verificationUriComplete] as a link/QR code), then [DeviceAuthorization.poll] it.
 *
 * `toString()` masks [deviceCode], the credential the poll redeems.
 */
class DeviceCodeGrant(
    val deviceCode: String,
    val userCode: String,
    val verificationUri: String,
    val verificationUriComplete: String?,
    val expiresInSeconds: Long,
    /** [expiresInSeconds] from when the request went out, on the [DeviceAuthorization] clock. */
    val expiresAtMs: Long,
    /** Minimum seconds between polls. */
    val intervalSeconds: Long,
) {
    override fun toString() =
        "DeviceCodeGrant(deviceCode=***, userCode=$userCode, verificationUri=$verificationUri, " +
            "verificationUriComplete=$verificationUriComplete, expiresInSeconds=$expiresInSeconds, " +
            "intervalSeconds=$intervalSeconds)"
}

/**
 * The OAuth 2.0 device authorization grant (RFC 8628), which lets the phone sign in through a
 * browser on any device without the app handling the password.
 *
 * Authelia only issues a refresh token when `offline_access` is among the scopes and the client
 * allows the `refresh_token` grant; without one, the session ends with the access token.
 *
 * @param nowMs wall clock for expiry; injectable for tests.
 * @param sleep waits between polls; injectable so tests can record the intervals.
 */
class DeviceAuthorization(
    private val provider: OidcProviderMetadata,
    private val client: OidcClient,
    private val http: OkHttpClient,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
) {
    /**
     * Requests a device code (RFC 8628 §3.1).
     *
     * @throws OidcException.Rejected for an OAuth error (e.g. `invalid_client`, `invalid_scope`).
     * @throws OidcException.InvalidResponse for a malformed reply.
     * @throws IOException on network failure, 429 or 5xx.
     */
    suspend fun start(scopes: List<String> = DEFAULT_SCOPES): DeviceCodeGrant {
        val fields = if (scopes.isEmpty()) emptyMap() else mapOf("scope" to scopes.joinToString(" "))
        val requestedAt = nowMs()
        val endpoint = "device authorization endpoint"
        val reply =
            http
                .newCall(
                    client.post(provider.deviceAuthorizationEndpoint, fields),
                ).await()
                .toOAuthReply(endpoint)
        val body =
            when (reply) {
                is OAuthReply.Ok -> reply.body
                is OAuthReply.Error -> throw reply.rejected()
            }
        val what = "device authorization response"
        val expiresIn =
            body.long("expires_in")?.takeIf { it > 0 }
                ?: throw OidcException.InvalidResponse("$what has no positive expires_in")
        return DeviceCodeGrant(
            deviceCode = body.requireString("device_code", what),
            userCode = body.requireString("user_code", what),
            verificationUri = body.requireString("verification_uri", what),
            verificationUriComplete = body.string("verification_uri_complete"),
            expiresInSeconds = expiresIn,
            expiresAtMs = requestedAt + expiresIn * 1000,
            intervalSeconds = body.long("interval")?.takeIf { it > 0 } ?: DEFAULT_INTERVAL_S,
        )
    }

    /**
     * Polls the token endpoint until the user decides (RFC 8628 §3.4, §3.5). Waits the interval
     * before every request, including the first; `authorization_pending` keeps the interval,
     * `slow_down` adds 5 s to it for all later polls, and a connection failure or transient HTTP
     * error doubles it (§3.5 requires backing off on timeouts). Cancel the coroutine to give up.
     *
     * Save the result in a [TokenStore] and hand that to a [RefreshingTokenSource].
     *
     * @throws OidcException.AccessDenied if the user declined.
     * @throws OidcException.DeviceCodeExpired if the code expired, by the provider's word or the
     *   local clock.
     * @throws OidcException.Rejected for any other OAuth error.
     * @throws OidcException.InvalidResponse for a malformed reply.
     */
    suspend fun poll(grant: DeviceCodeGrant): TokenSet {
        val fields = mapOf("grant_type" to DEVICE_CODE_GRANT_TYPE, "device_code" to grant.deviceCode)
        var intervalMs = grant.intervalSeconds * 1000
        while (true) {
            sleep(intervalMs)
            if (nowMs() >= grant.expiresAtMs) throw OidcException.DeviceCodeExpired()
            val requestedAt = nowMs()
            val reply =
                try {
                    http.newCall(client.post(provider.tokenEndpoint, fields)).await().toOAuthReply("token endpoint")
                } catch (_: IOException) {
                    intervalMs *= 2
                    continue
                }
            when (reply) {
                is OAuthReply.Ok -> {
                    return reply.body.toTokenSet(requestedAt)
                }

                is OAuthReply.Error -> {
                    when (reply.error) {
                        "authorization_pending" -> Unit
                        "slow_down" -> intervalMs += SLOW_DOWN_STEP_MS
                        "access_denied" -> throw OidcException.AccessDenied()
                        "expired_token" -> throw OidcException.DeviceCodeExpired()
                        else -> throw reply.rejected()
                    }
                }
            }
        }
    }

    companion object {
        const val DEVICE_CODE_GRANT_TYPE = "urn:ietf:params:oauth:grant-type:device_code"

        /** RFC 8628 §3.2: the interval when the provider names none. */
        const val DEFAULT_INTERVAL_S = 5L

        /** RFC 8628 §3.5: how much each `slow_down` lengthens the interval. */
        const val SLOW_DOWN_STEP_MS = 5_000L

        val DEFAULT_SCOPES = listOf("openid", "offline_access")
    }
}
