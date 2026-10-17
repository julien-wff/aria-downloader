package com.julien_wff.aria_android.downloads

import android.content.ContextWrapper
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PersistenceAndExportTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun roomReopensWithStableMappingProgressIntentAndExportIdentity() = runBlocking {
        val root = File(context.cacheDir, "room-test-${System.nanoTime()}").apply { mkdirs() }
        val wrapped = object : ContextWrapper(context) { override fun getNoBackupFilesDir() = root }
        val record = TransferRecord("lease", "https://example.org/file", "file.bin",
            DesiredState.PAUSED, DownloadStatus.PAUSED, taskId = "0123456789abcdef",
            completedBytes = 5_000_000_000, totalBytes = 6_000_000_000,
            publishedUri = "content://downloads/1")
        try {
            RoomTransferStore(wrapped).use { store ->
                assertNull(store.read())
                store.write(record)
            }
            RoomTransferStore(wrapped).use { store ->
                assertEquals(record, store.read())
                val newer = record.copy(id = "new-task", createdAt = record.createdAt + 1,
                    desired = DesiredState.RUNNING, status = DownloadStatus.EXPORTING)
                store.write(newer)
                assertEquals(newer, store.read())
            }
        } finally { root.deleteRecursively() }
    }

    @Test fun legacyImportIsIdempotentAndDoesNotInventARecoveryMapping() = runBlocking {
        val root = File(context.cacheDir, "legacy-test-${System.nanoTime()}").apply { mkdirs() }
        val wrapped = object : ContextWrapper(context) { override fun getNoBackupFilesDir() = root }
        try {
            val legacy = TransferRecord("lease", "https://example.org/file", "old.bin",
                DesiredState.RUNNING, DownloadStatus.DOWNLOADING)
            AtomicTransferStore(wrapped).write(legacy)
            RoomTransferStore(wrapped).use {
                assertEquals(legacy, it.read())
                assertNull(it.read()!!.taskId)
            }
            AtomicTransferStore(wrapped).write(legacy.copy(token = "stale"))
            RoomTransferStore(wrapped).use { assertEquals(legacy, it.read()) }
        } finally { root.deleteRecursively() }
    }

    @Test fun mediaStorePendingInsertionAndPublishedCommitWindowsRecoverWithoutDuplicates() = runBlocking {
        val root = File(context.cacheDir, "media-test-${System.nanoTime()}").apply { mkdirs() }
        val destination = DownloadsDestination(context)
        val record = TransferRecord("lease", "https://example.org/file", "collision.bin",
            DesiredState.RUNNING, DownloadStatus.STAGED, taskId = "0123456789abcdef",
            completedBytes = 600_000, totalBytes = 600_000)
        val source = File(root, "${record.taskId}/${record.fileName}")
        source.parentFile!!.mkdirs()
        val expected = ByteArray(600_000) { (it % 251).toByte() }
        source.writeBytes(expected)
        val exporter = DownloadExporter(root, destination)
        var saved = record
        var other: String? = null
        try {
            // Simulate death after MediaStore insertion, before the URI reaches Room.
            val inserted = destination.create(record)
            assertEquals(inserted, destination.find(record))
            assertEquals(false, destination.isPublished(inserted))
            try {
                exporter.export(record, {
                    if (it.status == DownloadStatus.COMPLETED) throw IOException("Process lost before commit")
                    saved = it
                }, { _, _ -> }, { true })
                fail("Expected simulated death")
            } catch (_: IOException) { }
            assertTrue(source.exists())
            assertEquals(true, destination.isPublished(inserted))
            assertEquals(inserted, saved.publishedUri)
            val completed = exporter.export(saved, { saved = it }, { _, _ -> }, { true })
            assertEquals(inserted, completed.publishedUri)
            assertEquals(inserted, destination.find(record))
            assertFalse(source.exists())
            val actual = context.contentResolver.openInputStream(Uri.parse(inserted))!!.use { it.readBytes() }
            assertArrayEquals(MessageDigest.getInstance("SHA-256").digest(expected),
                MessageDigest.getInstance("SHA-256").digest(actual))
            // A second task with the same filename gets its own destination, never a replacement.
            other = destination.create(record.copy(id = java.util.UUID.randomUUID().toString()))
            assertNotEquals(inserted, other)
            assertEquals(true, destination.isPublished(inserted))
        } finally {
            (saved.publishedUri ?: destination.find(record))?.let {
                context.contentResolver.delete(Uri.parse(it), null, null)
            }
            other?.let { context.contentResolver.delete(Uri.parse(it), null, null) }
            root.deleteRecursively()
        }
    }
}
