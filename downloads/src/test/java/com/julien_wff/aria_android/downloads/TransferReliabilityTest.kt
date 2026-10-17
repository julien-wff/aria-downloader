package com.julien_wff.aria_android.downloads

import com.julien_wff.aria_android.aria2.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TransferReliabilityTest {
    private class Engine : DownloadEngine {
        override val snapshots = MutableStateFlow(EngineSnapshot())
        val restarted = mutableListOf<EngineTaskId>()
        val additions = mutableListOf<EngineRequest>()
        var checkpoints = 0
        override suspend fun start() { }
        override suspend fun add(request: EngineRequest): EngineTaskId {
            additions += request
            val id = request.taskId!!
            snapshots.value = snapshots.value.copy(tasks = snapshots.value.tasks + EngineTaskSnapshot(id, EngineTaskState.DOWNLOADING))
            return id
        }
        override suspend fun pause(id: EngineTaskId) { emit(id, EngineTaskState.PAUSED) }
        override suspend fun resume(id: EngineTaskId) { emit(id, EngineTaskState.DOWNLOADING) }
        override suspend fun restart(id: EngineTaskId) { restarted += id; emit(id, EngineTaskState.DOWNLOADING) }
        override suspend fun remove(id: EngineTaskId) { emit(id, EngineTaskState.REMOVED) }
        override suspend fun updateOptions(id: EngineTaskId, options: EngineOptions) { }
        override suspend fun checkpoint() { checkpoints++ }
        override suspend fun stop() {
            snapshots.value = snapshots.value.copy(tasks = snapshots.value.tasks.map {
                if (it.state in setOf(EngineTaskState.DOWNLOADING, EngineTaskState.QUEUED)) it.copy(state = EngineTaskState.PAUSED) else it
            })
        }
        override suspend fun close() { }
        fun emit(id: EngineTaskId, state: EngineTaskState, code: Int? = null) {
            snapshots.value = snapshots.value.copy(tasks = snapshots.value.tasks.map {
                if (it.id == id) it.copy(state = state, errorCode = code, completedBytes = 64, totalBytes = 128) else it
            })
        }
    }
    private class Fixture(val scope: TestScope, initial: List<TransferRecord> = emptyList(), explicit: Boolean = false,
        loadGate: CompletableDeferred<Unit>? = null) {
        val engine = Engine()
        val rows = initial.associateBy { it.id }.toMutableMap()
        var failWrites = false
        var schedules = 0
        var token = ""
        var finished = 0
        var bindings = 0
        var releases = 0
        val controller = DownloadController(engine, object : TransferStore {
            override suspend fun read() = rows.values.firstOrNull()
            override suspend fun readAll(): List<TransferRecord> { loadGate?.await(); return rows.values.toList() }
            override suspend fun write(record: TransferRecord) {
                if (failWrites) error("storage unavailable")
                rows[record.id] = record
            }
        }, object : TransferScheduler {
            override fun schedule(token: String): Boolean { schedules++; this@Fixture.token = token; return true }
            override fun cancel() { }
        }, scope.backgroundScope, nowMillis = { 1_000_000 + scope.testScheduler.currentTime }, requireExplicitResume = explicit)
        suspend fun add(name: String = "private-name") = controller.enqueue("https://example.org/$name?secret=hidden", name,
            DownloadSettings(concurrency = 1), DownloadRequestOptions("https://secret.example/path", "Secret agent"))
        fun authorize(token: String = this.token) = ExecutionLease(token,
            prepareNetwork = { bindings++ }, releaseNetwork = { releases++ }) { finished++ }.also(controller::authorize)
        fun fail(id: String, code: Int = 6) = engine.emit(EngineTaskId(rows.getValue(id).taskId!!), EngineTaskState.FAILED, code)
        fun state(id: String) = controller.tasks.value.first { it.id == id }.state
    }

    @Test fun retriesUseExactBackoffPreserveIdentityAndStopAfterThreeAttempts() = runTest {
        val f = Fixture(this)
        val id = f.add(); val nativeId = f.rows.getValue(id).taskId
        f.authorize(); runCurrent()
        for ((index, delay) in listOf(5_000L, 15_000L, 45_000L).withIndex()) {
            f.fail(id); runCurrent()
            assertEquals(DownloadStatus.WAITING, f.state(id).status)
            assertEquals(index + 1, f.rows.getValue(id).retryCount)
            advanceTimeBy(delay - 1); runCurrent()
            assertEquals(index, f.engine.restarted.size)
            advanceTimeBy(1); runCurrent()
            assertEquals(index + 1, f.engine.restarted.size)
            assertEquals(0L, f.rows.getValue(id).retryAt)
            assertEquals(nativeId, f.engine.restarted.last().value)
        }
        f.fail(id); runCurrent()
        assertEquals(DownloadStatus.FAILED, f.state(id).status)
        assertEquals(3, f.state(id).retryCount)
        advanceTimeBy(100_000); runCurrent()
        assertEquals(3, f.engine.restarted.size)
        assertEquals(1, f.schedules)
        assertEquals(1, f.finished)
        assertEquals(1, f.releases)
    }

    @Test fun backoffReleasesSlotAndDuplicateFailureDoesNotSpendBudget() = runTest {
        val f = Fixture(this)
        val first = f.add("first"); val second = f.add("second")
        f.authorize(); runCurrent()
        f.fail(first); runCurrent()
        assertEquals(2, f.engine.additions.size)
        f.fail(first); runCurrent()
        assertEquals(1, f.rows.getValue(first).retryCount)
        advanceTimeBy(5_000); runCurrent()
        assertTrue(f.engine.restarted.isEmpty())
        f.controller.pauseTask(second); runCurrent()
        assertEquals(1, f.engine.restarted.size)
    }

    @Test fun permanentAndUnknownFailuresNeverRetry() = runTest {
        for (code in listOf(1, 3, 4, 8, 9, 10, 17, 22, 23, 24, 32)) {
            val f = Fixture(this)
            val id = f.add(); f.authorize(); runCurrent()
            f.fail(id, code); runCurrent()
            assertEquals("code $code", DownloadStatus.FAILED, f.state(id).status)
            assertEquals(0, f.state(id).retryCount)
            assertTrue(f.engine.restarted.isEmpty())
        }
    }

    @Test fun allTransientCodesScheduleBoundedRetry() = runTest {
        for (code in listOf(2, 5, 6, 19, 29)) {
            val f = Fixture(this)
            val id = f.add(); f.authorize(); runCurrent(); f.fail(id, code); runCurrent()
            assertEquals(DownloadStatus.WAITING, f.state(id).status)
            assertEquals(1, f.state(id).retryCount)
            f.controller.cancelTask(id)
        }
    }

    @Test fun pauseAndCancelFencePendingRetryButExplicitResumeRetainsPartialIdentity() = runTest {
        val f = Fixture(this)
        val id = f.add(); f.authorize(); runCurrent(); f.fail(id); runCurrent()
        f.controller.pauseTask(id); runCurrent()
        advanceTimeBy(10_000); runCurrent()
        assertTrue(f.engine.restarted.isEmpty())
        f.controller.resumeTask(id); f.authorize(); runCurrent()
        assertEquals(1, f.engine.restarted.size)
        f.fail(id); runCurrent(); f.controller.cancelTask(id); runCurrent()
        advanceTimeBy(60_000); runCurrent()
        assertEquals(1, f.engine.restarted.size)
        assertEquals(DownloadStatus.CANCELED, f.state(id).status)
    }

    @Test fun revokedLeaseAndReplacementCannotRunOldRetry() = runTest {
        val f = Fixture(this)
        val id = f.add(); val old = f.authorize(); runCurrent(); f.fail(id); runCurrent()
        assertTrue(f.controller.revoke(old, 7)); runCurrent()
        assertEquals(7, f.rows.getValue(id).stopReason)
        advanceTimeBy(10_000); runCurrent()
        assertTrue(f.engine.restarted.isEmpty())
        f.controller.resumeTask(id)
        f.authorize(); runCurrent()
        assertFalse(f.controller.revoke(old))
        f.controller.networkChanged(old); runCurrent()
        assertEquals(0, f.engine.checkpoints)
        assertEquals(1, f.engine.restarted.size)
    }

    @Test fun processRecoveryRetainsBudgetAndNeverSchedulesWork() = runTest {
        val original = Fixture(this)
        val id = original.add(); val old = original.authorize(); runCurrent(); original.fail(id); runCurrent()
        val saved = original.rows.values.toList()
        original.controller.revoke(old); runCurrent()
        val recovered = Fixture(this, saved); runCurrent()
        assertEquals(0, recovered.schedules)
        assertEquals(1, recovered.state(id).retryCount)
        recovered.authorize(saved.single().token); runCurrent()
        assertTrue(recovered.engine.additions.isEmpty())
        advanceTimeBy(5_000); runCurrent()
        assertEquals(saved.single().taskId, recovered.engine.additions.single().taskId!!.value)
        assertEquals("Secret agent", recovered.engine.additions.single().options.userAgent)
        recovered.fail(id); runCurrent()
        assertEquals(2, recovered.state(id).retryCount)
    }

    @Test fun rebootAndUserStopRequireExplicitResumeAndFenceExistingToken() = runTest {
        val original = Fixture(this)
        val id = original.add(); runCurrent()
        val saved = original.rows.values.toList()
        val recovered = Fixture(this, saved, explicit = true); runCurrent()
        assertEquals(DownloadStatus.PAUSED, recovered.state(id).status)
        assertEquals("", recovered.rows.getValue(id).token)
        recovered.authorize(saved.single().token); runCurrent()
        assertTrue(recovered.engine.additions.isEmpty())
        assertEquals(0, recovered.schedules)
        recovered.controller.resumeTask(id); recovered.authorize(); runCurrent()
        assertEquals(1, recovered.engine.additions.size)
    }

    @Test fun failedPersistenceCannotLaunchRetry() = runTest {
        val f = Fixture(this)
        val id = f.add(); f.authorize(); runCurrent()
        f.failWrites = true
        f.fail(id); runCurrent()
        advanceTimeBy(100_000); runCurrent()
        assertTrue(f.engine.restarted.isEmpty())
        assertEquals(0, f.rows.getValue(id).retryCount)
        assertEquals(DownloadStatus.WAITING, f.state(id).status)
        assertEquals(1, f.releases)
    }

    @Test fun networkCallbackBeforeStartupDoesNotInterruptAuthorization() = runTest {
        val gate = CompletableDeferred<Unit>()
        val row = TransferRecord("existing", "https://example.org/file", "file", DesiredState.RUNNING,
            DownloadStatus.DOWNLOADING, taskId = "0123456789abcdef")
        val f = Fixture(this, listOf(row), loadGate = gate)
        val lease = f.authorize(row.token)
        f.controller.networkChanged(lease)
        runCurrent()
        assertEquals(0, f.engine.checkpoints)
        assertEquals(0, f.bindings)
        gate.complete(Unit); runCurrent()
        assertEquals(1, f.bindings)
        assertEquals(1, f.engine.additions.size)
        f.controller.networkChanged(lease); runCurrent()
        assertEquals(1, f.engine.checkpoints)
        f.controller.cancelTask(row.id)
    }

    @Test fun networkChangesDuringBackoffPrepareNewNetworkWithoutSpendingAttempts() = runTest {
        val f = Fixture(this)
        val id = f.add(); val lease = f.authorize(); runCurrent(); f.fail(id); runCurrent()
        f.controller.networkChanged(lease); runCurrent()
        assertEquals(2, f.bindings)
        assertEquals(1, f.engine.checkpoints)
        assertEquals(1, f.state(id).retryCount)
        advanceTimeBy(5_000); runCurrent()
        assertEquals(1, f.engine.restarted.size)
        f.controller.cancelTask(id); runCurrent()
        assertEquals(1, f.releases)
    }

    @Test fun explicitUserStopDoesNotRescheduleAndRetainsResumeAction() = runTest {
        val f = Fixture(this)
        val id = f.add(); val lease = f.authorize(); runCurrent()
        assertFalse(f.controller.revoke(lease, 13, reschedule = false)); runCurrent()
        assertEquals(DownloadStatus.PAUSED, f.state(id).status)
        assertEquals("", f.rows.getValue(id).token)
        f.authorize(lease.token); runCurrent()
        assertEquals(1, f.engine.additions.size)
        assertEquals(1, f.schedules)
        assertTrue(f.state(id).canResume)
    }

    @Test fun stopPersistenceFailureLeavesWorkStoppedAndActionable() = runTest {
        val f = Fixture(this)
        val id = f.add(); val lease = f.authorize(); runCurrent()
        f.failWrites = true
        f.controller.revoke(lease, 7); runCurrent()
        assertEquals(DownloadStatus.PAUSED, f.state(id).status)
        assertTrue(f.state(id).message!!.contains("check storage"))
        advanceTimeBy(60_000); runCurrent()
        assertTrue(f.engine.restarted.isEmpty())
        assertEquals(1, f.releases)
    }

    @Test fun diagnosticsContainOnlyAllowlistedFields() {
        val report = transferDiagnostics(DownloadState(fileName = "secret-file", message = "https://user:password@example.org/?token=secret",
            publishedUri = "content://private/secret", failureCategory = "injected-header", engineCode = 6,
            retryCount = 2, retryAt = 100, stopReason = 7), "1.0 Secret agent", 37)
        for (secret in listOf("secret", "password", "example.org", "content://", "injected", "agent", "https://")) {
            assertFalse(secret, report.contains(secret, ignoreCase = true))
        }
        assertTrue(report.contains("NETWORK"))
        assertTrue(report.contains("2/3"))
    }
}
