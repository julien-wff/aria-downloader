package com.julien_wff.aria_android

import android.content.Intent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import com.julien_wff.aria_android.downloads.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ProductUiTest {
    @get:Rule(order = 0) val visibility = TestActivityVisibilityRule()
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()

    @Test fun addValidatesInputAndShareIntakeRequiresExplicitStart() {
        compose.onNodeWithText("Add download").performClick()
        compose.onNodeWithText("HTTP or HTTPS URL").performTextInput("ftp://invalid.example/file")
        compose.onNodeWithText("Start download").performClick()
        compose.onNodeWithText("An absolute HTTP or HTTPS URL is required").assertIsDisplayed()
        compose.onNodeWithText("Back").performClick()
        val controller = DownloadRuntime.controller(compose.activity)
        val count = controller.tasks.value.size
        compose.runOnUiThread {
            compose.activity.startActivity(Intent(compose.activity, MainActivity::class.java).setAction(Intent.ACTION_SEND).setType("text/plain")
                .putExtra(Intent.EXTRA_TEXT, "https://example.org/shared.bin"))
        }
        compose.onNodeWithText("HTTP or HTTPS URL").assertTextContains("https://example.org/shared.bin")
        compose.onNodeWithText("Filename").assertTextContains("shared.bin")
        assertEquals(count, controller.tasks.value.size)
    }

    @Test fun settingsAndHistoryNavigationPersistAppearanceAndDefaults(): Unit = runBlocking {
        val preferences = DownloadRuntime.preferences(compose.activity)
        val original = preferences.read()
        try {
            compose.onNodeWithText("Settings").performClick()
            compose.onNodeWithText("Simultaneous downloads (1–6)").performTextReplacement("2")
            compose.onNodeWithText("Connections per server (1–16)").performTextReplacement("8")
            compose.onNodeWithText("Dark").performScrollTo().performClick()
            compose.onNodeWithText("Save settings").performScrollTo().performClick()
            withTimeout(10_000) { preferences.settings.first { it.concurrency == 2 && it.connections == 8 && it.appearance == Appearance.DARK } }
            compose.onNodeWithText("History").performClick()
            compose.onNodeWithText("Search downloads").assertIsDisplayed()
            compose.onAllNodesWithText("Settings")[0].performClick()
            compose.onNodeWithText("Simultaneous downloads (1–6)").assertTextContains("2")
            compose.onNodeWithText("Connections per server (1–16)").assertTextContains("8")
        } finally { preferences.save(original) }
    }

    @Test fun detailsControlsPauseResumeAndConfirmCancellation(): Unit = runBlocking {
        val controller = DownloadRuntime.controller(compose.activity)
        val preferences = DownloadRuntime.preferences(compose.activity)
        val oldSettings = preferences.read()
        preferences.save(DownloadSettings())
        controller.tasks.value.filter { it.state.canCancel }.forEach { controller.cancelTask(it.id) }
        BackgroundDownloadTest.ControlledServer().use { server ->
            val name = "ui-${System.nanoTime()}.bin"
            var id: String? = null
            try {
                withContext(Dispatchers.Main) { id = controller.enqueue(server.url, name, DownloadSettings(connections = 1, speedLimit = 64 * 1024)) }
                withTimeout(30_000) { controller.tasks.first { tasks -> tasks.any { it.id == id && it.state.status == DownloadStatus.DOWNLOADING } } }
                compose.onNodeWithText(name).performScrollTo().performClick()
                compose.onNodeWithText("Pause").performClick()
                compose.onNodeWithText("Resume").assertIsDisplayed().performClick()
                withTimeout(10_000) { controller.tasks.first { tasks -> tasks.any { it.id == id && it.state.canPause } } }
                compose.onNodeWithText("Pause").performScrollTo().assertIsDisplayed()
                compose.onNodeWithText("More").performScrollTo().performClick()
                compose.onNodeWithText("Cancel download").performClick()
                compose.onNodeWithText("Keep downloading").assertIsDisplayed()
                compose.onAllNodesWithText("Cancel download")[0].performClick()
                withTimeout(10_000) { controller.tasks.first { tasks -> tasks.any { it.id == id && it.state.status == DownloadStatus.CANCELED } } }
                compose.onNodeWithText("Canceled").assertIsDisplayed()
            } finally {
                id?.let { controller.cancelTask(it) }
                preferences.save(oldSettings)
            }
        }
    }
}
