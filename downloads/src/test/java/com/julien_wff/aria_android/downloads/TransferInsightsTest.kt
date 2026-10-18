package com.julien_wff.aria_android.downloads

import org.junit.Assert.*
import org.junit.Test

class TransferInsightsTest {
    @Test fun readableDurationsCoverBoundariesAndSpokenUnits() {
        val examples = mapOf(0L to "0 sec", 45L to "45 sec", 60L to "1 min", 135L to "2 min 15 sec",
            3600L to "1 hr", 4320L to "1 hr 12 min", 183600L to "2 days 3 hr")
        examples.forEach { (seconds, expected) -> assertEquals(expected, formatTransferDuration(seconds)) }
        assertEquals("1 hour 12 minutes", formatTransferDuration(4320, true))
        assertEquals("0 sec", formatTransferDuration(-1))
        assertEquals("Less than 1 sec", formatTransferDurationMillis(999))
        assertEquals("Less than 1 second", formatTransferDurationMillis(1, true))
        assertEquals("1 sec", formatTransferDurationMillis(1000))
        assertEquals("2 sec", formatTransferDurationMillis(1001))
        assertEquals("0 sec", formatTransferDurationMillis(-1))
        assertTrue(formatTransferDuration(Long.MAX_VALUE).contains("days"))
    }

    @Test fun etaIsRoundedUpAndOnlyAppliesToRunningKnownTransfers() {
        val running = DownloadState(status = DownloadStatus.DOWNLOADING, completedBytes = 1, totalBytes = 100, bytesPerSecond = 10)
        assertEquals(10L, transferEta(running))
        assertEquals(1L, transferEta(running.copy(completedBytes = 99)))
        assertEquals("Less than 1 sec", transferEtaText(running.copy(completedBytes = 99)))
        assertNull(transferEta(running.copy(totalBytes = 0)))
        assertNull(transferEta(running.copy(bytesPerSecond = 0)))
        assertNull(transferEta(running.copy(status = DownloadStatus.EXPORTING)))
        assertEquals(Long.MAX_VALUE, transferEta(running.copy(completedBytes = 0, totalBytes = Long.MAX_VALUE, bytesPerSecond = 1)))
    }

    @Test fun historyIsBoundedThrottledAndShowsGaps() {
        val history = InsightHistory()
        history.update("task", 0, 100)
        history.update("task", 100, 200)
        assertEquals(1, history.get("task").samples.size)
        history.update("task", 1000, 300)
        history.update("task", 5000, 400)
        assertNull(history.get("task").samples[2].bytesPerSecond)
        history.update("task", 6000, null)
        assertNull(history.get("task").samples.last().bytesPerSecond)
        history.update("task", 130000, 500)
        assertTrue(history.get("task").samples.all { it.elapsedMillis >= 10000 })
        history.remove("task")
        assertTrue(history.get("task").samples.isEmpty())
    }

    @Test fun stoppedGraphFreezesAndResumingStartsANewSegment() {
        val history = InsightHistory()
        history.update("task", 0, 100)
        history.stop("task", 100)
        val stopped = history.get("task")
        history.stop("task", 200000)
        assertEquals(stopped, history.get("task"))
        assertEquals(100L, stopped.samples.first().bytesPerSecond)
        assertNull(stopped.samples.last().bytesPerSecond)
        history.update("task", 200000, 200)
        assertEquals(200L, history.get("task").samples.last().bytesPerSecond)
        assertTrue(history.get("task").samples.all { it.elapsedMillis >= 80000 })
    }

    @Test fun pieceMapIsRetainedUnlessExplicitlyReplaced() {
        val history = InsightHistory()
        val map = PieceOverview(3, 1024, listOf(PieceOverviewCell(1, 3)), null)
        history.update("task", 0, 100, map)
        history.update("task", 1000, null)
        assertEquals(map, history.get("task").pieces)
        history.update("task", 2000, 100, null)
        assertNull(history.get("task").pieces)
    }
}
