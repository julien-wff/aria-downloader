package com.julien_wff.aria_android.downloads

import android.app.RecoverableSecurityException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.net.Uri
import android.provider.MediaStore
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Public-file actions and private staging cleanup stay in the storage module. */
class DownloadFiles(private val context: Context, private val controller: DownloadController) {
    fun intent(task: DownloadTask, share: Boolean, fileIndex: Int? = null): Intent {
        check(task.state.status == DownloadStatus.COMPLETED) { "These files have not been saved to Downloads." }
        val selected = task.files.filter { it.selected && (fileIndex == null || it.index == fileIndex) }
        val uris = if (task.files.isEmpty()) listOf(Uri.parse(requireNotNull(task.state.publishedUri))) else selected.map {
            check(it.published); Uri.parse(requireNotNull(it.publishedUri))
        }
        require(uris.isNotEmpty())
        require(share || uris.size == 1) { "Choose a file from download details to open." }
        val resolver = context.contentResolver
        uris.forEach { uri -> resolver.openFileDescriptor(uri, "r")?.use { } ?: throw IOException("File is no longer available in Downloads.") }
        val mime = if (uris.size > 1) "*/*" else resolver.getType(uris.single())?.takeUnless { it == "application/octet-stream" }
            ?: mimeForFileName(selected.firstOrNull()?.path ?: task.state.fileName)
        val intent = when {
            share && uris.size > 1 -> Intent(Intent.ACTION_SEND_MULTIPLE).setType(mime).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
            share -> Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uris.single())
            else -> Intent(Intent.ACTION_VIEW).setDataAndType(uris.single(), mime)
        }
        intent.clipData = ClipData.newRawUri("Downloaded file", uris.first()).apply {
            uris.drop(1).forEach { addItem(ClipData.Item(it)) }
        }
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return if (share) Intent.createChooser(intent, "Share download") else intent
    }

    /** A returned sender needs user consent. Retry only after a successful consent result. */
    suspend fun delete(id: String, deletePublic: Boolean): IntentSender? {
        controller.cancelTask(id)
        val record = controller.recordForDeletion(id)
        check(record.status in setOf(DownloadStatus.COMPLETED, DownloadStatus.FAILED, DownloadStatus.CANCELED))
        val consent = withContext(Dispatchers.IO) {
            val destination = DownloadsDestination(context)
            val uris = if (record.files.isEmpty()) listOfNotNull(record.publishedUri ?: destination.find(record)) else
                record.files.filter { it.selected }.mapNotNull { file -> file.publishedUri ?: destination.find(record.copy(
                    id = "${record.id}-file-${file.index}", fileName = file.path)) }
            for (uri in uris.distinct()) {
                if (deletePublic || destination.isPublished(uri) == false) {
                    try { context.contentResolver.delete(Uri.parse(uri), null, null) }
                    catch (error: RecoverableSecurityException) { return@withContext error.userAction.actionIntent.intentSender }
                    catch (_: SecurityException) {
                        return@withContext MediaStore.createDeleteRequest(context.contentResolver,
                            uris.filter { destination.isPublished(it) != null }.map(Uri::parse)).intentSender
                    }
                    if (destination.isPublished(uri) != null) throw IOException("Unable to delete the Downloads file. Entry kept.")
                }
            }
            record.taskId?.let { taskId ->
                require(taskId.matches(Regex("[0-9a-f]{16}")))
                val root = File(context.noBackupFilesDir, "aria2")
                val directory = File(root, taskId)
                check(directory.canonicalFile.parentFile == root.canonicalFile)
                if (directory.exists() && !directory.deleteRecursively()) throw IOException("Unable to remove private staging. Entry kept.")
            }
            null
        }
        if (consent == null) {
            controller.deleteTask(id)
            if (record.url.startsWith("file:") && !controller.sourceReferenced(record.url)) withContext(Dispatchers.IO) {
                val imported = File(java.net.URI(record.url)).canonicalFile
                if (imported.parentFile == File(context.noBackupFilesDir, "aria2/imports").canonicalFile) imported.delete()
            }
        }
        return consent
    }
}
