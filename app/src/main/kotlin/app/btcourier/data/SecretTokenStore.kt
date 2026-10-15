package app.btcourier.data

import app.btcourier.core.auth.TokenSet
import app.btcourier.core.auth.TokenStore
import kotlinx.coroutines.runBlocking

/**
 * A [TokenStore] keeping one sink's [TokenSet] as JSON in [secrets] under [name].
 *
 * [TokenStore] is synchronous because `RefreshingTokenSource` calls it under a monitor from
 * `Auth.applyTo`, which core runs on `Dispatchers.IO`. Bridging with [runBlocking] is safe there:
 * it parks that IO thread only for one small file read or write, the secret store's mutex is held
 * by nobody that waits on this thread, and its own `withContext(Dispatchers.IO)` takes another
 * thread from the 64-thread IO pool rather than needing this one.
 *
 * A stored set that no longer parses reads as signed out, so the user is asked to sign in again
 * instead of every upload crashing.
 *
 * Only `RefreshingTokenSource` writes through [save], and only to rotate a set it loaded earlier;
 * the first set of a sign-in is stored by the sink editor directly. So a rotation that finds the
 * slot empty is one that raced a sign-out (WorkManager cancellation is cooperative, a refresh in
 * flight still completes) and is dropped rather than resurrecting the deleted credentials.
 */
class SecretTokenStore(private val secrets: SecretStore, private val name: String) : TokenStore {
    override fun load(): TokenSet? {
        val json = runBlocking { secrets.get(name) } ?: return null
        return try {
            TokenSet.decode(json)
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    override fun save(tokens: TokenSet?) = runBlocking {
        if (tokens != null && secrets.get(name) == null) return@runBlocking
        secrets.put(name, tokens?.encode())
    }

    override fun toString() = "SecretTokenStore($name)"
}
