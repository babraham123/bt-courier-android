package app.btcourier.ui.settings

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.btcourier.R
import app.btcourier.data.AuthKind
import app.btcourier.data.FormatKind
import app.btcourier.data.OidcClientAuth
import app.btcourier.data.PostEncoding
import app.btcourier.data.TransportKind
import app.btcourier.data.saf.persistGrant
import app.btcourier.ui.BackButton

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SinkEditorScreen(viewModel: SinkEditorViewModel, onDone: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val form = state.form
    val leave = {
        viewModel.discard()
        onDone()
    }
    BackHandler(onBack = leave)
    // Polling for a sign-in nobody can see would only burn requests until the code expires.
    DisposableEffect(Unit) { onDispose { viewModel.cancelSignIn() } }
    LaunchedEffect(state.saved, state.deleted) { if (state.saved || state.deleted) onDone() }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(if (state.isNew) R.string.sink_add_title else R.string.sink_edit_title)) },
                navigationIcon = { BackButton(leave) },
            )
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
            Field(R.string.sink_name, form.name, SinkField.NAME in state.invalid) { v -> viewModel.edit { it.copy(name = v) } }
            Toggle(R.string.sink_enabled, R.string.sink_enabled_help, form.enabled) { v -> viewModel.edit { it.copy(enabled = v) } }
            Picker(R.string.sink_transport, TransportKind.entries, form.kind, ::transportLabel, viewModel::setTransport)

            when (form.kind) {
                TransportKind.HTTP_PUT, TransportKind.HTTP_POST -> HttpFields(state, viewModel)
                TransportKind.SFTP -> SftpFields(state, viewModel)
                TransportKind.MQTT -> MqttFields(state, viewModel)
                TransportKind.LOCAL_FOLDER -> FolderFields(state, viewModel)
            }

            Picker(R.string.sink_format, FormatKind.entries, form.format, ::formatLabel) { v -> viewModel.edit { it.copy(format = v) } }

            if (form.allowedAuth.size > 1) {
                HorizontalDivider()
                Heading(R.string.settings_auth)
                Picker(R.string.settings_auth, form.allowedAuth, form.authKind, ::authLabel, viewModel::setAuth)
                AuthFields(state, viewModel)
            }

            HorizontalDivider()
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = viewModel::save, enabled = state.loaded) { Text(stringResource(R.string.settings_save)) }
                if (!state.isNew) {
                    OutlinedButton(onClick = { confirmDelete = true }) { Text(stringResource(R.string.sink_delete)) }
                }
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            text = { Text(stringResource(R.string.sink_delete_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    viewModel.delete()
                }) { Text(stringResource(R.string.sink_delete)) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.sink_cancel)) } },
        )
    }
}

@Composable
private fun HttpFields(state: SinkEditorState, viewModel: SinkEditorViewModel) {
    val form = state.form
    val post = form.kind == TransportKind.HTTP_POST
    OutlinedTextField(
        value = form.url,
        onValueChange = { v -> viewModel.edit { it.copy(url = v) } },
        label = { Text(stringResource(R.string.settings_sink_url)) },
        placeholder = { Text(stringResource(R.string.settings_sink_url_placeholder)) },
        supportingText = {
            val help = if (post) R.string.sink_post_url_help else R.string.settings_sink_url_help
            Text(stringResource(state.urlProblem?.let(::urlProblemText) ?: help))
        },
        isError = state.urlProblem != null,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        modifier = Modifier.fillMaxWidth(),
    )
    if (post) {
        Picker(R.string.sink_post_encoding, PostEncoding.entries, form.postEncoding, ::postEncodingLabel) { v ->
            viewModel.edit { it.copy(postEncoding = v) }
        }
        Toggle(R.string.sink_name_query, R.string.sink_name_query_help, form.nameQuery) { v -> viewModel.edit { it.copy(nameQuery = v) } }
    } else {
        Toggle(R.string.settings_compress, R.string.settings_compress_help, form.compress) { v -> viewModel.edit { it.copy(compress = v) } }
    }
}

