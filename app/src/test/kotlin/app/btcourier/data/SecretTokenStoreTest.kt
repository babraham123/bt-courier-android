package app.btcourier.data

import app.btcourier.core.auth.TokenSet
import app.btcourier.work.MapSecretStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class SecretTokenStoreTest {
    private val secrets = MapSecretStore()
    private val name = SinkSecrets.oidcTokens("abc")
    private val tokens = TokenSet("access-1", "refresh-1", expiresAtMs = 1_800_000_000_000L, scope = "openid offline_access")

    private val rotated = tokens.copy(accessToken = "access-2", refreshToken = "refresh-2")

    @Test
    fun `a rotated token set replaces the signed-in one, and null signs out`() = runBlocking {
        secrets.put(name, tokens.encode())
        val store = SecretTokenStore(secrets, name)
        assertEquals(tokens, store.load())

        // As RefreshingTokenSource calls it: synchronously, on an IO thread.
        withContext(Dispatchers.IO) { store.save(rotated) }

        assertEquals(rotated, SecretTokenStore(secrets, name).load())
        assertEquals(setOf("sink_abc_oidc_tokens"), secrets.names)

        withContext(Dispatchers.IO) { store.save(null) }
        assertNull(store.load())
        assertEquals(emptySet<String>(), secrets.names)
    }

    @Test
    fun `a rotation that lands after sign-out does not bring the credentials back`() = runBlocking {
        secrets.put(name, tokens.encode())
        val store = SecretTokenStore(secrets, name)
        store.load()

        secrets.put(name, null) // the editor's sign-out, while a refresh is in flight
        withContext(Dispatchers.IO) { store.save(rotated) }

        assertNull(store.load())
        assertEquals(emptySet<String>(), secrets.names)
    }

    @Test
    fun `a corrupt stored set reads as signed out, and toString shows no token`() = runBlocking {
        secrets.put(name, "{not json")
        val store = SecretTokenStore(secrets, name)

        assertNull(store.load())
        store.save(rotated)
        assertFalse(store.toString().contains("access-2"))
    }
}
