package com.julien_wff.aria_android.downloads

import android.content.Context
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

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(record: TransferRecord)
}

@Database(entities = [TransferRecord::class], version = 1, exportSchema = true)
internal abstract class TransferDatabase : RoomDatabase() {
    abstract fun transfers(): TransferDao
}

/** Kept beside staging and excluded from backup; a restored database alone cannot resume files. */
internal class RoomTransferStore(context: Context) : TransferStore, AutoCloseable {
    private val database = Room.databaseBuilder(context.applicationContext,
        TransferDatabase::class.java, File(context.noBackupFilesDir, "transfers.db").absolutePath).build()
    private val legacy = AtomicTransferStore(context)

    override suspend fun read(): TransferRecord? {
        database.transfers().latest()?.let { return it }
        // Legacy records have no trustworthy engine mapping. Preserve them without guessing paths.
        val old = legacy.read() ?: return null
        val imported = old.copy(status = if (old.status == DownloadStatus.COMPLETED)
            DownloadStatus.STAGED else old.status)
        database.transfers().put(imported)
        return imported
    }

    override suspend fun write(record: TransferRecord) = database.transfers().put(record)

    override fun close() = database.close()
}
