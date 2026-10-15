package app.btcourier.ui

import app.btcourier.ui.settings.SinkUrlProblem
import app.btcourier.ui.settings.validateSinkUrl
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class SinkUrlValidationTest {
    @ParameterizedTest
    @ValueSource(
        strings = [
            "",
            "   ",
            "https://sink.example.org/ingest/",
            "https://203.0.113.5:8443/",
            "https://localhost:8443/",
            "https://192.168.1.20/dav/",
            "https://[fd12:3456::1]/",
        ],
    )
    fun `blank or https to any host is accepted`(url: String) {
        assertEquals(null, validateSinkUrl(url))
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "http://sink.example.org/",
            "http://203.0.113.5/",
            "http://localhost:8080/",
            "http://127.0.0.1/",
            "http://10.1.2.3/up/",
            "http://192.168.1.20:8080/dav/",
            "http://169.254.10.10/",
            "http://[::1]/",
            "http://[fd12:3456::1]/",
            "http://nas.local/",
        ],
    )
    fun `http is refused for every host, private and loopback included`(url: String) {
        assertEquals(SinkUrlProblem.CLEARTEXT, validateSinkUrl(url))
    }

    @ParameterizedTest
    @ValueSource(strings = ["sink.example.org", "ftp://sink.example.org/", "https://", "not a url"])
    fun `non-http URLs are invalid`(url: String) {
        assertEquals(SinkUrlProblem.INVALID, validateSinkUrl(url))
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "https://user:pass@sink.example.org/",
            "https://user@sink.example.org/",
            "http://user:pass@192.168.1.20/",
        ],
    )
    fun `credentials in the URL are refused`(url: String) {
        assertEquals(SinkUrlProblem.CREDENTIALS_IN_URL, validateSinkUrl(url))
    }

    @ParameterizedTest
    @ValueSource(strings = ["https://sink.example.org/up?token=x", "https://sink.example.org/up#frag"])
    fun `a query or fragment is refused`(url: String) {
        assertEquals(SinkUrlProblem.QUERY_OR_FRAGMENT, validateSinkUrl(url))
    }
}
