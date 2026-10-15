package app.btcourier.core.sink.sftp

import app.btcourier.core.sink.Batch
import app.btcourier.core.sink.Gzip
import app.btcourier.core.sink.UploadResult
import kotlinx.coroutines.test.runTest
import org.apache.sshd.common.config.keys.KeyUtils
import org.apache.sshd.common.file.virtualfs.VirtualFileSystemFactory
import org.apache.sshd.common.keyprovider.KeyPairProvider
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.auth.pubkey.PublickeyAuthenticator
import org.apache.sshd.sftp.server.SftpSubsystemFactory
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.security.InvalidKeyException
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.Provider
import java.security.PublicKey
import java.security.SecureRandom
import java.security.Security
import java.security.Signature
import java.security.SignatureSpi
import java.security.interfaces.ECKey
import java.security.interfaces.ECPrivateKey
import java.security.spec.ECGenParameterSpec
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readBytes

class SftpUploadTransportTest {
    @TempDir
    lateinit var root: Path

    private lateinit var server: SshServer
    private val inbox get() = root.resolve("inbox")

    private val content = "{\"_file\":\"1700000000.jsonl\"}\n{\"ts\":1}\n".encodeToByteArray()
    private val batch = Batch("7", Gzip.compress(content), "wican-1700000000_0-0123456789abcdef.jsonl.gz")

    @BeforeEach
    fun setUp() = startServer()

    private fun startServer(port: Int = 0) {
        Files.createDirectories(inbox)
        server =
            SshServer.setUpDefaultServer().apply {
                host = "127.0.0.1"
                this.port = port
                keyPairProvider = KeyPairProvider.wrap(hostKey)
                publickeyAuthenticator =
                    PublickeyAuthenticator { user, key, _ -> user == USER && KeyUtils.compareKeys(key, userKey.public) }
                subsystemFactories = listOf(SftpSubsystemFactory())
                fileSystemFactory = VirtualFileSystemFactory(root)
                start()
            }
    }

    @AfterEach
    fun stopServer() = server.stop(true)

    private fun transport(
        port: Int = server.port,
        remoteDir: String = "inbox",
        privateKey: PrivateKey = OpaqueEcPrivateKey(userKey.private as ECPrivateKey),
        publicKey: PublicKey = userKey.public,
        hostKeyFingerprint: String = KeyUtils.getFingerPrint(hostKey.public),
    ) = SftpUploadTransport(
        host = "127.0.0.1",
        port = port,
        username = USER,
        remoteDir = remoteDir,
        privateKey = privateKey,
        publicKey = publicKey,
        hostKeyFingerprint = hostKeyFingerprint,
        connectTimeoutMs = 5_000,
        ioTimeoutMs = 5_000,
    )

    private fun failed(result: UploadResult) = assertInstanceOf(UploadResult.Failed::class.java, result)

    private fun retry(result: UploadResult) = assertInstanceOf(UploadResult.Retry::class.java, result)

    private fun assertInboxEmpty() = assertEquals(emptyList<Path>(), inbox.listDirectoryEntries())

    @Test
    fun `uploads under the final name with exact bytes, signing with an opaque key, and leaves no part file`() =
        runTest {
            assertEquals(UploadResult.Acked, transport().upload(batch))

            assertArrayEquals(batch.bytes, inbox.resolve(batch.name).readBytes())
            assertEquals(listOf(batch.name), inbox.listDirectoryEntries().map { it.name })
        }

    @Test
    fun `uploads a batch larger than one SFTP write intact`() =
        runTest {
            val big = batch.copy(bytes = ByteArray(1_000_000).also { SecureRandom().nextBytes(it) })
            assertEquals(UploadResult.Acked, transport().upload(big))
            assertArrayEquals(big.bytes, inbox.resolve(big.name).readBytes())
        }

    @Test
    fun `an absolute remote directory with a trailing slash works`() =
        runTest {
            assertEquals(UploadResult.Acked, transport(remoteDir = "/inbox/").upload(batch))
            assertArrayEquals(batch.bytes, inbox.resolve(batch.name).readBytes())
        }

    @Test
    fun `re-uploading an identical object acks without rewriting it`() =
        runTest {
            val sftp = transport()
            assertEquals(UploadResult.Acked, sftp.upload(batch))
            val target = inbox.resolve(batch.name)
            val old = FileTime.fromMillis(1_000_000_000_000)
            Files.setLastModifiedTime(target, old)

            assertEquals(UploadResult.Acked, sftp.upload(batch))
            assertEquals(old, Files.getLastModifiedTime(target), "object was rewritten")
            assertEquals(listOf(batch.name), inbox.listDirectoryEntries().map { it.name })
        }

    @Test
    fun `an object of a different length under the same name is replaced`() =
        runTest {
            Files.write(inbox.resolve(batch.name), byteArrayOf(1, 2, 3))
            assertEquals(UploadResult.Acked, transport().upload(batch))
            assertArrayEquals(batch.bytes, inbox.resolve(batch.name).readBytes())
            assertEquals(listOf(batch.name), inbox.listDirectoryEntries().map { it.name })
        }

