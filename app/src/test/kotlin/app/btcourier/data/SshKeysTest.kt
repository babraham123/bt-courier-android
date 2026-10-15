package app.btcourier.data

import app.btcourier.core.sink.sftp.SftpUploadTransport
import app.btcourier.work.InMemorySshKeys
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import java.math.BigInteger
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import java.util.Base64

class SshKeysTest {
    private val p256: ECParameterSpec =
        AlgorithmParameters
            .getInstance("EC")
            .apply { init(ECGenParameterSpec("secp256r1")) }
            .getParameterSpec(ECParameterSpec::class.java)

    private fun publicKey(
        x: BigInteger,
        y: BigInteger,
    ) = KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(ECPoint(x, y), p256)) as ECPublicKey

    private fun hex(s: String): ByteArray =
        s
            .replace(" ", "")
            .chunked(2)
            .map { it.toInt(16).toByte() }
            .toByteArray()

    private fun blobOf(line: String) = Base64.getDecoder().decode(line.split(' ')[1])

    @Test
    fun `the P-256 base point encodes as RFC 5656 section 3_1 prescribes`() {
        // SEC 2 section 2.4.2: the generator G of secp256r1.
        val gx = "6B17D1F2 E12C4247 F8BCE6E5 63A440F2 77037D81 2DEB33A0 F4A13945 D898C296"
        val gy = "4FE342E2 FE1A7F9B 8EE7EB4A 7C0F9E16 2BCE3357 6B315ECE CBB64068 37BF51F5"
        val key = publicKey(BigInteger(gx.replace(" ", ""), 16), BigInteger(gy.replace(" ", ""), 16))
        // string "ecdsa-sha2-nistp256" || string "nistp256" || string (0x04 || X || Y), each uint32-length-prefixed.
        val expected =
            hex("00000013") + "ecdsa-sha2-nistp256".toByteArray() +
                hex("00000008") + "nistp256".toByteArray() +
                hex("00000041 04") + hex(gx) + hex(gy)

        val line = openSshPublicKey(key, comment = "btcourier-test")

        assertEquals("ecdsa-sha2-nistp256 " + Base64.getEncoder().encodeToString(expected) + " btcourier-test", line)
        // Every OpenSSH nistp256 key starts with this; a quick sanity check against real-world output.
        assertEquals(
            true,
            line.startsWith("ecdsa-sha2-nistp256 AAAAE2VjZHNhLXNoYTItbmlzdHAyNTYAAAAIbmlzdHAyNTYAAABBBGsX0fLhLEJH"),
        )
    }

    @Test
    fun `coordinates with leading zero bytes are padded to 32 bytes, and the blob matches the SFTP transport's`() {
        val generator = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }
        // About 1 in 128 keys has a coordinate below 2^248.
        val key =
            generateSequence { generator.generateKeyPair().public as ECPublicKey }
                .take(20_000)
                .first { it.w.affineX.bitLength() <= 248 || it.w.affineY.bitLength() <= 248 }

        val blob = blobOf(openSshPublicKey(key))

        assertEquals(4 + 19 + 4 + 8 + 4 + 65, blob.size)
        val fingerprint =
            "SHA256:" +
                Base64.getEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(blob))
        assertEquals(SftpUploadTransport.fingerprintOf(key), fingerprint, "same key blob as sshj builds")
    }

    @Test
    fun `no comment means a two-field line`() {
        val key = InMemorySshKeys().loadOrCreate("a").public as ECPublicKey
        assertEquals(2, openSshPublicKey(key).split(' ').size)
    }

    @Test
    fun `the in-memory fake keeps one pair per alias until deleted`() {
        val keys = InMemorySshKeys()
        assertNull(keys.load("a"))
        val pair = keys.loadOrCreate("a")
        assertSame(pair, keys.loadOrCreate("a"))
        assertNotNull(keys.load("a"))
        keys.delete("a")
        assertNull(keys.load("a"))
    }
}
