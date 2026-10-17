package com.julien_wff.aria_android.downloads

import android.content.ContextWrapper
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import com.julien_wff.aria_android.aria2.*
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test

class QueuePersistenceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test fun v1MigrationRetainsAllRowsAndAddsQueueDefaults() = runBlocking {
        val root = File(context.cacheDir, "migration-${System.nanoTime()}").apply { mkdirs() }
        val wrapped = object : ContextWrapper(context) { override fun getNoBackupFilesDir() = root }
        try {
            // Exact v1 table and identity, independent of the generated v2 schema.
            SQLiteDatabase.openOrCreateDatabase(File(root, "transfers.db"), null).use { db ->
                db.execSQL("CREATE TABLE transfers (token TEXT NOT NULL, url TEXT NOT NULL, fileName TEXT NOT NULL, desired TEXT NOT NULL, status TEXT NOT NULL, id TEXT NOT NULL PRIMARY KEY, taskId TEXT, completedBytes INTEGER NOT NULL, totalBytes INTEGER NOT NULL, publishedUri TEXT, createdAt INTEGER NOT NULL)")
                db.execSQL("CREATE TABLE room_master_table (id INTEGER PRIMARY KEY, identity_hash TEXT)")
                db.execSQL("INSERT INTO room_master_table VALUES (42, '1f333d7034965eb7c253ee6e2d8c83df')")
                for ((index, status) in listOf("COMPLETED", "PAUSED", "DOWNLOADING").withIndex()) {
                    db.execSQL("INSERT INTO transfers VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                        arrayOf<Any?>("batch", "https://example.org/file", "file-$index.bin", if (index == 1) "PAUSED" else "RUNNING",
                            status, "id-$index", "%016x".format(index), 5_000_000_000L, 6_000_000_000L,
                            if (index == 0) "content://downloads/1" else null, index.toLong()))
                }
                db.version = 1
            }
            RoomTransferStore(wrapped).use { store ->
                val rows = store.readAll()
                assertEquals(3, rows.size)
                assertEquals(listOf("id-0", "id-1", "id-2"), rows.map { it.id })
                assertTrue(rows.all { it.connections == 4 && it.speedLimit == 0L && it.batchConcurrency == 3 && !it.batchWifiOnly })
                assertEquals(5_000_000_000L, rows.first().completedBytes)
                assertEquals("content://downloads/1", rows.first().publishedUri)
                val updated = rows.map { it.copy(token = "replacement", batchConcurrency = 2, batchWifiOnly = true) }
                store.writeAll(updated)
                assertEquals(updated, store.readAll())
                store.delete("id-1")
                assertEquals(listOf("id-0", "id-2"), store.readAll().map { it.id })
            }
        } finally { root.deleteRecursively() }
    }

    @Test fun settingsPersistAndValidate() = runBlocking {
        val preferences = DownloadPreferences(context)
        val old = preferences.read()
        try {
            val value = DownloadSettings(2, 8, 123_456, true, Appearance.DARK)
            preferences.save(value)
            assertEquals(value, DownloadPreferences(context).read())
            try { DownloadSettings(concurrency = 0); fail("Expected validation") } catch (_: IllegalArgumentException) { }
        } finally { preferences.save(old) }
    }

    @Test fun historyDeletionKeepsPublicFileUnlessExplicitlySelected() = runBlocking {
        for (deletePublic in listOf(false, true)) {
            val root = File(context.cacheDir, "delete-${System.nanoTime()}").apply { mkdirs() }
            val wrapped = object : ContextWrapper(context) { override fun getNoBackupFilesDir() = root }
            val destination = DownloadsDestination(wrapped)
            val row = TransferRecord("batch", "https://example.org/file", "delete-test.pdf", DesiredState.RUNNING,
                DownloadStatus.COMPLETED, taskId = "0123456789abcdef", completedBytes = 1)
            val uri = destination.create(row)
            destination.output(uri).use { it.write(42) }; destination.publish(uri)
            val source = File(root, "aria2/${row.taskId}/${row.fileName}").apply { parentFile!!.mkdirs(); writeText("staging") }
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                RoomTransferStore(wrapped).use { store ->
                    store.write(row.copy(publishedUri = uri))
                    val engine = object : DownloadEngine {
                        override val snapshots = MutableStateFlow(EngineSnapshot())
                        override suspend fun start() { error("No network work expected") }
                        override suspend fun add(request: EngineRequest): EngineTaskId = error("No network work expected")
                        override suspend fun pause(id: EngineTaskId) { }
                        override suspend fun resume(id: EngineTaskId) { }
                        override suspend fun remove(id: EngineTaskId) { }
                        override suspend fun updateOptions(id: EngineTaskId, options: EngineOptions) { }
                        override suspend fun checkpoint() { }
                        override suspend fun stop() { }
                        override suspend fun close() { }
                    }
                    val controller = DownloadController(engine, store, object : TransferScheduler {
                        override fun schedule(token: String): Boolean = error("No scheduling expected")
                        override fun cancel() { }
                    }, scope)
                    withTimeout(5_000) { controller.tasks.first { it.isNotEmpty() } }
                    val files = DownloadFiles(wrapped, controller)
                    val task = controller.tasks.value.single()
                    assertEquals(android.content.Intent.ACTION_VIEW, files.intent(task, false).action)
                    assertEquals("application/pdf", files.intent(task, false).type)
                    assertEquals(android.content.Intent.ACTION_CHOOSER, files.intent(task, true).action)
                    assertNull(files.delete(row.id, deletePublic))
                    assertTrue(controller.tasks.value.isEmpty())
                    assertFalse(source.exists())
                    assertEquals(if (deletePublic) null else true, destination.isPublished(uri))
                    assertTrue(store.readAll().isEmpty())
                }
            } finally {
                scope.cancel()
                context.contentResolver.delete(Uri.parse(uri), null, null)
                root.deleteRecursively()
            }
        }
    }
}
