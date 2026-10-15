package app.btcourier.core.auth

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.IOException
import java.util.Base64

/** Discovery plus the RFC 8628 device flow against an Authelia-shaped server. */
class DeviceFlowTest : OidcServerTest() {
    private val t0 = 1_800_000_000_000L
    private var now = t0
    private val sleeps = mutableListOf<Long>()

    private fun deviceAuthorization(client: OidcClient = publicClient) =
        DeviceAuthorization(provider, client, this.client, nowMs = { now }, sleep = { sleeps += it; now += it })

    private fun respondDeviceCode(expiresIn: Long = 600, interval: Long? = 5) = respondJson(
        200,
        """
        {"device_code":"dc-secret-123","user_code":"WDJB-MJHT",
         "verification_uri":"https://auth.example.com/consent/device",
         "verification_uri_complete":"https://auth.example.com/consent/device?user_code=WDJB-MJHT",
         "expires_in":$expiresIn${interval?.let { ""","interval":$it""" }.orEmpty()}}
        """.trimIndent(),
    )

    private fun metadataJson(issuer: String) =
        """
        {"issuer":"$issuer",
         "token_endpoint":"$issuer/api/oidc/token",
         "device_authorization_endpoint":"$issuer/api/oidc/device-authorization",
         "revocation_endpoint":"$issuer/api/oidc/revocation",
         "end_session_endpoint":"$issuer/logout",
         "grant_types_supported":["authorization_code","refresh_token","urn:ietf:params:oauth:grant-type:device_code"]}
        """.trimIndent()

    @Test
    fun `discovery reads the provider metadata from the well-known path under the issuer`() = runTest {
        respondJson(200, metadataJson(issuer))

        val metadata = OidcDiscovery(client).discover(issuer)

        assertEquals("/auth/.well-known/openid-configuration", takeRequest().target)
        assertEquals(
            OidcProviderMetadata(
                issuer = issuer,
                tokenEndpoint = server.url("/auth/api/oidc/token"),
                deviceAuthorizationEndpoint = server.url("/auth/api/oidc/device-authorization"),
                revocationEndpoint = server.url("/auth/api/oidc/revocation"),
                endSessionEndpoint = server.url("/auth/logout"),
            ),
            metadata,
        )
    }

    @Test
    fun `discovery forgives a trailing slash on the typed issuer`() = runTest {
        respondJson(200, metadataJson(issuer))
        assertEquals(issuer, OidcDiscovery(client).discover("$issuer/").issuer)
        assertEquals("/auth/.well-known/openid-configuration", takeRequest().target)
    }

    @Test
    fun `discovery rejects a document for another issuer`() = runTest {
        respondJson(200, metadataJson("https://evil.example.com"))

        val e = assertThrows<OidcException.IssuerMismatch> { OidcDiscovery(client).discover(issuer) }

        assertEquals(issuer, e.expected)
        assertEquals("https://evil.example.com", e.actual)
    }

    @Test
    fun `discovery rejects a provider without the device grant, a missing document, and treats 5xx as transient`() = runTest {
        respondJson(200, """{"issuer":"$issuer","token_endpoint":"$issuer/api/oidc/token"}""")
        respond(404)
        respond(503)
        val discovery = OidcDiscovery(client)

        assertThrows<OidcException.InvalidMetadata> { discovery.discover(issuer) }
        assertThrows<OidcException.InvalidMetadata> { discovery.discover(issuer) }
        assertThrows<IOException> { discovery.discover(issuer) }
    }

