package com.julien_wff.aria_android

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import com.julien_wff.aria_android.downloads.*
import com.julien_wff.aria_android.ui.theme.AriaTheme
import org.junit.Rule
import org.junit.Test

class TransferInsightsUiTest {
    @get:Rule(order = 0) val visibility = TestActivityVisibilityRule()
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()

    @Test fun graphAndMatrixHaveAccessibleSummariesAtLargeText() {
        val task = DownloadTask("test", "https://example.org", DownloadState(status = DownloadStatus.DOWNLOADING),
            0, 4, 0, DownloadProtocol.TORRENT, insights = TransferInsights(
                listOf(SpeedSample(0, 1024), SpeedSample(1000, null), SpeedSample(2000, 2048)),
                PieceOverview(1000, 1024, listOf(PieceOverviewCell(100, 500), PieceOverviewCell(0, 500)), null)))
        compose.activity.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.FontScale(1.5f)) {
                AriaTheme { Column(Modifier.verticalScroll(rememberScrollState())) { TransferInsightsPanel(task) } }
            }
        }
        compose.onNodeWithText("Speed · last 2 minutes").performScrollTo().assertIsDisplayed()
        captureUi(compose.onRoot(), "transfer-insights-speed")
        compose.onNodeWithContentDescription("Download speed over the last two minutes.", substring = true).assertExists()
        compose.onNodeWithText("Whole torrent", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("100 of 1000 pieces complete.", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Piece completion describes private download data, not public saving.").performScrollTo().assertIsDisplayed()
        captureUi(compose.onRoot(), "transfer-insights-pieces")
    }

    @Test fun absentInsightsDoNotInventProgress() {
        val task = DownloadTask("test", "https://example.org", DownloadState(status = DownloadStatus.PAUSED), 0, 4, 0)
        compose.activity.setContent { AriaTheme { Column(Modifier.verticalScroll(rememberScrollState())) { TransferInsightsPanel(task) } } }
        compose.onNodeWithText("Paused by you.", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Speed history will appear", substring = true).assertExists()
        compose.onNodeWithText("Piece map unavailable.", substring = true).performScrollTo().assertIsDisplayed()
    }
}
