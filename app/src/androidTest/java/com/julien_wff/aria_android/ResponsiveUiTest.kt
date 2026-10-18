package com.julien_wff.aria_android

import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.compose.runtime.key
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Rule
import org.junit.Test

/** Exercise adaptive navigation without changing the physical device's display settings. */
class ResponsiveUiTest {
    @get:Rule(order = 0) val visibility = TestActivityVisibilityRule()
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()

    @Test fun formsAndNavigationRemainReachableInLandscapeTabletAndLargeRtlText() {
        val cases = listOf(
            "landscape" to DeviceConfigurationOverride.ForcedSize(DpSize(700.dp, 360.dp)),
            "tablet" to DeviceConfigurationOverride.ForcedSize(DpSize(1000.dp, 800.dp)),
            "large-rtl" to (DeviceConfigurationOverride.ForcedSize(DpSize(360.dp, 740.dp)) then
                DeviceConfigurationOverride.FontScale(1.5f) then DeviceConfigurationOverride.LayoutDirection(LayoutDirection.Rtl)),
        )
        for ((name, configuration) in cases) {
            compose.runOnUiThread {
                val model = ViewModelProvider(compose.activity)[DownloadsViewModel::class.java]
                compose.activity.setContent {
                    key(name) { DeviceConfigurationOverride(configuration) { DownloadsScreen(model) } }
                }
            }
            compose.waitUntil(10_000) {
                runCatching { compose.onNodeWithContentDescription("Search downloads").assertIsDisplayed(); true }.getOrDefault(false)
            }
            captureUi(compose.onRoot(), "$name-downloads")
            compose.onNode(hasText("Settings") and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)).performClick()
            compose.onNodeWithText("Connections per server").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("Appearance").performScrollTo().assertIsDisplayed()
            captureUi(compose.onRoot(), "$name-settings")
            compose.onNodeWithText("Downloads").performClick()
            compose.onNode(hasText("Add download") or hasContentDescription("Add download")).performClick()
            compose.onNodeWithText("HTTP or HTTPS URL").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("Filename").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("Start download").assertIsDisplayed()
            captureUi(compose.onRoot(), "$name-add")
            compose.onNodeWithContentDescription("Back").performClick()
            compose.onNodeWithText("History").performClick()
            compose.waitUntil(10_000) {
                runCatching { compose.onNodeWithContentDescription("Search downloads").assertIsDisplayed(); true }.getOrDefault(false)
            }
        }
    }
}

/** Opt-in review artifacts remain in the debug app cache when instrumentation is run directly. */
internal fun captureUi(root: SemanticsNodeInteraction, name: String) {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    if (InstrumentationRegistry.getArguments().getString("captureUi") != "true") return
    val bitmap = if (name.endsWith("keyboard")) instrumentation.uiAutomation.takeScreenshot()
        else root.captureToImage().asAndroidBitmap()
    File(instrumentation.targetContext.cacheDir, "ui-$name.png").outputStream().use {
        check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
    }
}