    @Test
    fun `a host key not matching the pin fails and writes nothing`() =
        runTest {
            val other = KeyUtils.getFingerPrint(ecKeyPair().public)
            val result = failed(transport(hostKeyFingerprint = other).upload(batch))

            assertTrue(KeyUtils.getFingerPrint(hostKey.public) in result.reason, result.reason)
            assertInboxEmpty()
        }

    @Test
    fun `a user key the server does not accept fails`() =
        runTest {
            val stranger = ecKeyPair()
            failed(transport(privateKey = stranger.private, publicKey = stranger.public).upload(batch))
            assertInboxEmpty()
        }

    @Test
    fun `a missing remote directory fails`() =
        runTest {
            failed(transport(remoteDir = "nope").upload(batch))
            assertFalse(root.resolve("nope").exists())
        }

    @Test
    fun `an unreachable port is retried without a hint`() =
        runTest {
            val closedPort = ServerSocket(0).use { it.localPort }
            assertNull(retry(transport(port = closedPort).upload(batch)).retryAfterMs)
        }

    @Test
    fun `a server that went away and came back is retried, then acked`() =
        runTest {
            val port = server.port
            val sftp = transport(port = port)
            server.stop(true)
            retry(sftp.upload(batch))

            startServer(port)
            assertEquals(UploadResult.Acked, sftp.upload(batch))
        }

    @Test
    fun `fetchHostKeyFingerprint returns the OpenSSH SHA256 fingerprint of the server key`() =
        runTest {
            val fingerprint = SftpUploadTransport.fetchHostKeyFingerprint("127.0.0.1", server.port)

            assertEquals(KeyUtils.getFingerPrint(hostKey.public), fingerprint)
            assertTrue(fingerprint.startsWith("SHA256:"))
            assertEquals(UploadResult.Acked, transport(hostKeyFingerprint = fingerprint).upload(batch))
        }

    @Test
    fun `toString leaves out the keys`() {
        val text = transport().toString()
        assertTrue("127.0.0.1" in text, text)
        assertFalse(userKey.private.toString() in text, text)
        assertFalse((userKey.private as ECPrivateKey).s.toString(16) in text, text)
    }

    companion object {
        private const val USER = "courier"
        private val hostKey = ecKeyPair()
        private val userKey = ecKeyPair()

        private fun ecKeyPair(): KeyPair =
            KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()

        @BeforeAll
        @JvmStatic
        fun installOpaqueKeyProvider() {
            if (Security.getProvider(OpaqueKeyProvider.name) == null) Security.addProvider(OpaqueKeyProvider)
        }
    }
}

/**
 * Stands in for an AndroidKeyStore private key: an EC key whose material can't be read, so only
 * the provider that owns it can sign. Like `AndroidKeyStoreECPrivateKey`, it is an [ECKey] but
 * not an [ECPrivateKey], and has no encoding.
 */
class OpaqueEcPrivateKey(
    internal val real: ECPrivateKey,
) : PrivateKey,
    ECKey {
    override fun getAlgorithm() = "EC"

    override fun getFormat(): String? = null

    override fun getEncoded(): ByteArray? = null

    override fun getParams() = real.params
}

object OpaqueKeyProvider : Provider("OpaqueTestKeyStore", "1.0", "signs with OpaqueEcPrivateKey only") {
    private fun readResolve(): Any = OpaqueKeyProvider

    init {
        putService(
            object : Service(
                this,
                "Signature",
                "SHA256withECDSA",
                OpaqueSigner::class.java.name,
                null,
                mapOf("SupportedKeyClasses" to OpaqueEcPrivateKey::class.java.name),
            ) {
                override fun newInstance(constructorParameter: Any?) = OpaqueSigner()
            },
        )
    }
}

class OpaqueSigner : SignatureSpi() {
    private var delegate: Signature? = null

    override fun engineInitSign(privateKey: PrivateKey) {
        if (privateKey !is OpaqueEcPrivateKey) throw InvalidKeyException("not an opaque test key")
        delegate = Signature.getInstance("SHA256withECDSA", "SunEC").apply { initSign(privateKey.real) }
    }

    override fun engineInitVerify(publicKey: PublicKey) = throw InvalidKeyException("sign-only provider")

    override fun engineUpdate(b: Byte) = delegate!!.update(b)

    override fun engineUpdate(
        b: ByteArray,
        off: Int,
        len: Int,
    ) = delegate!!.update(b, off, len)

    override fun engineSign(): ByteArray = delegate!!.sign()

    override fun engineVerify(sigBytes: ByteArray) = throw UnsupportedOperationException()

    @Deprecated("JCA legacy API")
    override fun engineSetParameter(
        param: String?,
        value: Any?,
    ) = throw UnsupportedOperationException()

    @Deprecated("JCA legacy API")
    override fun engineGetParameter(param: String?) = throw UnsupportedOperationException()
}
