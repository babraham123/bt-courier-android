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
import app.btcourier.data.TransportKind
import app.btcourier.data.saf.persistGrant
import app.btcourier.data.sinkkind.FieldKeyboard
import app.btcourier.data.sinkkind.FieldProblem
import app.btcourier.data.sinkkind.SinkTransportKind
import app.btcourier.data.sinkkind.SinkUrlProblem
import app.btcourier.data.sinkkind.TransportCapability
import app.btcourier.data.sinkkind.TransportField
import app.btcourier.data.sinkkind.module
import app.btcourier.ui.BackButton
import app.btcourier.ui.Toggle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SinkEditorScreen(
    viewModel: SinkEditorViewModel,
    onDone: () -> Unit,
) {
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
                title = {
                    Text(
                        stringResource(if (state.isNew) R.string.sink_add_title else R.string.sink_edit_title),
                    )
                },
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
            Field(
                SinkField.NAME.label,
                form.name,
                SinkField.NAME in state.shownInvalid,
            ) { v -> viewModel.edit { it.copy(name = v) } }
            Toggle(
                R.string.sink_enabled,
                R.string.sink_enabled_help,
                form.enabled,
            ) { v -> viewModel.edit { it.copy(enabled = v) } }
            Picker(
                R.string.sink_transport,
                TransportKind.entries,
                form.kind,
                { it.module.label },
                viewModel::setTransport,
            )

            TransportFields(state, viewModel)

            Picker(R.string.sink_format, FormatKind.entries, form.format, ::formatLabel) { v ->
                viewModel.edit {
                    it.copy(format = v)
                }
            }

            if (form.allowedAuth.size > 1) {
                HorizontalDivider()
                Heading(R.string.settings_auth)
                Picker(R.string.settings_auth, form.allowedAuth, form.authKind, ::authLabel, viewModel::setAuth)
                AuthFields(state, viewModel)
            }

            HorizontalDivider()
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = viewModel::save,
                    enabled = state.canSave,
                ) { Text(stringResource(R.string.settings_save)) }
                if (!state.isNew) {
                    OutlinedButton(onClick = { confirmDelete = true }) { Text(stringResource(R.string.sink_delete)) }
                }
            }
            if (state.blockedByUntouched) SaveNeeds(state)
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
            dismissButton = {
                TextButton(
                    onClick = { confirmDelete = false },
                ) { Text(stringResource(R.string.sink_cancel)) }
            },
        )
    }
}

/** The current kind's fields, then its capabilities, as its [SinkTransportKind] lists them. */
@Composable
private fun TransportFields(
    state: SinkEditorState,
    viewModel: SinkEditorViewModel,
) {
    val transport = state.form.transport
    val values = state.form.values
    for (field in transport.fields) {
        if (!field.visibleWhen(values)) continue
        when (field) {
            is TransportField.Text -> {
                TextField(
                    field,
                    values[field.key],
                    state.shownTransportProblems[field.key],
                ) { viewModel.setField(field.key, it) }
            }

            is TransportField.Toggle -> {
                Toggle(field.label, field.help, values.flag(field.key)) { viewModel.setField(field.key, it.toString()) }
            }

            is TransportField.Choice -> {
                val selected = field.options.firstOrNull { it.value == values[field.key] } ?: field.options.first()
                Picker(field.label, field.options, selected, { it.label }) { viewModel.setField(field.key, it.value) }
            }
        }
    }
    for (capability in transport.capabilities) {
        when (capability) {
            TransportCapability.ClientKey -> {
                ClientKeySection(state)
            }

            is TransportCapability.HostKeyPin -> {
                HostKeySection(state, values.orNull(capability.fingerprintKey), viewModel)
            }

            is TransportCapability.FolderPick -> {
                FolderSection(
                    values.orNull(capability.treeUriKey),
                    capability.treeUriKey in state.shownTransportProblems,
                ) {
                    viewModel.setField(capability.treeUriKey, it)
                }
            }
        }
    }
}

