package app.btcourier.core.sink.sftp

import app.btcourier.core.sink.Batch
import app.btcourier.core.sink.UploadResult
import app.btcourier.core.sink.UploadTransport
import app.btcourier.core.sink.RetryBackoff
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import net.schmizz.sshj.Config
import net.schmizz.sshj.DefaultConfig
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.common.Buffer
import net.schmizz.sshj.common.SSHRuntimeException
import net.schmizz.sshj.sftp.FileMode
import net.schmizz.sshj.sftp.OpenMode
import net.schmizz.sshj.sftp.RemoteFile
import net.schmizz.sshj.sftp.Response.StatusCode
import net.schmizz.sshj.sftp.SFTPClient
import net.schmizz.sshj.sftp.SFTPException
import net.schmizz.sshj.transport.verification.HostKeyVerifier
import net.schmizz.sshj.userauth.UserAuthException
import net.schmizz.sshj.userauth.keyprovider.KeyPairWrapper
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.util.Base64
import java.util.EnumSet

/**
 * Uploads each batch over SFTP to `<remoteDir>/<batch.name>`, authenticating with a public key
 * and accepting only the server whose host key matches [hostKeyFingerprint].
 *
 * Each upload opens one SSH session, writes `<name>.part`, renames it to `<name>` and closes
 * everything; batches are small and infrequent, so a persistent session isn't worth its
 * reconnect logic. The rename makes the object appear whole or not at all. Names are
 * content-addressed, so an existing `<name>` with our length is the same object: it is
 * [UploadResult.Acked] without re-sending. One with a different length is replaced.
 *
 * Result mapping:
 * - Uploaded, or already present with the same length: [UploadResult.Acked].
 * - Host key not matching the pin, key rejected by the server, client key unusable (e.g. a
 *   Keystore key invalidated by a lock-screen change), remote directory missing or not a
 *   directory, permission denied: [UploadResult.Failed]. These need a config change.
 * - Any other I/O error (refused, unreachable, timed out, connection dropped, server-side write
 *   failure): [UploadResult.Retry], with the HTTP transports' exponential back-off
 *   ([RetryBackoff]) over this instance's consecutive retries, reset by any non-retry result.
 *
 * The client key is used only through `java.security.Signature`, so an AndroidKeyStore key that
 * can't be exported works as-is (see [ProviderNeutralEcdsa]). Only `ecdsa-sha2-nistp256`
 * (P-256) is routed that way; other key types go through sshj's provider choice.
 *
 * @param remoteDir directory on the server; relative paths resolve against the login directory,
 *   empty means the login directory itself. It must already exist.
 * @param hostKeyFingerprint OpenSSH form, `SHA256:<base64>` as printed by `ssh-keygen -lf`, of
 *   the host key type this client negotiates; [fetchHostKeyFingerprint] returns exactly that.
 * @param connectTimeoutMs TCP connect timeout.
 * @param ioTimeoutMs bound on each socket read and each SSH or SFTP request/response, which caps
 *   how long a stalled transfer can hang.
 */
