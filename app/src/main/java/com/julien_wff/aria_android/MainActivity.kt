package com.julien_wff.aria_android

import android.app.Application
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.julien_wff.aria_android.downloads.DownloadFiles
import com.julien_wff.aria_android.downloads.DownloadRuntime
import com.julien_wff.aria_android.downloads.DownloadSettings
import com.julien_wff.aria_android.downloads.DownloadTask
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private val model by lazy { ViewModelProvider(this)[DownloadsViewModel::class.java] }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) model.receive(intent)
        setContent { DownloadsScreen(model) }
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Consume each payload once without replacing the Activity's restoration intent.
        model.receive(intent)
    }
}

data class IncomingDownload(val text: String? = null, val taskId: String? = null, val fileAction: String? = null)

class DownloadsViewModel(application: Application) : AndroidViewModel(application) {
    private val controller = DownloadRuntime.controller(application)
    private val preferences = DownloadRuntime.preferences(application)
    private val files = DownloadFiles(application, controller)
    val tasks = controller.tasks
    val loaded = controller.loaded
    private val mutableError = MutableStateFlow<String?>(null)
    val settings = preferences.settings.catch {
        mutableError.value = "Unable to load settings. Check storage before starting a download."
        emit(DownloadSettings())
    }.stateIn(viewModelScope, SharingStarted.Eagerly, DownloadSettings())
    val error = mutableError.asStateFlow()
    private val mutableBusy = MutableStateFlow(false)
    val busy = mutableBusy.asStateFlow()
    val incoming = MutableStateFlow<IncomingDownload?>(null)
    val deletionConsent = MutableStateFlow<android.content.IntentSender?>(null)
    private var pendingDeletion: Pair<String, Boolean>? = null
    var askedNotificationPermission = false
    private var pendingRequest: Pair<String, String>? = null

    init {
        viewModelScope.launch {
            controller.loaded.first { it }
            if (controller.tasks.value.isEmpty()) controller.state.value.message?.let { mutableError.value = it }
        }
    }

    fun receive(intent: Intent) {
        if (intent.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            incoming.value = IncomingDownload(text = intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty().trim())
        } else intent.getStringExtra("taskId")?.let {
            incoming.value = IncomingDownload(taskId = it, fileAction = intent.getStringExtra("fileAction"))
        }
    }
    fun prepare(url: String, name: String) { pendingRequest = url to name }
    fun startPending(onAdded: () -> Unit) {
        val request = pendingRequest ?: return
        pendingRequest = null
        act(true) {
            controller.enqueue(request.first, request.second, preferences.read())
            onAdded()
        }
    }
    fun pause(id: String) = act { controller.pauseTask(id) }
    fun resume(id: String) = act { controller.resumeTask(id, preferences.read()) }
    fun cancel(id: String) = act { controller.cancelTask(id) }
    fun retry(id: String) = act { controller.retry(id, preferences.read()) }
    fun retrySaving(id: String) = act { controller.retrySavingTask(id) }
    fun saveSettings(value: DownloadSettings, onSaved: () -> Unit) = act(true) {
        preferences.save(value)
        controller.applySettings(value)
        onSaved()
    }
    fun delete(id: String, publicFile: Boolean) = act(true) {
        val sender = files.delete(id, publicFile)
        if (sender != null) {
            pendingDeletion = id to publicFile
            deletionConsent.value = sender
        } else pendingDeletion = null
    }
    fun deletionResult(approved: Boolean) {
        deletionConsent.value = null
        val pending = pendingDeletion
        pendingDeletion = null
        if (approved && pending != null) delete(pending.first, pending.second)
        else if (!approved) showError("File deletion was declined. The history entry was kept.")
    }
    fun open(task: DownloadTask, share: Boolean, launch: (Intent) -> Unit) = act {
        val intent = withContext(Dispatchers.IO) { files.intent(task, share) }
        launch(intent)
    }
    fun clearError() { mutableError.value = null }
    fun showError(message: String) { mutableError.value = message }
    private fun act(exclusive: Boolean = false, action: suspend () -> Unit) {
        if (exclusive && mutableBusy.value) return
        if (exclusive) mutableBusy.value = true
        mutableError.value = null
        viewModelScope.launch {
            try { action() }
            catch (canceled: CancellationException) { throw canceled }
            catch (error: IllegalArgumentException) { mutableError.value = error.message ?: "Check the URL and filename." }
            catch (_: android.content.ActivityNotFoundException) { mutableError.value = "No app can open this file. Try Share instead." }
            catch (_: java.io.FileNotFoundException) { mutableError.value = "File is no longer available in Downloads." }
            catch (_: Exception) { mutableError.value = "Unable to apply this action. Check storage and try again; your saved data is preserved." }
            finally { if (exclusive) mutableBusy.value = false }
        }
    }
}
