package com.julien_wff.aria_android.downloads

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.Bundle
import android.os.ParcelFileDescriptor
import com.julien_wff.aria_android.aria2.EngineHttpOutput
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
    fun openForTransfer(uri: String): ParcelFileDescriptor = error("Direct output unavailable")
    fun verifySize(uri: String, size: Long) { error("Direct output unavailable") }
    fun publish(uri: String)
    fun publish(uri: String, record: TransferRecord) = publish(uri)
}

internal class DownloadsDestination(private val context: Context) : ExportDestination {
    private val resolver get() = context.contentResolver
    private val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
    // A deterministic pending filename closes the insert-before-Room crash window without
    // creating temporary public directories. Publication restores the requested name.
    private fun pendingName(record: TransferRecord) = "Aria-pending-${record.id}.tmp"
    private fun legacyPath(record: TransferRecord) = "${Environment.DIRECTORY_DOWNLOADS}/Aria/${record.id}/"
    private fun finalPath(record: TransferRecord): String {
        val group = record.id.substringBefore("-file-")
        return if ("-file-" in record.id && record.files.size > 1)
            "${Environment.DIRECTORY_DOWNLOADS}/Aria-$group/" + record.fileName.substringBeforeLast('/', "").let { if (it.isEmpty()) "" else "$it/" }
            else "${Environment.DIRECTORY_DOWNLOADS}/"
    }

    override fun find(record: TransferRecord): String? = resolver.query(collection,
        arrayOf(MediaStore.Downloads._ID), Bundle().apply {
            putString(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION,
                "${MediaStore.MediaColumns.OWNER_PACKAGE_NAME} = ? AND " +
                    "((${MediaStore.Downloads.RELATIVE_PATH} = ? AND ${MediaStore.Downloads.DISPLAY_NAME} = ? " +
                    "AND ${MediaStore.Downloads.IS_PENDING} = 1) OR ${MediaStore.Downloads.RELATIVE_PATH} = ?)")
            putStringArray(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS,
                arrayOf(context.packageName, finalPath(record), pendingName(record), legacyPath(record)))
            putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE)
        }, null).let { it ?: throw IOException("Unable to inspect existing Downloads entries") }.use { cursor ->
        if (cursor.moveToFirst()) Uri.withAppendedPath(collection, cursor.getLong(0).toString()).toString()
        else null
    }

    override fun create(record: TransferRecord): String = resolver.insert(collection,
        ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, pendingName(record))
            // Keep the temporary suffix stable regardless of the final file's media type.
            put(MediaStore.Downloads.MIME_TYPE, "application/octet-stream")
            put(MediaStore.Downloads.RELATIVE_PATH, finalPath(record))
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

    override fun openForTransfer(uri: String): ParcelFileDescriptor {
        check(isPublished(uri) == false) { "Pending Downloads entry is unavailable" }
        // Refresh the platform's pending-entry lifetime each time work resumes.
        check(resolver.update(Uri.parse(uri), ContentValues().apply {
            put(MediaStore.Downloads.IS_PENDING, 1)
        }, "${MediaStore.Downloads.IS_PENDING} = ?", arrayOf("1")) == 1)
        return resolver.openFileDescriptor(Uri.parse(uri), "rw")
            ?: throw IOException("Unable to open the Downloads entry")
    }

    override fun verifySize(uri: String, size: Long) {
        resolver.openFileDescriptor(Uri.parse(uri), "r")?.use {
            check(it.statSize == size) { "Downloads file size changed" }
        } ?: throw IOException("Downloads file is unavailable")
    }

    override fun publish(uri: String) = publish(uri, TransferRecord("", "", "download", DesiredState.PAUSED, DownloadStatus.EXPORTING))

    override fun publish(uri: String, record: TransferRecord) {
        if (resolver.update(Uri.parse(uri), ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, record.fileName.substringAfterLast('/'))
                put(MediaStore.Downloads.MIME_TYPE, mimeForFileName(record.fileName))
                put(MediaStore.Downloads.RELATIVE_PATH, finalPath(record))
                put(MediaStore.Downloads.IS_PENDING, 0)
            }, null, null) != 1) throw IOException("Unable to publish the Downloads entry")
        cleanupLegacyFolders()
    }

    /** Remove only empty, UUID-named legacy task directories; never recursively delete files. */
    @Suppress("DEPRECATION")
    fun cleanupLegacyFolders() {
        runCatching {
            val root = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Aria")
            if (java.nio.file.Files.isSymbolicLink(root.toPath())) return
            val canonicalRoot = root.canonicalFile
            root.listFiles()?.forEach { directory ->
                if (directory.name.matches(Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}(-file-[0-9]+)?")) &&
                    !java.nio.file.Files.isSymbolicLink(directory.toPath()) && directory.canonicalFile.parentFile == canonicalRoot) {
                    // A trailing slash makes remove(3) directory-only. It cannot delete a file,
                    // and the filesystem rejects nonempty directories even if listing hid files.
                    runCatching { android.system.Os.remove(directory.path + "/") }
                }
            }
            runCatching { android.system.Os.remove(root.path + "/") }
        }
    }

}