class SftpUploadTransport(
    private val host: String,
    private val port: Int = 22,
    private val username: String,
    private val remoteDir: String,
    private val privateKey: PrivateKey,
    private val publicKey: PublicKey,
    hostKeyFingerprint: String,
    private val connectTimeoutMs: Int = DEFAULT_CONNECT_TIMEOUT_MS,
    private val ioTimeoutMs: Int = DEFAULT_IO_TIMEOUT_MS,
) : UploadTransport {
    private val pin = normaliseFingerprint(hostKeyFingerprint)
    private val backoff = RetryBackoff()

    override suspend fun upload(batch: Batch): UploadResult {
        val result = runInterruptible(Dispatchers.IO) { uploadBlocking(batch) }
        if (result !is UploadResult.Retry) backoff.reset()
        return result
    }

    private fun uploadBlocking(batch: Batch): UploadResult {
        // Names come from encodeBatch and are already safe; this guards hand-built batches.
        if (batch.name.isEmpty() || '/' in batch.name || batch.name == "." || batch.name == "..") {
            return UploadResult.Failed("invalid object name ${batch.name}")
        }
        val verifier = PinnedHostKey(pin)
        return try {
            openSession(host, port, verifier, connectTimeoutMs, ioTimeoutMs).use { ssh ->
                ssh.authPublickey(username, KeyPairWrapper(publicKey, privateKey))
                ssh.newSFTPClient().use { sftp ->
                    sftp.sftpEngine.timeoutMs = ioTimeoutMs
                    put(sftp, batch)
                }
            }
        } catch (e: IOException) {
            classify(e, batch.name, verifier)
        } catch (e: SSHRuntimeException) {
            classify(e, batch.name, verifier)
        }
    }

    private fun put(sftp: SFTPClient, batch: Batch): UploadResult {
        if (remoteDir.isNotEmpty()) {
            val dir = sftp.statExistence(remoteDir)
                ?: return UploadResult.Failed("remote directory $remoteDir does not exist")
            if (dir.type != FileMode.Type.DIRECTORY) {
                return UploadResult.Failed("remote path $remoteDir is not a directory")
            }
        }
        val target = remotePath(batch.name)
        val existing = sftp.statExistence(target)
        if (existing != null && existing.size == batch.bytes.size.toLong()) return UploadResult.Acked

        val part = "$target.part"
        try {
            sftp.open(part, EnumSet.of(OpenMode.WRITE, OpenMode.CREAT, OpenMode.TRUNC)).use { write(it, batch.bytes) }
            // SFTPv3 rename refuses to overwrite and posix-rename@openssh.com isn't universal; a
            // different-length object under a content-addressed name is debris worth the brief gap.
            if (existing != null) sftp.rm(target)
            sftp.rename(part, target)
        } catch (e: IOException) {
            runCatching { sftp.rm(part) }
            throw e
        }
        return UploadResult.Acked
    }

    // sshj sends each write() as one SSH_FXP_WRITE; servers cap those (OpenSSH at 256 KiB), so
    // send standard-size chunks, pipelined.
    private fun write(file: RemoteFile, bytes: ByteArray) {
        file.RemoteFileOutputStream(0, MAX_UNCONFIRMED_WRITES).use { out ->
            var off = 0
            while (off < bytes.size) {
                val len = minOf(WRITE_CHUNK, bytes.size - off)
                out.write(bytes, off, len)
                off += len
            }
        }
    }

    private fun remotePath(name: String) = when {
        remoteDir.isEmpty() -> name
        remoteDir.endsWith('/') -> remoteDir + name
        else -> "$remoteDir/$name"
    }

    private fun classify(e: Exception, name: String, verifier: PinnedHostKey): UploadResult {
        val presented = verifier.presented
        return when {
            presented != null && presented != pin ->
                UploadResult.Failed("host key of $host:$port is $presented, not the pinned $pin")
            // Checked before UserAuthException, which sshj also raises when local signing fails.
            e.causes().any { it is GeneralSecurityException } ->
                UploadResult.Failed("client key unusable: ${e.causes().first { it is GeneralSecurityException }}")
            e is UserAuthException -> UploadResult.Failed("server rejected key authentication for user $username")
            e is SFTPException && e.statusCode in PERMANENT_SFTP_STATUS ->
                UploadResult.Failed("SFTP ${e.statusCode} for $name: ${e.message}")
            else -> backoff.retry("${e.javaClass.simpleName} for $name: ${e.message}", retryAfterMs = null)
        }
    }

    private fun Throwable.causes() = generateSequence(this) { it.cause }

    // The key objects' own toString() may print key material (the JDK's EC keys do).
    override fun toString() =
        "SftpUploadTransport(host=$host, port=$port, username=$username, remoteDir=$remoteDir, hostKey=$pin)"

    /** Accepts exactly the host key whose fingerprint equals [pin]; records what it was shown. */
    private class PinnedHostKey(private val pin: String?) : HostKeyVerifier {
        @Volatile
        var presented: String? = null
            private set

        override fun verify(hostname: String, port: Int, key: PublicKey): Boolean {
            val fingerprint = fingerprintOf(key)
            presented = fingerprint
            return fingerprint == pin
        }

        override fun findExistingAlgorithms(hostname: String, port: Int): List<String> = emptyList()
    }

    companion object {
        const val DEFAULT_CONNECT_TIMEOUT_MS = 15_000
        const val DEFAULT_IO_TIMEOUT_MS = 30_000

        private const val WRITE_CHUNK = 32 * 1024
        private const val MAX_UNCONFIRMED_WRITES = 16

        private val PERMANENT_SFTP_STATUS = setOf(StatusCode.NO_SUCH_FILE, StatusCode.PERMISSION_DENIED)

        private val config: Config by lazy {
            DefaultConfig().apply {
                keyAlgorithms = keyAlgorithms.map {
                    if (it.name == ProviderNeutralEcdsa.keyAlgorithm.name) ProviderNeutralEcdsa.keyAlgorithm else it
                }
            }
        }

        /**
         * Connects to [host]:[port] only far enough to see its host key and returns that key's
         * fingerprint in [SftpUploadTransport]'s pin format, for a trust-on-first-use prompt. It
         * is the key type this client negotiates, which a server with several host keys decides.
         *
         * @throws IOException if the server can't be reached or doesn't speak SSH.
         */
        suspend fun fetchHostKeyFingerprint(
            host: String,
            port: Int = 22,
            connectTimeoutMs: Int = DEFAULT_CONNECT_TIMEOUT_MS,
            ioTimeoutMs: Int = DEFAULT_IO_TIMEOUT_MS,
        ): String = runInterruptible(Dispatchers.IO) {
            val verifier = PinnedHostKey(pin = null)
            try {
                openSession(host, port, verifier, connectTimeoutMs, ioTimeoutMs).close()
            } catch (e: IOException) {
                // Rejecting every key aborts key exchange right after the key is shown.
                verifier.presented ?: throw e
            }
            verifier.presented ?: throw IOException("$host:$port presented no host key")
        }

        /** OpenSSH `SHA256:` fingerprint: unpadded base64 of SHA-256 over the SSH key blob. */
        fun fingerprintOf(key: PublicKey): String {
            val blob = Buffer.PlainBuffer().putPublicKey(key).compactData
            val digest = MessageDigest.getInstance("SHA-256").digest(blob)
            return "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(digest)
        }

        private fun normaliseFingerprint(fingerprint: String): String {
            val trimmed = fingerprint.trim().trimEnd('=')
            require(trimmed.startsWith("SHA256:") && trimmed.length > "SHA256:".length) {
                "host key fingerprint must be in OpenSSH SHA256:<base64> form"
            }
            return trimmed
        }

        // Closes the client if connecting throws, since the caller never gets it to close.
        private fun openSession(host: String, port: Int, verifier: HostKeyVerifier, connectTimeoutMs: Int, ioTimeoutMs: Int) =
            SSHClient(config).apply {
                connectTimeout = connectTimeoutMs
                timeout = ioTimeoutMs
                transport.timeoutMs = ioTimeoutMs
                addHostKeyVerifier(verifier)
                try {
                    connect(host, port)
                } catch (e: Throwable) {
                    runCatching { close() }
                    throw e
                }
            }
    }
}
