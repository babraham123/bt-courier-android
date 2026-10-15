package app.btcourier.core.sink.sftp

import com.hierynomus.sshj.key.KeyAlgorithm
import com.hierynomus.sshj.key.KeyAlgorithms
import net.schmizz.sshj.common.Factory
import net.schmizz.sshj.common.KeyType
import net.schmizz.sshj.common.SSHRuntimeException
import net.schmizz.sshj.signature.Signature
import net.schmizz.sshj.signature.SignatureECDSA
import java.security.GeneralSecurityException
import java.security.PrivateKey
import java.security.PublicKey

/**
 * `ecdsa-sha2-nistp256` with signing done by `java.security.Signature.getInstance(alg)` and no
 * provider name.
 *
 * sshj's own [SignatureECDSA] asks for the algorithm from its registered provider by name ("BC"
 * whenever Bouncy Castle is on the classpath, which sshj itself pulls in). A provider-pinned
 * `Signature` can only use key material it can read, so `initSign` with an AndroidKeyStore key
 * (opaque, `getEncoded() == null`) throws `InvalidKeyException`. Leaving the provider unnamed
 * lets JCA's delayed provider selection hand the key to the provider that owns it.
 *
 * Verification (the server's host-key signature during key exchange) and the DER-to-SSH signature
 * encoding stay with sshj's implementation: those only touch public data.
 */
internal object ProviderNeutralEcdsa {
    private const val NAME = "ecdsa-sha2-nistp256"
    private const val JCA_ALGORITHM = "SHA256withECDSA"

    val keyAlgorithm: Factory.Named<KeyAlgorithm> =
        KeyAlgorithms.Factory(NAME, SignatureFactory, KeyType.ECDSA256)

    private object SignatureFactory : Factory.Named<Signature> {
        override fun getName() = NAME

        override fun create(): Signature = NeutralSignature()
    }

    private class NeutralSignature : Signature {
        private val sshj = SignatureECDSA(JCA_ALGORITHM, NAME)
        private var signer: java.security.Signature? = null

        override fun getSignatureName(): String = sshj.signatureName

        override fun initVerify(publicKey: PublicKey) {
            signer = null
            sshj.initVerify(publicKey)
        }

        override fun initSign(privateKey: PrivateKey) {
            signer =
                try {
                    java.security.Signature
                        .getInstance(JCA_ALGORITHM)
                        .apply { initSign(privateKey) }
                } catch (e: GeneralSecurityException) {
                    throw SSHRuntimeException(e)
                }
        }

        override fun update(data: ByteArray) = update(data, 0, data.size)

        override fun update(
            data: ByteArray,
            off: Int,
            len: Int,
        ) {
            val s = signer ?: return sshj.update(data, off, len)
            try {
                s.update(data, off, len)
            } catch (e: GeneralSecurityException) {
                throw SSHRuntimeException(e)
            }
        }

        override fun sign(): ByteArray {
            val s = checkNotNull(signer) { "initSign was not called" }
            return try {
                s.sign()
            } catch (e: GeneralSecurityException) {
                throw SSHRuntimeException(e)
            }
        }

        override fun encode(signature: ByteArray): ByteArray = sshj.encode(signature)

        override fun verify(signature: ByteArray): Boolean = sshj.verify(signature)
    }
}
