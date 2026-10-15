package app.btcourier.fakes

/** Deterministic test content that does not repeat at frame-sized periods, so misplaced bytes show up. */
fun patterned(size: Int): ByteArray = ByteArray(size) { (it * 31 + it / 251).toByte() }
