package com.julien_wff.aria_android.downloads

import com.julien_wff.aria_android.aria2.DownloadEngine
import com.julien_wff.aria_android.aria2.EngineOptions
import com.julien_wff.aria_android.aria2.EngineRequest
import com.julien_wff.aria_android.aria2.EngineSnapshot
import com.julien_wff.aria_android.aria2.EngineTaskId
import com.julien_wff.aria_android.aria2.EngineTaskSnapshot
import com.julien_wff.aria_android.aria2.EngineTaskState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DownloadControllerTest {
    private class Fixture(scope: TestScope, initial: TransferRecord? = null) {
        val events = mutableListOf<String>()
        val engine = FakeEngine(events)
        var stored = initial
        var schedulingSucceeds = true
        var scheduledToken = ""
        var finished = 0
        var writeFailure = false
        val controller = DownloadController(engine, object : TransferStore {
            override suspend fun read() = stored
            override suspend fun write(record: TransferRecord) {
                if (writeFailure) throw java.io.IOException("Disk full")
                events += "save:${record.desired}"
                stored = record
            }
        }, object : TransferScheduler {
            override fun schedule(token: String): Boolean {
                events += "schedule"
                scheduledToken = token
                return schedulingSucceeds
            }
            override fun cancel() { events += "cancelJob" }
        }, scope.backgroundScope, object : TransferExporter {
            override suspend fun export(record: TransferRecord, persist: suspend (TransferRecord) -> Unit,
                progress: (Long, Long) -> Unit, authorized: () -> Boolean): TransferRecord {
                val result = record.copy(status = DownloadStatus.COMPLETED, publishedUri = "content://downloads/test")
                persist(result)
                return result
            }
        })
        fun lease() = ExecutionLease(scheduledToken) { finished++ }
        suspend fun start() = controller.start("https://example.com/file", "file.bin")
    }

    @Test fun persistsBeforeSchedulingAndWaitsForJobAuthorization() = runTest {
        val f = Fixture(this)
        f.start()
        runCurrent()
        assertEquals("save:RUNNING", f.events.first())
        assertTrue(f.events.indexOf("save:RUNNING") < f.events.indexOf("schedule"))
        assertEquals(0, f.engine.adds)
        f.controller.authorize(f.lease())
        runCurrent()
        assertEquals(1, f.engine.adds)
        assertEquals(DownloadStatus.DOWNLOADING, f.controller.state.value.status)
        try { f.start(); fail("Duplicate start accepted") } catch (_: IllegalStateException) { }
    }

    @Test fun schedulingFailureNeverStartsEngine() = runTest {
        val f = Fixture(this)
        f.schedulingSucceeds = false
        f.start()
        runCurrent()
        assertEquals(DownloadStatus.FAILED, f.controller.state.value.status)
        assertEquals(0, f.engine.adds)
        f.controller.authorize(f.lease())
        runCurrent()
        assertEquals(0, f.engine.adds)
    }

    @Test fun pausePersistsIntentBeforeStoppingAndResumeNeedsNewAuthorization() = runTest {
        val f = Fixture(this)
        f.start()
        val old = f.lease()
        f.controller.authorize(old)
        runCurrent()
        f.events.clear()
        f.controller.pause()
        assertTrue(f.events.indexOf("save:PAUSED") < f.events.indexOf("stop"))
        assertEquals(DownloadStatus.PAUSED, f.controller.state.value.status)
        f.controller.resume()
        runCurrent()
        assertEquals(0, f.engine.resumes)
        val fresh = f.lease()
        f.controller.authorize(fresh)
        runCurrent()
        assertEquals(1, f.engine.resumes)
        assertFalse(f.controller.revoke(old))
        assertEquals(DownloadStatus.DOWNLOADING, f.controller.state.value.status)
    }

    @Test fun stopBeforeQueuedStartupPreventsNativeWork() = runTest {
        val f = Fixture(this)
        f.start()
        val lease = f.lease()
        f.controller.authorize(lease)
        assertTrue(f.controller.revoke(lease))
        runCurrent()
        assertEquals(0, f.engine.adds)
        assertEquals(DownloadStatus.WAITING, f.controller.state.value.status)
    }

    @Test fun systemRetryResumesOnlyTheAuthorizedTaskAndOldCleanupCannotStopIt() = runTest {
        val f = Fixture(this)
        f.start()
        val first = f.lease()
        f.controller.authorize(first)
        runCurrent()
        f.controller.revoke(first)
        val retry = f.lease()
        f.controller.authorize(retry)
        runCurrent()
        assertEquals(1, f.engine.adds)
        assertEquals(1, f.engine.resumes)
        assertEquals(DownloadStatus.DOWNLOADING, f.controller.state.value.status)
    }

    @Test fun cancelBeforeJobStartsRevokesPendingWork() = runTest {
        val f = Fixture(this)
        f.start()
        val lease = f.lease()
        f.controller.cancel()
        f.controller.authorize(lease)
        runCurrent()
        assertEquals(0, f.engine.adds)
        assertEquals(DesiredState.CANCELED, f.stored!!.desired)
        assertEquals(DownloadStatus.CANCELED, f.controller.state.value.status)
    }

    @Test fun completionAndFailureEndJobAndReleaseEngine() = runTest {
        for (terminal in listOf(EngineTaskState.COMPLETED, EngineTaskState.FAILED)) {
            val f = Fixture(this)
            f.start()
            f.controller.authorize(f.lease())
            runCurrent()
            f.engine.emit(terminal)
            runCurrent()
            assertEquals(1, f.finished)
            assertTrue(f.controller.state.value.canStart)
            assertFalse(f.engine.snapshots.value.started)
        }
    }

    @Test fun processRestartDoesNotBlindlyReAddSavedRequest() = runTest {
        val saved = TransferRecord("old", "https://example.com/file", "file.bin",
            DesiredState.RUNNING, DownloadStatus.SCHEDULED)
        val f = Fixture(this, saved)
        f.controller.authorize(ExecutionLease("old") { f.finished++ })
        runCurrent()
        assertEquals(DownloadStatus.INTERRUPTED, f.controller.state.value.status)
        assertEquals(0, f.engine.adds)
        assertFalse(f.controller.state.value.canResume)
    }

    @Test fun pausingBeforeFirstJobStillAllowsExplicitResume() = runTest {
        val f = Fixture(this)
        f.start()
        f.controller.pause()
        f.controller.resume()
        f.controller.authorize(f.lease())
        runCurrent()
        assertEquals(1, f.engine.adds)
    }

    @Test fun progressScalingHandlesUnknownAndLargeSizes() {
        assertEquals(0, progressValue(100, 0))
        assertEquals(500, progressValue(Long.MAX_VALUE / 2, Long.MAX_VALUE))
        assertEquals(1000, progressValue(Long.MAX_VALUE, Long.MAX_VALUE))
        assertEquals(1000, progressValue(200, 100))
        assertEquals(0, progressValue(-1, 100))
    }

    @Test fun networkRefreshRequiresCurrentExecutionAuthorization() = runTest {
        val f = Fixture(this)
        f.start()
        val lease = f.lease()
        f.controller.networkChanged(lease)
        runCurrent()
        assertEquals(0, f.engine.checkpoints)
        f.controller.authorize(lease)
        runCurrent()
        f.controller.networkChanged(lease)
        runCurrent()
        assertEquals(1, f.engine.checkpoints)
        f.controller.pause()
        f.controller.networkChanged(lease)
        runCurrent()
        assertEquals(1, f.engine.checkpoints)
    }

    @Test fun revokeDuringNativeStartupStopsBeforeReportingRunnableState() = runTest {
        val f = Fixture(this)
        f.start()
        val gate = CompletableDeferred<Unit>()
        f.engine.addGate = gate
        val lease = f.lease()
        f.controller.authorize(lease)
        runCurrent()
        assertEquals(1, f.engine.adds)
        f.controller.revoke(lease)
        gate.complete(Unit)
        runCurrent()
        assertFalse(f.engine.snapshots.value.started)
        assertEquals(DownloadStatus.WAITING, f.controller.state.value.status)
    }

    @Test fun failedPauseIntentWriteDoesNotClaimTheDownloadPaused() = runTest {
        val f = Fixture(this)
        f.start()
        f.controller.authorize(f.lease())
        runCurrent()
        f.writeFailure = true
        try { f.controller.pause(); fail("Expected storage failure") } catch (_: java.io.IOException) { }
        assertEquals(DownloadStatus.DOWNLOADING, f.controller.state.value.status)
        assertTrue(f.engine.snapshots.value.started)
        assertEquals(DesiredState.RUNNING, f.stored!!.desired)
    }

    @Test fun terminalPersistenceFailureStillEndsExecutionAndPreservesAnError() = runTest {
        val f = Fixture(this)
        f.start()
        f.controller.authorize(f.lease())
        runCurrent()
        f.writeFailure = true
        f.engine.emit(EngineTaskState.COMPLETED)
        runCurrent()
        assertEquals(1, f.finished)
        assertFalse(f.engine.snapshots.value.started)
        assertEquals(DownloadStatus.SAVE_FAILED, f.controller.state.value.status)
        assertTrue(f.controller.state.value.canRetrySaving)
        assertTrue(f.controller.state.value.message!!.contains("preserved"))
    }

    @Test fun processRestartRecoversStableIdentityOnlyUnderAValidJobLease() = runTest {
        val saved = TransferRecord("old", "https://example.com/file", "file.bin",
            DesiredState.RUNNING, DownloadStatus.DOWNLOADING, taskId = "0123456789abcdef",
            completedBytes = 40, totalBytes = 100)
        val f = Fixture(this, saved)
        runCurrent()
        assertEquals(DownloadStatus.WAITING, f.controller.state.value.status)
        assertTrue(f.controller.state.value.canResume)
        assertEquals(40, f.controller.state.value.completedBytes)
        assertEquals(0, f.engine.adds)
        f.controller.authorize(ExecutionLease("stale") { f.finished++ })
        runCurrent()
        assertEquals(0, f.engine.adds)
        f.controller.authorize(ExecutionLease("old") { f.finished++ })
        runCurrent()
        assertEquals(saved.taskId, f.engine.lastRequest!!.taskId!!.value)
        assertEquals(DownloadStatus.DOWNLOADING, f.controller.state.value.status)
    }

    @Test fun restoredPauseAndCancelNeverAuthorizeTransferWork() = runTest {
        for ((desired, status) in listOf(DesiredState.PAUSED to DownloadStatus.PAUSED,
                DesiredState.CANCELED to DownloadStatus.CANCELED)) {
            val f = Fixture(this, TransferRecord("old", "https://example.com/file", "file.bin",
                desired, status, taskId = "0123456789abcdef"))
            f.controller.authorize(ExecutionLease("old") { f.finished++ })
            runCurrent()
            assertEquals(status, f.controller.state.value.status)
            assertEquals(0, f.engine.adds)
        }
    }

    @Test fun interruptedExportOffersSavingRetryWithoutStartingTheEngine() = runTest {
        val f = Fixture(this, TransferRecord("old", "https://example.com/file", "file.bin",
            DesiredState.RUNNING, DownloadStatus.EXPORTING, taskId = "0123456789abcdef",
            publishedUri = "content://downloads/1", completedBytes = 100))
        runCurrent()
        assertEquals(DownloadStatus.SAVE_FAILED, f.controller.state.value.status)
        assertTrue(f.controller.state.value.canRetrySaving)
        f.controller.retrySaving()
        assertEquals(DownloadStatus.COMPLETED, f.controller.state.value.status)
        assertEquals(0, f.engine.adds)
        assertEquals("", f.scheduledToken)
    }

    @Test fun staleAuthorizationCannotReplaceAnActiveLease() = runTest {
        val f = Fixture(this)
        f.start()
        val current = f.lease()
        f.controller.authorize(current)
        runCurrent()
        f.controller.authorize(ExecutionLease("stale") { f.finished++ })
        runCurrent()
        assertTrue(f.controller.revoke(current))
        runCurrent()
        assertEquals(DownloadStatus.WAITING, f.controller.state.value.status)
    }

    @Test fun staleNotificationControlsCannotChangeANewerAuthorization() = runTest {
        val f = Fixture(this)
        f.start()
        val oldToken = f.scheduledToken
        f.controller.pause()
        f.controller.resume()
        f.controller.pause(oldToken)
        f.controller.cancel(oldToken)
        assertEquals(DownloadStatus.SCHEDULED, f.controller.state.value.status)
        assertEquals(DesiredState.RUNNING, f.stored!!.desired)
    }

    @Test fun lateCancelFromACompletedTransfersNotificationCannotUndoPublication() = runTest {
        val f = Fixture(this)
        f.start()
        f.controller.authorize(f.lease())
        runCurrent()
        f.engine.emit(EngineTaskState.COMPLETED)
        runCurrent()
        f.controller.cancel(f.scheduledToken)
        assertEquals(DownloadStatus.COMPLETED, f.controller.state.value.status)
        assertEquals(DownloadStatus.COMPLETED, f.stored!!.status)
    }

    @Test fun unreadableDatabaseDoesNotStartWorkOrCrashTheSnapshotCollector() = runTest {
        val events = mutableListOf<String>()
        val engine = FakeEngine(events)
        var finished = 0
        val controller = DownloadController(engine, object : TransferStore {
            override suspend fun read(): TransferRecord? = throw java.io.IOException("Unavailable database")
            override suspend fun write(record: TransferRecord) { fail("Unexpected write") }
        }, object : TransferScheduler {
            override fun schedule(token: String): Boolean { fail("Unexpected scheduling"); return false }
            override fun cancel() { }
        }, backgroundScope)
        controller.authorize(ExecutionLease("old") { finished++ })
        runCurrent()
        assertEquals(DownloadStatus.INTERRUPTED, controller.state.value.status)
        assertEquals(1, finished)
        assertEquals(0, engine.adds)
        try { controller.start("https://example.com/file"); fail("Should reject actions after load failure") }
        catch (_: IllegalStateException) { }
    }

    private class FakeEngine(private val events: MutableList<String>) : DownloadEngine {
        override val snapshots = MutableStateFlow(EngineSnapshot())
        private val id = EngineTaskId("test")
        var lastRequest: EngineRequest? = null
        var adds = 0
        var resumes = 0
        var checkpoints = 0
        var addGate: CompletableDeferred<Unit>? = null
        override suspend fun start() { events += "start"; snapshots.value = snapshots.value.copy(started = true) }
        override suspend fun add(request: EngineRequest): EngineTaskId {
            lastRequest = request
            adds++; events += "add"; addGate?.await(); emit(EngineTaskState.DOWNLOADING); return id
        }
        override suspend fun resume(id: EngineTaskId) { resumes++; emit(EngineTaskState.DOWNLOADING) }
        override suspend fun stop() {
            events += "stop"
            snapshots.value = snapshots.value.copy(started = false, tasks = snapshots.value.tasks.map {
                if (it.state in setOf(EngineTaskState.DOWNLOADING, EngineTaskState.QUEUED))
                    it.copy(state = EngineTaskState.PAUSED) else it
            })
        }
        fun emit(state: EngineTaskState) {
            snapshots.value = snapshots.value.copy(tasks = listOf(EngineTaskSnapshot(id, state, 50, 100, 10)))
        }
        override suspend fun pause(id: EngineTaskId) { emit(EngineTaskState.PAUSED) }
        override suspend fun remove(id: EngineTaskId) { emit(EngineTaskState.REMOVED) }
        override suspend fun updateOptions(id: EngineTaskId, options: EngineOptions) { }
        override suspend fun checkpoint() { checkpoints++ }
        override suspend fun close() { stop() }
    }
}
