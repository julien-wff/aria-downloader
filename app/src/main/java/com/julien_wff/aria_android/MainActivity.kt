package com.julien_wff.aria_android

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.os.Bundle
import android.text.format.Formatter
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.julien_wff.aria_android.downloads.DownloadRuntime
import com.julien_wff.aria_android.downloads.DownloadState
import com.julien_wff.aria_android.downloads.DownloadStatus
import com.julien_wff.aria_android.ui.theme.AriaTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { AriaTheme { DownloadsScreen() } }
    }
}

class DownloadsViewModel(application: Application) : AndroidViewModel(application) {
    private val controller = DownloadRuntime.controller(application)
    val state = controller.state
    private val mutableError = MutableStateFlow<String?>(null)
    val error = mutableError.asStateFlow()
    private val mutableBusy = MutableStateFlow(false)
    val busy = mutableBusy.asStateFlow()
    var askedNotificationPermission = false
    private var pendingRequest: Pair<String, String>? = null

    fun prepare(url: String, name: String) { pendingRequest = url to name }
    fun startPending() {
        val request = pendingRequest ?: return
        pendingRequest = null
        act { controller.start(request.first, request.second) }
    }
    fun pause() = act { controller.pause() }
    fun resume() = act { controller.resume() }
    fun cancel() = act { controller.cancel() }

    private fun act(action: suspend () -> Unit) {
        if (mutableBusy.value) return
        mutableBusy.value = true
        mutableError.value = null
        viewModelScope.launch {
            try { action() }
            catch (canceled: CancellationException) { throw canceled }
            catch (error: IllegalArgumentException) { mutableError.value = error.message ?: "Check the URL and filename." }
            catch (_: Exception) { mutableError.value = "Unable to update the download. Check available storage and try again." }
            finally { mutableBusy.value = false }
        }
    }
}

@Composable
internal fun DownloadsScreen(model: DownloadsViewModel = viewModel()) {
    val state by model.state.collectAsStateWithLifecycle()
    val error by model.error.collectAsStateWithLifecycle()
    val busy by model.busy.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var url by rememberSaveable { mutableStateOf("") }
    var name by rememberSaveable { mutableStateOf("download") }
    var denied by rememberSaveable { mutableStateOf(false) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        denied = !granted
        model.startPending()
    }
    Scaffold { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())
            .padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(stringResource(R.string.downloads_title), style = MaterialTheme.typography.headlineLarge)
            Text("Download one file at a time. Files stay inside this app; saving to your Downloads folder is coming later.",
                style = MaterialTheme.typography.bodyMedium)
            OutlinedTextField(url, { url = it }, label = { Text("HTTP or HTTPS URL") },
                modifier = Modifier.fillMaxWidth(), enabled = state.canStart && !busy,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), singleLine = true)
            OutlinedTextField(name, { name = it }, label = { Text("Filename") },
                modifier = Modifier.fillMaxWidth(), enabled = state.canStart && !busy, singleLine = true)
            Button(enabled = state.canStart && !busy && url.isNotBlank() && name.isNotBlank(), onClick = {
                model.prepare(url, name)
                if (!model.askedNotificationPermission && context.checkSelfPermission(
                        Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                    model.askedNotificationPermission = true
                    permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else model.startPending()
            }) { Text("Start download") }
            if (state.status == DownloadStatus.IDLE) {
                Text("No downloads yet", style = MaterialTheme.typography.titleLarge)
            } else {
                Text(state.fileName, style = MaterialTheme.typography.titleLarge)
                Text(statusLabel(state.status))
                if (state.canPause) {
                    if (state.totalBytes > 0) {
                        LinearProgressIndicator(progress = {
                            (state.completedBytes.toDouble() / state.totalBytes).toFloat().coerceIn(0f, 1f)
                        }, modifier = Modifier.fillMaxWidth())
                    } else LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                if (state.completedBytes > 0 || state.totalBytes > 0) Text(transferDescription(state, context))
                state.message?.let { Text(it) }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (state.canPause) OutlinedButton(onClick = model::pause, enabled = !busy) { Text("Pause") }
                    if (state.canResume) Button(onClick = model::resume, enabled = !busy) { Text("Resume") }
                    if (state.canCancel) OutlinedButton(onClick = model::cancel, enabled = !busy) { Text("Cancel") }
                }
            }
            if (denied) Text("Notifications are disabled. Use this screen to pause or cancel your transfer.")
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }
}

private fun statusLabel(status: DownloadStatus): String = when (status) {
    DownloadStatus.IDLE -> "Ready"
    DownloadStatus.SCHEDULED -> "Waiting to start"
    DownloadStatus.DOWNLOADING -> "Downloading"
    DownloadStatus.WAITING -> "Waiting for network or Android"
    DownloadStatus.PAUSED -> "Paused"
    DownloadStatus.COMPLETED -> "Downloaded to private staging"
    DownloadStatus.FAILED -> "Failed"
    DownloadStatus.CANCELED -> "Canceled"
    DownloadStatus.INTERRUPTED -> "Interrupted"
}

private fun transferDescription(state: DownloadState, context: android.content.Context): String {
    val completed = Formatter.formatShortFileSize(context, state.completedBytes)
    val total = if (state.totalBytes > 0) " / ${Formatter.formatShortFileSize(context, state.totalBytes)}" else ""
    val speed = if (state.bytesPerSecond > 0) " · ${Formatter.formatShortFileSize(context, state.bytesPerSecond)}/s" else ""
    return completed + total + speed
}
