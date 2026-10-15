package app.btcourier.core.sink

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.TRUNCATE_EXISTING
import java.nio.file.StandardOpenOption.WRITE

/**
 * Writes each batch to [dir] as `<name>.part`, fsyncs it, then renames it to `<name>`, so a
 * reader never sees a partial object and a re-upload atomically replaces an identical one.
 */
class LocalDirectoryUploadTransport(
    private val dir: Path,
) : UploadTransport {
    override suspend fun upload(batch: Batch): UploadResult =
        withContext(Dispatchers.IO) {
            try {
                Files.createDirectories(dir)
                val target = dir.resolve(batch.name)
                val part = dir.resolve("${batch.name}.part")
                FileChannel.open(part, CREATE, TRUNCATE_EXISTING, WRITE).use { ch ->
                    val buf = ByteBuffer.wrap(batch.bytes)
                    while (buf.hasRemaining()) ch.write(buf)
                    ch.force(true)
                }
                Files.move(part, target, ATOMIC_MOVE, REPLACE_EXISTING)
                UploadResult.Acked
            } catch (e: IOException) {
                // A full disk or a missing mount can clear up; the store keeps the batch meanwhile.
                UploadResult.Retry("local write failed: ${e.message}")
            }
        }
}
