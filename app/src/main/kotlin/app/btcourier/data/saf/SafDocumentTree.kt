package app.btcourier.data.saf

import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import java.io.FileOutputStream
import java.io.IOException

/**
 * Keeps access to a tree picked with `ACTION_OPEN_DOCUMENT_TREE` across reboots. Call with the
 * picker's result before handing the URI to [SafDocumentTree].
 */
fun persistGrant(context: Context, treeUri: Uri) {
    context.contentResolver.takePersistableUriPermission(
        treeUri,
        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
    )
}

/**
 * Gives up what [persistGrant] took, so the tree stops counting against the app's persisted-grant
 * limit and leaves the system's list of apps with access. A grant already gone is not an error.
 */
fun releaseGrant(context: Context, treeUri: Uri) {
    try {
        context.contentResolver.releasePersistableUriPermission(
            treeUri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
        )
    } catch (_: SecurityException) {
        // Not held any more (revoked in system settings, or the provider was uninstalled).
    }
}

/** A [DocumentTree] over the top level of a persisted SAF tree grant. */
class SafDocumentTree(context: Context, private val treeUri: Uri) : DocumentTree {
    private val context = context.applicationContext
    private val resolver = this.context.contentResolver
    private val rootUri: Uri by lazy {
        DocumentsContract.buildDocumentUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri))
    }

    override fun exists(): Boolean = call {
        hasGrant() && root()?.let { it.exists() && it.isDirectory } == true
    }

    override fun isWritable(): Boolean = call { hasGrant() && root()?.canWrite() == true }

    override fun list(): List<DocumentHandle> = call {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri))
        query(children).use { c ->
            generateSequence { if (c.moveToNext()) c else null }
                .map { handle(DocumentsContract.buildDocumentUriUsingTree(treeUri, it.getString(0)), it) }
                .toList()
        }
    }

    override fun create(name: String, mimeType: String): DocumentHandle = call {
        val uri = DocumentsContract.createDocument(resolver, rootUri, mimeType, name)
            ?: throw DocumentTreeException.Io("could not create $name")
        val created = describe(uri)
        if (created.name == name) return@call created
        // ExternalStorageProvider appends an extension for mimeType ("x.gz.part" -> "x.gz.part.gz")
        // and dedupes clashes as "x (1)"; a rename back fixes the former and fails on the latter.
        try {
            renameExact(created, name)
        } catch (e: DocumentTreeException.Io) {
            DocumentsContract.deleteDocument(resolver, uri)
            throw e
        }
    }

    override fun write(handle: DocumentHandle, bytes: ByteArray): Unit = call {
        val out = resolver.openOutputStream(handle.id.toUri(), "wt")
            ?: throw DocumentTreeException.Io("could not open ${handle.name}")
        out.use {
            it.write(bytes)
            it.flush()
            // Pipe-backed (e.g. cloud) providers cannot fsync and commit on close instead.
            if (it is FileOutputStream) runCatching { it.fd.sync() }
        }
    }

    override fun rename(handle: DocumentHandle, newName: String): DocumentHandle = call { renameExact(handle, newName) }

    override fun delete(handle: DocumentHandle): Unit = call {
        if (!DocumentsContract.deleteDocument(resolver, handle.id.toUri())) {
            throw DocumentTreeException.Io("could not delete ${handle.name}")
        }
    }

    override fun toString() = "SafDocumentTree(${treeUri.authority}/${treeUri.lastPathSegment})"

    private fun renameExact(handle: DocumentHandle, newName: String): DocumentHandle {
        val uri = DocumentsContract.renameDocument(resolver, handle.id.toUri(), newName)
            ?: throw DocumentTreeException.Io("could not rename ${handle.name}")
        val renamed = describe(uri)
        if (renamed.name != newName) {
            throw DocumentTreeException.Io("provider renamed ${handle.name} to ${renamed.name}, not $newName")
        }
        return renamed
    }

    private fun hasGrant() = resolver.persistedUriPermissions.any { it.uri == treeUri && it.isWritePermission }

    private fun root(): DocumentFile? = DocumentFile.fromTreeUri(context, treeUri)

    private fun describe(uri: Uri): DocumentHandle = query(uri).use { c ->
        if (!c.moveToFirst()) throw DocumentTreeException.Io("document vanished: $uri")
        handle(uri, c)
    }

    private fun query(uri: Uri): Cursor =
        resolver.query(uri, PROJECTION, null, null, null) ?: throw DocumentTreeException.Io("provider returned no cursor")

    private fun handle(uri: Uri, c: Cursor) =
        DocumentHandle(uri.toString(), c.getString(1), if (c.isNull(2)) null else c.getLong(2))

    /** Maps whatever a provider throws across IPC onto [DocumentTreeException]. */
    private inline fun <T> call(block: () -> T): T = try {
        block()
    } catch (e: DocumentTreeException) {
        throw e
    } catch (e: SecurityException) {
        throw DocumentTreeException.AccessLost(e.message ?: "permission denied", e)
    } catch (e: IOException) {
        throw DocumentTreeException.Io(e.message ?: e.javaClass.simpleName, e)
    } catch (e: RuntimeException) {
        throw DocumentTreeException.Io(e.message ?: e.javaClass.simpleName, e)
    }

    private companion object {
        val PROJECTION = arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_SIZE)
    }
}
