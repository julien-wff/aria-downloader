package com.julien_wff.aria_android

import android.app.NotificationManager
import android.content.Intent
import android.provider.Settings
import android.os.Build
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import android.Manifest
import android.content.ClipboardManager
import android.content.pm.PackageManager
import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.julien_wff.aria_android.downloads.*
import com.julien_wff.aria_android.ui.theme.AriaTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DownloadsScreen(model: DownloadsViewModel) {
    val tasks by model.tasks.collectAsStateWithLifecycle()
    val loaded by model.loaded.collectAsStateWithLifecycle()
    val persistedSettings by model.settings.collectAsStateWithLifecycle()
    val editorState by model.settingsEditor.state.collectAsStateWithLifecycle()
    val settings = if (model.settingsEditor.editable) editorState.settings else persistedSettings
    val error by model.error.collectAsStateWithLifecycle()
    val busy by model.busy.collectAsStateWithLifecycle()
    val incoming by model.incoming.collectAsStateWithLifecycle()
    val consent by model.deletionConsent.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val focus = LocalFocusManager.current
    val snackbar = remember { SnackbarHostState() }
    val backgroundStatus = rememberBackgroundDownloadStatus()
    var destination by rememberSaveable { mutableStateOf("Downloads") }
    var adding by rememberSaveable { mutableStateOf(false) }
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    var url by rememberSaveable { mutableStateOf("") }
    var protocol by rememberSaveable { mutableStateOf(DownloadProtocol.HTTP) }
    var protocolChosen by rememberSaveable { mutableStateOf(false) }
    var name by rememberSaveable { mutableStateOf("download") }
    var referrer by rememberSaveable { mutableStateOf("") }
    var userAgent by rememberSaveable { mutableStateOf("") }
    var advancedExpanded by rememberSaveable { mutableStateOf(false) }
    var denied by remember { mutableStateOf(!context.getSystemService(NotificationManager::class.java).areNotificationsEnabled()) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        denied = !context.getSystemService(NotificationManager::class.java).areNotificationsEnabled()
    }
    var deleteId by rememberSaveable { mutableStateOf<String?>(null) }
    var deletePublic by rememberSaveable { mutableStateOf(false) }
    var cancelId by rememberSaveable { mutableStateOf<String?>(null) }
    val open: (DownloadTask, Boolean) -> Unit = { task, share -> model.open(task, share) { context.startActivity(it) } }
    val deletePermission = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
        model.deletionResult(it.resultCode == android.app.Activity.RESULT_OK)
    }
    LaunchedEffect(consent) { consent?.let { model.deletionConsent.value = null; deletePermission.launch(IntentSenderRequest.Builder(it).build()) } }
    LaunchedEffect(incoming, tasks, loaded) {
        val request = incoming ?: return@LaunchedEffect
        if (request.text != null) {
            referrer = ""; userAgent = ""; advancedExpanded = false
            model.clearError()
            url = request.text
            protocol = request.protocol ?: downloadProtocol(request.text); protocolChosen = false
            name = if (request.text.startsWith("file:")) "Shared ${protocol.name.lowercase()}" else if (protocol == DownloadProtocol.MAGNET) "Magnet download" else suggestedName(request.text)
            adding = true
            model.incoming.value = null
        } else if (request.taskId != null) {
            selected = request.taskId
            val task = tasks.find { it.id == request.taskId }
            if (task != null) {
                destination = if (task.isHistory) "History" else "Downloads"
                request.fileAction?.let { if (it == "share" || task.files.count { f -> f.selected } <= 1) open(task, it == "share") }
                model.incoming.value = null
            } else if (loaded) {
                model.showError("This download is no longer available.")
                model.incoming.value = null
                selected = null
            }
        }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        denied = !granted
        model.startPending { adding = false; destination = "Downloads" }
    }
    val back = { focus.clearFocus(); model.clearError(); if (adding) adding = false else selected = null }
    BackHandler(adding || selected != null, onBack = back)
    LaunchedEffect(error, adding) {
        if (error != null && !adding) {
            snackbar.showSnackbar(error!!, actionLabel = "Dismiss", duration = SnackbarDuration.Long)
            model.clearError()
        }
    }
    AriaTheme(settings.appearance) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val wide = maxWidth >= 840.dp
            val useRail = maxWidth >= 600.dp
            val compactHeight = maxHeight < 480.dp
            val secondary = adding || (!wide && selected != null)
            val keyboard = keyboardVisible()
            val destinations = listOf("Downloads" to R.drawable.ic_download, "History" to R.drawable.ic_history, "Settings" to R.drawable.ic_settings)
            val navigate: (String) -> Unit = { label ->
                model.settingsEditor.commitSpeed()
                focus.clearFocus()
                destination = label; selected = null; adding = false
            }
            val add = {
                val draft = runCatching {
                    context.getSystemService(ClipboardManager::class.java)?.primaryClip?.let { clip ->
                        (0 until clip.itemCount).firstNotNullOfOrNull { index ->
                            val item = clip.getItemAt(index)
                            clipboardDraft(item.text?.toString() ?: item.uri?.toString())
                        }
                    }
                }.getOrNull()
                focus.clearFocus(); model.clearError(); selected = null
                referrer = ""; userAgent = ""; advancedExpanded = false
                url = draft?.url.orEmpty(); protocol = downloadProtocol(url); protocolChosen = false; name = draft?.fileName ?: "download"; adding = true
            }
            Scaffold(
                containerColor = MaterialTheme.colorScheme.surface,
                topBar = {
                    TopAppBar(
                        title = { Text(when { adding -> "Add download"; secondary -> "Download details"; else -> destination }, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        navigationIcon = { if (secondary) IconButton(onClick = back) { AriaIcon(R.drawable.ic_arrow_back, "Back") } },
                        actions = {
                            if (useRail && compactHeight && !secondary && destination != "Settings" && !keyboard)
                                IconButton(onClick = add) { AriaIcon(R.drawable.ic_add, "Add download") }
                        },
                    )
                },
                snackbarHost = { SnackbarHost(snackbar) },
                bottomBar = { if (!useRail && !secondary && !keyboard) NavigationBar {
                    destinations.forEach { (label, icon) -> NavigationBarItem(selected = destination == label,
                        onClick = { navigate(label) }, icon = { AriaIcon(icon) }, label = { Text(label) }) }
                } },
                floatingActionButton = {
                    if (!useRail && !secondary && !adding && destination != "Settings" && !keyboard) ExtendedFloatingActionButton(
                        onClick = add,
                    ) {
                        AriaIcon(R.drawable.ic_add)
                        Spacer(Modifier.width(8.dp))
                        Text("Add download")
                    }
                },
            ) { padding ->
                Row(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding()) {
                    if (useRail && !adding) NavigationRail(header = {
                        if (!compactHeight && destination != "Settings" && !secondary && !keyboard) FloatingActionButton(onClick = add) {
                            AriaIcon(R.drawable.ic_add, "Add download")
                        }
                    }) {
                        destinations.forEach { (label, icon) -> NavigationRailItem(selected = destination == label,
                            onClick = { navigate(label) }, icon = { AriaIcon(icon) }, label = { Text(label) }) }
                    }
                    Column(Modifier.weight(1f).fillMaxHeight().padding(horizontal = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        if (denied && !adding) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.weight(1f)) { Notice(R.drawable.ic_notifications_off,
                                "Notifications are disabled. Download controls remain available here.") }
                            TextButton(onClick = {
                                context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                    .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
                            }) { Text("Settings") }
                        }
                        if (!adding && destination == "Downloads") {
                            val relevantStates = if (selected != null) tasks.filter { it.id == selected }.map { it.state }
                                else tasks.filter { !it.isHistory }.map { it.state }
                            backgroundDownloadNotice(backgroundStatus, relevantStates)?.let { message ->
                                Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.large) {
                                    Column(Modifier.fillMaxWidth().padding(16.dp)) {
                                        Text(message)
                                        TextButton(onClick = { navigate("Settings") }) { Text("Background downloads") }
                                    }
                                }
                            }
                        }
                        when {
                            adding -> AddDownload(url, { url = it; if (!protocolChosen) protocol = downloadProtocol(it); model.clearError() }, name, { name = it; model.clearError() }, busy,
                                error = error, protocol = protocol, onProtocol = { protocol = it; protocolChosen = true },
                                referrer = referrer, onReferrer = { referrer = it; model.clearError() },
                                userAgent = userAgent, onUserAgent = { userAgent = it; model.clearError() },
                                advancedExpanded = advancedExpanded, onAdvancedExpanded = { advancedExpanded = it }, start = {
                                    focus.clearFocus()
                                    if (model.prepare(url, name, referrer, userAgent, protocol)) {
                                        if (!model.askedNotificationPermission && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                                            model.askedNotificationPermission = true
                                            permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                                        } else model.startPending { adding = false; destination = "Downloads" }
                                    }
                                })
                            destination == "Settings" -> SettingsScreen(editorState, model.settingsEditor)
                            else -> {
                                val task = tasks.find { it.id == selected }
                                val actions: @Composable (DownloadTask) -> Unit = { item -> TaskActions(item, model, open,
                                    cancel = { cancelId = item.id }, delete = { deleteId = item.id; deletePublic = false }) }
                                if (secondary) {
                                    if (task != null) TaskDetails(task, model, actions) else Notice(R.drawable.ic_description, "This download is no longer available.")
                                } else Row(Modifier.weight(1f)) {
                                    TaskList(destination, tasks, selected, { focus.clearFocus(); selected = it }, actions, Modifier.weight(1f))
                                    if (wide && task != null) {
                                        VerticalDivider(Modifier.fillMaxHeight().padding(horizontal = 16.dp))
                                        Box(Modifier.weight(1f)) { TaskDetails(task, model, actions) }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        if (cancelId != null) AlertDialog(onDismissRequest = { cancelId = null }, title = { Text("Cancel download?") },
            text = { Text("The transfer will stop. Private partial data is kept until you delete the entry.") },
            confirmButton = { TextButton(onClick = { model.cancel(cancelId!!); cancelId = null }) { Text("Cancel download") } },
            dismissButton = { TextButton(onClick = { cancelId = null }) { Text("Keep downloading") } })
        if (deleteId != null) {
            val task = tasks.find { it.id == deleteId }
            AlertDialog(onDismissRequest = { deleteId = null }, title = { Text("Delete download entry?") },
                text = { Column {
                    Text("Remove this entry and its private staging data.")
                    if (task?.state?.publishedUri != null || task?.files?.any { it.publishedUri != null } == true) Row(Modifier.fillMaxWidth().clickable { deletePublic = !deletePublic }) {
                        Checkbox(deletePublic, { deletePublic = it }); Text(if (task?.files?.count { it.selected }?.let { it > 1 } == true) "Also delete the files from Downloads" else "Also delete the file from Downloads", Modifier.padding(top = 12.dp))
                    } else Text("Public Downloads files are kept.")
                } },
                confirmButton = { TextButton(enabled = !busy, onClick = { model.delete(deleteId!!, deletePublic); deleteId = null }) { Text("Delete") } },
                dismissButton = { TextButton(onClick = { deleteId = null }) { Text("Keep entry") } })
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun keyboardVisible(): Boolean = WindowInsets.isImeVisible

@Composable
private fun AriaIcon(resource: Int, description: String? = null) {
    Icon(painterResource(resource), description, Modifier.size(24.dp))
}

@Composable
private fun Notice(icon: Int, text: String) {
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            AriaIcon(icon)
            Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
internal fun Section(title: String, icon: Int, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.padding(horizontal = 4.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            AriaIcon(icon)
            Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        }
        Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainer) {
            Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp), content = content)
        }
    }
}

@Composable
private fun AddDownload(url: String, onUrl: (String) -> Unit, name: String, onName: (String) -> Unit,
    busy: Boolean, error: String?, protocol: DownloadProtocol, onProtocol: (DownloadProtocol) -> Unit, referrer: String, onReferrer: (String) -> Unit,
    userAgent: String, onUserAgent: (String) -> Unit, advancedExpanded: Boolean,
    onAdvancedExpanded: (Boolean) -> Unit, start: () -> Unit) {
    val focus = LocalFocusManager.current
    Column(Modifier.widthIn(max = 640.dp).fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
        Section("File to download", R.drawable.ic_link) {
            if (url.startsWith("file:")) {
                Text("Shared ${protocol.name.lowercase()} file · Review before starting")
            } else {
            OutlinedTextField(url, onUrl, label = { Text(if (protocol == DownloadProtocol.MAGNET) "Magnet link" else "HTTP or HTTPS URL") }, modifier = Modifier.fillMaxWidth(),
                leadingIcon = { AriaIcon(R.drawable.ic_link) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
                keyboardActions = KeyboardActions(onNext = { focus.moveFocus(FocusDirection.Down) }), singleLine = true)
            }
            if (!url.startsWith("file:")) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DownloadProtocol.entries.forEach { kind -> FilterChip(protocol == kind, { onProtocol(kind) },
                    label = { Text(when (kind) { DownloadProtocol.HTTP -> "HTTP"; DownloadProtocol.TORRENT -> "Torrent"; DownloadProtocol.MAGNET -> "Magnet"; DownloadProtocol.METALINK -> "Metalink" }) }) }
            }
            if (protocol != DownloadProtocol.HTTP) Text("Resolve metadata, review files, then start the selected contents. Torrents upload to peers while downloading and stop when finished.", style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(name, onName, label = { Text(if (protocol == DownloadProtocol.HTTP) "Filename" else "Download title") }, modifier = Modifier.fillMaxWidth(),
                leadingIcon = { AriaIcon(R.drawable.ic_description) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { focus.clearFocus() }), singleLine = true)
        }
        Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainer) {
            Column(Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth().clickable(role = Role.Button) {
                    focus.clearFocus()
                    onAdvancedExpanded(!advancedExpanded)
                }.semantics { stateDescription = if (advancedExpanded) "Expanded" else "Collapsed" }
                    .padding(20.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    AriaIcon(R.drawable.ic_tune)
                    Text("Advanced", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    Icon(painterResource(R.drawable.ic_expand_more), contentDescription = null,
                        modifier = Modifier.rotate(if (advancedExpanded) 180f else 0f))
                }
                if (advancedExpanded) Column(Modifier.padding(start = 20.dp, end = 20.dp, bottom = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    OutlinedTextField(referrer, onReferrer, label = { Text("HTTP referrer") },
                        supportingText = { Text("Optional page URL required by the server") },
                        modifier = Modifier.fillMaxWidth(), singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
                        keyboardActions = KeyboardActions(onNext = { focus.moveFocus(FocusDirection.Down) }))
                    OutlinedTextField(userAgent, onUserAgent, label = { Text("User-Agent") },
                        supportingText = { Text("Optional override for this download") },
                        modifier = Modifier.fillMaxWidth(), singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { focus.clearFocus() }))
                }
            }
        }
        if (error != null) Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        Button(onClick = start, enabled = !busy && url.isNotBlank() && name.isNotBlank(),
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
            AriaIcon(R.drawable.ic_download)
            Spacer(Modifier.width(8.dp))
            Text(if (busy) "Starting…" else if (protocol == DownloadProtocol.HTTP) "Start download" else "Review files")
        }
    }
}

@Composable
private fun TaskList(destination: String, tasks: List<DownloadTask>, selected: String?, select: (String) -> Unit,
    actions: @Composable (DownloadTask) -> Unit, modifier: Modifier) {
    var query by rememberSaveable(destination) { mutableStateOf("") }
    var filter by rememberSaveable(destination) { mutableStateOf("All") }
    val history = destination == "History"
    val filters = if (history) listOf("All", "Saved", "Failed", "Canceled") else listOf("All", "Running", "Paused", "Saving")
    val shown = tasks.filter { it.isHistory == history && it.state.fileName.contains(query, true) && when (filter) {
        "Saved" -> it.state.status == DownloadStatus.COMPLETED
        "Failed" -> it.state.status == DownloadStatus.FAILED
        "Canceled" -> it.state.status == DownloadStatus.CANCELED
        "Running" -> it.state.canPause
        "Paused" -> it.state.status == DownloadStatus.PAUSED
        "Saving" -> it.state.status in setOf(DownloadStatus.STAGED, DownloadStatus.EXPORTING, DownloadStatus.SAVE_FAILED, DownloadStatus.VERIFYING)
        else -> true
    } }.let { if (history) it else it.sortedBy { task -> task.createdAt } }
    val context = LocalContext.current
    val focus = LocalFocusManager.current
    LazyColumn(modifier, verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(top = 12.dp, bottom = 100.dp)) {
        item(key = "list-header") {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (!history) Notice(R.drawable.ic_speed, "${tasks.count { it.state.canPause }} active or queued · ${Formatter.formatShortFileSize(context, tasks.sumOf { it.state.bytesPerSecond })}/s")
                OutlinedTextField(query, { query = it }, label = { Text("Search downloads") }, singleLine = true,
                    leadingIcon = { AriaIcon(R.drawable.ic_search) },
                    trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { AriaIcon(R.drawable.ic_close, "Clear search") } },
                    shape = MaterialTheme.shapes.extraLarge,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }), modifier = Modifier.fillMaxWidth())
                FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    filters.forEach { label -> FilterChip(filter == label, { filter = label }, label = { Text(label) }) }
                }
            }
        }
        if (shown.isEmpty()) item {
            Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 40.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                AriaIcon(if (history) R.drawable.ic_history else R.drawable.ic_download)
                Text(if (query.isNotBlank() || filter != "All") "No matching downloads" else if (history) "No download history" else "No downloads yet",
                    style = MaterialTheme.typography.titleLarge)
                Text(if (query.isNotBlank() || filter != "All") "Try another search or filter." else if (history) "Saved, failed, and canceled downloads appear here." else "Add a link to start your first download.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        items(shown, key = { it.id }) { task ->
            Card(onClick = { select(task.id) }, shape = MaterialTheme.shapes.extraLarge, colors = CardDefaults.cardColors(containerColor =
                if (selected == task.id) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer)) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        AriaIcon(if (task.state.status == DownloadStatus.COMPLETED) R.drawable.ic_check_circle else R.drawable.ic_description)
                        Text(task.state.fileName, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    }
                    TaskProgress(task.state)
                    actions(task)
                }
            }
        }
    }
}

