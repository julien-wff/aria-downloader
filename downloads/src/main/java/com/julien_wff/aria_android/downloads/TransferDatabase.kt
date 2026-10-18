package com.julien_wff.aria_android.downloads

import android.content.Context
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.room.TypeConverters
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import java.io.File

@Dao
internal interface TransferDao {
    @Query("SELECT * FROM transfers ORDER BY createdAt DESC LIMIT 1")
    suspend fun latest(): TransferRecord?

    @Query("SELECT * FROM transfers ORDER BY createdAt ASC, id ASC")
    suspend fun all(): List<TransferRecord>

    @Query("DELETE FROM transfers WHERE id = :id")
    suspend fun delete(id: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putAll(records: List<TransferRecord>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(record: TransferRecord)
}

@Database(entities = [TransferRecord::class], version = 6, exportSchema = true)
@TypeConverters(ProtocolConverters::class)
internal abstract class TransferDatabase : RoomDatabase() {
    abstract fun transfers(): TransferDao
}

internal val TRANSFER_MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE transfers ADD COLUMN connections INTEGER NOT NULL DEFAULT 4")
        db.execSQL("ALTER TABLE transfers ADD COLUMN speedLimit INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE transfers ADD COLUMN failure TEXT DEFAULT NULL")
        db.execSQL("ALTER TABLE transfers ADD COLUMN batchConcurrency INTEGER NOT NULL DEFAULT 3")
        db.execSQL("ALTER TABLE transfers ADD COLUMN batchWifiOnly INTEGER NOT NULL DEFAULT 0")
    }
}

internal val TRANSFER_MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE transfers ADD COLUMN referrer TEXT DEFAULT NULL")
        db.execSQL("ALTER TABLE transfers ADD COLUMN userAgent TEXT DEFAULT NULL")
    }
}

internal val TRANSFER_MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE transfers ADD COLUMN retryCount INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE transfers ADD COLUMN retryAt INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE transfers ADD COLUMN failureCategory TEXT DEFAULT NULL")
        db.execSQL("ALTER TABLE transfers ADD COLUMN engineCode INTEGER DEFAULT NULL")
        db.execSQL("ALTER TABLE transfers ADD COLUMN stopReason INTEGER DEFAULT NULL")
    }
}

internal val TRANSFER_MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE transfers ADD COLUMN protocol TEXT NOT NULL DEFAULT 'HTTP'")
        db.execSQL("ALTER TABLE transfers ADD COLUMN selectionConfirmed INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE transfers ADD COLUMN files TEXT NOT NULL DEFAULT ''")
    }
}

internal val TRANSFER_MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE transfers ADD COLUMN directHttp INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE transfers ADD COLUMN pendingUri TEXT DEFAULT NULL")
    }
}

/** Kept beside staging and excluded from backup; a restored database alone cannot resume files. */
internal class RoomTransferStore(context: Context) : TransferStore, AutoCloseable {
    private val database = Room.databaseBuilder(context.applicationContext,
        TransferDatabase::class.java, File(context.noBackupFilesDir, "transfers.db").absolutePath).addMigrations(TRANSFER_MIGRATION_1_2, TRANSFER_MIGRATION_2_3, TRANSFER_MIGRATION_3_4, TRANSFER_MIGRATION_4_5, TRANSFER_MIGRATION_5_6).build()
    private val legacy = AtomicTransferStore(context)

    override suspend fun read(): TransferRecord? {
        database.transfers().latest()?.let { legacy.clear(); return it }
        // Legacy records have no trustworthy engine mapping. Preserve them without guessing paths.
        val old = legacy.read() ?: return null
        val imported = old.copy(status = if (old.status == DownloadStatus.COMPLETED)
            DownloadStatus.STAGED else old.status)
        database.transfers().put(imported)
        legacy.clear()
        return imported
    }

    override suspend fun readAll(): List<TransferRecord> {
        read() // Import the legacy record exactly once before exposing the queue.
        return database.transfers().all()
    }
    override suspend fun writeAll(records: List<TransferRecord>) = database.transfers().putAll(records)
    override suspend fun delete(id: String) = database.transfers().delete(id)

    override suspend fun write(record: TransferRecord) = database.transfers().put(record)

    override fun close() = database.close()
}
