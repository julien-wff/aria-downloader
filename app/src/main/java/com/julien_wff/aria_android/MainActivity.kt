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
import com.julien_wff.aria_android.downloads.DownloadProtocol
import com.julien_wff.aria_android.downloads.downloadProtocol
import com.julien_wff.aria_android.downloads.MetadataImports
import com.julien_wff.aria_android.downloads.DownloadRequestOptions
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

data class IncomingDownload(val text: String? = null, val taskId: String? = null, val fileAction: String? = null, val protocol: DownloadProtocol? = null)

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
    private data class PendingRequest(val url: String, val name: String, val options: DownloadRequestOptions, val protocol: DownloadProtocol)
    private var pendingRequest: PendingRequest? = null

    init {
        viewModelScope.launch {
            controller.loaded.first { it }
            if (controller.tasks.value.isEmpty()) controller.state.value.message?.let { mutableError.value = it }
        }
    }

    fun receive(intent: Intent) {
        val shared = if (intent.action == Intent.ACTION_SEND) intent.getParcelableExtra(Intent.EXTRA_STREAM, android.net.Uri::class.java) else null
        val document = shared ?: if (intent.action == Intent.ACTION_VIEW && intent.data?.scheme == "content") intent.data else null
        if (document != null) {
            act(true) {
                val imported = withContext(Dispatchers.IO) { MetadataImports(getApplication()).copy(document, intent.type) }
                incoming.value = IncomingDownload(text = imported.first, protocol = imported.second)
            }
        } else if (intent.action == Intent.ACTION_VIEW && intent.data?.scheme == "magnet") {
            incoming.value = IncomingDownload(text = intent.dataString)
        } else if (intent.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            incoming.value = IncomingDownload(text = intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty().trim())
        } else intent.getStringExtra("taskId")?.let {
            incoming.value = IncomingDownload(taskId = it, fileAction = intent.getStringExtra("fileAction"))
        }
    }
    fun prepare(url: String, name: String, referrer: String = "", userAgent: String = "", protocol: DownloadProtocol = downloadProtocol(url)): Boolean {
        pendingRequest = null
        return try {
            val options = DownloadRequestOptions.fromInput(referrer, userAgent)
            controller.validateRequest(url, name, options, protocol)
            pendingRequest = PendingRequest(url, name, options, protocol)
            clearError()
            true
        } catch (error: IllegalArgumentException) {
            showError(error.message ?: "Check the URL and filename.")
            false
        }
    }
    fun startPending(onAdded: () -> Unit) {
        val request = pendingRequest ?: return
        pendingRequest = null
        act(true) {
            controller.enqueue(request.url, request.name, preferences.read(), request.options, request.protocol)
            onAdded()
        }
    }
    fun startSelected(id: String, indexes: List<Int>) = act(true) { controller.startSelectedFiles(id, indexes, preferences.read()) }
    fun pause(id: String) = act { controller.pauseTask(id) }
    fun resume(id: String) = act { controller.resumeTask(id, preferences.read()) }
    fun cancel(id: String) = act { controller.cancelTask(id) }
    fun retry(id: String) = act { controller.retry(id, preferences.read()) }
    fun retrySaving(id: String) = act { controller.retrySavingTask(id) }
    internal val settingsEditor = SettingsEditor(viewModelScope, preferences::read) { value ->
        preferences.save(value)
        controller.applySettings(value)
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
    fun open(task: DownloadTask, share: Boolean, fileIndex: Int? = null, launch: (Intent) -> Unit) = act {
        val intent = withContext(Dispatchers.IO) { files.intent(task, share, fileIndex) }
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