@Composable
private fun TaskDetails(task: DownloadTask, model: DownloadsViewModel, actions: @Composable (DownloadTask) -> Unit) {
    val context = LocalContext.current
    var report by rememberSaveable(task.id) { mutableStateOf<String?>(null) }
    report?.let { text ->
        AlertDialog(onDismissRequest = { report = null }, title = { Text("Diagnostics preview") },
            text = { SelectionContainer(Modifier.verticalScroll(rememberScrollState())) { Text(text) } },
            confirmButton = { TextButton(onClick = {
                try {
                    context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, text)
                    }, "Share diagnostics"))
                } catch (_: android.content.ActivityNotFoundException) { report = null }
            }) { Text("Share") } },
            dismissButton = { TextButton(onClick = { report = null }) { Text("Close") } })
    }
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
        Text(task.state.fileName, style = MaterialTheme.typography.headlineMedium)
        Section("Transfer", R.drawable.ic_download) { TaskProgress(task.state); actions(task) }
        if (task.files.isNotEmpty()) FileReview(task, model)
        TransferInsightsPanel(task)
        Section("Source", R.drawable.ic_link) { SelectionContainer { Text(task.source, style = MaterialTheme.typography.bodyMedium) } }
        Section("Configuration", R.drawable.ic_tune) {
            Text("${task.connections} connections per server")
            Text(if (task.speedLimit == 0L) "No speed limit" else "${Formatter.formatShortFileSize(LocalContext.current, task.speedLimit)}/s limit")
        }
        Section("Diagnostics", R.drawable.ic_description) {
            Text("Automatic retries: ${task.state.retryCount}/3")
            task.state.engineCode?.let { Text("Engine code: $it") }
            task.state.stopReason?.let { Text("Android stop reason: $it") }
            TextButton(onClick = {
                val version = context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
                report = transferDiagnostics(task.state, version, Build.VERSION.SDK_INT)
            }) { Text("Preview diagnostics") }
        }
        if (task.state.status == DownloadStatus.COMPLETED) Notice(R.drawable.ic_folder, "Saved to Downloads")
    }
}

