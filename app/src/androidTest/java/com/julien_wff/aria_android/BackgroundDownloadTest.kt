package com.julien_wff.aria_android

import android.app.Notification
import android.app.NotificationManager
import android.os.Build
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.julien_wff.aria_android.downloads.DownloadRuntime
import com.julien_wff.aria_android.downloads.DownloadState
import com.julien_wff.aria_android.downloads.DownloadStatus
import java.io.Closeable
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BackgroundDownloadTest {
    @get:Rule val visibility = TestActivityVisibilityRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val controller get() = DownloadRuntime.controller(context)

    private fun shell(command: String) {
        instrumentation.uiAutomation.executeShellCommand(command).use { descriptor ->
            android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes() }
        }
    }

    private suspend fun await(predicate: (DownloadState) -> Boolean) = withTimeout(60_000) {
        controller.state.first { predicate(it) || it.status == DownloadStatus.FAILED }.also {
            assertNotEquals(it.message, DownloadStatus.FAILED, it.status)
        }
    }

    private suspend fun notification(predicate: (Notification) -> Boolean): Notification = withTimeout(15_000) {
        val manager = context.getSystemService(NotificationManager::class.java)
        var found: Notification? = null
        while (found == null) {
            found = manager.activeNotifications.map { it.notification }.find(predicate)
            if (found == null) delay(100)
        }
        found
    }

    @Test fun backgroundTransferPausesFromNotificationResumesAndMatchesHash() = runBlocking {
        shell("pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS")
        shell("input keyevent KEYCODE_WAKEUP")
        shell("wm dismiss-keyguard")
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            ControlledServer().use { server ->
                val fileName = "uidt-${System.nanoTime()}.bin"
                try {
                    withContext(Dispatchers.Main) {
                        controller.cancel()
                        controller.start(server.url, fileName)
                    }
                    await { it.status == DownloadStatus.DOWNLOADING && it.completedBytes > 128 * 1024 }
                    val active = notification { it.actions?.any { action -> action.title == "Pause" } == true }
                    if (Build.VERSION.SDK_INT >= 36) {
                        assertEquals(Notification.ProgressStyle::class.java.name,
                            active.extras.getString(Notification.EXTRA_TEMPLATE))
                    }
                    // Leave the Activity and turn off the screen while the job owns the engine.
                    shell("input keyevent KEYCODE_HOME")
                    val beforeLock = controller.state.value.completedBytes
                    shell("input keyevent KEYCODE_SLEEP")
                    await { it.completedBytes > beforeLock + 128 * 1024 }
                    active.actions.first { it.title == "Pause" }.actionIntent.send()
                    // Once paused, the job releases its wake lock; wake the test harness too.
                    shell("input keyevent KEYCODE_WAKEUP")
                    await { it.status == DownloadStatus.PAUSED }
                    val paused = controller.state.value.completedBytes
                    delay(1200)
                    assertEquals(paused, controller.state.value.completedBytes)
                    assertEquals(DownloadStatus.PAUSED, controller.state.value.status)
                    shell("input keyevent KEYCODE_WAKEUP")
                    shell("wm dismiss-keyguard")
                    notification { it.actions?.any { a -> a.title == "Resume" } == true }
                        .actions.first { it.title == "Resume" }.actionIntent.send()
                    scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
                    delay(500)
                    withContext(Dispatchers.Main) { controller.resume() }
                    await { it.status == DownloadStatus.COMPLETED }
                    assertTrue("Expected range resume", server.rangeRequests.get() > 0)
                    val staged = File(context.noBackupFilesDir, "aria2").listFiles().orEmpty()
                        .map { File(it, fileName) }.single { it.isFile }
                    assertArrayEquals(server.hash(), staged.inputStream().use {
                        val digest = MessageDigest.getInstance("SHA-256")
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val count = it.read(buffer)
                            if (count < 0) break
                            digest.update(buffer, 0, count)
                        }
                        digest.digest()
                    })
                    staged.parentFile?.deleteRecursively()
                    // Also exercise Cancel while the Activity is absent.
                    withContext(Dispatchers.Main) { controller.start(server.url, fileName) }
                    await { it.status == DownloadStatus.DOWNLOADING && it.completedBytes > 0 }
                    shell("input keyevent KEYCODE_HOME")
                    notification { it.actions?.any { a -> a.title == "Cancel" } == true }
                        .actions.first { it.title == "Cancel" }.actionIntent.send()
                    await { it.status == DownloadStatus.CANCELED }
                    val canceled = controller.state.value.completedBytes
                    delay(800)
                    assertEquals(canceled, controller.state.value.completedBytes)
                } finally {
                    withContext(Dispatchers.Main) { controller.cancel() }
                    shell("input keyevent KEYCODE_WAKEUP")
                    File(context.noBackupFilesDir, "aria2").listFiles().orEmpty()
                        .filter { File(it, fileName).isFile }.forEach { it.deleteRecursively() }
                }
            }
        }
    }

    private class ControlledServer : Closeable {
        private val bytes = ByteArray(8 * 1024 * 1024) { (it % 251).toByte() }
        private val socket = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        private val executor = Executors.newCachedThreadPool()
        val rangeRequests = AtomicLong()
        val url = "http://127.0.0.1:${socket.localPort}/file"
        init {
            executor.submit {
                while (!socket.isClosed) {
                    try { val client = socket.accept(); executor.submit { serve(client) } }
                    catch (_: Exception) { break }
                }
            }
        }
        private fun serve(client: Socket) {
            try {
                client.use {
                    val reader = client.getInputStream().bufferedReader()
                    reader.readLine() ?: return
                    var start = 0
                    var end = bytes.lastIndex
                    var range = false
                    while (true) {
                        val line = reader.readLine() ?: return
                        if (line.isEmpty()) break
                        if (line.startsWith("Range:", ignoreCase = true)) {
                            val value = line.substringAfter("=").trim().split("-")
                            start = value[0].toInt()
                            end = value.getOrNull(1)?.toIntOrNull()?.coerceAtMost(bytes.lastIndex) ?: bytes.lastIndex
                            range = true
                            rangeRequests.incrementAndGet()
                        }
                    }
                    val output = client.getOutputStream()
                    val header = buildString {
                        append(if (range) "HTTP/1.1 206 Partial Content\r\n" else "HTTP/1.1 200 OK\r\n")
                        append("Content-Length: ${end - start + 1}\r\nAccept-Ranges: bytes\r\n")
                        if (range) append("Content-Range: bytes $start-$end/${bytes.size}\r\n")
                        append("Connection: close\r\n\r\n")
                    }
                    output.write(header.toByteArray())
                    var offset = start
                    while (offset <= end) {
                        val count = minOf(16 * 1024, end - offset + 1)
                        output.write(bytes, offset, count)
                        output.flush()
                        offset += count
                        Thread.sleep(20)
                    }
                }
            } catch (_: Exception) { /* Client pause/cancel closes the socket. */ }
        }
        fun hash(): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)
        override fun close() { socket.close(); executor.shutdownNow() }
    }
}
