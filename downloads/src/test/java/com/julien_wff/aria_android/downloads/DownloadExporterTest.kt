package com.julien_wff.aria_android.downloads

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

class DownloadExporterTest {
    private class Destination : ExportDestination {
        var uri: String? = null
        var published = false
        var creates = 0
        var writes = 0
        var bytes = byteArrayOf()
        var diskFull = false
        override fun find(record: TransferRecord) = uri
        override fun create(record: TransferRecord): String {
            creates++
            return "content://downloads/$creates".also { uri = it }
        }
        override fun isPublished(uri: String): Boolean? = if (this.uri == uri) published else null
        override fun output(uri: String): OutputStream {
            writes++
            return object : ByteArrayOutputStream() {
                override fun write(b: ByteArray, off: Int, len: Int) {
                    if (diskFull) throw IOException("Disk full")
                    super.write(b, off, len)
                }
                override fun close() { bytes = toByteArray(); super.close() }
            }
        }
        override fun publish(uri: String) { published = true }
    }

    private fun staged(root: File): Pair<TransferRecord, File> {
        val record = TransferRecord("lease", "https://example.org/file", "file.bin",
            DesiredState.RUNNING, DownloadStatus.STAGED, taskId = "0123456789abcdef",
            completedBytes = 600_000, totalBytes = 600_000)
        val source = File(root, "${record.taskId}/${record.fileName}")
        source.parentFile!!.mkdirs()
        source.writeBytes(ByteArray(record.completedBytes.toInt()) { (it % 251).toByte() })
        return record to source
    }

    @Test fun interruptedMultiFilePublicationReusesPublishedAndPendingEntries() = runBlocking {
        val root = Files.createTempDirectory("multi-export").toFile()
        val files = listOf(DownloadFile(1, "folder/a.bin", 3, 3), DownloadFile(2, "folder/b.bin", 2, 2))
        var saved = TransferRecord("lease", "https://example.org/source.torrent", "Torrent", DesiredState.RUNNING,
            DownloadStatus.STAGED, taskId = "0123456789abcdef", completedBytes = 5, totalBytes = 5,
            protocol = DownloadProtocol.TORRENT, selectionConfirmed = true, files = files)
        val destinations = mutableMapOf<String, String>()
        val published = mutableSetOf<String>()
        val writes = mutableMapOf<String, Int>()
        var failSecond = true
        val destination = object : ExportDestination {
            override fun find(record: TransferRecord) = destinations[record.id]
            override fun create(record: TransferRecord) = "content://downloads/${record.id}".also { destinations[record.id] = it }
            override fun isPublished(uri: String): Boolean? = if (uri in destinations.values) uri in published else null
            override fun output(uri: String): OutputStream {
                writes[uri] = (writes[uri] ?: 0) + 1
                if (uri.endsWith("-file-2") && failSecond) throw IOException("Disk full")
                return ByteArrayOutputStream()
            }
            override fun publish(uri: String) { published += uri }
        }
        try {
            files.forEach { file -> File(root, "${saved.taskId}/${file.path}").apply {
                parentFile!!.mkdirs(); writeBytes(ByteArray(file.size.toInt()) { 7 })
            } }
            File(root, "${saved.taskId}/bundle.aria2").writeText("checkpoint for unselected torrent contents")
            val exporter = DownloadExporter(root, destination)
            try { exporter.export(saved, { saved = it }, { _, _ -> }, { true }); fail("Expected failure") } catch (_: IOException) { }
            assertEquals(1, published.size)
            assertEquals(DownloadStatus.EXPORTING, saved.status)
            assertTrue(saved.files.first().published)
            assertFalse(saved.files.last().published)
            assertTrue(File(root, saved.taskId!!).exists())
            val converter = ProtocolConverters()
            assertEquals(saved.files, converter.decode(converter.encode(saved.files)))
            failSecond = false
            exporter.export(saved, { saved = it }, { _, _ -> }, { true })
            assertEquals(DownloadStatus.COMPLETED, saved.status)
            assertEquals(2, destinations.size)
            assertEquals(1, writes.getValue(destinations.values.first { it.endsWith("-file-1") }))
            assertTrue(saved.files.all { it.published })
            assertFalse(File(root, saved.taskId!!).exists())
        } finally { root.deleteRecursively() }
    }

