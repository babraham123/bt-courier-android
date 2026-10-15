package app.btcourier.core.sink

import app.btcourier.core.auth.OidcException
import app.btcourier.core.store.SealedBatch
import java.security.MessageDigest

/**
 * A delivery target: a [Format] that turns sealed lines into upload bytes and an
 * [UploadTransport] that ships them. Keeping the two orthogonal lets any format go over any
 * transport (e.g. JSONL over HTTP PUT, CSV to a local file).
 */
interface Sink {
    /** Stable id used for per-sink acks in the store. */
    val id: String
    val format: Format
    val transport: UploadTransport

    fun encode(batch: SealedBatch): Batch = encodeBatch(batch, format)

    /**
     * An [OidcException] from a refreshing bearer token (sign-in revoked, client rejected) is a
     * configuration failure, not a transport error: transports only retry [java.io.IOException],
     * and letting it escape would crash the upload job instead of surfacing "sign in again".
     */
    suspend fun upload(batch: SealedBatch): UploadResult =
        try {
            transport.upload(encode(batch))
        } catch (e: OidcException) {
            UploadResult.Failed("${batch.id}: ${e.message}")
        }
}

/** The plain [Format] × [UploadTransport] product; most sinks need nothing more. */
data class CompositeSink(
    override val id: String,
    override val transport: UploadTransport,
    override val format: Format,
) : Sink

/**
 * One upload unit: encoded (and compressed) bytes plus the object name to store them under.
 *
 * Encoding must be deterministic so a retry produces the same [bytes] and receivers can dedupe
 * by content hash.
 *
 * @property id store batch id this was encoded from.
 * @property name object name including the content extension, e.g. `src-1_0-<sha256>.jsonl.gz`.
 */
data class Batch(
    val id: String,
    val bytes: ByteArray,
    val name: String,
) {
    // Arrays compare by identity by default; batches are compared by content.
    override fun equals(other: Any?): Boolean =
        other is Batch && id == other.id && name == other.name && bytes.contentEquals(other.bytes)

    override fun hashCode(): Int = 31 * (31 * id.hashCode() + name.hashCode()) + bytes.contentHashCode()
}

/**
 * Encodes [batch] with [format] and gzips it. Named
 * `<sourceId>-<firstCursor>-<sha256 prefix>.<extension>.gz`, with ids sanitised for file systems
 * and object stores. The hash covers the uncompressed encoding, so the name stays stable even if
 * a zlib upgrade changes the compressed bytes between a failed upload and its retry.
 */
fun encodeBatch(batch: SealedBatch, format: Format): Batch {
    val content = format.encode(batch.lines, batch.sourceId)
    val hash = MessageDigest.getInstance("SHA-256").digest(content).joinToString("") { "%02x".format(it) }
    val name = "${sanitise(batch.sourceId)}-${sanitise(batch.firstCursor)}-${hash.take(16)}.${format.extension}.gz"
    return Batch(batch.id, Gzip.compress(content), name)
}

private val unsafe = Regex("[^A-Za-z0-9._-]")

private fun sanitise(s: String): String = unsafe.replace(s, "_")
