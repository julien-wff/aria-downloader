package com.julien_wff.aria_android

import android.content.ActivityNotFoundException
import android.provider.Settings
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import com.julien_wff.aria_android.ui.theme.AriaTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class BackgroundDownloadsUiTest {
    @get:Rule(order = 0) val visibility = TestActivityVisibilityRule()
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()

    @Test fun statusRefreshesOnResumeAndSettingsFailureRemainsActionable() {
        var status = BackgroundDownloadStatus(false, true, true)
        val actions = mutableListOf<Boolean>()
        compose.activity.setContent {
            AriaTheme {
                DeviceConfigurationOverride(
                    DeviceConfigurationOverride.ForcedSize(DpSize(360.dp, 640.dp)) then
                        DeviceConfigurationOverride.FontScale(1.5f),
                ) {
                    val current = rememberBackgroundDownloadStatus { status }
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        BackgroundDownloadsSettings(current) { actions.add(it); false }
                    }
                }
            }
        }
        compose.onNode(hasText("Background activity") and hasText("Restricted")).assertExists()
        compose.onNode(hasText("Battery Saver") and hasText("On")).assertExists()
        compose.onNodeWithText("Battery settings").performScrollTo().performClick()
        compose.onNodeWithText("App settings").performScrollTo().performClick()
        compose.onNodeWithText("Open Android Settings → Apps → Aria manually.").performScrollTo().assertIsDisplayed()
        compose.runOnIdle { assertEquals(listOf(true, false), actions) }
        compose.activityRule.scenario.moveToState(Lifecycle.State.STARTED)
        status = BackgroundDownloadStatus(true, false, false)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.onNode(hasText("Battery optimization") and hasText("Exempt")).assertExists()
        compose.onNode(hasText("Background activity") and hasText("Allowed")).assertExists()
        compose.onNode(hasText("Battery Saver") and hasText("Off")).assertExists()
    }

    @Test fun publicSettingsIntentsFallBackAndHandleMissingScreens() {
        val attempted = mutableListOf<String?>()
        assertTrue(openBackgroundSettings(true, { intent ->
            attempted.add(intent.action)
            if (intent.action == Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS) throw ActivityNotFoundException()
            assertEquals("package:${compose.activity.packageName}", intent.data.toString())
        }, compose.activity.packageName))
        assertEquals(listOf(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS, Settings.ACTION_APPLICATION_DETAILS_SETTINGS), attempted)
        attempted.clear()
        assertFalse(openBackgroundSettings(false, { intent ->
            attempted.add(intent.action)
            throw SecurityException()
        }, compose.activity.packageName))
        assertEquals(listOf(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Settings.ACTION_SETTINGS), attempted)
    }
}
