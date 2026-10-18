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

    @Test fun v5MigrationKeepsLegacyStorageAndPersistsDirectHttpIdentity() = runBlocking {
        val root = File(context.cacheDir, "migration-v5-${System.nanoTime()}").apply { mkdirs() }
        val wrapped = object : ContextWrapper(context) { override fun getNoBackupFilesDir() = root }
        try {
            // Build the exact previous table from its checked-in schema.
            val schema = org.json.JSONObject(instrumentation.context.assets.open("schemas/5.json").bufferedReader().use { it.readText() })
                .getJSONObject("database")
            SQLiteDatabase.openOrCreateDatabase(File(root, "transfers.db"), null).use { db ->
                db.execSQL(schema.getJSONArray("entities").getJSONObject(0).getString("createSql").replace("\${TABLE_NAME}", "transfers"))
                db.execSQL("INSERT INTO transfers (token,url,fileName,desired,status,id,taskId,completedBytes,totalBytes,createdAt) VALUES ('batch','https://example.org/a','a','PAUSED','PAUSED','old','0123456789abcdef',123,1000,1)")
                db.version = 5
            }
            RoomTransferStore(wrapped).use { store ->
                val old = store.readAll().single()
                assertFalse(old.directHttp)
                assertNull(old.pendingUri)
                assertEquals(123L, old.completedBytes)
                store.write(old.copy(directHttp = true, pendingUri = "content://downloads/42"))
            }
            RoomTransferStore(wrapped).use { store ->
                val restored = store.readAll().single()
                assertTrue(restored.directHttp)
                assertEquals("content://downloads/42", restored.pendingUri)
                assertNull(restored.publishedUri)
            }
        } finally { root.deleteRecursively() }
    }

    @Test fun v4MigrationAndFileReviewJournalSurviveDatabaseReopening() = runBlocking {
        val root = File(context.cacheDir, "migration-v4-${System.nanoTime()}").apply { mkdirs() }
        val wrapped = object : ContextWrapper(context) { override fun getNoBackupFilesDir() = root }
        try {
            SQLiteDatabase.openOrCreateDatabase(File(root, "transfers.db"), null).use { db ->
                db.execSQL("""CREATE TABLE IF NOT EXISTS `transfers` (`token` TEXT NOT NULL, `url` TEXT NOT NULL, `fileName` TEXT NOT NULL, `desired` TEXT NOT NULL, `status` TEXT NOT NULL, `id` TEXT NOT NULL, `taskId` TEXT, `completedBytes` INTEGER NOT NULL, `totalBytes` INTEGER NOT NULL, `publishedUri` TEXT, `createdAt` INTEGER NOT NULL, `connections` INTEGER NOT NULL DEFAULT 4, `speedLimit` INTEGER NOT NULL DEFAULT 0, `failure` TEXT DEFAULT NULL, `batchConcurrency` INTEGER NOT NULL DEFAULT 3, `batchWifiOnly` INTEGER NOT NULL DEFAULT 0, `referrer` TEXT DEFAULT NULL, `userAgent` TEXT DEFAULT NULL, `retryCount` INTEGER NOT NULL DEFAULT 0, `retryAt` INTEGER NOT NULL DEFAULT 0, `failureCategory` TEXT DEFAULT NULL, `engineCode` INTEGER DEFAULT NULL, `stopReason` INTEGER DEFAULT NULL, PRIMARY KEY(`id`))""")
                db.execSQL("CREATE TABLE room_master_table (id INTEGER PRIMARY KEY, identity_hash TEXT)")
                db.execSQL("INSERT INTO room_master_table VALUES (42, 'ad3a792c5f6524f394cf3c5d2d4d8f28')")
                db.execSQL("INSERT INTO transfers (token, url, fileName, desired, status, id, taskId, completedBytes, totalBytes, createdAt, retryCount, retryAt) VALUES ('batch', 'https://example.org/a', 'a', 'PAUSED', 'PAUSED', 'old', '0123456789abcdef', 5, 10, 1, 2, 123)")
                db.version = 4
            }
            val files = listOf(DownloadFile(1, "folder/a", 5_000_000_000L, 123, publishedUri = "content://downloads/7", published = true),
                DownloadFile(2, "folder/b", 10, selected = false))
            RoomTransferStore(wrapped).use { store ->
                val old = store.readAll().single()
                assertEquals(DownloadProtocol.HTTP, old.protocol)
                assertTrue(old.files.isEmpty())
                assertFalse(old.selectionConfirmed)
                assertEquals(2, old.retryCount)
                assertEquals(123L, old.retryAt)
                store.write(old.copy(protocol = DownloadProtocol.TORRENT, status = DownloadStatus.AWAITING_SELECTION, files = files))
            }
            RoomTransferStore(wrapped).use { store ->
                val restored = store.readAll().single()
                assertEquals(DownloadProtocol.TORRENT, restored.protocol)
                assertEquals(DownloadStatus.AWAITING_SELECTION, restored.status)
                assertEquals(files, restored.files)
            }
        } finally { root.deleteRecursively() }
    }

    @Test fun v1MigrationRetainsAllRowsAndAddsQueueDefaults() = runBlocking {
        val root = File(context.cacheDir, "migration-${System.nanoTime()}").apply { mkdirs() }
        val wrapped = object : ContextWrapper(context) { override fun getNoBackupFilesDir() = root }
        try {
            // Exact v1 table and identity, independent of the generated current schema.
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
                assertTrue(rows.all { it.referrer == null && it.userAgent == null })
                assertEquals(5_000_000_000L, rows.first().completedBytes)
                assertEquals("content://downloads/1", rows.first().publishedUri)
                val updated = rows.map { it.copy(token = "replacement", batchConcurrency = 2, batchWifiOnly = true,
                    referrer = "https://example.org/page", userAgent = "Custom/1.0") }
                store.writeAll(updated)
                assertEquals(updated, store.readAll())
                store.delete("id-1")
                assertEquals(listOf("id-0", "id-2"), store.readAll().map { it.id })
            }
        } finally { root.deleteRecursively() }
    }

    @Test fun v2MigrationPreservesExistingOptionsAndDefaultsRequestHeaders() = runBlocking {
        val root = File(context.cacheDir, "migration-v2-${System.nanoTime()}").apply { mkdirs() }
        val wrapped = object : ContextWrapper(context) { override fun getNoBackupFilesDir() = root }
        try {
            SQLiteDatabase.openOrCreateDatabase(File(root, "transfers.db"), null).use { db ->
                db.execSQL("CREATE TABLE IF NOT EXISTS `transfers` (`token` TEXT NOT NULL, `url` TEXT NOT NULL, `fileName` TEXT NOT NULL, `desired` TEXT NOT NULL, `status` TEXT NOT NULL, `id` TEXT NOT NULL, `taskId` TEXT, `completedBytes` INTEGER NOT NULL, `totalBytes` INTEGER NOT NULL, `publishedUri` TEXT, `createdAt` INTEGER NOT NULL, `connections` INTEGER NOT NULL DEFAULT 4, `speedLimit` INTEGER NOT NULL DEFAULT 0, `failure` TEXT DEFAULT NULL, `batchConcurrency` INTEGER NOT NULL DEFAULT 3, `batchWifiOnly` INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(`id`))")
                db.execSQL("CREATE TABLE room_master_table (id INTEGER PRIMARY KEY, identity_hash TEXT)")
                db.execSQL("INSERT INTO room_master_table VALUES (42, 'e5cc791d14f9ac6f158745a787693475')")
                db.execSQL("INSERT INTO transfers (token, url, fileName, desired, status, id, taskId, completedBytes, totalBytes, createdAt, connections, speedLimit) VALUES ('batch', 'https://example.org/file', 'file', 'PAUSED', 'PAUSED', 'existing', '0123456789abcdef', 123, 456, 1, 8, 1000)")
                db.version = 2
            }
            RoomTransferStore(wrapped).use { store ->
                val row = store.readAll().single()
                assertEquals("existing", row.id)
                assertEquals(8, row.connections)
                assertEquals(1000L, row.speedLimit)
                assertEquals(123L, row.completedBytes)
                assertNull(row.referrer)
                assertNull(row.userAgent)
                store.write(row.copy(referrer = "https://example.org/page", userAgent = "Custom/1.0"))
            }
            RoomTransferStore(wrapped).use { store ->
                val row = store.readAll().single()
                assertEquals("https://example.org/page", row.referrer)
                assertEquals("Custom/1.0", row.userAgent)
            }
        } finally { root.deleteRecursively() }
    }

    @Test fun v3MigrationPreservesHeadersFilesAndAddsDurableRetryDefaults() = runBlocking {
        val root = File(context.cacheDir, "migration-v3-${System.nanoTime()}").apply { mkdirs() }
        val wrapped = object : ContextWrapper(context) { override fun getNoBackupFilesDir() = root }
        try {
            SQLiteDatabase.openOrCreateDatabase(File(root, "transfers.db"), null).use { db ->
                db.execSQL("CREATE TABLE IF NOT EXISTS `transfers` (`token` TEXT NOT NULL, `url` TEXT NOT NULL, `fileName` TEXT NOT NULL, `desired` TEXT NOT NULL, `status` TEXT NOT NULL, `id` TEXT NOT NULL, `taskId` TEXT, `completedBytes` INTEGER NOT NULL, `totalBytes` INTEGER NOT NULL, `publishedUri` TEXT, `createdAt` INTEGER NOT NULL, `connections` INTEGER NOT NULL DEFAULT 4, `speedLimit` INTEGER NOT NULL DEFAULT 0, `failure` TEXT DEFAULT NULL, `batchConcurrency` INTEGER NOT NULL DEFAULT 3, `batchWifiOnly` INTEGER NOT NULL DEFAULT 0, `referrer` TEXT DEFAULT NULL, `userAgent` TEXT DEFAULT NULL, PRIMARY KEY(`id`))")
                db.execSQL("CREATE TABLE room_master_table (id INTEGER PRIMARY KEY, identity_hash TEXT)")
                db.execSQL("INSERT INTO room_master_table VALUES (42, '095ae40fabe743c46c5f0330c91123da')")
                db.execSQL("INSERT INTO transfers (token, url, fileName, desired, status, id, taskId, completedBytes, totalBytes, publishedUri, createdAt, referrer, userAgent) VALUES ('batch', 'https://example.org/file', 'file', 'RUNNING', 'COMPLETED', 'existing', '0123456789abcdef', 5000000000, 5000000000, 'content://downloads/1', 1, 'https://example.org/page', 'Custom/1.0')")
                db.version = 3
            }
            RoomTransferStore(wrapped).use { store ->
                val row = store.readAll().single()
                assertEquals(0, row.retryCount)
                assertEquals(0L, row.retryAt)
                assertNull(row.engineCode)
                assertNull(row.stopReason)
                assertNull(row.failureCategory)
                assertEquals("https://example.org/page", row.referrer)
                assertEquals("Custom/1.0", row.userAgent)
                assertEquals("content://downloads/1", row.publishedUri)
                assertEquals(5_000_000_000L, row.completedBytes)
                store.write(row.copy(status = DownloadStatus.WAITING, retryCount = 2, retryAt = 123456,
                    failureCategory = "NETWORK", engineCode = 6, stopReason = 7))
            }
            RoomTransferStore(wrapped).use { store ->
                val row = store.readAll().single()
                assertEquals(2, row.retryCount)
                assertEquals(123456L, row.retryAt)
                assertEquals("NETWORK", row.failureCategory)
                assertEquals(6, row.engineCode)
                assertEquals(7, row.stopReason)
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