    @Test fun successfulPublicationPersistsIdentityAndCompletionBeforeDeletingStaging() = runBlocking {
        val root = Files.createTempDirectory("export-test").toFile()
        try {
            val (record, source) = staged(root)
            val expected = source.readBytes()
            val destination = Destination()
            val states = mutableListOf<DownloadStatus>()
            val result = DownloadExporter(root, destination).export(record, {
                assertTrue(source.isFile)
                assertNotNull(it.publishedUri)
                if (it.status == DownloadStatus.COMPLETED) assertTrue(destination.published)
                states += it.status
            }, { _, _ -> }, { true })
            assertEquals(listOf(DownloadStatus.EXPORTING, DownloadStatus.COMPLETED), states)
            assertEquals(DownloadStatus.COMPLETED, result.status)
            assertArrayEquals(expected, destination.bytes)
            assertFalse(source.exists())
        } finally { root.deleteRecursively() }
    }

    @Test fun multiFileCleanupFailureDoesNotUndoDurablePublication() = runBlocking {
        val root = Files.createTempDirectory("multi-export").toFile()
        try {
            var saved = TransferRecord("lease", "https://example.org/source.torrent", "Torrent",
                DesiredState.RUNNING, DownloadStatus.STAGED, taskId = "0123456789abcdef",
                completedBytes = 3, totalBytes = 3, protocol = DownloadProtocol.TORRENT,
                selectionConfirmed = true, files = listOf(DownloadFile(1, "a.bin", 3, 3)))
            val source = File(root, "${saved.taskId}/a.bin").apply {
                parentFile!!.mkdirs(); writeBytes(byteArrayOf(1, 2, 3))
            }
            var published = false
            var durableCompletion = false
            val destination = object : ExportDestination {
                override fun find(record: TransferRecord): String? = null
                override fun create(record: TransferRecord) = "content://downloads/1"
                override fun isPublished(uri: String): Boolean? {
                    if (durableCompletion) throw IOException("MediaStore temporarily unavailable during cleanup")
                    return published
                }
                override fun output(uri: String): OutputStream = ByteArrayOutputStream()
                override fun publish(uri: String) { published = true }
            }
            val result = DownloadExporter(root, destination).export(saved, {
                saved = it
                durableCompletion = it.status == DownloadStatus.COMPLETED
            }, { _, _ -> }, { true })
            assertEquals(DownloadStatus.COMPLETED, result.status)
            assertEquals(DownloadStatus.COMPLETED, saved.status)
            assertTrue(saved.files.single().published)
            assertTrue(source.exists()) // Startup can retry this best-effort cleanup later.
        } finally { root.deleteRecursively() }
    }

    @Test fun successfulSaveRetryClearsPreviousFailure() = runBlocking {
        val root = Files.createTempDirectory("export-test").toFile()
        try {
            val (record, _) = staged(root)
            var saved = record.copy(status = DownloadStatus.SAVE_FAILED, failure = "Unable to save to Downloads")
            val result = DownloadExporter(root, Destination()).export(saved, { saved = it }, { _, _ -> }, { true })
            assertEquals(DownloadStatus.COMPLETED, result.status)
            assertNull(result.failure)
            assertNull(saved.failure)
        } finally { root.deleteRecursively() }
    }

    @Test fun destinationInsertionBeforeDatabaseFailureIsDiscoveredOnRetry() = runBlocking {
        val root = Files.createTempDirectory("export-test").toFile()
        try {
            val (record, source) = staged(root)
            val destination = Destination()
            val exporter = DownloadExporter(root, destination)
            try {
                exporter.export(record, { throw IOException("Database full") }, { _, _ -> }, { true })
                fail("Expected persistence failure")
            } catch (_: IOException) { }
            assertTrue(source.exists())
            assertEquals(0, destination.writes)
            exporter.export(record, {}, { _, _ -> }, { true })
            assertEquals(1, destination.creates)
            assertTrue(destination.published)
        } finally { root.deleteRecursively() }
    }

    @Test fun diskFullPreservesStagingAndRetryReusesAndTruncatesPendingEntry() = runBlocking {
        val root = Files.createTempDirectory("export-test").toFile()
        try {
            val (record, source) = staged(root)
            val expected = source.readBytes()
            val destination = Destination().apply { diskFull = true }
            var saved = record
            val exporter = DownloadExporter(root, destination)
            try {
                exporter.export(saved, { saved = it }, { _, _ -> }, { true })
                fail("Expected disk full")
            } catch (_: IOException) { }
            assertTrue(source.isFile)
            assertFalse(destination.published)
            destination.diskFull = false
            exporter.export(saved, { saved = it }, { _, _ -> }, { true })
            assertEquals(1, destination.creates)
            assertEquals(2, destination.writes)
            assertArrayEquals(expected, destination.bytes)
        } finally { root.deleteRecursively() }
    }

