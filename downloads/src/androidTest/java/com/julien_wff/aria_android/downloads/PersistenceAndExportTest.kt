package com.julien_wff.aria_android.downloads

import android.content.ContextWrapper
import android.net.Uri
import android.provider.MediaStore
import android.os.Environment
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

    @Test fun multiFilePublicationRecoversPerFileJournalAndPreservesRelativeFolders() = runBlocking {
        val root = File(context.cacheDir, "multi-media-${System.nanoTime()}").apply { mkdirs() }
        val destination = DownloadsDestination(context)
        val files = listOf(DownloadFile(1, "bundle/a.bin", 3, 3), DownloadFile(2, "bundle/nested/b.bin", 2, 2),
            DownloadFile(3, "bundle/excluded.bin", 1, selected = false))
        var saved = TransferRecord("lease", "https://example.org/source.torrent", "Bundle", DesiredState.RUNNING,
            DownloadStatus.STAGED, taskId = "0123456789abcdef", completedBytes = 5, totalBytes = 5,
            protocol = DownloadProtocol.TORRENT, selectionConfirmed = true, files = files)
        try {
            files.filter { it.selected }.forEach { file -> File(root, "${saved.taskId}/${file.path}").apply {
                parentFile!!.mkdirs(); writeBytes(ByteArray(file.size.toInt()) { file.index.toByte() })
            } }
            val exporter = DownloadExporter(root, destination)
            try {
                exporter.export(saved, { next ->
                    if (next.files.count { it.published } == 2) throw IOException("Lost commit after second publication")
                    saved = next
                }, { _, _ -> }, { true })
                fail("Expected interrupted save")
            } catch (_: IOException) { }
            assertEquals(DownloadStatus.EXPORTING, saved.status)
            assertTrue(File(root, saved.taskId!!).exists())
            val before = saved.files.filter { it.selected }.map { it.publishedUri }
            exporter.export(saved, { saved = it }, { _, _ -> }, { true })
            assertEquals(DownloadStatus.COMPLETED, saved.status)
            assertEquals(before, saved.files.filter { it.selected }.map { it.publishedUri })
            assertFalse(File(root, saved.taskId!!).exists())
            for (file in saved.files.filter { it.selected }) {
                val uri = Uri.parse(file.publishedUri)
                assertEquals(true, destination.isPublished(uri.toString()))
                context.contentResolver.query(uri, arrayOf(MediaStore.Downloads.RELATIVE_PATH), null, null, null)!!.use {
                    assertTrue(it.moveToFirst())
                    assertEquals("${Environment.DIRECTORY_DOWNLOADS}/Aria-${saved.id}/${file.path.substringBeforeLast('/')}/", it.getString(0))
                }
                assertArrayEquals(ByteArray(file.size.toInt()) { file.index.toByte() },
                    context.contentResolver.openInputStream(uri)!!.use { it.readBytes() })
            }
            assertNull(saved.files.last().publishedUri)
        } finally {
            saved.files.mapNotNull { it.publishedUri }.forEach { context.contentResolver.delete(Uri.parse(it), null, null) }
            root.deleteRecursively()
        }
    }

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
        val record = TransferRecord("lease", "https://example.org/file", "collision-${System.nanoTime()}.bin",
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
            assertNull(destination.find(record)) // Publication moved it out of the pending namespace.
            assertFalse(source.exists())
            val actual = context.contentResolver.openInputStream(Uri.parse(inserted))!!.use { it.readBytes() }
            assertArrayEquals(MessageDigest.getInstance("SHA-256").digest(expected),
                MessageDigest.getInstance("SHA-256").digest(actual))
            // A second task with the same filename gets its own destination, never a replacement.
            val second = record.copy(id = java.util.UUID.randomUUID().toString())
            other = destination.create(second)
            destination.output(other).use { it.write(byteArrayOf(9, 8, 7)) }
            destination.publish(other)
            assertNotEquals(inserted, other)
            assertEquals(true, destination.isPublished(inserted))
            assertEquals(true, destination.isPublished(other))
            fun publicName(uri: String): String = context.contentResolver.query(Uri.parse(uri),
                arrayOf(MediaStore.Downloads.RELATIVE_PATH, MediaStore.Downloads.DISPLAY_NAME), null, null, null)!!.use {
                assertTrue(it.moveToFirst())
                assertEquals("${Environment.DIRECTORY_DOWNLOADS}/", it.getString(0))
                it.getString(1)
            }
            assertNotEquals(publicName(inserted), publicName(other))
            val originalAfterCollision = context.contentResolver.openInputStream(Uri.parse(inserted))!!.use { it.readBytes() }
            assertArrayEquals(expected, originalAfterCollision)
        } finally {
            (saved.publishedUri ?: destination.find(record))?.let {
                context.contentResolver.delete(Uri.parse(it), null, null)
            }
            other?.let { context.contentResolver.delete(Uri.parse(it), null, null) }
            root.deleteRecursively()
        }
    }
}
