package app.btcourier.core.transport.blehttp

/** Bytes from a hex dump such as `57 02 01 00`. */
fun hex(dump: String): ByteArray =
    dump
        .split(' ')
        .filter { it.isNotEmpty() }
        .map { it.toInt(16).toByte() }
        .toByteArray()

fun le16(value: Int): ByteArray = byteArrayOf(value.toByte(), (value ushr 8).toByte())

/** Deterministic non-repeating-looking content, so misplaced bytes show up in comparisons. */
fun patterned(size: Int): ByteArray = ByteArray(size) { (it * 31 + it / 251).toByte() }
