package com.julien_wff.aria_android

import android.Manifest
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.julien_wff.aria_android.downloads.*
import com.julien_wff.aria_android.ui.theme.AriaTheme

@Composable
internal fun DownloadsScreen(model: DownloadsViewModel) {
    val tasks by model.tasks.collectAsStateWithLifecycle()
    val loaded by model.loaded.collectAsStateWithLifecycle()
    val settings by model.settings.collectAsStateWithLifecycle()
    val error by model.error.collectAsStateWithLifecycle()
    val busy by model.busy.collectAsStateWithLifecycle()
    val incoming by model.incoming.collectAsStateWithLifecycle()
    val consent by model.deletionConsent.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var destination by rememberSaveable { mutableStateOf("Downloads") }
    var adding by rememberSaveable { mutableStateOf(false) }
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    var url by rememberSaveable { mutableStateOf("") }
    var name by rememberSaveable { mutableStateOf("download") }
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
    BackHandler(adding || selected != null) { if (adding) adding = false else selected = null }
    AriaTheme(settings.appearance) {
        BoxWithConstraints(Modifier.fillMaxSize().imePadding()) {
            val wide = maxWidth >= 840.dp
            val useRail = maxWidth >= 600.dp
            val destinations = listOf("Downloads", "History", "Settings")
            Scaffold(
                bottomBar = { if (!useRail) NavigationBar {
                    destinations.forEach { label -> NavigationBarItem(selected = destination == label,
                        onClick = { destination = label; selected = null; adding = false },
                        icon = { Text(label.take(1)) }, label = { Text(label) }) }
                } },
                floatingActionButton = {
                    if (!adding && destination != "Settings") ExtendedFloatingActionButton(onClick = {
                        adding = true; selected = null; url = ""; name = "download"
                    }) { Text("Add download") }
                },
            ) { padding ->
                Row(Modifier.fillMaxSize().padding(padding)) {
                    if (useRail) NavigationRail {
                        destinations.forEach { label -> NavigationRailItem(selected = destination == label,
                            onClick = { destination = label; selected = null; adding = false },
                            icon = { Text(label.take(1)) }, label = { Text(label) }) }
                    }
                    Column(Modifier.weight(1f).fillMaxHeight().padding(horizontal = 20.dp)) {
                        if (error != null) {
                            Text(error!!, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 12.dp))
                            TextButton(onClick = model::clearError) { Text("Dismiss") }
                        }
                        if (denied) Text("Notifications are disabled. Download controls remain available here.")
                        when {
                            adding -> AddDownload(url, { url = it }, name, { name = it }, busy,
                                close = { adding = false }, start = {
                                    model.prepare(url, name)
                                    if (!model.askedNotificationPermission && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                                        model.askedNotificationPermission = true
                                        permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                                    } else model.startPending { adding = false; destination = "Downloads" }
                                })
                            destination == "Settings" -> SettingsScreen(settings, busy) { value -> model.saveSettings(value) { } }
                            else -> {
                                val task = tasks.find { it.id == selected }
                                val actions: @Composable (DownloadTask) -> Unit = { item -> TaskActions(item, model, open,
                                    cancel = { cancelId = item.id }, delete = { deleteId = item.id; deletePublic = false }) }
                                if (!wide && selected != null) {
                                    TextButton(onClick = { selected = null }) { Text("Back to $destination") }
                                    if (task != null) TaskDetails(task, actions) else Text("This download is no longer available.")
                                } else Row(Modifier.weight(1f)) {
                                    TaskList(destination, tasks, selected, { selected = it }, actions, Modifier.weight(1f))
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

private fun suggestedName(url: String): String = runCatching {
    java.net.URI(url).path.substringAfterLast('/').takeIf { it.isNotBlank() && it != "." && it != ".." && it.none { char -> char == '\\' || char.isISOControl() } }
}.getOrNull() ?: "download"

@Composable
private fun AddDownload(url: String, onUrl: (String) -> Unit, name: String, onName: (String) -> Unit,
    busy: Boolean, close: () -> Unit, start: () -> Unit) {
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(vertical = 20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Add download", style = MaterialTheme.typography.headlineLarge)
        Text("Files are saved to Downloads/Aria after the transfer completes.")
        OutlinedTextField(url, onUrl, label = { Text("HTTP or HTTPS URL") }, modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), singleLine = true)
        OutlinedTextField(name, onName, label = { Text("Filename") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = start, enabled = !busy && url.isNotBlank() && name.isNotBlank()) { Text("Start download") }
            TextButton(onClick = close) { Text("Back") }
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
    LazyColumn(modifier, verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom = 100.dp)) {
        item(key = "list-header") {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(destination, style = MaterialTheme.typography.headlineLarge, modifier = Modifier.padding(top = 20.dp))
                if (!history) Text("${tasks.count { it.state.canPause }} active or queued · ${Formatter.formatShortFileSize(context, tasks.sumOf { it.state.bytesPerSecond })}/s")
                OutlinedTextField(query, { query = it }, label = { Text("Search downloads") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    filters.forEach { label -> FilterChip(filter == label, { filter = label }, label = { Text(label) }) }
                }
            }
        }
        if (shown.isEmpty()) item { Text(if (query.isNotBlank() || filter != "All") "No matching downloads" else "No downloads yet") }
        items(shown, key = { it.id }) { task ->
            Card(onClick = { select(task.id) }, colors = CardDefaults.cardColors(containerColor =
                if (selected == task.id) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer)) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(task.state.fileName, style = MaterialTheme.typography.titleMedium)
                    TaskProgress(task.state)
                    actions(task)
                }
            }
        }
    }
}

@Composable
private fun TaskDetails(task: DownloadTask, actions: @Composable (DownloadTask) -> Unit) {
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(vertical = 20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(task.state.fileName, style = MaterialTheme.typography.headlineMedium)
        TaskProgress(task.state)
        Text("Source", style = MaterialTheme.typography.titleMedium)
        Text(task.source)
        Text("${task.connections} connections per server")
        Text(if (task.speedLimit == 0L) "No speed limit" else "${Formatter.formatShortFileSize(LocalContext.current, task.speedLimit)}/s limit")
        if (task.state.status == DownloadStatus.COMPLETED) Text("Saved in Downloads/Aria")
        actions(task)
    }
}

@Composable
private fun TaskProgress(state: DownloadState) {
    val context = LocalContext.current
    Text(statusLabel(state.status))
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
private fun TaskActions(task: DownloadTask, model: DownloadsViewModel, open: (DownloadTask, Boolean) -> Unit, cancel: () -> Unit, delete: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (task.state.canPause) TextButton(onClick = { model.pause(task.id) }) { Text("Pause") }
        if (task.state.canResume) TextButton(onClick = { model.resume(task.id) }) { Text("Resume") }
        if (task.state.canRetrySaving) TextButton(onClick = { model.retrySaving(task.id) }) { Text("Retry saving") }
        if (task.state.status == DownloadStatus.COMPLETED) {
            TextButton(onClick = { open(task, false) }) { Text("Open") }
            TextButton(onClick = { open(task, true) }) { Text("Share") }
        }
        if (task.state.status in setOf(DownloadStatus.FAILED, DownloadStatus.CANCELED)) TextButton(onClick = { model.retry(task.id) }) { Text("Retry") }
        if (task.state.status != DownloadStatus.EXPORTING) Box {
            TextButton(onClick = { expanded = true }) { Text("More") }
            DropdownMenu(expanded, { expanded = false }) {
                if (task.state.canCancel) DropdownMenuItem(text = { Text("Cancel download") }, onClick = { expanded = false; cancel() })
                DropdownMenuItem(text = { Text("Delete entry") }, onClick = { expanded = false; delete() })
            }
        }
    }
}

@Composable
private fun SettingsScreen(settings: DownloadSettings, busy: Boolean, save: (DownloadSettings) -> Unit) {
    var concurrency by rememberSaveable(settings.concurrency) { mutableStateOf(settings.concurrency.toString()) }
    var connections by rememberSaveable(settings.connections) { mutableStateOf(settings.connections.toString()) }
    var speed by rememberSaveable(settings.speedLimit) { mutableStateOf(settings.speedLimit.toString()) }
    var wifi by rememberSaveable(settings.wifiOnly) { mutableStateOf(settings.wifiOnly) }
    var appearance by rememberSaveable(settings.appearance) { mutableStateOf(settings.appearance.name) }
    var saved by rememberSaveable { mutableStateOf(false) }
    val valid = concurrency.toIntOrNull() in 1..6 && connections.toIntOrNull() in 1..16 && (speed.toLongOrNull() ?: -1) >= 0
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(vertical = 20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Settings", style = MaterialTheme.typography.headlineLarge)
        OutlinedTextField(concurrency, { concurrency = it; saved = false }, label = { Text("Simultaneous downloads (1–6)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(connections, { connections = it; saved = false }, label = { Text("Connections per server (1–16)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(speed, { speed = it; saved = false }, label = { Text("Speed limit in bytes/s (0 = unlimited)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true, modifier = Modifier.fillMaxWidth())
        Text("Connection and speed defaults apply to new downloads. Concurrency and network changes update the current queue.")
        Row(Modifier.fillMaxWidth().clickable { wifi = !wifi; saved = false }, horizontalArrangement = Arrangement.SpaceBetween) {
            Column(Modifier.weight(1f)) { Text("Wi-Fi only"); Text("Requires unmetered Wi-Fi", style = MaterialTheme.typography.bodySmall) }
            Switch(wifi, { wifi = it; saved = false })
        }
        Text("Appearance", style = MaterialTheme.typography.titleMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Appearance.entries.forEach { value -> FilterChip(appearance == value.name, { appearance = value.name; saved = false }, label = { Text(value.name.lowercase().replaceFirstChar { it.uppercase() }) }) }
        }
        Button(enabled = valid && !busy, onClick = {
            save(DownloadSettings(concurrency.toInt(), connections.toInt(), speed.toLong(), wifi, Appearance.valueOf(appearance)))
            saved = true
        }) { Text(if (saved && !busy && valid && settings == DownloadSettings(concurrency.toInt(), connections.toInt(), speed.toLong(), wifi, Appearance.valueOf(appearance))) "Settings saved" else "Save settings") }
        if (!valid) Text("Enter valid whole numbers in the ranges shown.", color = MaterialTheme.colorScheme.error)
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
    DownloadStatus.SAVE_FAILED -> "Saving failed · Staged file preserved"
    DownloadStatus.COMPLETED -> "Saved to Downloads"
    DownloadStatus.FAILED -> "Failed"
    DownloadStatus.CANCELED -> "Canceled"
    DownloadStatus.INTERRUPTED -> "Interrupted"
}
