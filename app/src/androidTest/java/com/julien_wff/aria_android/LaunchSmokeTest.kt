package com.julien_wff.aria_android

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LaunchSmokeTest {
    @get:Rule(order = 0)
    val visibility = TestActivityVisibilityRule()

    @get:Rule(order = 1)
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun launcherShowsDownloadControls() {
        composeRule.onNodeWithText(composeRule.activity.getString(R.string.downloads_title))
            .assertIsDisplayed()
        composeRule.onNodeWithText("HTTP or HTTPS URL").assertIsDisplayed()
        composeRule.onNodeWithText("Filename").assertIsDisplayed()
        composeRule.onNodeWithText("Start download").assertIsDisplayed()
    }
}
