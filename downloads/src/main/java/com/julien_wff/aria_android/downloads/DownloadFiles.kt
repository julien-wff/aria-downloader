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
    fun intent(task: DownloadTask, share: Boolean): Intent {
        check(task.state.status == DownloadStatus.COMPLETED) { "This file has not been saved to Downloads." }
        val uri = Uri.parse(requireNotNull(task.state.publishedUri))
        val resolver = context.contentResolver
        resolver.openFileDescriptor(uri, "r")?.use { } ?: throw IOException("File is no longer available in Downloads.")
        val mime = resolver.getType(uri)?.takeUnless { it == "application/octet-stream" } ?: mimeForFileName(task.state.fileName)
        val intent = if (share) Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uri)
            else Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime)
        intent.clipData = ClipData.newRawUri("Downloaded file", uri)
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return if (share) Intent.createChooser(intent, "Share download") else intent
    }

    /** A returned sender needs user consent. Retry only after a successful consent result. */
    suspend fun delete(id: String, deletePublic: Boolean): IntentSender? {
        controller.cancelTask(id)
        val record = controller.recordForDeletion(id)
        check(record.status in setOf(DownloadStatus.COMPLETED, DownloadStatus.FAILED, DownloadStatus.CANCELED))
        val consent = withContext(Dispatchers.IO) {
            val uri = record.publishedUri ?: DownloadsDestination(context).find(record)
            if (uri != null) {
                val destination = DownloadsDestination(context)
                if (deletePublic || destination.isPublished(uri) == false) {
                    try { context.contentResolver.delete(Uri.parse(uri), null, null) }
                    catch (error: RecoverableSecurityException) { return@withContext error.userAction.actionIntent.intentSender }
                    catch (_: SecurityException) {
                        return@withContext MediaStore.createDeleteRequest(context.contentResolver, listOf(Uri.parse(uri))).intentSender
                    }
                    // Confirm deletion, including providers that return zero without an error.
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
        if (consent == null) controller.deleteTask(id)
        return consent
    }
}
