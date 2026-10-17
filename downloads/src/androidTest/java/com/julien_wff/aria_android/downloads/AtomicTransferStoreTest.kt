package com.julien_wff.aria_android.downloads

import android.content.ContextWrapper
import android.util.AtomicFile
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AtomicTransferStoreTest {
    @Test fun intentRoundTripsAndFailedReplacementKeepsPreviousRecord() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.cacheDir, "intent-test-${System.nanoTime()}").apply { mkdirs() }
        val wrapped = object : ContextWrapper(context) {
            override fun getNoBackupFilesDir() = root
        }
        try {
            val record = TransferRecord("task", "https://example.com/file", "file.bin",
                DesiredState.RUNNING, DownloadStatus.SCHEDULED)
            val store = AtomicTransferStore(wrapped)
            assertNull(store.read())
            store.write(record)
            assertEquals(record, AtomicTransferStore(wrapped).read())
            val file = AtomicFile(File(root, "transfer.json"))
            val partial = file.startWrite()
            partial.write("incomplete".toByteArray())
            file.failWrite(partial)
            assertEquals(record, AtomicTransferStore(wrapped).read())
            store.write(record.copy(desired = DesiredState.PAUSED, status = DownloadStatus.PAUSED))
            assertEquals(DesiredState.PAUSED, store.read()!!.desired)
        } finally { root.deleteRecursively() }
    }
}
