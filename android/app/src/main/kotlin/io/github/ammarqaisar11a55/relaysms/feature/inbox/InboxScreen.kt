package io.github.ammarqaisar11a55.relaysms.feature.inbox

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.ui.platform.LocalContext
import io.github.ammarqaisar11a55.relaysms.feature.home.openAppSettings
import io.github.ammarqaisar11a55.relaysms.ui.components.FullWidthButton
import io.github.ammarqaisar11a55.relaysms.ui.components.InfoCard
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Password
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.ammarqaisar11a55.relaysms.core.database.MessageDirection
import io.github.ammarqaisar11a55.relaysms.core.prefs.AppSettings
import io.github.ammarqaisar11a55.relaysms.inbox.InboxFilter
import io.github.ammarqaisar11a55.relaysms.inbox.InboxMessage
import io.github.ammarqaisar11a55.relaysms.ui.components.EmptyState
import io.github.ammarqaisar11a55.relaysms.ui.components.ErrorState
import io.github.ammarqaisar11a55.relaysms.ui.components.LoadingState
import io.github.ammarqaisar11a55.relaysms.ui.components.RelayScaffold
import io.github.ammarqaisar11a55.relaysms.ui.components.Tone
import io.github.ammarqaisar11a55.relaysms.ui.components.toneColors
import io.github.ammarqaisar11a55.relaysms.ui.navigation.LocalAppContainer
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InboxScreen(
    settings: AppSettings,
    onOpenMessage: (String) -> Unit,
    onCompose: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenPairing: () -> Unit,
    onOpenDevice: () -> Unit,
) {
    val container = LocalAppContainer.current
    val vm: InboxViewModel = viewModel { InboxViewModel(container) }
    val state by vm.state.collectAsStateWithLifecycle()
    val query by vm.queryText.collectAsStateWithLifecycle()
    val refreshing by vm.refreshing.collectAsStateWithLifecycle()
    val peer by container.identityStore.peer.collectAsStateWithLifecycle()
    val link by container.linkMonitor.status.collectAsStateWithLifecycle()
    val socket by container.relaySocket.state.collectAsStateWithLifecycle()
    val sync by container.relaySync.status.collectAsStateWithLifecycle()
    var searching by rememberSaveable { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }

    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(30_000)
        }
    }
    // Android 13+ only shows notifications after the user allowed them. Without this check a
    // paired companion would receive messages in silence and never be told why.
    val context = LocalContext.current
    var canNotify by remember { mutableStateOf(container.messageNotifier.canPost()) }
    var notifyDenied by rememberSaveable { mutableStateOf(false) }
    val notifyLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        canNotify = container.messageNotifier.canPost()
        notifyDenied = !granted
    }
    LifecycleResumeEffect(Unit) {
        canNotify = container.messageNotifier.canPost()
        val job = container.appScope.launch { container.linkMonitor.refresh() }
        onPauseOrDispose { job.cancel() }
    }
    val online by container.networkMonitor.online.collectAsStateWithLifecycle()
    val summary = ConnectionSummaries.of(link, peer != null, socket, sync, now, online)

    RelayScaffold(
        title = "Messages",
        modifier = Modifier.testTag("companion_home"),
        floatingActionButton = {
            FloatingActionButton(onClick = onCompose, modifier = Modifier.testTag("inbox_compose")) {
                Icon(Icons.Filled.Edit, contentDescription = "New message")
            }
        },
        actions = {
            IconButton(onClick = { searching = !searching; if (!searching) vm.setQuery("") }, modifier = Modifier.testTag("inbox_search_toggle")) {
                Icon(if (searching) Icons.Filled.Close else Icons.Filled.Search, contentDescription = if (searching) "Close search" else "Search messages")
            }
            IconButton(onClick = { menuOpen = true }, modifier = Modifier.testTag("inbox_menu")) {
                Icon(Icons.Filled.MoreVert, contentDescription = "More options")
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text(if (settings.newestFirst) "Sort: oldest first" else "Sort: newest first") },
                    leadingIcon = { Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = null) },
                    onClick = { menuOpen = false; vm.toggleSort() },
                    modifier = Modifier.testTag("inbox_sort"),
                )
                DropdownMenuItem(
                    text = { Text("Mark all as read") },
                    leadingIcon = { Icon(Icons.Filled.DoneAll, contentDescription = null) },
                    onClick = { menuOpen = false; vm.markAllRead() },
                )
                DropdownMenuItem(
                    text = { Text(if (peer == null) "Pair with source phone" else "Paired device") },
                    leadingIcon = { Icon(Icons.Filled.Link, contentDescription = null) },
                    onClick = { menuOpen = false; if (peer == null) onOpenPairing() else onOpenDevice() },
                    modifier = Modifier.testTag("open_device"),
                )
                DropdownMenuItem(
                    text = { Text("Settings") },
                    leadingIcon = { Icon(Icons.Filled.Settings, contentDescription = null) },
                    onClick = { menuOpen = false; onOpenSettings() },
                    modifier = Modifier.testTag("open_settings"),
                )
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            ConnectionBar(summary, onClick = { if (peer == null) onOpenPairing() else onOpenDevice() })
            if (peer != null && settings.notificationsEnabled && !canNotify) {
                val canAsk = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !notifyDenied
                InfoCard(
                    title = "Notifications are blocked",
                    body = if (canAsk) "Android is not letting RelaySMS show notifications, so new messages arrive silently. Allow notifications to be told when a message comes in. They say only \"New message received\" unless you choose otherwise."
                    else "Android is not letting RelaySMS show notifications. Open App info, tap Notifications and allow them.",
                    tone = Tone.WARN,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).testTag("notifications_prompt"),
                    action = {
                        FullWidthButton(
                            if (canAsk) "Allow notifications" else "Open App info",
                            onClick = { if (canAsk) notifyLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) else openAppSettings(context) },
                            modifier = Modifier.testTag("notifications_prompt_allow"),
                        )
                    },
                )
            }
            if (peer != null && !settings.backgroundConnection) {
                Text(
                    "Messages arrive right away only while RelaySMS is open. Turn on \"Stay connected in the background\" in Settings to receive them when it is closed.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp).testTag("background_hint"),
                )
            }
            if (searching) SearchField(query, vm::setQuery)
            val ready = state as? InboxUiState.Ready
            if (ready != null && ready.total > 0) FilterRow(ready, vm::setFilter)

            PullToRefreshBox(isRefreshing = refreshing, onRefresh = vm::refresh, modifier = Modifier.fillMaxSize()) {
                when (val s = state) {
                    InboxUiState.Loading -> LoadingState(label = "Loading messages")
                    InboxUiState.StorageError -> ErrorState("Stored messages could not be read. Restart the app; if this continues, free up storage space.")
                    is InboxUiState.Ready -> when {
                        s.total == 0 && peer == null -> EmptyState(
                            icon = Icons.Filled.Link,
                            title = if (summary.kind == ConnectionKind.REVOKED) "Pairing was removed" else "Pair your source phone",
                            body = if (summary.kind == ConnectionKind.REVOKED) "This phone is no longer paired. Pair again to receive messages."
                            else "Messages forwarded by your source phone will appear here, in RelaySMS's own inbox.",
                            actionLabel = "Pair with source phone",
                            onAction = onOpenPairing,
                            actionTestTag = "open_pairing",
                        )
                        s.total == 0 -> EmptyState(
                            icon = Icons.Filled.Inbox,
                            title = "No messages yet",
                            body = "Messages appear here when your source phone receives an SMS from a sender you chose to forward. Pull down to check now.",
                        )
                        s.visible.isEmpty() -> EmptyState(
                            icon = Icons.Filled.SearchOff,
                            title = "Nothing matches",
                            body = if (s.query.isNotBlank()) "No message matches \"${s.query}\" with the current filter." else "No messages match this filter.",
                        )
                        else -> LazyColumn(Modifier.fillMaxSize().testTag("inbox_list")) {
                            items(s.visible, key = { it.id }) { m ->
                                MessageRow(m, now, onClick = { onOpenMessage(m.id) })
                                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ConnectionBar(summary: ConnectionSummary, onClick: () -> Unit) {
    val (bg, fg) = toneColors(summary.tone)
    Surface(color = bg, contentColor = fg, modifier = Modifier.fillMaxWidth()) {
        Column(
            Modifier.fillMaxWidth().clickable(onClickLabel = "Open pairing details", onClick = onClick).heightIn(min = 48.dp)
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }.testTag("connection_bar"),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(summary.label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.testTag("connection_label"))
            Text(summary.detail, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun SearchField(query: String, onChange: (String) -> Unit) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    OutlinedTextField(
        value = query,
        onValueChange = onChange,
        placeholder = { Text("Search sender or text") },
        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
        trailingIcon = {
            if (query.isNotEmpty()) IconButton(onClick = { onChange("") }) { Icon(Icons.Filled.Close, contentDescription = "Clear search") }
        },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).focusRequester(focus).testTag("inbox_search"),
    )
}

@Composable
private fun FilterRow(state: InboxUiState.Ready, onFilter: (InboxFilter) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(selected = state.filter == InboxFilter.ALL, onClick = { onFilter(InboxFilter.ALL) }, label = { Text("All") }, modifier = Modifier.testTag("filter_all"))
        FilterChip(
            selected = state.filter == InboxFilter.UNREAD,
            onClick = { onFilter(InboxFilter.UNREAD) },
            label = { Text(if (state.unread > 0) "Unread (${state.unread})" else "Unread") },
            modifier = Modifier.testTag("filter_unread"),
        )
        FilterChip(selected = state.filter == InboxFilter.CODES, onClick = { onFilter(InboxFilter.CODES) }, label = { Text("Codes") }, modifier = Modifier.testTag("filter_codes"))
    }
}

@Composable
private fun MessageRow(m: InboxMessage, now: Long, onClick: () -> Unit) {
    val time = Times.short(m.receivedAtMillis, now)
    val outgoing = m.direction == MessageDirection.OUTGOING
    val description = buildString {
        if (!m.isRead) append("Unread. ")
        append(if (outgoing) "To ${m.address}. ${outgoingStatusLabel(m.status, m.origin).first}. " else "From ${m.address}. ")
        if (m.hasOtp) append("Contains a code. ")
        append(m.body.take(160)).append(". ").append(time)
    }
    Row(
        Modifier.fillMaxWidth().clickable(onClickLabel = "Open message", onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp).heightIn(min = 48.dp)
            .semantics(mergeDescendants = true) { contentDescription = description }.testTag("message_${m.id}"),
        verticalAlignment = Alignment.Top,
    ) {
        Box(Modifier.padding(top = 6.dp).size(10.dp).clip(CircleShape)) {
            if (!m.isRead) Surface(color = MaterialTheme.colorScheme.primary, modifier = Modifier.fillMaxSize()) {}
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (outgoing) "To: ${m.address}" else m.address,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = if (m.isRead) FontWeight.Normal else FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                Text(time, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(
                m.body,
                style = MaterialTheme.typography.bodyMedium,
                color = if (m.isRead) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (outgoing) {
                val (label, tone) = outgoingStatusLabel(m.status, m.origin)
                val (bg, fg) = toneColors(tone)
                Surface(color = bg, contentColor = fg, shape = MaterialTheme.shapes.small, modifier = Modifier.padding(top = 6.dp)) {
                    Text(label, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp))
                }
            }
            if (m.hasOtp) {
                val (bg, fg) = toneColors(Tone.OK)
                Surface(color = bg, contentColor = fg, shape = MaterialTheme.shapes.small, modifier = Modifier.padding(top = 6.dp)) {
                    Row(Modifier.padding(horizontal = 8.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Password, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Code", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}
