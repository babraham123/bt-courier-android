package app.btcourier.data

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File
import javax.crypto.KeyGenerator

/**
 * The file format and failure handling of [KeystoreSecretStore], with a software AES key standing
 * in for the AndroidKeyStore one (same cipher, same calls; the provider exists only on a device).
 */
class KeystoreSecretStoreTest {
    @TempDir
    lateinit var dir: File

    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    private fun store(k: javax.crypto.SecretKey = key) = KeystoreSecretStore(dir, { k })

    @Test
    fun `a stored secret reads back, survives reopening and is not stored in clear`() =
        runTest {
            store().put("upload", "pässwörd")

            assertEquals("pässwörd", store().get("upload"))
            assertFalse(
                File(dir, "upload.bin").readBytes().decodeToString(throwOnInvalidSequence = false).contains("pässwörd"),
            )
        }

    @Test
    fun `overwriting replaces and null deletes`() =
        runTest {
            val s = store()
            s.put("upload", "one")
            s.put("upload", "two")
            assertEquals("two", s.get("upload"))

            s.put("upload", null)

            assertNull(s.get("upload"))
            assertFalse(File(dir, "upload.bin").exists())
        }

    @Test
    fun `a secret that no longer decrypts reads as absent`() =
        runTest {
            store().put("upload", "token")
            val otherKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

            assertNull(store(otherKey).get("upload"), "key lost, e.g. after a lock-screen reset")

            File(dir, "upload.bin").writeBytes(byteArrayOf(1, 12, 0))
            assertNull(store().get("upload"), "truncated file")
        }

    @Test
    fun `names that could escape the directory are rejected`() =
        runTest {
            assertThrows<IllegalArgumentException> { store().put("../x", "v") }
        }
}
