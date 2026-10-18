package com.julien_wff.aria_android

import android.app.job.JobParameters
import com.julien_wff.aria_android.downloads.DownloadState
import com.julien_wff.aria_android.downloads.DownloadStatus
import org.junit.Assert.*
import org.junit.Test

class BackgroundDownloadsTest {
    private val normal = BackgroundDownloadStatus(false, false, false)

    @Test fun optimizationAndBatterySaverAloneDoNotWarn() {
        for (exempt in listOf(false, true)) for (saver in listOf(false, true)) {
            assertNull(backgroundDownloadNotice(normal.copy(batteryExempt = exempt, batterySaver = saver), emptyList()))
        }
    }

    @Test fun currentRestrictionWarnsEvenWithoutTasksOrWithExemption() {
        for (exempt in listOf(false, true)) {
            assertTrue(backgroundDownloadNotice(normal.copy(backgroundRestricted = true, batteryExempt = exempt), emptyList())!!.startsWith("Android restricts"))
        }
    }

    @Test fun onlyRestrictionRelatedUnresolvedInterruptionsWarn() {
        for (reason in listOf(JobParameters.STOP_REASON_BACKGROUND_RESTRICTION, JobParameters.STOP_REASON_APP_STANDBY)) {
            for (status in DownloadStatus.entries) {
                val notice = backgroundDownloadNotice(normal, listOf(DownloadState(status = status, stopReason = reason)))
                assertEquals(status in setOf(DownloadStatus.WAITING, DownloadStatus.INTERRUPTED), notice != null)
                if (notice != null) assertTrue(notice.contains("previously"))
            }
        }
        for (reason in listOf(null, JobParameters.STOP_REASON_USER, JobParameters.STOP_REASON_DEVICE_STATE,
            JobParameters.STOP_REASON_CONSTRAINT_CONNECTIVITY, JobParameters.STOP_REASON_TIMEOUT)) {
            assertNull(backgroundDownloadNotice(normal, listOf(DownloadState(status = DownloadStatus.WAITING, stopReason = reason))))
        }
    }
}
