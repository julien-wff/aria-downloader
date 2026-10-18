package com.julien_wff.aria_android

import android.net.Uri
import androidx.activity.compose.setContent
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import com.julien_wff.aria_android.downloads.*
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class DownloadActionsUiTest {
    @get:Rule(order = 0) val visibility = TestActivityVisibilityRule()
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()

    @Test fun retryFromHistoryOpensNewTaskAndRejectsDuplicateSubmission(): Unit = runBlocking {
        val controller = DownloadRuntime.controller(compose.activity)
        val preferences = DownloadRuntime.preferences(compose.activity)
        val original = preferences.read()
        val name = "retry-ui-${System.nanoTime()}.bin"
        BackgroundDownloadTest.ControlledServer().use { server ->
            try {
                preferences.save(DownloadSettings(connections = 1, speedLimit = 64 * 1024))
                val old = withContext(Dispatchers.Main) { controller.enqueue(server.url, name) }
                controller.cancelTask(old)
                compose.onNodeWithText("History").performClick()
                compose.onNodeWithText(name).performScrollTo().performClick()
                compose.onNodeWithText("Retry").assertIsDisplayed().performClick()
                compose.waitUntil(10_000) { controller.tasks.value.count { it.state.fileName == name } == 2 }
                val added = controller.tasks.value.single { it.state.fileName == name && it.id != old }
                compose.waitUntil(10_000) { compose.onAllNodesWithText("Download added to queue").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithText("Download details").assertIsDisplayed()
                compose.onNodeWithText(name).assertIsDisplayed()
                captureUi(compose.onRoot(), "ux-retry-details")
                compose.activityRule.scenario.recreate()
                compose.onNodeWithText("Download details").assertIsDisplayed()
                compose.onNodeWithContentDescription("Back").performClick()
                compose.onNode(hasText("Downloads") and isSelectable()).assertIsSelected()
                compose.onNode(hasText("All") and isSelectable()).assertIsSelected()
                compose.onNodeWithText(name).assertExists()
                assertEquals(DownloadStatus.CANCELED, controller.tasks.value.single { it.id == old }.state.status)
                controller.cancelTask(added.id)
                // Two requests in the same main-thread dispatch must create exactly one new task.
                compose.runOnUiThread {
                    val model = ViewModelProvider(compose.activity)[DownloadsViewModel::class.java]
                    model.retry(old)
                    model.retry(old)
                    assertEquals(TaskOperation.RETRYING, model.pendingActions.value[old])
                }
                compose.waitUntil(10_000) { controller.tasks.value.count { it.state.fileName == name } == 3 }
                compose.waitForIdle()
                assertEquals(3, controller.tasks.value.count { it.state.fileName == name })
            } finally {
                controller.tasks.value.filter { it.state.fileName == name }.forEach {
                    DownloadFiles(compose.activity, controller).delete(it.id, true)
                }
                preferences.save(original)
            }
        }
    }

    @Test fun failedPrivateCleanupKeepsDetailsAndAllowsAnotherAttempt(): Unit = runBlocking {
        val controller = DownloadRuntime.controller(compose.activity)
        val name = "delete-failure-${System.nanoTime()}.bin"
        var directory: File? = null
        var id: String? = null
        BackgroundDownloadTest.ControlledServer().use { server ->
            try {
                id = withContext(Dispatchers.Main) { controller.enqueue(server.url, name, DownloadSettings(connections = 1)) }
                withTimeout(30_000) { controller.tasks.first { tasks -> tasks.any { it.id == id && it.state.completedBytes > 0 } } }
                controller.cancelTask(id!!)
                directory = File(compose.activity.noBackupFilesDir, "aria2").listFiles()!!.single { File(it, name).exists() }
                File(directory, "cleanup-test").writeText("retained partial data")
                assertTrue(directory!!.setWritable(false, false))
                assertFalse(directory!!.canWrite())
                compose.onNodeWithText("History").performClick()
                compose.onNodeWithText(name).performScrollTo().performClick()
                openDelete()
                compose.onNode(hasText("Delete") and hasAnyAncestor(isDialog())).performClick()
                compose.waitUntil(10_000) {
                    compose.onAllNodesWithText("Couldn't finish deleting this download. The entry was kept. Try again.").fetchSemanticsNodes().isNotEmpty()
                }
                compose.onNodeWithText("Download details").assertIsDisplayed()
                compose.onNodeWithText(name).assertIsDisplayed()
                compose.onNodeWithText("Delete").assertIsEnabled()
                assertTrue(controller.tasks.value.any { it.id == id })
                assertTrue(File(directory, "cleanup-test").exists())
                assertTrue(directory!!.setWritable(true))
                openDelete()
                compose.onNode(hasText("Delete") and hasAnyAncestor(isDialog())).performClick()
                compose.waitUntil(10_000) { controller.tasks.value.none { it.id == id } }
            } finally {
                directory?.setWritable(true)
                id?.takeIf { target -> controller.tasks.value.any { it.id == target } }?.let {
                    DownloadFiles(compose.activity, controller).delete(it, true)
                }
            }
        }
    }

    @Test fun selectedTaskDisappearingClosesTabletDetails(): Unit = runBlocking {
        val controller = DownloadRuntime.controller(compose.activity)
        val name = "tablet-removal-${System.nanoTime()}.bin"
        var id: String? = null
        BackgroundDownloadTest.ControlledServer().use { server ->
            try {
                id = withContext(Dispatchers.Main) { controller.enqueue(server.url, name) }
                controller.pauseTask(id!!)
                compose.runOnUiThread {
                    val model = ViewModelProvider(compose.activity)[DownloadsViewModel::class.java]
                    compose.activity.setContent {
                        DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(DpSize(1000.dp, 800.dp))) {
                            DownloadsScreen(model)
                        }
                    }
                }
                compose.onNodeWithText(name).performScrollTo().performClick()
                val details = SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, "Download details")
                compose.onNode(details).assertExists()
                DownloadFiles(compose.activity, controller).delete(id!!, false)
                compose.waitUntil(10_000) { compose.onAllNodes(details).fetchSemanticsNodes().isEmpty() }
                compose.onNode(hasText("Downloads") and isSelectable()).assertIsSelected()
                compose.onNodeWithContentDescription("Search downloads").assertExists()
            } finally {
                id?.takeIf { target -> controller.tasks.value.any { it.id == target } }?.let {
                    DownloadFiles(compose.activity, controller).delete(it, true)
                }
            }
        }
    }

    @Test fun savedFileIsKeptByDefaultWhenDeletingHistoryEntry(): Unit = runBlocking {
        val controller = DownloadRuntime.controller(compose.activity)
        val name = "keep-public-${System.nanoTime()}.bin"
        var id: String? = null
        var uri: Uri? = null
        BackgroundDownloadTest.ControlledServer().use { server ->
            try {
                id = withContext(Dispatchers.Main) { controller.enqueue(server.url, name, DownloadSettings(connections = 1)) }
                val completed = withTimeout(30_000) { controller.tasks.first { tasks -> tasks.any {
                    it.id == id && it.state.status == DownloadStatus.COMPLETED
                } } }.single { it.id == id }
                uri = Uri.parse(completed.state.publishedUri)
                compose.onNodeWithText("History").performClick()
                compose.onNodeWithText(name).performScrollTo().performClick()
                compose.onNodeWithText("Open").assertIsDisplayed()
                compose.onNodeWithText("Share").assertIsDisplayed()
                openDelete()
                compose.onNode(isToggleable()).assertIsOff()
                compose.onNode(hasText("Delete") and hasAnyAncestor(isDialog())).performClick()
                compose.waitUntil(10_000) { controller.tasks.value.none { it.id == id } }
                compose.waitUntil(10_000) { compose.onAllNodesWithText("Download details").fetchSemanticsNodes().isEmpty() }
                compose.onNode(hasText("Downloads") and isSelectable()).assertIsSelected()
                assertArrayEquals(server.hash(), java.security.MessageDigest.getInstance("SHA-256").digest(
                    compose.activity.contentResolver.openInputStream(uri!!)!!.use { it.readBytes() }))
            } finally {
                id?.takeIf { target -> controller.tasks.value.any { it.id == target } }?.let {
                    DownloadFiles(compose.activity, controller).delete(it, true)
                }
                uri?.let { compose.activity.contentResolver.delete(it, null, null) }
            }
        }
    }
    private fun openDelete() {
        compose.onNodeWithText("Download details").assertIsDisplayed()
        compose.onNodeWithText("Delete").performScrollTo().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Keep").fetchSemanticsNodes().isNotEmpty() }
    }

}
