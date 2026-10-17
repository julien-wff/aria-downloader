package com.julien_wff.aria_android.downloads

import android.app.Notification
import android.Manifest
import android.app.NotificationManager
import android.content.Intent
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TransferNotificationsTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val notifications get() = TransferNotifications(context, Intent(context, TransferJobService::class.java))

    @Test fun deniedPermissionSkipsOptionalUpdatesWithoutCrashing() {
        // The instrumentation runner grants manifest permissions by default. Override only the
        // permission query instead of revoking a live process's permission (which kills it).
        val denied = object : ContextWrapper(context) {
            override fun checkSelfPermission(permission: String) =
                if (permission == Manifest.permission.POST_NOTIFICATIONS) PackageManager.PERMISSION_DENIED
                else super.checkSelfPermission(permission)
        }
        TransferNotifications(denied, Intent(context, TransferJobService::class.java))
            .update(DownloadState(status = DownloadStatus.DOWNLOADING), "task")
        assertTrue(context.getSystemService(NotificationManager::class.java).activeNotifications.isEmpty())
    }

    @Test fun taskControlsAndFileActionsHaveDistinctImmutableIdentities() {
        val notifications = notifications
        val downloading = DownloadState(status = DownloadStatus.DOWNLOADING)
        val first = notifications.build(downloading, "batch", "first")
        val second = notifications.build(downloading, "batch", "second")
        assertNotEquals(first.actions[0].actionIntent, second.actions[0].actionIntent)
        assertNotEquals(first.actions[1].actionIntent, second.actions[1].actionIntent)
        assertNotEquals(first.actions[0].actionIntent, notifications.build(downloading, "replacement", "first").actions[0].actionIntent)
        val staged = notifications.build(DownloadState(status = DownloadStatus.SAVE_FAILED), "batch", "first")
        assertEquals(listOf("Retry saving"), staged.actions.map { it.title.toString() })
        val completed = notifications.build(DownloadState(status = DownloadStatus.COMPLETED,
            publishedUri = "content://downloads/1"), "batch", "first")
        assertEquals(listOf("Open", "Share"), completed.actions.map { it.title.toString() })
        assertTrue(completed.actions.all { it.actionIntent.isImmutable && it.actionIntent.isActivity })
        assertNotEquals(completed.actions[0].actionIntent, completed.actions[1].actionIntent)
        val tasks = listOf("first", "second").map { DownloadTask(it, "https://example.org", downloading, 0, 4, 0) }
        assertTrue(notifications.summary(tasks, "batch").flags and Notification.FLAG_GROUP_SUMMARY != 0)
    }

    @Test fun knownLargeSizeUsesSystemProgressAndImmutableControls() {
        val notification = notifications.build(DownloadState(fileName = "large.bin",
            status = DownloadStatus.DOWNLOADING, completedBytes = 5L * 1024 * 1024 * 1024,
            totalBytes = 10L * 1024 * 1024 * 1024, bytesPerSecond = 1024 * 1024), "task")
        assertEquals("large.bin", notification.extras.getString(Notification.EXTRA_TITLE))
        assertEquals("active_transfers", notification.channelId)
        assertEquals(listOf("Pause", "Cancel"), notification.actions.map { it.title.toString() })
        assertTrue(notification.actions.all { it.actionIntent.isImmutable })
        assertTrue(notification.flags and Notification.FLAG_ONGOING_EVENT != 0)
        assertEquals(Notification.VISIBILITY_PRIVATE, notification.visibility)
        if (Build.VERSION.SDK_INT >= 36) {
            assertEquals(Notification.ProgressStyle::class.java.name,
                notification.extras.getString(Notification.EXTRA_TEMPLATE))
            val style = Notification.Builder.recoverBuilder(context, notification).style as Notification.ProgressStyle
            assertEquals(500, style.progress)
            assertEquals(1000, style.progressMax)
            assertFalse(style.isProgressIndeterminate)
        } else {
            assertEquals(500, notification.extras.getInt(Notification.EXTRA_PROGRESS))
            assertEquals(1000, notification.extras.getInt(Notification.EXTRA_PROGRESS_MAX))
        }
    }

    @Test fun unknownLengthIsIndeterminateAndOmitsEta() {
        val notification = notifications.build(DownloadState(status = DownloadStatus.DOWNLOADING,
            completedBytes = 1024, bytesPerSecond = 128), "task")
        assertFalse(notification.extras.getString(Notification.EXTRA_TEXT).orEmpty().contains("left"))
        if (Build.VERSION.SDK_INT >= 36) {
            val style = Notification.Builder.recoverBuilder(context, notification).style as Notification.ProgressStyle
            assertTrue(style.isProgressIndeterminate)
        } else assertTrue(notification.extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE))
    }

    @Test fun zeroSpeedOmitsEtaAndTerminalStatesRemoveProgressAndTransferControls() {
        val stopped = notifications.build(DownloadState(status = DownloadStatus.DOWNLOADING,
            completedBytes = 1, totalBytes = 100), "task")
        assertFalse(stopped.extras.getString(Notification.EXTRA_TEXT).orEmpty().contains("left"))
        for (status in listOf(DownloadStatus.COMPLETED, DownloadStatus.CANCELED,
                DownloadStatus.FAILED, DownloadStatus.PAUSED, DownloadStatus.INTERRUPTED)) {
            val notification = notifications.build(DownloadState(status = status), "task")
            assertEquals("transfer_results", notification.channelId)
            assertEquals(0, notification.flags and Notification.FLAG_ONGOING_EVENT)
            assertNotEquals("android.app.Notification\$ProgressStyle",
                notification.extras.getString(Notification.EXTRA_TEMPLATE))
            assertTrue(notification.actions.orEmpty().none { it.title in listOf("Pause", "Cancel") })
            if (status == DownloadStatus.PAUSED) {
                assertEquals("Resume", notification.actions.single().title)
                assertTrue(notification.actions.single().actionIntent.isActivity)
            }
        }
    }
}
