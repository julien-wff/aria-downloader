package com.julien_wff.aria_android

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import java.io.File

/** Inspect durable identity without exposing pending destinations in the public UI model. */
internal fun Context.httpStorageForTest(id: String): Pair<File, Uri> =
    SQLiteDatabase.openDatabase(File(noBackupFilesDir, "transfers.db").path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
        db.rawQuery("SELECT taskId, pendingUri, directHttp FROM transfers WHERE id = ?", arrayOf(id)).use {
            check(it.moveToFirst() && it.getInt(2) == 1)
            val nativeId = it.getString(0)
            check(nativeId.matches(Regex("[0-9a-f]{16}")))
            File(noBackupFilesDir, "aria2/$nativeId") to Uri.parse(requireNotNull(it.getString(1)))
        }
    }
