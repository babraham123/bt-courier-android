package app.btcourier.data.saf

import java.io.IOException

/**
 * A document in a [DocumentTree].
 *
 * @property id opaque to callers; only the tree that issued it can resolve it.
 * @property size in bytes, or null when the provider does not report one.
 */
data class DocumentHandle(val id: String, val name: String, val size: Long?)

/**
 * The flat, user-picked folder a [SafUploadTransport] writes into. Methods block; every failure is
 * a [DocumentTreeException].
 */
interface DocumentTree {
    /** Whether the folder is still there and this app still holds a grant for it. */
    fun exists(): Boolean

    fun isWritable(): Boolean

    /** Every document in the folder, in one provider query. */
    fun list(): List<DocumentHandle>

    /** Creates an empty document; fails if [name] is taken rather than picking another name. */
    fun create(name: String, mimeType: String): DocumentHandle

    /** Replaces the document's content with [bytes]. */
    fun write(handle: DocumentHandle, bytes: ByteArray)

    /** Fails if [newName] is taken rather than picking another name. */
    fun rename(handle: DocumentHandle, newName: String): DocumentHandle

    fun delete(handle: DocumentHandle)
}

sealed class DocumentTreeException(message: String, cause: Throwable? = null) : IOException(message, cause) {
    /** The grant was revoked or the folder is gone; only picking the folder again helps. */
    class AccessLost(message: String, cause: Throwable? = null) : DocumentTreeException(message, cause)

    /** Anything else; may clear up on its own (provider busy, storage full, ...). */
    class Io(message: String, cause: Throwable? = null) : DocumentTreeException(message, cause)
}
