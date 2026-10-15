package app.btcourier.ui.status

import android.content.Context
import android.net.Uri
import app.btcourier.log.AppLog
import app.btcourier.log.diagnosticsText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

class AndroidDiagnostics(
    context: Context,
    private val log: AppLog,
    private val storeDir: File,
) : DiagnosticsExport {
    private val resolver = context.applicationContext.contentResolver

    override suspend fun text(): String =
        withContext(Dispatchers.IO) {
            diagnosticsText(log, storeDir, System.currentTimeMillis())
        }

    override suspend fun save(
        target: Uri,
        text: String,
    ) = withContext(Dispatchers.IO) {
        val out = resolver.openOutputStream(target, "wt") ?: throw IOException("cannot open export target")
        out.use { it.write(text.encodeToByteArray()) }
    }
}
