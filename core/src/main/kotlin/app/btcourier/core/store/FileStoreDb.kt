package app.btcourier.core.store

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.TRUNCATE_EXISTING
import java.nio.file.StandardOpenOption.WRITE

/**
 * Crash-safe [StoreDb] in one directory: the app's store (in its private files dir) and the JVM
 * tests'. Sticks to `java.nio.file` calls Android has had since API 26 (no `Files.readString`).
 *
 * Layout: `index.json` holds every table except page lines; `pages/<seq>.json` holds each page's
 * lines. A commit writes new page files, then the index, each to a `.tmp` sibling that is fsynced
 * and renamed over the target. The index rename is the commit point: page files not named by the
 * committed index are uncommitted leftovers and are deleted on [open], as are `.tmp` files.
 *
 * The whole index is rewritten per commit. It holds page metadata only (~250 bytes per page
 * part), so even a full 200 MB cache of 40 KB export pages is a ~1.3 MB index; small enough to
 * rewrite on each commit at sync rates.
 */
class FileStoreDb private constructor(db: SnapshotStoreDb) : StoreDb by db {
    companion object {
        /** Opens (or creates) the store in [dir], discarding anything a crash left uncommitted. */
        fun open(dir: Path): FileStoreDb = open(dir) {}

        /** [beforeCommit] runs between writing the index `.tmp` and renaming it, to simulate crashes. */
        internal fun open(dir: Path, beforeCommit: () -> Unit): FileStoreDb {
            val persistence = FilePersistence(dir, beforeCommit)
            return FileStoreDb(SnapshotStoreDb(persistence.recover(), persistence))
        }
    }
}

private val json = Json { ignoreUnknownKeys = true }
private val linesSerializer = ListSerializer(String.serializer())

private class FilePersistence(
    private val dir: Path,
    private val beforeCommit: () -> Unit,
) : SnapshotPersistence {
    private val index = dir.resolve("index.json")
    private val pagesDir = dir.resolve("pages")

    fun recover(): DbSnapshot {
        Files.createDirectories(pagesDir)
        val snapshot = if (Files.exists(index)) {
            json.decodeFromString(DbSnapshot.serializer(), Files.readAllBytes(index).decodeToString())
        } else {
            DbSnapshot()
        }
        val committed = snapshot.pages.mapTo(HashSet()) { pageFile(it.seq).fileName.toString() }
        listOf(dir, pagesDir).forEach { d ->
            Files.list(d).use { entries ->
                entries.filter { it.fileName.toString().endsWith(".tmp") }.forEach(Files::delete)
            }
        }
        Files.list(pagesDir).use { entries ->
            entries.filter { it.fileName.toString() !in committed }.forEach(Files::delete)
        }
        return snapshot
    }

    override suspend fun commit(next: DbSnapshot, added: Map<Long, List<String>>, removed: Collection<Long>) =
        withContext(Dispatchers.IO) {
            for ((seq, lines) in added) {
                writeAtomically(pageFile(seq), json.encodeToString(linesSerializer, lines))
            }
            fsyncDir(pagesDir)
            writeAtomically(index, json.encodeToString(DbSnapshot.serializer(), next), beforeCommit)
            fsyncDir(dir)
            // After the commit point; a crash here only leaves orphans that recover() deletes.
            removed.forEach { Files.deleteIfExists(pageFile(it)) }
        }

    override suspend fun lines(seq: Long): List<String> = withContext(Dispatchers.IO) {
        json.decodeFromString(linesSerializer, Files.readAllBytes(pageFile(seq)).decodeToString())
    }

    private fun pageFile(seq: Long): Path = pagesDir.resolve("$seq.json")

    private fun writeAtomically(target: Path, content: String, beforeRename: () -> Unit = {}) {
        val tmp = target.resolveSibling("${target.fileName}.tmp")
        FileChannel.open(tmp, CREATE, TRUNCATE_EXISTING, WRITE).use { ch ->
            val buf = ByteBuffer.wrap(content.encodeToByteArray())
            while (buf.hasRemaining()) ch.write(buf)
            ch.force(true)
        }
        beforeRename()
        Files.move(tmp, target, ATOMIC_MOVE, REPLACE_EXISTING)
    }

    // Makes the rename itself durable on POSIX; some platforms can't open directories, and there
    // the rename is as durable as the platform allows anyway.
    private fun fsyncDir(d: Path) {
        try {
            FileChannel.open(d, READ).use { it.force(true) }
        } catch (_: IOException) {
        } catch (_: UnsupportedOperationException) {
        }
    }
}
