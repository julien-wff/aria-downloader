package com.julien_wff.aria_android

import androidx.activity.compose.setContent
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import com.julien_wff.aria_android.downloads.DownloadSettings
import com.julien_wff.aria_android.ui.theme.AriaTheme
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class SettingsLayoutTest {
    @get:Rule(order = 0) val visibility = TestActivityVisibilityRule()
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()

    @Test fun autosaveAndAppearanceSelectionKeepControlsInPlace() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        var save = CompletableDeferred<Unit>()
        lateinit var editor: SettingsEditor
        try {
            compose.runOnUiThread {
                editor = SettingsEditor(scope, { DownloadSettings() }, { save.await() })
                compose.activity.setContent {
                    val state by editor.state.collectAsState()
                    AriaTheme(state.settings.appearance) { SettingsScreen(state, editor) }
                }
            }
            compose.waitUntil(10_000) { editor.editable }
            val initial = compose.onNodeWithText("Simultaneous downloads").fetchSemanticsNode().boundsInRoot
            compose.onNodeWithContentDescription("Increase Simultaneous downloads").performClick()
            compose.runOnIdle { assertEquals(SettingsSaveStatus.PENDING, editor.state.value.status) }
            assertEquals(initial, compose.onNodeWithText("Simultaneous downloads").fetchSemanticsNode().boundsInRoot)
            compose.onNodeWithText("Saving changes…").assertDoesNotExist()
            save.complete(Unit)
            compose.waitUntil(10_000) { editor.state.value.status == SettingsSaveStatus.SAVED }
            assertEquals(initial, compose.onNodeWithText("Simultaneous downloads").fetchSemanticsNode().boundsInRoot)

            compose.onNodeWithText("Dark").performScrollTo()
            val labels = listOf("System", "Light", "Dark")
            val positions = labels.associateWith { compose.onNodeWithText(it).fetchSemanticsNode().boundsInRoot }
            compose.runOnUiThread { save = CompletableDeferred() }
            compose.onNodeWithText("Dark").performClick()
            compose.runOnIdle { assertEquals(SettingsSaveStatus.PENDING, editor.state.value.status) }
            for (label in labels) assertEquals(positions[label], compose.onNodeWithText(label).fetchSemanticsNode().boundsInRoot)
            save.complete(Unit)
            compose.waitUntil(10_000) { editor.state.value.status == SettingsSaveStatus.SAVED }
            for (label in labels) assertEquals(positions[label], compose.onNodeWithText(label).fetchSemanticsNode().boundsInRoot)
        } finally { scope.cancel() }
    }
}
