package io.github.sshtunnelvpn.ui.profiles

import android.content.ClipData
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.FileOpen
import androidx.compose.material.icons.outlined.NetworkCheck
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.sshtunnelvpn.R
import io.github.sshtunnelvpn.container
import io.github.sshtunnelvpn.data.AuthType
import io.github.sshtunnelvpn.ui.components.BackTopBar
import io.github.sshtunnelvpn.ui.components.SectionHeader
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileEditScreen(profileId: String?, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val container = ctx.container
    val vm: ProfileEditViewModel = viewModel { ProfileEditViewModel(container, profileId) }
    val p = vm.profile
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboard.current
    var showErrors by rememberSaveable { mutableStateOf(false) }
    var advanced by rememberSaveable { mutableStateOf(false) }
    val invalid = vm.validation
    fun err(field: String) = showErrors && field in invalid

    val importKey = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        val text = runCatching {
            ctx.contentResolver.openInputStream(uri)?.use { s -> s.readBytes().take(64 * 1024).toByteArray().decodeToString() }
        }.getOrNull()
        if (text != null) vm.update { it.copy(privateKey = text.trim() + "\n") }
    }

    fun copy(text: String) = scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("ssh", text))) }
    fun share(text: String) = ctx.startActivity(
        Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), null),
    )

    Scaffold(
        topBar = {
            BackTopBar(
                stringResource(if (profileId == null) R.string.title_new_profile else R.string.title_edit_profile),
                onBack,
            ) {
                TextButton(onClick = {
                    if (invalid.isNotEmpty()) {
                        showErrors = true
                    } else {
                        scope.launch { vm.save(); onBack() }
                    }
                }) { Text(stringResource(R.string.action_save)) }
            }
        },
    ) { padding ->
        if (!vm.loaded) return@Scaffold
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                value = p.name, onValueChange = { v -> vm.update { it.copy(name = v) } },
                label = { Text(stringResource(R.string.field_name)) },
                placeholder = { Text(p.endpoint.takeIf { p.host.isNotBlank() } ?: stringResource(R.string.field_name_hint)) },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = p.host, onValueChange = { v -> vm.update { it.copy(host = v.trim()) } },
                    label = { Text(stringResource(R.string.field_host)) },
                    isError = err("host"), singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = if (p.port == 0) "" else p.port.toString(),
                    onValueChange = { v -> vm.update { it.copy(port = v.filter(Char::isDigit).take(5).toIntOrNull() ?: 0) } },
                    label = { Text(stringResource(R.string.field_port)) },
                    isError = err("port"), singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.width(96.dp),
                )
            }
            OutlinedTextField(
                value = p.username, onValueChange = { v -> vm.update { it.copy(username = v.trim()) } },
                label = { Text(stringResource(R.string.field_username)) },
                isError = err("username"), singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                modifier = Modifier.fillMaxWidth(),
            )

            SectionHeader(stringResource(R.string.section_auth))
            val authOptions = listOf(
                AuthType.PASSWORD to R.string.auth_password,
                AuthType.KEY to R.string.auth_key,
                AuthType.KEY_AND_PASSWORD to R.string.auth_key_password,
            )
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                authOptions.forEachIndexed { i, (type, label) ->
                    SegmentedButton(
                        selected = p.authType == type,
                        onClick = { vm.update { it.copy(authType = type) } },
                        shape = SegmentedButtonDefaults.itemShape(i, authOptions.size),
                    ) { Text(stringResource(label), maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
            }

            if (p.authType != AuthType.PASSWORD) {
                OutlinedTextField(
                    value = p.privateKey, onValueChange = { v -> vm.update { it.copy(privateKey = v) } },
                    label = { Text(stringResource(R.string.field_private_key)) },
                    placeholder = { Text("-----BEGIN OPENSSH PRIVATE KEY-----", fontFamily = FontFamily.Monospace) },
                    isError = err("privateKey") || vm.keyError != null,
                    supportingText = vm.keyError?.let { { Text(it) } }
                        ?: vm.keyInfo?.let { { Text(it.fingerprint, fontFamily = FontFamily.Monospace) } },
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    minLines = 3, maxLines = 6,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { importKey.launch(arrayOf("*/*")) }) {
                        Icon(Icons.Outlined.FileOpen, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.action_import_key))
                    }
                    var genMenu by remember { mutableStateOf(false) }
                    OutlinedButton(onClick = { genMenu = true }) {
                        Icon(Icons.Outlined.AutoAwesome, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.action_generate_key))
                        DropdownMenu(expanded = genMenu, onDismissRequest = { genMenu = false }) {
                            listOf("ed25519" to "Ed25519", "ecdsa" to "ECDSA P-256", "rsa" to "RSA 3072").forEach { (k, l) ->
                                DropdownMenuItem(text = { Text(l) }, onClick = { genMenu = false; vm.generateKey(k) })
                            }
                        }
                    }
                }
                vm.keyInfo?.let { info ->
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                        Column(Modifier.padding(12.dp)) {
                            Text(stringResource(R.string.label_public_key), style = MaterialTheme.typography.labelMedium)
                            SelectionContainer {
                                Text(info.publicKey, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall,
                                    maxLines = 3, overflow = TextOverflow.Ellipsis)
                            }
                            Row {
                                TextButton(onClick = { copy(info.publicKey) }) {
                                    Icon(Icons.Outlined.ContentCopy, null, Modifier.size(16.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text(stringResource(R.string.action_copy))
                                }
                                TextButton(onClick = { share(info.publicKey) }) {
                                    Icon(Icons.Outlined.Share, null, Modifier.size(16.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text(stringResource(R.string.action_share))
                                }
                            }
                        }
                    }
                }
                SecretField(
                    value = p.passphrase, onChange = { v -> vm.update { it.copy(passphrase = v) } },
                    label = stringResource(R.string.field_passphrase), isError = false,
                )
            }
            if (p.authType != AuthType.KEY) {
                SecretField(
                    value = p.password, onChange = { v -> vm.update { it.copy(password = v) } },
                    label = stringResource(R.string.field_password), isError = err("password"),
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            ) {
                Text(stringResource(R.string.section_advanced), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                IconButton(onClick = { advanced = !advanced }) {
                    Icon(if (advanced) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null)
                }
            }
            HorizontalDivider()
            AnimatedVisibility(advanced) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    LabeledSlider(
                        stringResource(R.string.field_connections), stringResource(R.string.field_connections_hint),
                        p.connections, 1..6,
                    ) { v -> vm.update { it.copy(connections = v) } }
                    LabeledSlider(
                        stringResource(R.string.field_keepalive), stringResource(R.string.field_keepalive_hint),
                        p.keepaliveSec, 5..120, suffix = " s",
                    ) { v -> vm.update { it.copy(keepaliveSec = v) } }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.field_udpgw), style = MaterialTheme.typography.bodyLarge)
                            Text(stringResource(R.string.field_udpgw_hint), style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(checked = p.udpgwEnabled, onCheckedChange = { v -> vm.update { it.copy(udpgwEnabled = v) } })
                    }
                    if (p.udpgwEnabled) {
                        OutlinedTextField(
                            value = p.udpgwAddress, onValueChange = { v -> vm.update { it.copy(udpgwAddress = v.trim()) } },
                            label = { Text(stringResource(R.string.field_udpgw_address)) }, singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }

            Spacer(Modifier.height(4.dp))
            Button(
                onClick = { if (invalid.isEmpty()) vm.runTest() else showErrors = true },
                enabled = vm.test != TestState.Running,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (vm.test == TestState.Running) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Outlined.NetworkCheck, null, Modifier.size(18.dp))
                }
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.action_test_connection))
            }
            TestResultCard(vm.test, vm.exit, onTrust = vm::trustAndRetest)
            Spacer(Modifier.height(32.dp))
        }
    }

    vm.generatedKey?.let { k ->
        AlertDialog(
            onDismissRequest = { vm.generatedKey = null },
            title = { Text(stringResource(R.string.keygen_title)) },
            text = {
                Column {
                    Text(stringResource(R.string.keygen_body))
                    Spacer(Modifier.height(12.dp))
                    SelectionContainer {
                        Text(k.publicKey, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { copy(k.publicKey); vm.generatedKey = null }) { Text(stringResource(R.string.action_copy)) }
            },
            dismissButton = {
                TextButton(onClick = { share(k.publicKey); vm.generatedKey = null }) { Text(stringResource(R.string.action_share)) }
            },
        )
    }
}

@Composable
private fun SecretField(value: String, onChange: (String) -> Unit, label: String, isError: Boolean) {
    var visible by rememberSaveable { mutableStateOf(false) }
    OutlinedTextField(
        value = value, onValueChange = onChange, label = { Text(label) }, isError = isError, singleLine = true,
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        trailingIcon = {
            IconButton(onClick = { visible = !visible }) {
                Icon(if (visible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility, null)
            }
        },
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun LabeledSlider(
    title: String,
    hint: String,
    value: Int,
    range: IntRange,
    suffix: String = "",
    onChange: (Int) -> Unit,
) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text("$value$suffix", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        }
        Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Slider(
            value = value.toFloat(),
            onValueChange = { onChange(it.roundToInt()) },
            valueRange = range.first.toFloat()..range.last.toFloat(),
            steps = if (range.last - range.first <= 10) range.last - range.first - 1 else 0,
        )
    }
}

@Composable
private fun TestResultCard(state: TestState, exit: io.github.sshtunnelvpn.data.IpInfo?, onTrust: (io.github.sshtunnelvpn.tunnel.HostKeyMismatch) -> Unit) {
    when (state) {
        is TestState.Ok -> Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
            Row(Modifier.padding(16.dp)) {
                Icon(Icons.Outlined.CheckCircle, null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
                Spacer(Modifier.width(12.dp))
                val r = state.result
                SelectionContainer {
                    Column {
                        Text(stringResource(R.string.test_ok), style = MaterialTheme.typography.titleSmall)
                        Text(r.serverVersion, style = MaterialTheme.typography.bodySmall)
                        Text(stringResource(R.string.test_timing, r.handshakeMs, r.rttMillis), style = MaterialTheme.typography.bodySmall)
                        Text("${r.hostKeyType} ${r.fingerprint}", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                        if (exit != null) {
                            Text(
                                listOfNotNull(stringResource(R.string.label_exit), placeLabel(exit), exit.ip).joinToString("  "),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        } else if (r.exitError.isNotEmpty()) {
                            Text(stringResource(R.string.exit_check_failed, r.exitError), style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error)
                        }
                        if (r.getUDPGWOK()) Text(stringResource(R.string.test_udpgw_ok), style = MaterialTheme.typography.bodySmall)
                        if (r.udpgwError.isNotEmpty()) {
                            Text(stringResource(R.string.test_udpgw_fail, r.udpgwError), style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }
        is TestState.Failed -> Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
            Column(Modifier.padding(16.dp)) {
                Row {
                    Icon(Icons.Outlined.ErrorOutline, null, tint = MaterialTheme.colorScheme.onErrorContainer)
                    Spacer(Modifier.width(12.dp))
                    SelectionContainer { Text(state.message, style = MaterialTheme.typography.bodyMedium) }
                }
                state.mismatch?.let { m ->
                    Spacer(Modifier.height(8.dp))
                    Text(stringResource(R.string.hostkey_known), style = MaterialTheme.typography.labelMedium)
                    Text(m.knownFingerprint, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                    Text(stringResource(R.string.hostkey_new, m.keyType), style = MaterialTheme.typography.labelMedium)
                    Text(m.newFingerprint, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                    FilledTonalButton(onClick = { onTrust(m) }, modifier = Modifier.padding(top = 8.dp)) {
                        Text(stringResource(R.string.hostkey_trust))
                    }
                }
            }
        }
        else -> {}
    }
}
