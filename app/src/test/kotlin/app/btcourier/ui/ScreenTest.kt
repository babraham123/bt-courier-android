package app.btcourier.ui

import android.Manifest
import android.app.Application
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.isPopup
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import app.btcourier.ui.theme.BtCourierTheme
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Base of the Robolectric screen tests: owns the compose rule and the app theme, and names nodes
 * the way the user sees them, by their string resources. The plain [Application] keeps
 * `BtCourierApp` from building the real graph (DataStore, WorkManager, companion associations).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
abstract class ScreenTest {
    @get:Rule
    val compose = createComposeRule()

    protected val app: Application get() = RuntimeEnvironment.getApplication()

    protected fun setScreen(content: @Composable () -> Unit) = compose.setContent { BtCourierTheme(content) }

    protected fun string(
        @StringRes id: Int,
        vararg args: Any,
    ): String = app.getString(id, *args)

    protected fun grantBluetoothConnect() = shadowOf(app).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT)

    protected fun onText(
        @StringRes id: Int,
        vararg args: Any,
    ): SemanticsNodeInteraction = compose.onNodeWithText(string(id, *args))

    /** Scrolls the node into view first: the screens are scrolling columns taller than the test window. */
    protected fun SemanticsNodeInteraction.tap(): SemanticsNodeInteraction = performScrollTo().performClick()

    /**
     * Nothing on screen shows [text], as a label or as a field's rendered value. A field's raw input
     * ([SemanticsProperties.InputText]) is test-only and not what the user sees, so it is not checked.
     */
    protected fun assertNoText(
        text: String,
        substring: Boolean = false,
    ) {
        val shows =
            SemanticsMatcher("shows '$text'") { node ->
                val config = node.config
                val shown =
                    config.getOrElse(SemanticsProperties.Text) { emptyList() }.map { it.text } +
                        listOfNotNull(config.getOrElseNullable(SemanticsProperties.EditableText) { null }?.text)
                shown.any { if (substring) text in it else it == text }
            }
        compose.onAllNodes(shows, useUnmergedTree = true).assertCountEquals(0)
    }

    protected fun assertNoText(
        @StringRes id: Int,
    ) = assertNoText(string(id))

    /** A switch row labelled [title]. */
    protected fun toggle(
        @StringRes title: Int,
    ): SemanticsNodeInteraction = compose.onNode(isToggleable() and hasText(string(title)))

    /** A text field or dropdown labelled [label]; its node also carries the value and placeholder. */
    protected fun field(
        @StringRes label: Int,
    ): SemanticsNodeInteraction =
        compose.onNode(hasText(string(label)) and SemanticsMatcher.keyIsDefined(SemanticsProperties.EditableText))

    /** A label and value read together, as a screen reader reads them. */
    protected fun infoRow(
        label: String,
        value: String,
    ): SemanticsNodeInteraction = compose.onNode(hasText(label) and hasText(value, substring = true))

    protected fun infoRow(
        @StringRes label: Int,
        value: String,
    ) = infoRow(string(label), value)

    /** Text in the dropdown menu that is open. */
    protected fun inOpenMenu(text: String): SemanticsMatcher = hasText(text) and hasAnyAncestor(isPopup())

    protected fun menuItem(text: String): SemanticsNodeInteraction =
        compose.onNode(inOpenMenu(text) and hasClickAction())

    protected fun menuItem(
        @StringRes id: Int,
    ) = menuItem(string(id))

    /** Opens the dropdown labelled [label] and picks [option]. */
    protected fun pick(
        @StringRes label: Int,
        @StringRes option: Int,
    ) = pick(label, string(option))

    /** Opens the dropdown labelled [label] and picks the option shown as [option]. */
    protected fun pick(
        @StringRes label: Int,
        option: String,
    ) {
        field(label).tap()
        menuItem(option).performClick()
    }

    protected fun dialogButton(
        @StringRes id: Int,
    ): SemanticsNodeInteraction =
        compose.onNode(hasText(string(id)) and hasClickAction() and hasAnyAncestor(isDialog()))

    /** Masked input: the field never exposes what was typed as text. */
    protected fun SemanticsNodeInteraction.assertIsPassword() =
        assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password))

    protected fun SemanticsNodeInteraction.assertIsInError() =
        assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Error))

    protected fun SemanticsNodeInteraction.assertIsNotInError() =
        assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.Error))
}
