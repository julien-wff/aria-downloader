package com.julien_wff.aria_android

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
    var destination by rememberSaveable { mutableStateOf("Downloads") }
    var adding by rememberSaveable { mutableStateOf(false) }
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    var url by rememberSaveable { mutableStateOf("") }
    var name by rememberSaveable { mutableStateOf("download") }
    var referrer by rememberSaveable { mutableStateOf("") }
    var userAgent by rememberSaveable { mutableStateOf("") }
    var advancedExpanded by rememberSaveable { mutableStateOf(false) }
    var denied by rememberSaveable { mutableStateOf(false) }
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
            name = suggestedName(request.text)
            adding = true
            model.incoming.value = null
        } else if (request.taskId != null) {
            selected = request.taskId
            val task = tasks.find { it.id == request.taskId }
            if (task != null) {
                destination = if (task.isHistory) "History" else "Downloads"
                request.fileAction?.let { open(task, it == "share") }
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
                url = draft?.url.orEmpty(); name = draft?.fileName ?: "download"; adding = true
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
                        if (denied) Notice(R.drawable.ic_notifications_off, "Notifications are disabled. Download controls remain available here.")
                        when {
                            adding -> AddDownload(url, { url = it; model.clearError() }, name, { name = it; model.clearError() }, busy,
                                error = error,
                                referrer = referrer, onReferrer = { referrer = it; model.clearError() },
                                userAgent = userAgent, onUserAgent = { userAgent = it; model.clearError() },
                                advancedExpanded = advancedExpanded, onAdvancedExpanded = { advancedExpanded = it }, start = {
                                    focus.clearFocus()
                                    if (model.prepare(url, name, referrer, userAgent)) {
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
                                    if (task != null) TaskDetails(task, actions) else Notice(R.drawable.ic_description, "This download is no longer available.")
                                } else Row(Modifier.weight(1f)) {
                                    TaskList(destination, tasks, selected, { focus.clearFocus(); selected = it }, actions, Modifier.weight(1f))
                                    if (wide && task != null) {
                                        VerticalDivider(Modifier.fillMaxHeight().padding(horizontal = 16.dp))
                                        Box(Modifier.weight(1f)) { TaskDetails(task, actions) }
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
                    if (task?.state?.publishedUri != null) Row(Modifier.fillMaxWidth().clickable { deletePublic = !deletePublic }) {
                        Checkbox(deletePublic, { deletePublic = it }); Text("Also delete the file from Downloads", Modifier.padding(top = 12.dp))
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
private fun Section(title: String, icon: Int, content: @Composable ColumnScope.() -> Unit) {
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
    busy: Boolean, error: String?, referrer: String, onReferrer: (String) -> Unit,
    userAgent: String, onUserAgent: (String) -> Unit, advancedExpanded: Boolean,
    onAdvancedExpanded: (Boolean) -> Unit, start: () -> Unit) {
    val focus = LocalFocusManager.current
    Column(Modifier.widthIn(max = 640.dp).fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
        Section("File to download", R.drawable.ic_link) {
            OutlinedTextField(url, onUrl, label = { Text("HTTP or HTTPS URL") }, modifier = Modifier.fillMaxWidth(),
                leadingIcon = { AriaIcon(R.drawable.ic_link) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
                keyboardActions = KeyboardActions(onNext = { focus.moveFocus(FocusDirection.Down) }), singleLine = true)
            OutlinedTextField(name, onName, label = { Text("Filename") }, modifier = Modifier.fillMaxWidth(),
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
            Text(if (busy) "Starting…" else "Start download")
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
        "Saving" -> it.state.status in setOf(DownloadStatus.STAGED, DownloadStatus.EXPORTING, DownloadStatus.SAVE_FAILED)
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
private fun TaskDetails(task: DownloadTask, actions: @Composable (DownloadTask) -> Unit) {
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
        Text(task.state.fileName, style = MaterialTheme.typography.headlineMedium)
        Section("Transfer", R.drawable.ic_download) { TaskProgress(task.state); actions(task) }
        Section("Source", R.drawable.ic_link) { SelectionContainer { Text(task.source, style = MaterialTheme.typography.bodyMedium) } }
        Section("Configuration", R.drawable.ic_tune) {
            Text("${task.connections} connections per server")
            Text(if (task.speedLimit == 0L) "No speed limit" else "${Formatter.formatShortFileSize(LocalContext.current, task.speedLimit)}/s limit")
        }
        if (task.state.status == DownloadStatus.COMPLETED) Notice(R.drawable.ic_folder, "Saved to Downloads")
    }
}

@Composable
private fun TaskProgress(state: DownloadState) {
    val context = LocalContext.current
    Text(statusLabel(state.status), style = MaterialTheme.typography.labelLarge,
        color = if (state.status in setOf(DownloadStatus.FAILED, DownloadStatus.SAVE_FAILED)) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
    if (state.canPause || state.status == DownloadStatus.EXPORTING) {
        val total = if (state.status == DownloadStatus.EXPORTING) state.completedBytes else state.totalBytes
        val completed = if (state.status == DownloadStatus.EXPORTING) state.savedBytes else state.completedBytes
        if (total > 0) LinearProgressIndicator(progress = { (completed.toDouble() / total).toFloat().coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
        else LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
    }
    val size = Formatter.formatShortFileSize(context, state.completedBytes)
    val total = if (state.totalBytes > 0) " / ${Formatter.formatShortFileSize(context, state.totalBytes)}" else ""
    val speed = if (state.bytesPerSecond > 0) " · ${Formatter.formatShortFileSize(context, state.bytesPerSecond)}/s" else ""
    val eta = if (state.totalBytes > state.completedBytes && state.bytesPerSecond > 0) " · ${(state.totalBytes - state.completedBytes) / state.bytesPerSecond}s left" else ""
    Text(size + total + speed + eta, style = MaterialTheme.typography.bodySmall)
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
            Action("Open", R.drawable.ic_open_in_new) { open(task, false) }
            IconButton(onClick = { open(task, true) }) { AriaIcon(R.drawable.ic_share, "Share") }
        }
        if (task.state.status in setOf(DownloadStatus.FAILED, DownloadStatus.CANCELED)) Action("Retry", R.drawable.ic_refresh) { model.retry(task.id) }
        if (task.state.status != DownloadStatus.EXPORTING) Box {
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
internal fun SettingsScreen(state: SettingsEditorState, editor: SettingsEditor) {
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
}
