package app.btcourier.data.saf

/**
 * An in-memory [DocumentTree] that enforces the interface contract (no silent renames on a name
 * clash) and logs every mutation so tests can tell a rewrite from a no-op.
 */
class FakeDocumentTree : DocumentTree {
    class Doc(val name: String, val bytes: ByteArray, val mimeType: String)

    private val docs = linkedMapOf<String, Doc>()
    private var nextId = 0

    /** Mutations in order, e.g. `create a.part`, `write a.part`, `rename a.part -> a`, `delete a`. */
    val ops = mutableListOf<String>()

    /** How often [list] ran, i.e. full folder listings a provider would have served. */
    var listings = 0
        private set

    var present = true
    var writable = true

    /** Every call after this throws [DocumentTreeException.AccessLost], as SAF does once a grant goes. */
    var revoked = false

    /** The next [write] throws [DocumentTreeException.Io]. */
    var failNextWrite = false

    /** Current content by name. */
    val files: Map<String, ByteArray> get() = docs.values.associate { it.name to it.bytes }

    fun mimeTypeOf(name: String): String? = docs.values.firstOrNull { it.name == name }?.mimeType

    /** Seeds a document without logging it, e.g. a previous run's leftovers. */
    fun put(name: String, bytes: ByteArray, mimeType: String = "application/octet-stream") {
        docs["${nextId++}"] = Doc(name, bytes, mimeType)
    }

    override fun exists() = present && !revoked

    override fun isWritable() = exists() && writable

    override fun list(): List<DocumentHandle> = guarded {
        listings++
        docs.map { (id, doc) -> handle(id, doc) }
    }

    override fun create(name: String, mimeType: String): DocumentHandle = guarded {
        requireFree(name)
        val id = "${nextId++}"
        docs[id] = Doc(name, ByteArray(0), mimeType)
        ops += "create $name"
        handle(id, docs.getValue(id))
    }

    override fun write(handle: DocumentHandle, bytes: ByteArray): Unit = guarded {
        val doc = doc(handle)
        if (failNextWrite) {
            failNextWrite = false
            // A failed write may leave a partial document behind, as a real provider can.
            docs[handle.id] = Doc(doc.name, bytes.copyOf(bytes.size / 2), doc.mimeType)
            throw DocumentTreeException.Io("disk full")
        }
        docs[handle.id] = Doc(doc.name, bytes.copyOf(), doc.mimeType)
        ops += "write ${doc.name}"
    }

    override fun rename(handle: DocumentHandle, newName: String): DocumentHandle = guarded {
        val doc = doc(handle)
        requireFree(newName)
        docs[handle.id] = Doc(newName, doc.bytes, doc.mimeType)
        ops += "rename ${doc.name} -> $newName"
        handle(handle.id, docs.getValue(handle.id))
    }

    override fun delete(handle: DocumentHandle): Unit = guarded {
        val doc = doc(handle)
        docs.remove(handle.id)
        ops += "delete ${doc.name}"
    }

    override fun toString() = "FakeDocumentTree"

    private fun requireFree(name: String) {
        if (docs.values.any { it.name == name }) throw DocumentTreeException.Io("$name exists")
    }

    private fun doc(handle: DocumentHandle) =
        docs[handle.id] ?: throw DocumentTreeException.Io("no document ${handle.id}")

    private fun handle(id: String, doc: Doc) = DocumentHandle(id, doc.name, doc.bytes.size.toLong())

    private fun <T> guarded(block: () -> T): T {
        if (revoked) throw DocumentTreeException.AccessLost("permission revoked")
        if (!present) throw DocumentTreeException.Io("folder missing")
        return block()
    }
}