@Composable
private fun SftpFields(state: SinkEditorState, viewModel: SinkEditorViewModel) {
    val form = state.form
    val context = LocalContext.current
    Field(R.string.sink_host, form.host, SinkField.HOST in state.invalid, KeyboardType.Uri) { v -> viewModel.edit { it.copy(host = v) } }
    Field(R.string.sink_port, form.sftpPort, SinkField.PORT in state.invalid, KeyboardType.Number) { v -> viewModel.edit { it.copy(sftpPort = v) } }
    Field(R.string.settings_username, form.sftpUser, SinkField.USERNAME in state.invalid) { v -> viewModel.edit { it.copy(sftpUser = v) } }
    Field(R.string.sink_remote_dir, form.remoteDir, help = R.string.sink_remote_dir_help) { v -> viewModel.edit { it.copy(remoteDir = v) } }

    Text(stringResource(R.string.sink_public_key), style = MaterialTheme.typography.labelLarge)
    val publicKey = state.publicKey
    when {
        publicKey != null -> {
            SelectionContainer { Text(publicKey, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) }
            Text(stringResource(R.string.sink_public_key_help), style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = { copy(context, publicKey) }) { Text(stringResource(R.string.sink_copy)) }
        }
        state.keyError != null -> ErrorText(stringResource(R.string.sink_key_error, state.keyError))
        else -> Text(stringResource(R.string.pairing_working))
    }

    Text(stringResource(R.string.sink_host_key), style = MaterialTheme.typography.labelLarge)
    val pinned = form.hostKeyFingerprint
    if (pinned != null) {
        SelectionContainer { Text(pinned, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) }
        OutlinedButton(onClick = viewModel::forgetHostKey) { Text(stringResource(R.string.sink_host_key_forget)) }
    } else {
        Text(stringResource(R.string.sink_host_key_missing), style = MaterialTheme.typography.bodySmall)
        when (val hostKey = state.hostKey) {
            HostKeyState.Idle, is HostKeyState.Failed -> {
                if (hostKey is HostKeyState.Failed) ErrorText(hostKey.message)
                OutlinedButton(onClick = viewModel::fetchHostKey) { Text(stringResource(R.string.sink_host_key_fetch)) }
            }
            HostKeyState.Fetching -> Text(stringResource(R.string.pairing_working))
            is HostKeyState.Presented -> {
                SelectionContainer { Text(hostKey.fingerprint, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) }
                Text(stringResource(R.string.sink_host_key_confirm), style = MaterialTheme.typography.bodySmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = viewModel::trustHostKey) { Text(stringResource(R.string.sink_host_key_trust)) }
                    OutlinedButton(onClick = viewModel::forgetHostKey) { Text(stringResource(R.string.sink_cancel)) }
                }
            }
        }
    }
}

@Composable
private fun MqttFields(state: SinkEditorState, viewModel: SinkEditorViewModel) {
    val form = state.form
    Field(R.string.sink_host, form.host, SinkField.HOST in state.invalid, KeyboardType.Uri) { v -> viewModel.edit { it.copy(host = v) } }
    Toggle(R.string.sink_tls, R.string.sink_tls_help, form.mqttTls, viewModel::setMqttTls)
    Field(R.string.sink_port, form.mqttPort, SinkField.PORT in state.invalid, KeyboardType.Number) { v -> viewModel.edit { it.copy(mqttPort = v) } }
    Field(R.string.sink_client_id, form.mqttClientId, SinkField.MQTT_CLIENT_ID in state.invalid, help = R.string.sink_client_id_help) { v ->
        viewModel.edit { it.copy(mqttClientId = v) }
    }
    Field(R.string.sink_base_topic, form.baseTopic, SinkField.BASE_TOPIC in state.invalid, help = R.string.sink_base_topic_help) { v ->
        viewModel.edit { it.copy(baseTopic = v) }
    }
}

