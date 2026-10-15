package app.btcourier.core.sink

import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater

/**
 * gzip (RFC 1952) with a fixed header: no file name, mtime 0, OS "unknown". Written by hand
 * rather than with `GZIPOutputStream` because that header's OS byte differs between JDK and
 * Android releases, and batch bytes must not depend on where they were encoded.
 */
object Gzip {
    private val header = byteArrayOf(0x1f, 0x8b.toByte(), 8, 0, 0, 0, 0, 0, 0, 0xff.toByte())

    fun compress(data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(data.size / 4 + 32)
        out.write(header)
        val deflater = Deflater(Deflater.DEFAULT_COMPRESSION, true)
        try {
            deflater.setInput(data)
            deflater.finish()
            val buf = ByteArray(16 * 1024)
            while (!deflater.finished()) {
                val n = deflater.deflate(buf)
                out.write(buf, 0, n)
            }
        } finally {
            deflater.end()
        }
        val crc = CRC32().apply { update(data) }.value
        writeIntLe(out, crc.toInt())
        writeIntLe(out, data.size)
        return out.toByteArray()
    }

    private fun writeIntLe(
        out: ByteArrayOutputStream,
        v: Int,
    ) {
        for (shift in 0 until 32 step 8) out.write((v ushr shift) and 0xff)
    }
}
