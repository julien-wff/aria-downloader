package com.julien_wff.aria_android

import android.content.Intent
import android.content.ClipData
import android.content.ClipboardManager
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

    @Test fun advancedRequestFieldsCollapseRetainAndReset() {
        compose.onNodeWithText("Add download").performClick()
        compose.onNodeWithText("Files are saved to Downloads after the transfer completes.").assertDoesNotExist()
        compose.onNodeWithText("HTTP referrer").assertDoesNotExist()
        compose.onNodeWithText("User-Agent").assertDoesNotExist()
        compose.onNodeWithText("Advanced").performScrollTo().performClick()
        compose.onNodeWithText("HTTP referrer").performScrollTo().performTextReplacement("https://example.org/page")
        compose.onNodeWithText("User-Agent").performScrollTo().performTextReplacement("Custom/1.0")
        compose.onNodeWithText("User-Agent").performImeAction()
        compose.onNodeWithText("Advanced").performScrollTo().performClick()
        compose.onNodeWithText("User-Agent").assertDoesNotExist()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithText("User-Agent").assertDoesNotExist()
        compose.onNodeWithText("Advanced").performScrollTo().performClick()
        compose.onNodeWithText("HTTP referrer").performScrollTo().assertTextContains("https://example.org/page")
        compose.onNodeWithText("User-Agent").performScrollTo().assertTextContains("Custom/1.0")
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithText("Add download").performClick()
        compose.onNodeWithText("HTTP referrer").assertDoesNotExist()
        compose.onNodeWithText("Advanced").performScrollTo().performClick()
        compose.onNodeWithText("HTTP referrer").performScrollTo().assert(SemanticsMatcher.expectValue(
            androidx.compose.ui.semantics.SemanticsProperties.EditableText, androidx.compose.ui.text.AnnotatedString("")))
        compose.onNodeWithText("User-Agent").performScrollTo().assert(SemanticsMatcher.expectValue(
            androidx.compose.ui.semantics.SemanticsProperties.EditableText, androidx.compose.ui.text.AnnotatedString("")))
        compose.onNodeWithText("HTTP referrer").performScrollTo().performTextReplacement("relative")
        compose.onNodeWithText("HTTP or HTTPS URL").performScrollTo().performTextReplacement("https://example.org/file")
        compose.onNodeWithText("HTTP or HTTPS URL").performImeAction()
        compose.onNodeWithText("Filename").performImeAction()
        compose.onNodeWithText("Start download").performScrollTo().performClick()
        compose.onNodeWithText("HTTP referrer must be an absolute HTTP or HTTPS URL without credentials.").performScrollTo().assertIsDisplayed()
        compose.runOnUiThread {
            compose.activity.startActivity(Intent(compose.activity, MainActivity::class.java)
                .setAction(Intent.ACTION_SEND).setType("text/plain")
                .putExtra(Intent.EXTRA_TEXT, "https://example.org/shared.bin"))
        }
        compose.onNodeWithText("HTTP referrer").assertDoesNotExist()
        compose.onNodeWithText("Advanced").performScrollTo().performClick()
        compose.onNodeWithText("HTTP referrer").performScrollTo().assert(SemanticsMatcher.expectValue(
            androidx.compose.ui.semantics.SemanticsProperties.EditableText, androidx.compose.ui.text.AnnotatedString("")))
    }

    @Test fun addValidatesInputAndShareIntakeRequiresExplicitStart() {
        compose.onNodeWithText("Add download").performClick()
        compose.onNodeWithText("HTTP or HTTPS URL").performTextReplacement("ftp://invalid.example/file")
        compose.onNodeWithText("Start download").performClick()
        compose.onNodeWithText("An absolute HTTP or HTTPS URL is required").assertIsDisplayed()
        compose.onNodeWithContentDescription("Back").performClick()
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
            compose.waitUntil(10_000) {
                compose.onAllNodes((hasContentDescription("Increase Simultaneous downloads") or
                    hasContentDescription("Decrease Simultaneous downloads")) and isEnabled()).fetchSemanticsNodes().isNotEmpty()
            }
            repeat(kotlin.math.abs(original.concurrency - 2)) {
                compose.onNodeWithContentDescription("${if (original.concurrency > 2) "Decrease" else "Increase"} Simultaneous downloads").performScrollTo().performClick()
            }
            repeat(kotlin.math.abs(original.connections - 8)) {
                compose.onNodeWithContentDescription("${if (original.connections > 8) "Decrease" else "Increase"} Connections per server").performScrollTo().performClick()
            }
            compose.onNodeWithText("Light").performScrollTo().performClick()
            compose.onNodeWithText("Simultaneous downloads").performScrollTo()
            captureUi(compose.onRoot(), "light-settings")
            compose.onNodeWithText("Dark").performScrollTo().performClick()
            compose.onNodeWithText("Simultaneous downloads").performScrollTo()
            captureUi(compose.onRoot(), "dark-settings")
            compose.onNodeWithText("Save settings").assertDoesNotExist()
            compose.onNodeWithText("All changes saved automatically").assertDoesNotExist()
            withTimeout(10_000) { preferences.settings.first { it.concurrency == 2 && it.connections == 8 && it.appearance == Appearance.DARK } }
            compose.waitUntil(10_000) { compose.onAllNodesWithText("History").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("History").performClick()
            compose.onNodeWithText("Search downloads").assertIsDisplayed()
            compose.onNodeWithText("Settings").performClick()
            compose.onNodeWithText("Simultaneous downloads").onParent().assert(hasAnyDescendant(hasText("2")))
            compose.onNodeWithText("Connections per server").onParent().assert(hasAnyDescendant(hasText("8")))
        } finally { preferences.save(original) }
    }

    @Test fun keyboardFormsAndAutosaveSurviveNavigationAndRecreation(): Unit = runBlocking {
        val preferences = DownloadRuntime.preferences(compose.activity)
        val original = preferences.read()
        try {
            compose.onNodeWithText("Settings").performClick()
            compose.waitUntil(10_000) {
                compose.onAllNodes((hasContentDescription("Increase Simultaneous downloads") or
                    hasContentDescription("Decrease Simultaneous downloads")) and isEnabled()).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText("Speed limit in bytes/s").performScrollTo().performClick().performTextReplacement("12345")
            compose.waitUntil(10_000) {
                compose.activity.window.decorView.rootWindowInsets.isVisible(android.view.WindowInsets.Type.ime())
            }
            compose.waitUntil(10_000) { compose.onAllNodesWithText("History").fetchSemanticsNodes().isEmpty() }
            compose.onNodeWithText("History").assertDoesNotExist()
            captureUi(compose.onRoot(), "settings-keyboard")
            compose.onNodeWithText("Speed limit in bytes/s").performImeAction()
            withTimeout(10_000) { preferences.settings.first { it.speedLimit == 12345L } }
            compose.onNodeWithText("Speed limit in bytes/s").performTextReplacement("-1")
            compose.onNodeWithText("Speed limit in bytes/s").performImeAction()
            assertEquals(12345L, preferences.read().speedLimit)
            compose.waitUntil(10_000) { compose.onAllNodesWithText("History").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("History").performClick()
            compose.onNodeWithText("Settings").performClick()
            compose.onNodeWithText("Speed limit in bytes/s").performScrollTo().assertTextContains("-1")
            compose.onNodeWithText("Speed limit in bytes/s").performTextReplacement("67890")
            compose.onNodeWithText("Speed limit in bytes/s").performImeAction()
            compose.waitUntil(10_000) { compose.onAllNodesWithText("History").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("History").performClick()
            withTimeout(10_000) { preferences.settings.first { it.speedLimit == 67890L } }
            compose.activityRule.scenario.recreate()
            compose.onNodeWithText("Settings").performClick()
            compose.onNodeWithText("Speed limit in bytes/s").performScrollTo().assertTextContains("67890")
            compose.onNodeWithText("Downloads").performClick()
            compose.onNodeWithText("Add download").performClick()
            compose.onNodeWithText("HTTP or HTTPS URL").performClick().performTextReplacement("https://example.org/a.bin")
            compose.waitUntil(10_000) {
                compose.activity.window.decorView.rootWindowInsets.isVisible(android.view.WindowInsets.Type.ime())
            }
            compose.onNodeWithText("HTTP or HTTPS URL").performImeAction()
            compose.onNodeWithText("Filename").assertIsFocused()
            compose.onNodeWithText("Start download").performScrollTo().assertIsDisplayed()
            captureUi(compose.onRoot(), "add-keyboard")
            compose.onNodeWithText("Filename").performImeAction()
            compose.waitUntil(10_000) {
                !compose.activity.window.decorView.rootWindowInsets.isVisible(android.view.WindowInsets.Type.ime())
            }
            androidx.test.espresso.Espresso.pressBack()
            compose.onNodeWithText("Search downloads").assertIsDisplayed()
        } finally { preferences.save(original) }
    }

    @Test fun clipboardPrefillsNewDownloadButNeverOverwritesDraftOrShareIntake() {
        org.junit.Assume.assumeFalse("Clipboard verification requires an unlocked device",
            compose.activity.getSystemService(android.app.KeyguardManager::class.java).isDeviceLocked)
        val clipboard = compose.activity.getSystemService(ClipboardManager::class.java)
        var original: ClipData? = null
        compose.runOnUiThread { original = clipboard.primaryClip }
        fun copy(text: String) = compose.runOnUiThread {
            clipboard.setPrimaryClip(ClipData.newPlainText("test", text))
        }
        try {
            copy("Download this: https://example.org/files/my%20report.pdf?token=123")
            val count = DownloadRuntime.controller(compose.activity).tasks.value.size
            compose.onNodeWithText("Add download").performClick()
            compose.onNodeWithText("HTTP or HTTPS URL").assertTextContains("https://example.org/files/my%20report.pdf?token=123")
            compose.onNodeWithText("Filename").assertTextContains("my report.pdf")
            copy("https://example.org/other.bin")
            compose.activityRule.scenario.recreate()
            compose.onNodeWithText("Filename").assertTextContains("my report.pdf")
            compose.runOnUiThread {
                compose.activity.startActivity(Intent(compose.activity, MainActivity::class.java).setAction(Intent.ACTION_SEND).setType("text/plain")
                    .putExtra(Intent.EXTRA_TEXT, "https://example.org/shared.zip"))
            }
            compose.onNodeWithText("HTTP or HTTPS URL").assertTextContains("https://example.org/shared.zip")
            compose.onNodeWithText("Filename").assertTextContains("shared.zip")
            assertEquals(count, DownloadRuntime.controller(compose.activity).tasks.value.size)
            compose.onNodeWithContentDescription("Back").performClick()
            copy("No web link here")
            compose.onNodeWithText("Add download").performClick()
            compose.onNodeWithText("HTTP or HTTPS URL").assert(hasText(""))
            compose.onNodeWithText("Filename").assertTextContains("download")
        } finally {
            compose.runOnUiThread { original?.let(clipboard::setPrimaryClip) ?: clipboard.clearPrimaryClip() }
        }
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
                compose.onNodeWithContentDescription("More").performScrollTo().performClick()
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
