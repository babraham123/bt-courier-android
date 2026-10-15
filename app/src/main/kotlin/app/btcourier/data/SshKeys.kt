package app.btcourier.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.util.Base64

/**
 * SFTP client keys, one P-256 pair per alias; [SinkCredentials] picks each sink's alias. A seam so
 * the settings and the sink builder run on the JVM, where AndroidKeyStore does not exist.
 */
interface SshKeys {
    /** The pair under [alias], or null if none exists. */
    fun load(alias: String): KeyPair?

    /** The pair under [alias], generating it first if needed. */
    fun loadOrCreate(alias: String): KeyPair

    fun delete(alias: String)
}

/**
 * [SshKeys] in AndroidKeyStore. The private key never leaves secure hardware; signing goes through
 * `java.security.Signature`, which core's SFTP transport routes to the Keystore provider.
 *
 * No user authentication is required for the key: uploads sign in the background, often with the
 * screen locked. A lock-screen reset may still invalidate it; the upload then fails with "client
 * key unusable" and deleting and recreating the sink's key is the fix.
 */
class AndroidKeystoreSshKeys : SshKeys {
    private fun keyStore() = KeyStore.getInstance(KEYSTORE).apply { load(null) }

    override fun load(alias: String): KeyPair? {
        val ks = keyStore()
        val private = ks.getKey(alias, null) as? PrivateKey ?: return null
        val public = ks.getCertificate(alias)?.publicKey ?: return null
        return KeyPair(public, private)
    }

    override fun loadOrCreate(alias: String): KeyPair = load(alias) ?: generate(alias)

    override fun delete(alias: String) {
        val ks = keyStore()
        if (ks.containsAlias(alias)) ks.deleteEntry(alias)
    }

    private fun generate(alias: String): KeyPair {
        val spec =
            KeyGenParameterSpec
                .Builder(alias, KeyProperties.PURPOSE_SIGN)
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_SHA256)
                .setUserAuthenticationRequired(false)
                .build()
        return KeyPairGenerator
            .getInstance(KeyProperties.KEY_ALGORITHM_EC, KEYSTORE)
            .apply {
                initialize(spec)
            }.generateKeyPair()
    }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
    }
}

/**
 * The `authorized_keys` line for a P-256 public key: `ecdsa-sha2-nistp256 <base64 blob>`, the blob
 * being RFC 5656 §3.1's `string "ecdsa-sha2-nistp256" || string "nistp256" || string Q` with Q the
 * uncompressed SEC1 point `0x04 || X || Y`, coordinates left-padded to 32 bytes.
 *
 * @throws IllegalArgumentException for a key on another curve.
 */
fun openSshPublicKey(
    key: ECPublicKey,
    comment: String? = null,
): String {
    require(key.params.curve.field.fieldSize == P256_BYTES * 8) { "only P-256 keys are supported" }
    val point = byteArrayOf(0x04) + fixedWidth(key.w.affineX) + fixedWidth(key.w.affineY)
    val blob =
        ByteArrayOutputStream()
            .also { bytes ->
                DataOutputStream(bytes).use { out ->
                    out.sshString(KEY_TYPE.toByteArray(Charsets.US_ASCII))
                    out.sshString(CURVE.toByteArray(Charsets.US_ASCII))
                    out.sshString(point)
                }
            }.toByteArray()
    val line = KEY_TYPE + " " + Base64.getEncoder().encodeToString(blob)
    return if (comment.isNullOrBlank()) line else "$line $comment"
}

private const val KEY_TYPE = "ecdsa-sha2-nistp256"
private const val CURVE = "nistp256"
private const val P256_BYTES = 32

/** RFC 4251 §5 `string`: uint32 big-endian length, then the bytes. */
private fun DataOutputStream.sshString(bytes: ByteArray) {
    writeInt(bytes.size)
    write(bytes)
}

// BigInteger.toByteArray is minimal two's complement: a leading 0x00 sign byte, or fewer bytes.
private fun fixedWidth(n: BigInteger): ByteArray {
    val raw = n.toByteArray().dropWhile { it == 0.toByte() }.toByteArray()
    require(raw.size <= P256_BYTES) { "coordinate wider than the curve" }
    return ByteArray(P256_BYTES - raw.size) + raw
}