    @Test fun removedPendingEntryIsRecreatedWithoutLosingTheStagedSource() = runBlocking {
        val root = Files.createTempDirectory("export-test").toFile()
        try {
            val (record, source) = staged(root)
            val destination = Destination().apply { diskFull = true }
            val exporter = DownloadExporter(root, destination)
            var saved = record
            try { exporter.export(saved, { saved = it }, { _, _ -> }, { true }); fail("Expected disk full") }
            catch (_: IOException) { }
            assertTrue(source.exists())
            destination.uri = null // Simulate MediaStore expiring/deleting the incomplete entry.
            destination.diskFull = false
            exporter.export(saved, { saved = it }, { _, _ -> }, { true })
            assertEquals(2, destination.creates)
            assertEquals("content://downloads/2", saved.publishedUri)
            assertTrue(destination.published)
        } finally { root.deleteRecursively() }
    }

    @Test fun crashAfterPublicationNeverCopiesOrPublishesAnotherFile() = runBlocking {
        val root = Files.createTempDirectory("export-test").toFile()
        try {
            val (record, source) = staged(root)
            val destination = Destination()
            var saved = record
            val exporter = DownloadExporter(root, destination)
            try {
                exporter.export(record, {
                    if (it.status == DownloadStatus.COMPLETED) throw IOException("Process lost before commit")
                    saved = it
                }, { _, _ -> }, { true })
                fail("Expected failure")
            } catch (_: IOException) { }
            assertTrue(destination.published)
            assertTrue(source.isFile)
            exporter.export(saved, { saved = it }, { _, _ -> }, { true })
            assertEquals(1, destination.creates)
            assertEquals(1, destination.writes)
            assertFalse(source.exists())
        } finally { root.deleteRecursively() }
    }

    @Test fun revokedExecutionLeavesPendingEntryAndStagingRecoverable() = runBlocking {
        val root = Files.createTempDirectory("export-test").toFile()
        try {
            val (record, source) = staged(root)
            val destination = Destination()
            var allowed = true
            try {
                DownloadExporter(root, destination).export(record, {}, { _, _ -> allowed = false }, { allowed })
                fail("Expected revocation")
            } catch (_: IOException) { }
            assertTrue(source.isFile)
            assertFalse(destination.published)
        } finally { root.deleteRecursively() }
    }

    @Test fun canceledLocalRetryStopsCopyingAndPreservesPendingData() = runBlocking {
        val root = Files.createTempDirectory("export-test").toFile()
        try {
            val (record, source) = staged(root)
            val destination = Destination()
            val owner = Job()
            try {
                withContext(owner) {
                    DownloadExporter(root, destination).export(record, {}, { _, _ -> owner.cancel() }, { true })
                }
                fail("Expected cancellation")
            } catch (_: CancellationException) { }
            assertTrue(source.exists())
            assertFalse(destination.published)
            assertTrue(destination.bytes.size < record.completedBytes)
        } finally { root.deleteRecursively() }
    }

    @Test fun durableCompletionCanCleanUpStagingAfterProcessRestart() = runBlocking {
        val root = Files.createTempDirectory("export-test").toFile()
        try {
            val (record, source) = staged(root)
            val destination = Destination().apply {
                uri = "content://downloads/1"
                published = true
            }
            DownloadExporter(root, destination).cleanup(record.copy(status = DownloadStatus.COMPLETED,
                publishedUri = destination.uri))
            assertFalse(source.exists())
            assertEquals(0, destination.writes)
        } finally { root.deleteRecursively() }
    }

    @Test fun partialOrChangedStagingCannotBePublished() = runBlocking {
        val root = Files.createTempDirectory("export-test").toFile()
        try {
            val (record, source) = staged(root)
            val destination = Destination()
            File(source.parentFile, "file.bin.aria2").writeText("partial")
            try {
                DownloadExporter(root, destination).export(record, {}, { _, _ -> }, { true })
                fail("Partial file accepted")
            } catch (_: IOException) { }
            assertEquals(0, destination.writes)
            assertTrue(source.exists())
        } finally { root.deleteRecursively() }
    }
}
