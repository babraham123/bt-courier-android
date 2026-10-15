package app.btcourier.core.auth

/**
 * Failures of the OIDC flows that another attempt with the same input cannot fix.
 *
 * Transient failures are not [OidcException]s: a dropped connection, a timeout, HTTP 429 or a 5xx
 * from the provider surface as plain [java.io.IOException]s, so transports that retry on
 * `IOException` retry them, while an [OidcException] escapes `Auth.applyTo` as a non-retryable
 * error.
 *
 * Messages carry only OAuth error codes, the provider's `error_description` and issuer URLs;
 * never tokens, device codes or client secrets.
 */
sealed class OidcException(
    message: String,
) : Exception(message) {
    /** The discovery document is missing, not JSON, or lacks an endpoint this app needs. */
    class InvalidMetadata(
        message: String,
    ) : OidcException(message)

    /**
     * The discovery document names a different issuer than the one asked for (OIDC Discovery
     * §4.3), which would let one provider pose as another.
     */
    class IssuerMismatch(
        val expected: String,
        val actual: String,
    ) : OidcException("discovery document is for issuer $actual, expected $expected")

    /** A device authorization or token response that is not a well-formed RFC 6749/8628 reply. */
    class InvalidResponse(
        message: String,
    ) : OidcException(message)

    /** The user declined the device authorization request (RFC 8628 §3.5 `access_denied`). */
    class AccessDenied : OidcException("the user denied the authorization request")

    /** The device code ran out before the user approved it (RFC 8628 §3.5 `expired_token`). */
    class DeviceCodeExpired : OidcException("the device code expired before it was approved")

    /**
     * The stored grant is gone: no tokens were ever saved, the access token expired without a
     * refresh token, or the provider answered a refresh with `invalid_grant` (revoked, expired,
     * or already rotated). Only a new device flow fixes this, so the UI asks to sign in again.
     */
    class ReauthorizationRequired(
        reason: String,
    ) : OidcException("sign in again: $reason")

    /**
     * Any other OAuth error reply (RFC 6749 §5.2), e.g. `invalid_client`, `unauthorized_client`
     * or `invalid_scope`; these point at the client registration, not the user.
     */
    class Rejected(
        val error: String,
        val description: String?,
        val httpCode: Int,
    ) : OidcException("provider rejected the request: HTTP $httpCode $error" + description?.let { ": $it" }.orEmpty())
}