@Composable
private fun FolderFields(state: SinkEditorState, viewModel: SinkEditorViewModel) {
    val context = LocalContext.current
    val treeUri = state.form.treeUri
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            try {
                persistGrant(context, uri)
                viewModel.edit { it.copy(treeUri = uri.toString()) }
            } catch (_: SecurityException) {
                // The provider offers no persistable grant; the folder would be lost on reboot.
                viewModel.edit { it.copy(treeUri = null) }
            }
        }
    }
    val folderName = remember(treeUri) {
        treeUri?.let { runCatching { DocumentFile.fromTreeUri(context, it.toUri())?.name }.getOrNull() ?: it }
    }
    Text(folderName ?: stringResource(R.string.sink_folder_none), color = if (SinkField.FOLDER in state.invalid) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
    OutlinedButton(onClick = { pick.launch(treeUri?.toUri()) }) { Text(stringResource(R.string.sink_folder_pick)) }
}

@Composable
private fun AuthFields(state: SinkEditorState, viewModel: SinkEditorViewModel) {
    val form = state.form
    when (form.authKind) {
        AuthKind.NONE -> Unit
        AuthKind.BASIC -> {
            Field(R.string.settings_username, form.basicUser, SinkField.BASIC_USER in state.invalid) { v -> viewModel.edit { it.copy(basicUser = v) } }
            SecretField(R.string.settings_password, form.secretInput, state.hasStoredSecret, viewModel::clearSecret) { v ->
                viewModel.edit { it.copy(secretInput = v) }
            }
        }
        AuthKind.BEARER -> SecretField(R.string.settings_token, form.secretInput, state.hasStoredSecret, viewModel::clearSecret) { v ->
            viewModel.edit { it.copy(secretInput = v) }
        }
        AuthKind.OIDC -> OidcFields(state, viewModel)
    }
}

@Composable
private fun OidcFields(state: SinkEditorState, viewModel: SinkEditorViewModel) {
    val form = state.form
    val context = LocalContext.current
    OutlinedTextField(
        value = form.oidcIssuer,
        onValueChange = { v -> viewModel.edit { it.copy(oidcIssuer = v) } },
        label = { Text(stringResource(R.string.sink_oidc_issuer)) },
        placeholder = { Text(stringResource(R.string.sink_oidc_issuer_placeholder)) },
        supportingText = { state.issuerProblem?.let { Text(stringResource(urlProblemText(it))) } },
        isError = state.issuerProblem != null,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        modifier = Modifier.fillMaxWidth(),
    )
    Field(R.string.sink_oidc_client_id, form.oidcClientId, SinkField.OIDC_CLIENT_ID in state.invalid) { v -> viewModel.edit { it.copy(oidcClientId = v) } }
    Picker(R.string.sink_oidc_client_auth, OidcClientAuth.entries, form.oidcClientAuth, ::clientAuthLabel) { v ->
        viewModel.edit { it.copy(oidcClientAuth = v) }
    }
    if (form.oidcClientAuth != OidcClientAuth.NONE) {
        SecretField(R.string.sink_oidc_client_secret, form.oidcClientSecretInput, state.hasStoredClientSecret, onClear = null) { v ->
            viewModel.edit { it.copy(oidcClientSecretInput = v) }
        }
    }
    when (val signIn = state.signIn) {
        SignInState.SignedOut, is SignInState.Failed -> {
            if (signIn is SignInState.Failed) ErrorText(signIn.message)
            Button(onClick = viewModel::signIn) { Text(stringResource(R.string.sink_oidc_sign_in)) }
        }
        SignInState.Starting -> Text(stringResource(R.string.pairing_working))
        is SignInState.AwaitingUser -> {
            Text(stringResource(R.string.sink_oidc_instructions, signIn.verificationUri))
            SelectionContainer { Text(signIn.userCode, style = MaterialTheme.typography.headlineSmall, fontFamily = FontFamily.Monospace) }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { open(context, signIn.openUri) }) { Text(stringResource(R.string.sink_oidc_open)) }
                OutlinedButton(onClick = { copy(context, signIn.userCode) }) { Text(stringResource(R.string.sink_oidc_copy_code)) }
                TextButton(onClick = viewModel::cancelSignIn) { Text(stringResource(R.string.sink_cancel)) }
            }
        }
        SignInState.SignedIn -> {
            Text(stringResource(R.string.sink_oidc_signed_in))
            OutlinedButton(onClick = viewModel::signOut) { Text(stringResource(R.string.sink_oidc_sign_out)) }
        }
    }
}

