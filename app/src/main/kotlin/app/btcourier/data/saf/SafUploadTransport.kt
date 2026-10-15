package app.btcourier.data.saf

import app.btcourier.core.sink.Batch
import app.btcourier.core.sink.UploadResult
import app.btcourier.core.sink.UploadTransport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

const val FOLDER_ACCESS_LOST = "folder access lost; pick it again"

/**
 * The `LocalFile` sink's transport: writes each batch into a user-picked SAF folder as
 * `<name>.part`, then renames it to `<name>`, so a reader never sees a partial object.
 *
 * A transport lives for one upload round, which delivers its batches one at a time. It lists the
 * folder once per round and keeps that name index current with its own changes, because a
 * provider query per lookup would cost a backlog of B batches B full listings of the folder.
 */
class SafUploadTransport(
    private val tree: DocumentTree,
) : UploadTransport {
    private var index: MutableMap<String, DocumentHandle>? = null

    override suspend fun upload(batch: Batch): UploadResult =
        withContext(Dispatchers.IO) {
            try {
                if (!tree.exists() || !tree.isWritable()) return@withContext UploadResult.Failed(FOLDER_ACCESS_LOST)
                val names = names()
                val existing = names[batch.name]
                // Names carry a content hash, so an equal size means the earlier upload completed.
                if (existing != null &&
                    existing.size == batch.bytes.size.toLong()
                ) {
                    return@withContext UploadResult.Acked
                }

                val partName = "${batch.name}.part"
                // A leftover part from a crash is reused: creating beside it would fail on the name clash.
                val part =
                    names[partName] ?: tree.create(partName, mimeTypeFor(batch.name)).also { names[partName] = it }
                tree.write(part, batch.bytes)
                // SAF has no atomic replace, and the rename must not clash with a stale copy.
                existing?.let {
                    tree.delete(it)
                    names.remove(batch.name)
                }
                names[batch.name] = tree.rename(part, batch.name)
                names.remove(partName)
                UploadResult.Acked
            } catch (e: DocumentTreeException) {
                // A failure (e.g. a name clash with a document added behind our back) means the folder
                // may not match the index any more; the next batch lists it again.
                index = null
                when (e) {
                    is DocumentTreeException.AccessLost -> UploadResult.Failed(FOLDER_ACCESS_LOST)
                    is DocumentTreeException.Io -> UploadResult.Retry("local write failed: ${e.message}")
                }
            }
        }

    /** Name to document; a duplicate name (some providers allow them) resolves to the first listed. */
    private fun names(): MutableMap<String, DocumentHandle> =
        index ?: tree
            .list()
            .reversed()
            .associateByTo(mutableMapOf()) { it.name }
            .also { index = it }

    override fun toString() = "SafUploadTransport($tree)"
}

fun mimeTypeFor(name: String): String = if (name.endsWith(".gz")) "application/gzip" else "application/octet-stream"