/** Names the inputs whose problems disable Save, for when none of them is drawn yet. */
@Composable
private fun SaveNeeds(state: SinkEditorState) {
    val fields = state.invalid + listOfNotNull(SinkField.OIDC_ISSUER.takeIf { state.issuerProblem != null })
    // In screen order: the name, the transport's fields, then the auth fields.
    val labels =
        listOfNotNull(SinkField.NAME.label.takeIf { SinkField.NAME in fields }) +
            state.transportProblems.keys.mapNotNull(state.form.transport::labelOf) +
            fields.sorted().filter { it != SinkField.NAME }.map { it.label }
    Text(
        stringResource(R.string.sink_save_needs, labels.map { stringResource(it) }.joinToString()),
        style = MaterialTheme.typography.bodySmall,
    )
}

@Composable
private fun TextField(
    field: TransportField.Text,
    value: String,
    problem: FieldProblem?,
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(stringResource(field.label)) },
        placeholder = field.placeholder?.let { { Text(stringResource(it)) } },
        supportingText =
            ((problem as? FieldProblem.Url)?.problem?.let(::urlProblemText) ?: field.help)?.let {
                { Text(stringResource(it)) }
            },
        isError = problem != null,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = field.keyboard.type),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun ClientKeySection(state: SinkEditorState) {
    val context = LocalContext.current
    Text(stringResource(R.string.sink_public_key), style = MaterialTheme.typography.labelLarge)
    val publicKey = state.publicKey
    when {
        publicKey != null -> {
            SelectionContainer {
                Text(
                    publicKey,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Text(stringResource(R.string.sink_public_key_help), style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = { copy(context, publicKey) }) { Text(stringResource(R.string.sink_copy)) }
        }

        state.keyError != null -> {
            ErrorText(stringResource(R.string.sink_key_error, state.keyError))
        }

        else -> {
            Text(stringResource(R.string.pairing_working))
        }
    }
}

@Composable
private fun HostKeySection(
    state: SinkEditorState,
    pinned: String?,
    viewModel: SinkEditorViewModel,
) {
    Text(stringResource(R.string.sink_host_key), style = MaterialTheme.typography.labelLarge)
    if (pinned != null) {
        SelectionContainer {
            Text(
                pinned,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        OutlinedButton(onClick = viewModel::forgetHostKey) { Text(stringResource(R.string.sink_host_key_forget)) }
        return
    }
    Text(stringResource(R.string.sink_host_key_missing), style = MaterialTheme.typography.bodySmall)
    when (val hostKey = state.hostKey) {
        HostKeyState.Idle, is HostKeyState.Failed -> {
            if (hostKey is HostKeyState.Failed) ErrorText(hostKey.message)
            OutlinedButton(onClick = viewModel::fetchHostKey) { Text(stringResource(R.string.sink_host_key_fetch)) }
        }

        HostKeyState.Fetching -> {
            Text(stringResource(R.string.pairing_working))
        }

        is HostKeyState.Presented -> {
            SelectionContainer {
                Text(hostKey.fingerprint, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            }
            Text(stringResource(R.string.sink_host_key_confirm), style = MaterialTheme.typography.bodySmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = viewModel::trustHostKey) { Text(stringResource(R.string.sink_host_key_trust)) }
                OutlinedButton(onClick = viewModel::forgetHostKey) { Text(stringResource(R.string.sink_cancel)) }
            }
        }
    }
}

@Composable
private fun FolderSection(
    treeUri: String?,
    isError: Boolean,
    onPicked: (String?) -> Unit,
) {
    val context = LocalContext.current
    val pick =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) {
                try {
                    persistGrant(context, uri)
                    onPicked(uri.toString())
                } catch (_: SecurityException) {
                    // The provider offers no persistable grant; the folder would be lost on reboot.
                    onPicked(null)
                }
            }
        }
    val folderName =
        remember(treeUri) {
            treeUri?.let { runCatching { DocumentFile.fromTreeUri(context, it.toUri())?.name }.getOrNull() ?: it }
        }
    Text(
        folderName ?: stringResource(R.string.sink_folder_none),
        color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
    )
    OutlinedButton(onClick = { pick.launch(treeUri?.toUri()) }) { Text(stringResource(R.string.sink_folder_pick)) }
}

@Composable
private fun AuthFields(
    state: SinkEditorState,
    viewModel: SinkEditorViewModel,
) {
    val form = state.form
    when (form.authKind) {
        AuthKind.NONE -> {
            Unit
        }

        AuthKind.BASIC -> {
            Field(
                SinkField.BASIC_USER.label,
                form.basicUser,
                SinkField.BASIC_USER in state.shownInvalid,
            ) { v ->
                viewModel.edit {
                    it.copy(basicUser = v)
                }
            }
            SecretField(
                R.string.settings_password,
                form.secretInput,
                state.hasStoredSecret,
                viewModel::clearSecret,
            ) { v ->
                viewModel.edit { it.copy(secretInput = v) }
            }
        }

        AuthKind.BEARER -> {
            SecretField(R.string.settings_token, form.secretInput, state.hasStoredSecret, viewModel::clearSecret) { v ->
                viewModel.edit { it.copy(secretInput = v) }
            }
        }

        AuthKind.OIDC -> {
            OidcFields(state, viewModel)
        }
    }
}

@Composable
private fun OidcFields(
    state: SinkEditorState,
    viewModel: SinkEditorViewModel,
) {
    val form = state.form
    val context = LocalContext.current
    OutlinedTextField(
        value = form.oidcIssuer,
        onValueChange = { v -> viewModel.edit { it.copy(oidcIssuer = v) } },
        label = { Text(stringResource(SinkField.OIDC_ISSUER.label)) },
        placeholder = { Text(stringResource(R.string.sink_oidc_issuer_placeholder)) },
        supportingText = { state.shownIssuerProblem?.let { Text(stringResource(urlProblemText(it))) } },
        isError = state.shownIssuerProblem != null,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        modifier = Modifier.fillMaxWidth(),
    )
    Field(
        SinkField.OIDC_CLIENT_ID.label,
        form.oidcClientId,
        SinkField.OIDC_CLIENT_ID in state.shownInvalid,
    ) { v ->
        viewModel.edit {
            it.copy(oidcClientId = v)
        }
    }
    Picker(R.string.sink_oidc_client_auth, OidcClientAuth.entries, form.oidcClientAuth, ::clientAuthLabel) { v ->
        viewModel.edit { it.copy(oidcClientAuth = v) }
    }
    if (form.oidcClientAuth != OidcClientAuth.NONE) {
        SecretField(
            R.string.sink_oidc_client_secret,
            form.oidcClientSecretInput,
            state.hasStoredClientSecret,
            onClear = null,
        ) { v ->
            viewModel.edit { it.copy(oidcClientSecretInput = v) }
        }
    }
    when (val signIn = state.signIn) {
        SignInState.SignedOut, is SignInState.Failed -> {
            if (signIn is SignInState.Failed) ErrorText(signIn.message)
            Button(onClick = viewModel::signIn) { Text(stringResource(R.string.sink_oidc_sign_in)) }
        }

        SignInState.Starting -> {
            Text(stringResource(R.string.pairing_working))
        }

        is SignInState.AwaitingUser -> {
            Text(stringResource(R.string.sink_oidc_instructions, signIn.verificationUri))
            SelectionContainer {
                Text(signIn.userCode, style = MaterialTheme.typography.headlineSmall, fontFamily = FontFamily.Monospace)
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { open(context, signIn.openUri) }) { Text(stringResource(R.string.sink_oidc_open)) }
                OutlinedButton(
                    onClick = { copy(context, signIn.userCode) },
                ) { Text(stringResource(R.string.sink_oidc_copy_code)) }
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

/** Never shows the stored value; the remove button says one exists and empty input keeps it. */
@Composable
private fun SecretField(
    @StringRes label: Int,
    value: String,
    stored: Boolean,
    onClear: (() -> Unit)?,
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(stringResource(label)) },
        supportingText = { Text(stringResource(R.string.settings_secret_help)) },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        modifier = Modifier.fillMaxWidth(),
    )
    if (stored &&
        onClear != null
    ) {
        TextButton(onClick = onClear) { Text(stringResource(R.string.settings_secret_clear)) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> Picker(
    @StringRes label: Int,
    options: List<T>,
    selected: T,
    labelOf: (T) -> Int,
    onSelect: (T) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = stringResource(labelOf(selected)),
            onValueChange = {},
            readOnly = true,
            label = { Text(stringResource(label)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            singleLine = true,
            modifier =
                Modifier
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

private fun copy(
    context: Context,
    text: String,
) {
    val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText(context.getString(R.string.app_name), text))
}

private fun open(
    context: Context,
    uri: String,
) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, uri.toUri()))
    } catch (_: ActivityNotFoundException) {
        // No browser; the URL and code are on screen to type elsewhere.
    }
}

private val SinkField.label: Int
    @StringRes get() =
        when (this) {
            SinkField.NAME -> R.string.sink_name
            SinkField.BASIC_USER -> R.string.settings_username
            SinkField.OIDC_ISSUER -> R.string.sink_oidc_issuer
            SinkField.OIDC_CLIENT_ID -> R.string.sink_oidc_client_id
        }

/** The label of the field or capability that keeps its value under [key]. */
@StringRes
private fun SinkTransportKind.labelOf(key: String): Int? =
    fields.firstOrNull { it.key == key }?.label
        ?: capabilities.firstNotNullOfOrNull {
            when (it) {
                is TransportCapability.FolderPick -> if (it.treeUriKey == key) R.string.sink_folder else null
                is TransportCapability.HostKeyPin -> if (it.fingerprintKey == key) R.string.sink_host_key else null
                TransportCapability.ClientKey -> null
            }
        }

@StringRes
internal fun formatLabel(kind: FormatKind): Int =
    when (kind) {
        FormatKind.JSONL -> R.string.format_jsonl
        FormatKind.CSV -> R.string.format_csv
        FormatKind.PROMETHEUS -> R.string.format_prometheus
    }

@StringRes
private fun authLabel(kind: AuthKind): Int =
    when (kind) {
        AuthKind.NONE -> R.string.settings_auth_none
        AuthKind.BASIC -> R.string.settings_auth_basic
        AuthKind.BEARER -> R.string.settings_auth_bearer
        AuthKind.OIDC -> R.string.settings_auth_oidc
    }

@StringRes
private fun clientAuthLabel(method: OidcClientAuth): Int =
    when (method) {
        OidcClientAuth.NONE -> R.string.oidc_client_public
        OidcClientAuth.SECRET_BASIC -> R.string.oidc_client_secret_basic
        OidcClientAuth.SECRET_POST -> R.string.oidc_client_secret_post
    }

@StringRes
private fun urlProblemText(problem: SinkUrlProblem): Int =
    when (problem) {
        SinkUrlProblem.INVALID -> R.string.settings_url_invalid
        SinkUrlProblem.CREDENTIALS_IN_URL -> R.string.settings_url_credentials
        SinkUrlProblem.QUERY_OR_FRAGMENT -> R.string.settings_url_query
        SinkUrlProblem.CLEARTEXT -> R.string.settings_url_cleartext
    }

private val FieldKeyboard.type: KeyboardType
    get() =
        when (this) {
            FieldKeyboard.TEXT -> KeyboardType.Text
            FieldKeyboard.URI -> KeyboardType.Uri
            FieldKeyboard.NUMBER -> KeyboardType.Number
        }
