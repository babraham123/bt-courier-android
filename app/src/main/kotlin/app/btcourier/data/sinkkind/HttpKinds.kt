package app.btcourier.data.sinkkind

import app.btcourier.R
import app.btcourier.core.sink.http.HttpPostUploadTransport
import app.btcourier.core.sink.http.HttpPutUploadTransport
import app.btcourier.data.AuthKind
import app.btcourier.data.PostEncoding
import app.btcourier.data.SinkConfig
import app.btcourier.data.SinkCredentials
import app.btcourier.data.SinkEnvironment
import app.btcourier.data.TransportConfig
import app.btcourier.data.TransportKind

/** The field key both HTTP kinds keep their URL under, so switching between them keeps it. */
private const val URL = "url"

private fun urlField(help: Int) =
    TransportField.Text(URL, R.string.settings_sink_url, help, FieldKeyboard.URI, R.string.settings_sink_url_placeholder)

private fun urlProblems(values: FieldValues, allowQuery: Boolean) =
    problemsOf(URL to validateSinkUrl(values[URL], allowQuery, required = true)?.let(FieldProblem::Url))

/** `PUT <url>/<batch name>`; HTTP takes every auth kind. */
object HttpPutKind : SinkTransportKind {
    const val URL_KEY = URL
    const val COMPRESS = "compress"

    override val kind = TransportKind.HTTP_PUT
    override val label = R.string.transport_http_put
    override val allowedAuth = AuthKind.entries
    override val fields = listOf(
        urlField(R.string.settings_sink_url_help),
        TransportField.Toggle(COMPRESS, R.string.settings_compress, R.string.settings_compress_help),
    )

    override fun defaults() = FieldValues(COMPRESS to "true")

    override fun toValues(config: TransportConfig): FieldValues {
        val t = config as TransportConfig.HttpPut
        return FieldValues(URL to t.url, COMPRESS to t.compress.toString())
    }

    override fun validate(values: FieldValues) = urlProblems(values, allowQuery = false)

    override fun toConfig(values: FieldValues) = TransportConfig.HttpPut(values[URL].trim(), values.flag(COMPRESS))

    override suspend fun build(config: SinkConfig, credentials: SinkCredentials, env: SinkEnvironment): HttpPutUploadTransport {
        val t = config.transport as TransportConfig.HttpPut
        return HttpPutUploadTransport(t.url.trim(), env.http, credentials.httpAuth(config, env.http), t.compress)
    }
}

/** `POST <url>` with the batch name in a header; the URL may carry a query. */
object HttpPostKind : SinkTransportKind {
    const val URL_KEY = URL
    const val ENCODING = "encoding"
    const val NAME_QUERY = "nameQuery"

    override val kind = TransportKind.HTTP_POST
    override val label = R.string.transport_http_post
    override val allowedAuth = AuthKind.entries
    override val fields = listOf(
        urlField(R.string.sink_post_url_help),
        TransportField.Choice(
            ENCODING,
            R.string.sink_post_encoding,
            PostEncoding.entries.map { ChoiceOption(it.name, it.label) },
        ),
        TransportField.Toggle(NAME_QUERY, R.string.sink_name_query, R.string.sink_name_query_help),
    )

    override fun defaults() = FieldValues(ENCODING to PostEncoding.GZIP_FILE.name, NAME_QUERY to "false")

    override fun toValues(config: TransportConfig): FieldValues {
        val t = config as TransportConfig.HttpPost
        return FieldValues(URL to t.url, ENCODING to t.encoding.name, NAME_QUERY to t.nameQuery.toString())
    }

    override fun validate(values: FieldValues) = urlProblems(values, allowQuery = true)

    override fun toConfig(values: FieldValues) =
        TransportConfig.HttpPost(values[URL].trim(), PostEncoding.valueOf(values[ENCODING]), values.flag(NAME_QUERY))

    override suspend fun build(config: SinkConfig, credentials: SinkCredentials, env: SinkEnvironment): HttpPostUploadTransport {
        val t = config.transport as TransportConfig.HttpPost
        return HttpPostUploadTransport(t.url.trim(), env.http, credentials.httpAuth(config, env.http), t.encoding.core, t.nameQuery)
    }

    private val PostEncoding.label
        get() = when (this) {
            PostEncoding.GZIP_FILE -> R.string.post_encoding_gzip_file
            PostEncoding.CONTENT_ENCODING_GZIP -> R.string.post_encoding_content_encoding
            PostEncoding.IDENTITY -> R.string.post_encoding_identity
        }
}
