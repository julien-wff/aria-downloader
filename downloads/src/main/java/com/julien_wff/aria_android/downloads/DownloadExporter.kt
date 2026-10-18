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
    fun publish(uri: String, record: TransferRecord) = publish(uri)
}

internal class DownloadsDestination(private val context: Context) : ExportDestination {
    private val resolver get() = context.contentResolver
    private val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
    // Pending entries have a recoverable task namespace until their URI is durable in Room.
    // Publication moves the completed file to Downloads; MediaStore resolves name collisions.
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
            put(MediaStore.Downloads.DISPLAY_NAME, record.fileName.substringAfterLast('/'))
            put(MediaStore.Downloads.MIME_TYPE, mimeForFileName(record.fileName))
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

    override fun publish(uri: String) = publish(uri, TransferRecord("", "", "download", DesiredState.PAUSED, DownloadStatus.EXPORTING))

    override fun publish(uri: String, record: TransferRecord) {
        val group = record.id.substringBefore("-file-")
        val relative = if ("-file-" in record.id && record.files.size > 1)
            "${Environment.DIRECTORY_DOWNLOADS}/Aria-$group/" + record.fileName.substringBeforeLast('/', "").let { if (it.isEmpty()) "" else "$it/" }
            else "${Environment.DIRECTORY_DOWNLOADS}/"
        if (resolver.update(Uri.parse(uri), ContentValues().apply {
                put(MediaStore.Downloads.RELATIVE_PATH, relative)
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
        progress: (Long, Long) -> Unit, authorized: () -> Boolean): TransferRecord {
        if (record.files.isEmpty()) return exportOne(record, record.fileName, persist, progress, authorized)
        var next = record.copy(status = DownloadStatus.VERIFYING)
        persist(next)
        val selected = record.files.filter { it.selected }
        require(selected.isNotEmpty())
        withContext(Dispatchers.IO) {
            val taskId = requireNotNull(record.taskId)
            require(taskId.matches(Regex("[0-9a-f]{16}")))
            val directory = File(stagingRoot, taskId).canonicalFile
            require(directory.parentFile == stagingRoot.canonicalFile)
            for (file in selected) {
                currentCoroutineContext().ensureActive()
                if (!authorized()) throw IOException("Verification interrupted")
                val source = File(directory, file.path).canonicalFile
                require(source.path.startsWith(directory.path + File.separator))
                // Already-public files can be reconciled even if staging cleanup had started.
                if (file.publishedUri?.let { destination.isPublished(it) } != true)
                    check(source.isFile && source.length() == file.size) { "Verified staged file is unavailable" }
            }
        }
        var saved = 0L
        for (file in selected) {
            val child = record.copy(id = "${record.id}-file-${file.index}", fileName = file.path,
                completedBytes = file.size, totalBytes = file.size, publishedUri = file.publishedUri, status = DownloadStatus.EXPORTING)
            exportOne(child, file.path,
                persist = { updated ->
                    next = next.copy(status = DownloadStatus.EXPORTING, files = next.files.map { current ->
                        if (current.index == file.index) current.copy(publishedUri = updated.publishedUri,
                            published = updated.status == DownloadStatus.COMPLETED) else current
                    })
                    persist(next)
                }, progress = { copied, _ -> progress(saved + copied, record.completedBytes) }, authorized = authorized,
                cleanupAfter = false)
            saved += file.size
        }
        next = next.copy(status = DownloadStatus.COMPLETED,
            publishedUri = next.files.first { it.selected }.publishedUri, failure = null)
        persist(next)
        runCatching { cleanup(next) }
        return next
    }

    private suspend fun exportOne(record: TransferRecord, sourcePath: String, persist: suspend (TransferRecord) -> Unit,
        progress: (Long, Long) -> Unit, authorized: () -> Boolean, cleanupAfter: Boolean = true): TransferRecord = withContext(Dispatchers.IO) {
        val exportContext = currentCoroutineContext()
        val taskId = record.taskId ?: throw IOException("Legacy staging cannot be located safely")
        require(taskId.matches(Regex("[0-9a-f]{16}")))
        val directory = File(stagingRoot, taskId)
        val source = File(directory, sourcePath)
        check(directory.canonicalFile.parentFile == stagingRoot.canonicalFile &&
            source.canonicalPath.startsWith(directory.canonicalPath + File.separator) &&
            sourcePath.split('/').all { it.isNotEmpty() && it != "." && it != ".." && '\\' !in it && ':' !in it }) { "Invalid staging path" }
        var next = record
        val uri = record.publishedUri?.takeIf { destination.isPublished(it) != null }
            ?: destination.find(record) ?: destination.create(record)
        next = next.copy(publishedUri = uri, status = DownloadStatus.EXPORTING)
        persist(next) // Record identity before opening/truncating or publishing.
        if (destination.isPublished(uri) != true) {
            // Selective torrents retain a control file for their unselected contents.
            // The durable parent completion authorizes export of its selected, verified files.
            if (!source.isFile || (record.files.isEmpty() && File(directory, "${record.fileName}.aria2").exists()))
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
            destination.publish(uri, record)
        }
        next = next.copy(status = DownloadStatus.COMPLETED, failure = null)
        persist(next) // Never remove staging until publication and its durable record succeed.
        if (cleanupAfter) runCatching { cleanup(next) }
        next
    }

    override suspend fun cleanup(record: TransferRecord) = withContext(Dispatchers.IO) {
        val taskId = record.taskId
        val uri = record.publishedUri
        if (record.status == DownloadStatus.COMPLETED && taskId != null && uri != null &&
            taskId.matches(Regex("[0-9a-f]{16}")) && (if (record.files.isEmpty()) destination.isPublished(uri) == true else record.files.filter { it.selected }.all { it.published && it.publishedUri?.let(destination::isPublished) == true })) {
            val directory = File(stagingRoot, taskId)
            check(directory.canonicalFile.parentFile == stagingRoot.canonicalFile)
            directory.deleteRecursively()
        }
        Unit
    }
}

internal fun mimeForFileName(name: String): String = android.webkit.MimeTypeMap.getSingleton()
    .getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase(java.util.Locale.ROOT))
    ?: "application/octet-stream"
