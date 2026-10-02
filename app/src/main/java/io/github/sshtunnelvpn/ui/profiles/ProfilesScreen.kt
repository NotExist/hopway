package io.github.sshtunnelvpn.ui.profiles

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Password
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.sshtunnelvpn.R
import io.github.sshtunnelvpn.container
import io.github.sshtunnelvpn.data.AppSettings
import io.github.sshtunnelvpn.data.AuthType
import io.github.sshtunnelvpn.data.Profile
import io.github.sshtunnelvpn.ui.Route
import io.github.sshtunnelvpn.ui.components.BackTopBar
import kotlinx.coroutines.launch
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfilesScreen(navigate: (Route) -> Unit, onBack: () -> Unit) {
    val c = LocalContext.current.container
    val profiles by c.profiles.profiles.collectAsStateWithLifecycle(emptyList())
    val settings by c.settings.settings.collectAsStateWithLifecycle(AppSettings())
    val status by c.tunnel.status.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var deleting by remember { mutableStateOf<Profile?>(null) }
    val scroll = TopAppBarDefaults.pinnedScrollBehavior()

    fun select(p: Profile) = scope.launch {
        c.settings.update { it.copy(selectedProfileId = p.id) }
        // 連線中切換伺服器:直接改連新的
        if (c.tunnel.isActive && status.profileId != p.id) c.tunnel.connect(p.id)
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = { BackTopBar(stringResource(R.string.title_profiles), onBack, scroll) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { navigate(Route.EditProfile()) },
                icon = { Icon(Icons.Filled.Add, null) },
                text = { Text(stringResource(R.string.action_add_profile)) },
            )
        },
    ) { padding ->
        if (profiles.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
                    Icon(Icons.Outlined.Storage, null, Modifier.size(64.dp), tint = MaterialTheme.colorScheme.outline)
                    Spacer(Modifier.height(16.dp))
                    Text(stringResource(R.string.profiles_empty), textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            return@Scaffold
        }
        val selectedId = settings.selectedProfileId ?: profiles.first().id
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding() + 8.dp,
                bottom = padding.calculateBottomPadding() + 96.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(profiles, key = { it.id }) { p ->
                ProfileRow(
                    p,
                    selected = p.id == selectedId,
                    onSelect = { select(p) },
                    onEdit = { navigate(Route.EditProfile(p.id)) },
                    onDuplicate = {
                        scope.launch { c.profiles.upsert(p.copy(id = UUID.randomUUID().toString(), name = p.displayName + " (copy)")) }
                    },
                    onDelete = { deleting = p },
                    modifier = Modifier.animateItem(),
                )
            }
        }
    }

    deleting?.let { p ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.profile_delete_title)) },
            text = { Text(stringResource(R.string.profile_delete_body, p.displayName)) },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        c.profiles.delete(p.id)
                        if (settings.selectedProfileId == p.id) c.settings.update { it.copy(selectedProfileId = null) }
                    }
                    deleting = null
                }) { Text(stringResource(R.string.action_delete)) }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

@Composable
private fun ProfileRow(
    p: Profile,
    selected: Boolean,
    onSelect: () -> Unit,
    onEdit: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var menu by remember { mutableStateOf(false) }
    Card(
        onClick = onSelect,
        colors = CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
        ),
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(start = 4.dp, end = 4.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = selected, onClick = onSelect)
            Column(Modifier.weight(1f)) {
                Text(p.displayName, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(p.endpoint, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                    Icon(
                        if (p.authType == AuthType.PASSWORD) Icons.Outlined.Password else Icons.Outlined.Key,
                        null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        buildString {
                            append(
                                when (p.authType) {
                                    AuthType.PASSWORD -> stringResource(R.string.auth_password)
                                    AuthType.KEY -> stringResource(R.string.auth_key)
                                    AuthType.KEY_AND_PASSWORD -> stringResource(R.string.auth_key_password)
                                },
                            )
                            append(" · ×").append(p.connections)
                            if (p.udpgwEnabled) append(" · UDP")
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 4.dp),
                    )
                }
            }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, stringResource(R.string.action_more)) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.action_edit)) },
                        leadingIcon = { Icon(Icons.Outlined.Edit, null) },
                        onClick = { menu = false; onEdit() },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.action_duplicate)) },
                        leadingIcon = { Icon(Icons.Outlined.ContentCopy, null) },
                        onClick = { menu = false; onDuplicate() },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.action_delete)) },
                        leadingIcon = { Icon(Icons.Outlined.Delete, null) },
                        onClick = { menu = false; onDelete() },
                    )
                }
            }
        }
    }
}
