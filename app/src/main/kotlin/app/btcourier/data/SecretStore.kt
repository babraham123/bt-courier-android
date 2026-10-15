package app.btcourier.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Small secrets (sink passwords, tokens, OIDC token sets) kept apart from plain settings. Names
 * are `[A-Za-z0-9_-]+`; [SinkCredentials] owns the names sinks use.
 */
interface SecretStore {
    /** The secret under [name], or null if none is stored or it can no longer be decrypted. */
    suspend fun get(name: String): String?

    /** Stores [value] under [name]; null deletes it. */
    suspend fun put(
        name: String,
        value: String?,
    )
}

/**
 * [SecretStore] encrypting each secret with AES-256-GCM under a key that never leaves the
 * AndroidKeyStore, one `<name>.bin` file (`version | iv length | iv | ciphertext`) per secret in
 * [dir], which should be app-private (`noBackupFilesDir`): the key does not survive a restore to
 * another device, so a backed-up ciphertext would be useless there anyway.
 *
 * A file that fails to decrypt (key wiped by a lock-screen reset, or corrupt) reads as absent, so
 * the user is asked again rather than the app crashing.
 *
 * @param key supplies the key; the default creates it in AndroidKeyStore on first use. Tests pass
 *   a software key, since the AndroidKeyStore provider does not exist on the JVM.
 */
class KeystoreSecretStore(
    private val dir: File,
    private val key: () -> SecretKey = ::androidKeystoreKey,
) : SecretStore {
    private val mutex = Mutex()

    override suspend fun get(name: String): String? =
        mutex.withLock {
            withContext(Dispatchers.IO) {
                val file = fileOf(name)
                if (!file.exists()) return@withContext null
                try {
                    decrypt(file.readBytes())
                } catch (_: GeneralSecurityException) {
                    null
                } catch (_: IllegalArgumentException) {
                    null
                }
            }
        }

    override suspend fun put(
        name: String,
        value: String?,
    ) = mutex.withLock {
        withContext(Dispatchers.IO) {
            val file = fileOf(name)
            if (value == null) {
                file.delete()
                return@withContext
            }
            dir.mkdirs()
            val tmp = File(dir, "${file.name}.tmp")
            tmp.writeBytes(encrypt(value))
            if (!tmp.renameTo(file)) throw IOException("could not replace secret file")
        }
    }

    private fun fileOf(name: String): File {
        require(name.matches(SAFE_NAME)) { "secret names are [A-Za-z0-9_-]+" }
        return File(dir, "$name.bin")
    }

    private fun encrypt(value: String): ByteArray {
        // AndroidKeyStore keys refuse caller-chosen IVs (randomized encryption), so the cipher picks it.
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key()) }
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(value.encodeToByteArray())
        return byteArrayOf(FORMAT_VERSION, iv.size.toByte()) + iv + ciphertext
    }

    private fun decrypt(bytes: ByteArray): String {
        require(bytes.size > 2 && bytes[0] == FORMAT_VERSION) { "unknown secret format" }
        val ivLength = bytes[1].toInt()
        require(ivLength in 12..16 && bytes.size > 2 + ivLength) { "bad secret header" }
        val iv = bytes.copyOfRange(2, 2 + ivLength)
        val cipher =
            Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, iv))
            }
        return cipher.doFinal(bytes, 2 + ivLength, bytes.size - 2 - ivLength).decodeToString()
    }

    private companion object {
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val TAG_BITS = 128
        const val FORMAT_VERSION: Byte = 1
        val SAFE_NAME = Regex("[A-Za-z0-9_-]+")
    }
}

private const val KEYSTORE = "AndroidKeyStore"
private const val KEY_ALIAS = "btcourier.secrets"

private fun androidKeystoreKey(): SecretKey {
    val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
    (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
    val spec =
        KeyGenParameterSpec
            .Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
    return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).apply { init(spec) }.generateKey()
}