internal interface TransferExporter {
    val supportsDirectHttp: Boolean get() = false
    suspend fun prepareHttp(record: TransferRecord, persist: suspend (TransferRecord) -> Unit): EngineHttpOutput =
        error("Direct output unavailable")
    suspend fun cleanup(record: TransferRecord) { }
    suspend fun export(record: TransferRecord, persist: suspend (TransferRecord) -> Unit,
        progress: (Long, Long) -> Unit, authorized: () -> Boolean): TransferRecord
}

/** All phases are restartable. A published entry is never truncated, even after a database failure. */
internal class DownloadExporter(
    private val stagingRoot: File,
    private val destination: ExportDestination,
) : TransferExporter {
    override val supportsDirectHttp = true

    override suspend fun prepareHttp(record: TransferRecord, persist: suspend (TransferRecord) -> Unit): EngineHttpOutput =
        withContext(Dispatchers.IO) {
            require(record.directHttp && record.protocol == DownloadProtocol.HTTP && record.files.isEmpty())
            val uri = record.pendingUri ?: run {
                check(record.completedBytes == 0L) { "Downloads identity is unavailable" }
                destination.find(record) ?: destination.create(record)
            }
            check(destination.isPublished(uri) == false) { "Pending Downloads entry is unavailable" }
            persist(record.copy(pendingUri = uri))
            EngineHttpOutput { destination.openForTransfer(uri) }
        }

    override suspend fun export(record: TransferRecord, persist: suspend (TransferRecord) -> Unit,
        progress: (Long, Long) -> Unit, authorized: () -> Boolean): TransferRecord {
        if (record.directHttp) return publishHttp(record, persist, progress, authorized)
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

    private suspend fun publishHttp(record: TransferRecord, persist: suspend (TransferRecord) -> Unit,
        progress: (Long, Long) -> Unit, authorized: () -> Boolean): TransferRecord = withContext(Dispatchers.IO) {
        require(record.protocol == DownloadProtocol.HTTP && record.files.isEmpty())
        val uri = requireNotNull(record.pendingUri) { "Downloads identity is unavailable" }
        var next = record.copy(status = DownloadStatus.VERIFYING)
        persist(next)
        val published = destination.isPublished(uri) ?: throw IOException("Downloads entry is unavailable")
        if (!published) {
            val taskId = requireNotNull(record.taskId)
            require(taskId.matches(Regex("[0-9a-f]{16}")))
            val directory = File(stagingRoot, taskId).canonicalFile
            require(directory.parentFile == stagingRoot.canonicalFile)
            check(File(directory, ".http-complete").readText().toLong() == record.completedBytes) {
                "HTTP completion journal is unavailable"
            }
            destination.verifySize(uri, record.completedBytes)
            currentCoroutineContext().ensureActive()
            if (!authorized()) throw IOException("Saving interrupted; retry saving to continue")
            next = next.copy(status = DownloadStatus.EXPORTING)
            persist(next)
            destination.publish(uri, record)
        }
        next = next.copy(status = DownloadStatus.COMPLETED, publishedUri = uri, failure = null)
        persist(next)
        progress(record.completedBytes, record.completedBytes)
        runCatching { cleanup(next) }
        next
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
