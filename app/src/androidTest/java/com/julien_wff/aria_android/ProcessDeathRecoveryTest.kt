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
            withContext(Dispatchers.Main) {
                controller.cancel()
                controller.start(requireNotNull(args.getString("recoveryUrl")),
                    requireNotNull(args.getString("recoveryName")))
            }
            withTimeout(60_000) {
                while (true) {
                    val state = controller.state.value
                    check(state.status !in setOf(DownloadStatus.FAILED, DownloadStatus.SAVE_FAILED)) { state.message.orEmpty() }
                    val checkpoint = File(context.noBackupFilesDir, "aria2").listFiles().orEmpty()
                        .any { File(it, "${state.fileName}.aria2").isFile }
                    if (state.status == DownloadStatus.DOWNLOADING && state.completedBytes > 256 * 1024 && checkpoint) break
                    delay(100)
                }
            }
            delay(5500) // Let periodic native checkpoints and throttled Room progress writes land.
            assertEquals(DownloadStatus.DOWNLOADING, controller.state.value.status)
            // Keep instrumentation alive: finishing the runner would itself terminate the target.
            File(context.noBackupFilesDir, "process-recovery-ready").writeText(controller.state.value.fileName)
            delay(120_000)
            error("Host did not kill the live process")
        }
    }

    @Test fun recoverAndVerifyPublishedContent() = runBlocking {
        assumeTrue(args.getString("recoveryPhase") == "recover")
        ActivityScenario.launch(MainActivity::class.java).use {
            delay(500)
            val restored = withTimeout(15_000) {
                controller.state.first { it.fileName == args.getString("recoveryName") }
            }
            assertNotEquals(DownloadStatus.INTERRUPTED, restored.status)
            if (restored.canResume) withContext(Dispatchers.Main) { controller.resume() }
            val complete = withTimeout(120_000) {
                controller.state.first { it.status in setOf(DownloadStatus.COMPLETED,
                    DownloadStatus.FAILED, DownloadStatus.SAVE_FAILED) }
            }
            assertEquals(complete.message, DownloadStatus.COMPLETED, complete.status)
            val uri = Uri.parse(requireNotNull(complete.publishedUri))
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
    }
}
