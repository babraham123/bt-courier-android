package app.btcourier.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import app.btcourier.R
import app.btcourier.ui.about.AboutScreen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AboutScreenTest : ScreenTest() {
    private val opened = mutableListOf<String>()
    private var backed = false

    private fun show() {
        val uris =
            object : UriHandler {
                override fun openUri(uri: String) {
                    opened += uri
                }
            }
        setScreen {
            CompositionLocalProvider(LocalUriHandler provides uris) {
                AboutScreen(version = "1.2.3", onBack = { backed = true })
            }
        }
    }

    @Test
    fun `shows the version and the license`() {
        show()

        onText(R.string.about_version, "1.2.3").assertIsDisplayed()
        onText(R.string.about_license_body).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `the buttons open the repository, its issues and its license`() {
        show()

        onText(R.string.about_source).tap()
        onText(R.string.about_issues).tap()
        onText(R.string.about_license_read).tap()

        val repo = "https://github.com/babraham123/bt-courier-android"
        assertEquals(listOf(repo, "$repo/issues", "$repo/blob/main/LICENSE"), opened)
    }

    @Test
    fun `back leaves the screen`() {
        show()

        compose.onNodeWithContentDescription(string(R.string.nav_back)).performClick()

        assertTrue(backed)
    }
}