@Composable
private fun TaskProgress(state: DownloadState) {
    val context = LocalContext.current
    Text(statusLabel(state.status), style = MaterialTheme.typography.labelLarge,
        color = if (state.status in setOf(DownloadStatus.FAILED, DownloadStatus.SAVE_FAILED)) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
    if (state.canPause || state.status in setOf(DownloadStatus.EXPORTING, DownloadStatus.VERIFYING)) {
        val total = if (state.status in setOf(DownloadStatus.EXPORTING, DownloadStatus.VERIFYING)) state.completedBytes else state.totalBytes
        val completed = if (state.status in setOf(DownloadStatus.EXPORTING, DownloadStatus.VERIFYING)) state.savedBytes else state.completedBytes
        if (total > 0) LinearProgressIndicator(progress = { (completed.toDouble() / total).toFloat().coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
        else LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
    }
    val size = Formatter.formatShortFileSize(context, state.completedBytes)
    val total = if (state.totalBytes > 0) " / ${Formatter.formatShortFileSize(context, state.totalBytes)}" else ""
    val speed = if (state.bytesPerSecond > 0) " · ${Formatter.formatShortFileSize(context, state.bytesPerSecond)}/s" else ""
    val eta = transferEtaText(state)?.let { " · $it left" }.orEmpty()
    Text(size + total + speed + eta, style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.semantics { stateDescription = transferEtaText(state, spoken = true)?.let { "$it remaining" }.orEmpty() })
    state.message?.let { Text(it, color = if (state.status in setOf(DownloadStatus.FAILED, DownloadStatus.SAVE_FAILED)) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant) }
}

@Composable
private fun Action(label: String, icon: Int, action: () -> Unit) {
    FilledTonalButton(onClick = action) {
        AriaIcon(icon)
        Spacer(Modifier.width(8.dp))
        Text(label)
    }
}

@Composable
private fun TaskActions(task: DownloadTask, model: DownloadsViewModel, open: (DownloadTask, Boolean) -> Unit, cancel: () -> Unit, delete: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp), itemVerticalAlignment = Alignment.CenterVertically) {
        if (task.state.canPause) Action("Pause", R.drawable.ic_pause) { model.pause(task.id) }
        if (task.state.canResume) Action("Resume", R.drawable.ic_play_arrow) { model.resume(task.id) }
        if (task.state.canRetrySaving) Action("Retry saving", R.drawable.ic_refresh) { model.retrySaving(task.id) }
        if (task.state.status == DownloadStatus.COMPLETED) {
            if (task.files.count { it.selected } <= 1) Action("Open", R.drawable.ic_open_in_new) { open(task, false) }
            IconButton(onClick = { open(task, true) }) { AriaIcon(R.drawable.ic_share, "Share") }
        }
        if (task.state.status in setOf(DownloadStatus.FAILED, DownloadStatus.CANCELED)) Action("Retry", R.drawable.ic_refresh) { model.retry(task.id) }
        if (task.state.status !in setOf(DownloadStatus.EXPORTING, DownloadStatus.VERIFYING)) Box {
            IconButton(onClick = { expanded = true }) { AriaIcon(R.drawable.ic_more_vert, "More") }
            DropdownMenu(expanded, { expanded = false }) {
                if (task.state.canCancel) DropdownMenuItem(text = { Text("Cancel download") }, leadingIcon = { AriaIcon(R.drawable.ic_cancel) }, onClick = { expanded = false; cancel() })
                DropdownMenuItem(text = { Text("Delete entry") }, leadingIcon = { AriaIcon(R.drawable.ic_delete) }, onClick = { expanded = false; delete() })
            }
        }
    }
}

@Composable
private fun Stepper(label: String, value: Int, range: IntRange, enabled: Boolean, change: (Int) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.titleSmall)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Text(value.toString(), style = MaterialTheme.typography.headlineSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalIconButton(onClick = { change(value - 1) }, enabled = enabled && value > range.first) {
                    AriaIcon(R.drawable.ic_remove, "Decrease $label")
                }
                FilledTonalIconButton(onClick = { change(value + 1) }, enabled = enabled && value < range.last) {
                    AriaIcon(R.drawable.ic_add, "Increase $label")
                }
            }
        }
    }
}