@Composable
private fun Field(
    @StringRes label: Int,
    value: String,
    isError: Boolean = false,
    keyboard: KeyboardType = KeyboardType.Text,
    @StringRes help: Int? = null,
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(stringResource(label)) },
        supportingText = help?.let { { Text(stringResource(it)) } },
        isError = isError,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = keyboard),
        modifier = Modifier.fillMaxWidth(),
    )
}

/** Never shows the stored value; a placeholder says one exists and empty input keeps it. */
@Composable
private fun SecretField(@StringRes label: Int, value: String, stored: Boolean, onClear: (() -> Unit)?, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(stringResource(label)) },
        placeholder = { if (stored) Text(stringResource(R.string.settings_secret_stored)) },
        supportingText = { Text(stringResource(R.string.settings_secret_help)) },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        modifier = Modifier.fillMaxWidth(),
    )
    if (stored && onClear != null) TextButton(onClick = onClear) { Text(stringResource(R.string.settings_secret_clear)) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> Picker(@StringRes label: Int, options: List<T>, selected: T, labelOf: (T) -> Int, onSelect: (T) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = stringResource(labelOf(selected)),
            onValueChange = {},
            readOnly = true,
            label = { Text(stringResource(label)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            singleLine = true,
            modifier = Modifier
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                .fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(stringResource(labelOf(option))) },
                    onClick = {
                        onSelect(option)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun ErrorText(text: String) {
    Text(text, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
}

private fun copy(context: Context, text: String) {
    val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText(context.getString(R.string.app_name), text))
}

private fun open(context: Context, uri: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, uri.toUri()))
    } catch (_: ActivityNotFoundException) {
        // No browser; the URL and code are on screen to type elsewhere.
    }
}

@StringRes
internal fun transportLabel(kind: TransportKind): Int = when (kind) {
    TransportKind.HTTP_PUT -> R.string.transport_http_put
    TransportKind.HTTP_POST -> R.string.transport_http_post
    TransportKind.SFTP -> R.string.transport_sftp
    TransportKind.MQTT -> R.string.transport_mqtt
    TransportKind.LOCAL_FOLDER -> R.string.transport_local_folder
}

@StringRes
internal fun formatLabel(kind: FormatKind): Int = when (kind) {
    FormatKind.JSONL -> R.string.format_jsonl
    FormatKind.CSV -> R.string.format_csv
    FormatKind.PROMETHEUS -> R.string.format_prometheus
}

@StringRes
private fun authLabel(kind: AuthKind): Int = when (kind) {
    AuthKind.NONE -> R.string.settings_auth_none
    AuthKind.BASIC -> R.string.settings_auth_basic
    AuthKind.BEARER -> R.string.settings_auth_bearer
    AuthKind.OIDC -> R.string.settings_auth_oidc
}

@StringRes
private fun postEncodingLabel(encoding: PostEncoding): Int = when (encoding) {
    PostEncoding.GZIP_FILE -> R.string.post_encoding_gzip_file
    PostEncoding.CONTENT_ENCODING_GZIP -> R.string.post_encoding_content_encoding
    PostEncoding.IDENTITY -> R.string.post_encoding_identity
}

@StringRes
private fun clientAuthLabel(method: OidcClientAuth): Int = when (method) {
    OidcClientAuth.NONE -> R.string.oidc_client_public
    OidcClientAuth.SECRET_BASIC -> R.string.oidc_client_secret_basic
    OidcClientAuth.SECRET_POST -> R.string.oidc_client_secret_post
}

@StringRes
private fun urlProblemText(problem: SinkUrlProblem): Int = when (problem) {
    SinkUrlProblem.INVALID -> R.string.settings_url_invalid
    SinkUrlProblem.CREDENTIALS_IN_URL -> R.string.settings_url_credentials
    SinkUrlProblem.QUERY_OR_FRAGMENT -> R.string.settings_url_query
    SinkUrlProblem.CLEARTEXT -> R.string.settings_url_cleartext
}
