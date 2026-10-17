package com.julien_wff.aria_android.downloads

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.Bundle
import android.provider.MediaStore
import java.io.File
import java.io.IOException
import java.io.OutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

internal interface ExportDestination {
    fun find(record: TransferRecord): String?
    fun create(record: TransferRecord): String
    /** Null means the entry was removed (for example, an expired pending entry). */
    fun isPublished(uri: String): Boolean?
    fun output(uri: String): OutputStream
    fun publish(uri: String)
}

internal class DownloadsDestination(private val context: Context) : ExportDestination {
    private val resolver get() = context.contentResolver
    private val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
    // An isolated namespace makes the insertion-to-database crash window discoverable, including
    // providers that rename colliding display names. This app never replaces somebody else's file.
    private fun path(record: TransferRecord) = "${Environment.DIRECTORY_DOWNLOADS}/Aria/${record.id}/"

    override fun find(record: TransferRecord): String? = resolver.query(collection,
        arrayOf(MediaStore.Downloads._ID), Bundle().apply {
            putString(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION,
                "${MediaStore.Downloads.RELATIVE_PATH} = ? AND ${MediaStore.MediaColumns.OWNER_PACKAGE_NAME} = ?")
            putStringArray(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS,
                arrayOf(path(record), context.packageName))
            putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE)
        }, null).let { it ?: throw IOException("Unable to inspect existing Downloads entries") }.use { cursor ->
        if (cursor.moveToFirst()) Uri.withAppendedPath(collection, cursor.getLong(0).toString()).toString()
        else null
    }

    override fun create(record: TransferRecord): String = resolver.insert(collection,
        ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, record.fileName)
            put(MediaStore.Downloads.MIME_TYPE, "application/octet-stream")
            put(MediaStore.Downloads.RELATIVE_PATH, path(record))
            put(MediaStore.Downloads.IS_PENDING, 1)
        })?.toString() ?: throw IOException("Unable to create a Downloads entry")

    override fun isPublished(uri: String): Boolean? {
        val cursor = resolver.query(Uri.parse(uri), arrayOf(MediaStore.Downloads.IS_PENDING),
            Bundle().apply { putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE) }, null)
            ?: throw IOException("Unable to inspect Downloads entry")
        return cursor.use { if (it.moveToFirst()) it.getInt(0) == 0 else null }
    }

    override fun output(uri: String): OutputStream = resolver.openOutputStream(Uri.parse(uri), "wt")
        ?: throw IOException("Unable to write the Downloads entry")

    override fun publish(uri: String) {
        if (resolver.update(Uri.parse(uri), ContentValues().apply {
                put(MediaStore.Downloads.IS_PENDING, 0)
            }, null, null) != 1) throw IOException("Unable to publish the Downloads entry")
    }
}

internal interface TransferExporter {
    suspend fun cleanup(record: TransferRecord) { }
    suspend fun export(record: TransferRecord, persist: suspend (TransferRecord) -> Unit,
        progress: (Long, Long) -> Unit, authorized: () -> Boolean): TransferRecord
}

/** All phases are restartable. A published entry is never truncated, even after a database failure. */
internal class DownloadExporter(
    private val stagingRoot: File,
    private val destination: ExportDestination,
) : TransferExporter {
    override suspend fun export(record: TransferRecord, persist: suspend (TransferRecord) -> Unit,
        progress: (Long, Long) -> Unit, authorized: () -> Boolean): TransferRecord = withContext(Dispatchers.IO) {
        val exportContext = currentCoroutineContext()
        val taskId = record.taskId ?: throw IOException("Legacy staging cannot be located safely")
        require(taskId.matches(Regex("[0-9a-f]{16}")))
        val directory = File(stagingRoot, taskId)
        val source = File(directory, record.fileName)
        check(directory.canonicalFile.parentFile == stagingRoot.canonicalFile &&
            source.canonicalFile.parentFile == directory.canonicalFile) { "Invalid staging path" }
        var next = record
        val uri = record.publishedUri?.takeIf { destination.isPublished(it) != null }
            ?: destination.find(record) ?: destination.create(record)
        next = next.copy(publishedUri = uri, status = DownloadStatus.EXPORTING)
        persist(next) // Record identity before opening/truncating or publishing.
        if (destination.isPublished(uri) != true) {
            if (!source.isFile || File(directory, "${record.fileName}.aria2").exists())
                throw IOException("Completed staged data is unavailable; partial data has been preserved")
            if (source.length() != record.completedBytes)
                throw IOException("Staged file size changed; data has been preserved")
            source.inputStream().use { input ->
                destination.output(uri).use { output ->
                    val buffer = ByteArray(256 * 1024)
                    var copied = 0L
                    while (true) {
                        exportContext.ensureActive()
                        if (!authorized()) throw IOException("Saving interrupted; retry saving to continue")
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        copied += count
                        progress(copied, record.completedBytes)
                    }
                    if (copied != record.completedBytes) throw IOException("Staged file size changed during saving")
                    output.flush()
                }
            }
            exportContext.ensureActive()
            if (!authorized()) throw IOException("Saving interrupted; retry saving to continue")
            destination.publish(uri)
        }
        next = next.copy(status = DownloadStatus.COMPLETED)
        persist(next) // Never remove staging until publication and its durable record succeed.
        runCatching { cleanup(next) }
        next
    }

    override suspend fun cleanup(record: TransferRecord) = withContext(Dispatchers.IO) {
        val taskId = record.taskId
        val uri = record.publishedUri
        if (record.status == DownloadStatus.COMPLETED && taskId != null && uri != null &&
            taskId.matches(Regex("[0-9a-f]{16}")) && destination.isPublished(uri) == true) {
            val directory = File(stagingRoot, taskId)
            check(directory.canonicalFile.parentFile == stagingRoot.canonicalFile)
            directory.deleteRecursively()
        }
        Unit
    }
}
