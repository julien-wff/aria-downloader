package com.julien_wff.aria_android

import com.julien_wff.aria_android.downloads.Appearance
import com.julien_wff.aria_android.downloads.DownloadSettings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsEditorTest {
    @Test fun changesDuringSlowSaveAreMergedAndSerialized() = runTest {
        val gate = CompletableDeferred<Unit>()
        val writes = mutableListOf<DownloadSettings>()
        var active = 0
        val editor = SettingsEditor(backgroundScope, { DownloadSettings() }) {
            active++
            assertEquals(1, active)
            writes += it
            if (writes.size == 1) gate.await()
            active--
        }
        runCurrent()
        editor.update { it.copy(concurrency = 2) }
        runCurrent()
        editor.update { it.copy(connections = 8) }
        editor.update { it.copy(appearance = Appearance.DARK, wifiOnly = true) }
        assertEquals(SettingsSaveStatus.PENDING, editor.state.value.status)
        gate.complete(Unit)
        runCurrent()
        assertEquals(2, writes.size)
        assertEquals(DownloadSettings(2, 8, 0, true, Appearance.DARK), writes.last())
        assertEquals(SettingsSaveStatus.SAVED, editor.state.value.status)
    }

    @Test fun speedDebouncesButNavigationCommitIsImmediateAndInvalidDraftDoesNotSave() = runTest {
        val writes = mutableListOf<DownloadSettings>()
        val editor = SettingsEditor(backgroundScope, { DownloadSettings() }, { writes += it })
        runCurrent()
        editor.editSpeed("12")
        advanceTimeBy(300)
        editor.editSpeed("123")
        advanceTimeBy(399)
        runCurrent()
        assertTrue(writes.isEmpty())
        advanceTimeBy(1)
        runCurrent()
        assertEquals(123L, writes.last().speedLimit)
        editor.editSpeed("456")
        editor.update { it.copy(connections = 6) }
        editor.commitSpeed()
        runCurrent()
        assertEquals(456L, writes.last().speedLimit)
        assertEquals(6, writes.last().connections)
        for (invalid in listOf("", "-1", "oops", "9223372036854775808")) {
            editor.editSpeed(invalid)
            editor.commitSpeed()
            advanceTimeBy(500)
            runCurrent()
            assertFalse(editor.state.value.validSpeed)
            assertEquals(456L, editor.state.value.settings.speedLimit)
            assertEquals(invalid, editor.state.value.speed)
        }
        editor.editSpeed("0")
        editor.commitSpeed()
        runCurrent()
        assertEquals(0L, writes.last().speedLimit)
    }

    @Test fun failedSaveRetainsDraftAndRetryAppliesLatestChanges() = runTest {
        var fail = true
        var saved = DownloadSettings()
        val editor = SettingsEditor(backgroundScope, { saved }) {
            if (fail) error("storage failure")
            saved = it
        }
        runCurrent()
        editor.update { it.copy(concurrency = 6) }
        runCurrent()
        assertEquals(SettingsSaveStatus.ERROR, editor.state.value.status)
        assertEquals(6, editor.state.value.settings.concurrency)
        fail = false
        editor.retry()
        runCurrent()
        assertEquals(6, saved.concurrency)
        assertEquals(SettingsSaveStatus.SAVED, editor.state.value.status)
    }

    @Test fun loadFailureDoesNotOverwriteExistingPreferencesAndCanBeRetried() = runTest {
        var fail = true
        val editor = SettingsEditor(backgroundScope, {
            if (fail) error("read failed")
            DownloadSettings(concurrency = 5)
        }, { error("must not save") })
        runCurrent()
        editor.update { it.copy(concurrency = 1) }
        assertFalse(editor.editable)
        assertEquals(SettingsSaveStatus.ERROR, editor.state.value.status)
        fail = false
        editor.retry()
        runCurrent()
        assertTrue(editor.editable)
        assertEquals(5, editor.state.value.settings.concurrency)
    }

    @Test fun returningToOriginalValueStillWaitsForInFlightWrite() = runTest {
        val gate = CompletableDeferred<Unit>()
        var saves = 0
        val editor = SettingsEditor(backgroundScope, { DownloadSettings() }) {
            if (++saves == 1) gate.await()
        }
        runCurrent()
        editor.update { it.copy(concurrency = 2) }
        runCurrent()
        editor.update { it.copy(concurrency = 3) }
        editor.commitSpeed()
        editor.editSpeed("0")
        assertEquals(SettingsSaveStatus.PENDING, editor.state.value.status)
        gate.complete(Unit)
        runCurrent()
        assertEquals(SettingsSaveStatus.SAVED, editor.state.value.status)
    }

    @Test fun invalidSpeedEditDoesNotHideFailedSave() = runTest {
        val editor = SettingsEditor(backgroundScope, { DownloadSettings() }, { error("write failed") })
        runCurrent()
        editor.update { it.copy(concurrency = 5) }
        runCurrent()
        editor.editSpeed("")
        editor.commitSpeed()
        assertEquals(SettingsSaveStatus.ERROR, editor.state.value.status)
    }

    @Test fun revertingSpeedDraftCancelsPendingSaveWithoutStuckIndicator() = runTest {
        val editor = SettingsEditor(backgroundScope, { DownloadSettings() }, { error("must not save") })
        runCurrent()
        editor.editSpeed("10")
        editor.editSpeed("0")
        advanceTimeBy(500)
        runCurrent()
        assertEquals(SettingsSaveStatus.SAVED, editor.state.value.status)
    }
}
