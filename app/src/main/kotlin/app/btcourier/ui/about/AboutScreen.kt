package app.btcourier.ui.about

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import app.btcourier.R
import app.btcourier.ui.BackButton
import app.btcourier.ui.settings.Heading

const val SOURCE_URL = "https://github.com/babraham123/bt-courier-android"
const val ISSUES_URL = "$SOURCE_URL/issues"
const val LICENSE_URL = "$SOURCE_URL/blob/main/LICENSE"

/** A project the app builds on, named as its authors name it, so the name is not translated. */
private class Credit(
    val name: String,
    val url: String,
    @StringRes val role: Int,
)

private val CREDITS =
    listOf(
        Credit("wican-fw", "https://github.com/meatpiHQ/wican-fw", R.string.about_credit_wican),
        Credit("colmi_r02_client", "https://github.com/tahnok/colmi_r02_client", R.string.about_credit_colmi),
        Credit("Gadgetbridge", "https://gadgetbridge.org", R.string.about_credit_gadgetbridge),
        Credit("Kable", "https://github.com/JuulLabs/kable", R.string.about_credit_kable),
        Credit("sshj", "https://github.com/hierynomus/sshj", R.string.about_credit_sshj),
        Credit("HiveMQ MQTT Client", "https://github.com/hivemq/hivemq-mqtt-client", R.string.about_credit_hivemq),
    )

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(
    version: String,
    onBack: () -> Unit,
) {
    val uris = LocalUriHandler.current
    Scaffold(
        topBar = {
            TopAppBar(title = { Text(stringResource(R.string.about_title)) }, navigationIcon = { BackButton(onBack) })
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineSmall)
            Text(stringResource(R.string.about_version, version), style = MaterialTheme.typography.bodySmall)
            Text(stringResource(R.string.about_summary))
            Text(stringResource(R.string.about_prealpha))
            Text(stringResource(R.string.about_privacy))
            OutlinedButton(onClick = { uris.openUri(SOURCE_URL) }) { Text(stringResource(R.string.about_source)) }
            OutlinedButton(onClick = { uris.openUri(ISSUES_URL) }) { Text(stringResource(R.string.about_issues)) }

            HorizontalDivider()
            Heading(R.string.about_license)
            Text(stringResource(R.string.about_license_body))
            OutlinedButton(onClick = { uris.openUri(LICENSE_URL) }) {
                Text(stringResource(R.string.about_license_read))
            }

            HorizontalDivider()
            Heading(R.string.about_credits)
            CREDITS.forEach { CreditLine(it) }
        }
    }
}

@Composable
private fun CreditLine(credit: Credit) {
    val linkStyle =
        TextLinkStyles(SpanStyle(MaterialTheme.colorScheme.primary, textDecoration = TextDecoration.Underline))
    val role = stringResource(credit.role)
    Text(
        buildAnnotatedString {
            withLink(LinkAnnotation.Url(credit.url, linkStyle)) { append(credit.name) }
            append(": ")
            append(role)
        },
    )
}
