package com.julien_wff.aria_android

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import org.junit.Rule
import org.junit.Test

class DownloadDesignUiTest {
    @get:Rule(order = 0) val visibility = TestActivityVisibilityRule()
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()

    @Test fun searchAndFiltersRemainIndependentAcrossDestinationsAndRecreation() {
        compose.onNodeWithContentDescription("Search downloads").performClick()
        compose.onNode(hasSetTextAction()).performTextInput("aria-no-match-82931")
        finishSearch()
        compose.onNode(hasText("Paused") and isSelectable()).performClick()
        compose.onNodeWithText("No matching downloads").assertIsDisplayed()
        compose.onNodeWithText("History").performClick()
        compose.onNode(hasSetTextAction()).assertDoesNotExist()
        compose.onNodeWithContentDescription("Search downloads").performClick()
        compose.onNode(hasSetTextAction()).performTextInput("history-no-match-53912")
        finishSearch()
        compose.activityRule.scenario.recreate()
        compose.onNode(hasSetTextAction()).assertTextContains("history-no-match-53912")
        finishSearch()
        compose.onNodeWithText("Downloads").performClick()
        compose.onNode(hasSetTextAction()).assertTextContains("aria-no-match-82931")
        finishSearch()
        compose.onNode(hasText("Paused") and isSelectable()).assertIsSelected()
        compose.onNodeWithContentDescription("Close search").performClick()
        compose.onNode(hasSetTextAction()).assertDoesNotExist()
        compose.onNode(hasText("Paused") and isSelectable()).assertIsSelected()
        compose.onNodeWithContentDescription("Search downloads").performClick()
        compose.onNode(hasSetTextAction()).performTextInput("aria-no-match-82931")
        finishSearch()
        compose.onNodeWithText("Clear search and filters").performScrollTo().performClick()
        compose.onNode(hasText("All") and isSelectable()).assertIsSelected()
        compose.onNodeWithText("History").performClick()
        compose.onNode(hasSetTextAction()).assertTextContains("history-no-match-53912")
    }

    @Test fun backDismissesKeyboardBeforeClosingSearch() {
        compose.onNodeWithContentDescription("Search downloads").performClick()
        compose.onNode(hasSetTextAction()).performTextInput("draft query")
        compose.waitUntil(10_000) {
            compose.activity.window.decorView.rootWindowInsets.isVisible(android.view.WindowInsets.Type.ime())
        }
        androidx.test.espresso.Espresso.pressBack()
        compose.waitUntil(10_000) {
            !compose.activity.window.decorView.rootWindowInsets.isVisible(android.view.WindowInsets.Type.ime())
        }
        compose.onNode(hasSetTextAction()).assertTextContains("draft query")
        androidx.test.espresso.Espresso.pressBack()
        compose.onNode(hasSetTextAction()).assertDoesNotExist()
        compose.onNodeWithContentDescription("Search downloads").assertIsDisplayed()
    }

    private fun finishSearch() {
        compose.onNode(hasSetTextAction()).performImeAction()
        compose.waitUntil(10_000) {
            compose.onAllNodes(hasText("History") and SemanticsMatcher.expectValue(
                androidx.compose.ui.semantics.SemanticsProperties.Role, androidx.compose.ui.semantics.Role.Tab,
            )).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test fun protocolOverrideAndDraftSurviveRecreationWithKeyboardSafeAction() {
        compose.onNodeWithText("Add download").performClick()
        compose.onNodeWithText("HTTP or HTTPS URL").performTextReplacement("https://example.org/archive.meta4")
        compose.onNodeWithText("Protocol: Metalink").performClick()
        compose.onNodeWithText("Torrent").performClick()
        compose.onNodeWithText("Protocol: Torrent").assertIsDisplayed()
        compose.onNodeWithText("HTTP or HTTPS URL").performTextReplacement("https://example.org/metadata")
        compose.onNodeWithText("Download title").performTextReplacement("My files")
        compose.activityRule.scenario.recreate()
        compose.onNodeWithText("Protocol: Torrent").assertExists()
        compose.onNodeWithText("Download title").performScrollTo().assertTextContains("My files")
        compose.onNodeWithText("HTTP or HTTPS URL").performScrollTo().performClick()
        compose.waitUntil(10_000) {
            compose.activity.window.decorView.rootWindowInsets.isVisible(android.view.WindowInsets.Type.ime())
        }
        compose.onNodeWithText("Review files").assertIsDisplayed()
        captureUi(compose.onRoot(), "redesign-add-keyboard")
        compose.onNodeWithText("HTTP or HTTPS URL").performImeAction()
        compose.onNodeWithText("Download title").assertIsFocused()
    }
}
