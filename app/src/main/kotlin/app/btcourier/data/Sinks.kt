package app.btcourier.data

import app.btcourier.core.auth.Auth
import app.btcourier.core.sink.CompositeSink
import app.btcourier.core.sink.Jsonl
import app.btcourier.core.sink.Sink
import app.btcourier.core.sink.http.HttpPutUploadTransport
import okhttp3.OkHttpClient

/**
 * Store ack key of the HTTP PUT sink. It stays the same when the URL changes: batches the old URL
 * acked were deleted already, and the rest should go to the new one.
 */
const val HTTP_SINK_ID = "http"

/** Ids of the sinks [settings] configures; the store keeps every batch until all of them ack it. */
fun configuredSinkIds(settings: AppSettings): Set<String> =
    if (settings.sinkUrl.isBlank()) emptySet() else setOf(HTTP_SINK_ID)

/**
 * The sinks [settings] configures, with their secret from [secrets].
 *
 * A Basic or Bearer sink whose secret is missing is still built (with an empty secret): the
 * server's 401 then surfaces as a failed upload on the status screen instead of the data silently
 * waiting for a sink that never appears.
 */
suspend fun buildSinks(settings: AppSettings, secrets: SecretStore, client: OkHttpClient): List<Sink> {
    if (HTTP_SINK_ID !in configuredSinkIds(settings)) return emptyList()
    val secret = if (settings.sinkAuth == SinkAuth.NONE) "" else secrets.get(SecretStore.UPLOAD_SECRET).orEmpty()
    val auth = when (settings.sinkAuth) {
        SinkAuth.NONE -> Auth.None
        SinkAuth.BASIC -> Auth.Basic(settings.sinkUser, secret)
        SinkAuth.BEARER -> Auth.Bearer(secret)
    }
    val transport = HttpPutUploadTransport(settings.sinkUrl.trim(), client, auth, settings.sinkCompress)
    return listOf(CompositeSink(HTTP_SINK_ID, transport, Jsonl))
}
