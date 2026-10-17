package com.julien_wff.aria_android

import android.net.Uri
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.julien_wff.aria_android.downloads.DownloadRuntime
import com.julien_wff.aria_android.downloads.DownloadStatus
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class HostRecovery

/** Two opt-in invocations; tools/test-process-recovery.py owns the server and kills between them. */
@HostRecovery
@RunWith(AndroidJUnit4::class)
class ProcessDeathRecoveryTest {
    @get:Rule val visibility = TestActivityVisibilityRule()
    private val args get() = InstrumentationRegistry.getArguments()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val controller get() = DownloadRuntime.controller(context)

    @Test fun seedRunningTransfer(): Unit = runBlocking {
        assumeTrue(args.getString("recoveryPhase") == "seed")
        ActivityScenario.launch(MainActivity::class.java).use {
            delay(500)
            val baseName = requireNotNull(args.getString("recoveryName"))
            val names = listOf(baseName, "$baseName.second")
            withContext(Dispatchers.Main) {
                controller.tasks.value.filter { it.state.canCancel }.forEach { controller.cancelTask(it.id) }
                names.forEachIndexed { index, name ->
                    controller.enqueue(requireNotNull(args.getString("recoveryUrl")) + "?recoveryTask=$index", name)
                }
            }
            withTimeout(60_000) {
                while (true) {
                    val tasks = controller.tasks.value.filter { it.state.fileName in names }
                    check(tasks.none { it.state.status in setOf(DownloadStatus.FAILED, DownloadStatus.SAVE_FAILED) })
                    val checkpoints = File(context.noBackupFilesDir, "aria2").listFiles().orEmpty()
                    if (tasks.size == 2 && tasks.all { task ->
                        task.state.status == DownloadStatus.DOWNLOADING && task.state.completedBytes > 256 * 1024 &&
                            checkpoints.any { File(it, "${task.state.fileName}.aria2").isFile }
                    }) break
                    delay(100)
                }
            }
            delay(5500)
            assertTrue(controller.tasks.value.filter { it.state.fileName in names }.all { it.state.status == DownloadStatus.DOWNLOADING })
            // Keep instrumentation alive: finishing the runner would itself terminate the target.
            File(context.noBackupFilesDir, "process-recovery-ready").writeText(baseName)
            delay(120_000)
            error("Host did not kill the live process")
        }
    }

    @Test fun recoverAndVerifyPublishedContent() = runBlocking {
        assumeTrue(args.getString("recoveryPhase") == "recover")
        ActivityScenario.launch(MainActivity::class.java).use {
            delay(500)
            val baseName = requireNotNull(args.getString("recoveryName"))
            val names = listOf(baseName, "$baseName.second")
            val restored = withTimeout(15_000) { controller.tasks.first { tasks -> names.all { name -> tasks.any { it.state.fileName == name } } } }
                .filter { it.state.fileName in names }
            assertEquals(2, restored.size)
            restored.forEach { task ->
                assertNotEquals(DownloadStatus.INTERRUPTED, task.state.status)
            }
            // One visible Resume captures all previously running eligible batch members.
            restored.firstOrNull { it.state.canResume }?.let { task ->
                withContext(Dispatchers.Main) { controller.resumeTask(task.id) }
            }
            val complete = withTimeout(120_000) {
                controller.tasks.first { tasks -> tasks.filter { it.state.fileName in names }.all {
                    it.state.status in setOf(DownloadStatus.COMPLETED, DownloadStatus.FAILED, DownloadStatus.SAVE_FAILED)
                } }
            }.filter { it.state.fileName in names }
            assertEquals(2, complete.size)
            for (task in complete) {
                assertEquals(task.state.message, DownloadStatus.COMPLETED, task.state.status)
                val uri = Uri.parse(requireNotNull(task.state.publishedUri))
                try {
                    val digest = MessageDigest.getInstance("SHA-256")
                    context.contentResolver.openInputStream(uri)!!.use { input ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            digest.update(buffer, 0, count)
                        }
                    }
                    assertEquals(args.getString("recoveryHash"), digest.digest().joinToString("") { "%02x".format(it) })
                } finally { context.contentResolver.delete(uri, null, null) }
            }
            assertEquals(2, complete.map { it.state.publishedUri }.toSet().size)

        }
    }
}