@Composable
internal fun SettingsScreen(
    state: SettingsEditorState,
    editor: SettingsEditor,
    backgroundStatus: BackgroundDownloadStatus = rememberBackgroundDownloadStatus(),
    openSystemSettings: ((Boolean) -> Boolean)? = null,
) {
    val context = LocalContext.current
    val settings = state.settings
    val focus = LocalFocusManager.current
    DisposableEffect(editor) { onDispose { editor.commitSpeed() } }
    Column(Modifier.widthIn(max = 640.dp).fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
        if (state.status == SettingsSaveStatus.LOADING || state.status == SettingsSaveStatus.ERROR) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            AriaIcon(R.drawable.ic_refresh)
            Text(if (state.status == SettingsSaveStatus.LOADING) "Loading settings…" else state.message.orEmpty(), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f),
                color = if (state.status == SettingsSaveStatus.ERROR) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
            if (state.status == SettingsSaveStatus.ERROR) TextButton(onClick = editor::retry) { Text("Retry") }
        }
        BackgroundDownloadsSettings(backgroundStatus, openSystemSettings ?: { battery ->
            openBackgroundSettings(battery, context::startActivity, context.packageName)
        })
        Section("Transfers", R.drawable.ic_tune) {
            Stepper("Simultaneous downloads", settings.concurrency, 1..6, editor.editable) { value -> editor.update { it.copy(concurrency = value) } }
            HorizontalDivider()
            Stepper("Connections per server", settings.connections, 1..16, editor.editable) { value -> editor.update { it.copy(connections = value) } }
            OutlinedTextField(state.speed, editor::editSpeed, label = { Text("Speed limit in bytes/s") },
                supportingText = { Text(if (state.validSpeed) "0 = unlimited" else "Enter a whole number of bytes/s (0 = unlimited).") },
                isError = !state.validSpeed, enabled = editor.editable, leadingIcon = { AriaIcon(R.drawable.ic_speed) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { editor.commitSpeed(); focus.clearFocus() }), singleLine = true,
                modifier = Modifier.fillMaxWidth().onFocusChanged { if (!it.isFocused) editor.commitSpeed() })
            Text("Connection and speed defaults apply to new downloads. Concurrency and network changes update the current queue.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Section("Network", R.drawable.ic_wifi) {
            Row(Modifier.fillMaxWidth().toggleable(settings.wifiOnly, enabled = editor.editable, role = Role.Switch,
                onValueChange = { value -> editor.update { it.copy(wifiOnly = value) } }),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Column(Modifier.weight(1f)) {
                    Text("Wi-Fi only", style = MaterialTheme.typography.titleSmall)
                    Text("Requires unmetered Wi-Fi", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(settings.wifiOnly, onCheckedChange = null, enabled = editor.editable)
            }
        }
        Section("Appearance", R.drawable.ic_palette) {
            Text("Colors follow your Android wallpaper.", style = MaterialTheme.typography.bodyMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Appearance.entries.forEach { value -> FilterChip(settings.appearance == value,
                    { editor.update { it.copy(appearance = value) } }, enabled = editor.editable,
                    label = { Text(value.name.lowercase().replaceFirstChar { it.uppercase() }) }) }
            }
        }
    }
}

internal fun statusLabel(status: DownloadStatus): String = when (status) {
    DownloadStatus.IDLE -> "Ready"
    DownloadStatus.SCHEDULED -> "Queued · Waiting to start"
    DownloadStatus.DOWNLOADING -> "Downloading"
    DownloadStatus.WAITING -> "Waiting for network or Android"
    DownloadStatus.PAUSED -> "Paused"
    DownloadStatus.STAGED -> "Ready to save"
    DownloadStatus.EXPORTING -> "Saving to Downloads…"
    DownloadStatus.SAVE_FAILED -> "Couldn’t save to Downloads"
    DownloadStatus.COMPLETED -> "Saved to Downloads"
    DownloadStatus.FAILED -> "Failed"
    DownloadStatus.CANCELED -> "Canceled"
    DownloadStatus.INTERRUPTED -> "Interrupted"
    DownloadStatus.VERIFYING -> "Verifying staged files…"
    DownloadStatus.METADATA -> "Resolving metadata…"
    DownloadStatus.AWAITING_SELECTION -> "Ready to select files"
}


@Composable
private fun FileReview(task: DownloadTask, model: DownloadsViewModel) {
    val context = LocalContext.current
    val reviewing = task.state.status == DownloadStatus.AWAITING_SELECTION
    var chosen by rememberSaveable(task.id) { mutableStateOf(task.files.filter { it.selected && it.supported }.map { it.index }.toIntArray()) }
    val selected = chosen.toSet()
    Section("Files", R.drawable.ic_folder) {
        if (reviewing) {
            Text("${selected.size} selected · ${Formatter.formatShortFileSize(context, task.files.filter { it.index in selected }.sumOf { it.size })}")
            Row {
                TextButton(onClick = { chosen = task.files.filter { it.supported }.map { it.index }.toIntArray() }) { Text("Select all") }
                TextButton(onClick = { chosen = intArrayOf() }) { Text("Clear selection") }
            }
        }
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 360.dp)) {
            items(task.files, key = { it.index }) { file ->
                Row(Modifier.fillMaxWidth().then(if (reviewing && file.supported) Modifier.toggleable(file.index in selected,
                    role = Role.Checkbox, onValueChange = { checked -> chosen = if (checked) (selected + file.index).toIntArray() else (selected - file.index).toIntArray() }) else Modifier)
                    .padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (reviewing) Checkbox(file.index in selected, onCheckedChange = null, enabled = file.supported)
                    Column(Modifier.weight(1f)) {
                        Text(file.path, style = MaterialTheme.typography.bodyMedium)
                        Text(if (!file.supported) "No supported HTTP/HTTPS mirror" else if (!reviewing && !file.selected) "Not selected" else
                            Formatter.formatShortFileSize(context, file.size), style = MaterialTheme.typography.bodySmall)
                    }
                    if (task.state.status == DownloadStatus.COMPLETED && file.selected && file.published) IconButton(onClick = {
                        model.open(task, false, file.index) { context.startActivity(it) }
                    }) { AriaIcon(R.drawable.ic_open_in_new, "Open ${file.path}") }
                }
            }
        }
        if (reviewing) Button(onClick = { model.startSelected(task.id, chosen.toList()) }, enabled = chosen.isNotEmpty()) { Text("Start selected files") }
    }
}