    @Test
    fun `a full device flow polls per RFC 8628 through pending and slow_down to tokens`() = runTest {
        respondDeviceCode(expiresIn = 600, interval = 5)
        respondError("authorization_pending")
        respondError("slow_down")
        respondTokens("at-1", "rt-1", expiresIn = 3600, scope = "openid offline_access")
        val flow = deviceAuthorization()

        val grant = flow.start(listOf("openid", "offline_access"))

        val authRequest = takeRequest()
        assertEquals("POST", authRequest.method)
        assertEquals("/auth/api/oidc/device-authorization", authRequest.target)
        assertEquals(mapOf("client_id" to "bt-courier", "scope" to "openid offline_access"), authRequest.form())
        assertEquals("WDJB-MJHT", grant.userCode)
        assertEquals("https://auth.example.com/consent/device", grant.verificationUri)
        assertEquals("https://auth.example.com/consent/device?user_code=WDJB-MJHT", grant.verificationUriComplete)
        assertEquals(t0 + 600_000, grant.expiresAtMs)
        assertEquals(5, grant.intervalSeconds)

        val tokens = flow.poll(grant)

        // §3.5: wait the interval before each poll; slow_down adds 5 s for this and later polls.
        assertEquals(listOf(5_000L, 5_000L, 10_000L), sleeps)
        val expectedPoll = mapOf(
            "grant_type" to "urn:ietf:params:oauth:grant-type:device_code",
            "device_code" to "dc-secret-123",
            "client_id" to "bt-courier",
        )
        repeat(3) {
            val poll = takeRequest()
            assertEquals("/auth/api/oidc/token", poll.target)
            assertEquals(expectedPoll, poll.form())
        }
        assertEquals(TokenSet("at-1", "rt-1", t0 + 20_000 + 3_600_000, "openid offline_access"), tokens)
        for (s in listOf(grant.toString(), tokens.toString())) {
            assertFalse("dc-secret" in s || "at-1" in s || "rt-1" in s, s)
        }
    }

    @Test
    fun `the default interval is 5 s and a failed poll doubles it`() = runTest {
        respondDeviceCode(interval = null)
        respond(503)
        respondError("authorization_pending")
        respondTokens("at-1", null, expiresIn = 60)
        val flow = deviceAuthorization()

        val tokens = flow.poll(flow.start())

        assertEquals(listOf(5_000L, 10_000L, 10_000L), sleeps)
        assertNull(tokens.refreshToken)
    }

    @Test
    fun `access_denied ends the flow`() = runTest {
        respondDeviceCode()
        respondError("access_denied")
        val flow = deviceAuthorization()
        assertThrows<OidcException.AccessDenied> { flow.poll(flow.start()) }
    }

    @Test
    fun `expired_token ends the flow`() = runTest {
        respondDeviceCode()
        respondError("expired_token")
        val flow = deviceAuthorization()
        assertThrows<OidcException.DeviceCodeExpired> { flow.poll(flow.start()) }
    }

    @Test
    fun `polling stops at the local expiry without another request`() = runTest {
        respondDeviceCode(expiresIn = 12, interval = 5)
        respondError("authorization_pending")
        respondError("authorization_pending")
        val flow = deviceAuthorization()

        assertThrows<OidcException.DeviceCodeExpired> { flow.poll(flow.start()) }

        assertEquals(3, server.requestCount, "device authorization plus polls at 5 s and 10 s, none at 15 s")
    }

    @ParameterizedTest
    @ValueSource(strings = ["invalid_client", "unauthorized_client", "invalid_grant"])
    fun `other OAuth errors end the flow as rejected`(error: String) = runTest {
        respondDeviceCode()
        respondError(error, "client is not allowed this grant")
        val flow = deviceAuthorization()

        val e = assertThrows<OidcException.Rejected> { flow.poll(flow.start()) }

        assertEquals(error, e.error)
        assertEquals(400, e.httpCode)
    }

    @Test
    fun `client_secret_basic form-encodes id and secret into the Basic header and keeps them out of the body`() = runTest {
        respondDeviceCode()
        val secret = "s3cret:pässword"

        deviceAuthorization(OidcClient("bt courier", secret, ClientAuthMethod.ClientSecretBasic)).start()

        val req = takeRequest()
        // RFC 6749 §2.3.1: application/x-www-form-urlencoded, then Basic.
        val encoded = Base64.getEncoder().encodeToString("bt+courier:s3cret%3Ap%C3%A4ssword".toByteArray())
        assertEquals("Basic $encoded", req.headers["Authorization"])
        assertEquals(mapOf("scope" to "openid offline_access"), req.form())
    }

    @Test
    fun `client_secret_post sends id and secret in the body`() = runTest {
        respondDeviceCode()

        deviceAuthorization(OidcClient("bt-courier", "s3cret", ClientAuthMethod.ClientSecretPost)).start(emptyList())

        val req = takeRequest()
        assertNull(req.headers["Authorization"])
        assertEquals(mapOf("client_id" to "bt-courier", "client_secret" to "s3cret"), req.form())
    }
}
