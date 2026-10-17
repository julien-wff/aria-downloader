package com.julien_wff.aria_android.downloads

import com.julien_wff.aria_android.aria2.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DownloadQueueTest {
    private class Engine : DownloadEngine {
        override val snapshots = MutableStateFlow(EngineSnapshot())
        val requests = mutableListOf<EngineRequest>()
        val resumed = mutableListOf<EngineTaskId>()
        var configured = 0
        override suspend fun configureConcurrency(limit: Int) { configured = limit }
        override suspend fun start() { snapshots.value = snapshots.value.copy(started = true) }
        override suspend fun add(request: EngineRequest): EngineTaskId {
            requests += request
            val id = request.taskId!!
            snapshots.value = snapshots.value.copy(tasks = snapshots.value.tasks + EngineTaskSnapshot(id, EngineTaskState.DOWNLOADING))
            return id
        }
        override suspend fun selectFiles(id: EngineTaskId, indexes: List<Int>) { emit(id, EngineTaskState.PAUSED) }
        override suspend fun pause(id: EngineTaskId) { emit(id, EngineTaskState.PAUSED) }
        override suspend fun resume(id: EngineTaskId) { resumed += id; emit(id, EngineTaskState.DOWNLOADING) }
        override suspend fun remove(id: EngineTaskId) { emit(id, EngineTaskState.REMOVED) }
        override suspend fun updateOptions(id: EngineTaskId, options: EngineOptions) { }
        override suspend fun checkpoint() { }
        override suspend fun stop() {
            snapshots.value = snapshots.value.copy(started = false, tasks = snapshots.value.tasks.map {
                if (it.state in setOf(EngineTaskState.DOWNLOADING, EngineTaskState.QUEUED)) it.copy(state = EngineTaskState.PAUSED) else it
            })
        }
        override suspend fun close() { stop() }
        fun emit(id: EngineTaskId, state: EngineTaskState) {
            snapshots.value = snapshots.value.copy(tasks = snapshots.value.tasks.map {
                if (it.id == id) it.copy(state = state, completedBytes = 100, totalBytes = 100) else it
            })
        }
    }
    private class Fixture(scope: TestScope, initial: List<TransferRecord> = emptyList()) {
        val saved = initial.associateBy { it.id }.toMutableMap()
        val engine = Engine()
        var token = ""
        var schedules = 0
        var finished = 0
        var schedulingSucceeds = true
        var batchWriteFails = false
        var exportGate: CompletableDeferred<Unit>? = null
        var failExport = false
        val controller = DownloadController(engine, object : TransferStore {
            override suspend fun read() = saved.values.maxByOrNull { it.createdAt }
            override suspend fun readAll() = saved.values.toList()
            override suspend fun write(record: TransferRecord) { saved[record.id] = record }
            override suspend fun writeAll(records: List<TransferRecord>) {
                if (batchWriteFails) throw java.io.IOException("full")
                records.forEach { saved[it.id] = it }
            }
            override suspend fun delete(id: String) { saved.remove(id) }
        }, object : TransferScheduler {
            override fun schedule(token: String): Boolean { this@Fixture.token = token; schedules++; return schedulingSucceeds }
            override fun cancel() { }
        }, scope.backgroundScope, object : TransferExporter {
            override suspend fun export(record: TransferRecord, persist: suspend (TransferRecord) -> Unit,
                progress: (Long, Long) -> Unit, authorized: () -> Boolean): TransferRecord {
                exportGate?.await()
                if (failExport || !authorized()) throw java.io.IOException("full")
                return record.copy(status = DownloadStatus.COMPLETED, publishedUri = "content://downloads/${record.id}").also { persist(it) }
            }
        })
        suspend fun add(name: String, settings: DownloadSettings = DownloadSettings()) = controller.enqueue("https://example.org/$name?secret=hidden", name, settings)
        fun authorize(): ExecutionLease = ExecutionLease(token) { finished++ }.also { controller.authorize(it) }
        fun state(id: String) = controller.tasks.value.first { it.id == id }.state
        fun engineId(id: String) = EngineTaskId(saved.getValue(id).taskId!!)
    }

    @Test fun metadataReviewReleasesSlotAndRequiresExplicitSelectionAcrossRecreation() = runTest {
        val f = Fixture(this)
        val id = f.controller.enqueue("https://example.org/files.torrent", "Torrent", DownloadSettings(concurrency = 1))
        f.authorize(); runCurrent()
        assertEquals(EngineSourceKind.TORRENT, f.engine.requests.single().kind)
        val files = listOf(EngineFile(1, "folder/a", 100), EngineFile(2, "folder/b", 200))
        f.engine.snapshots.value = EngineSnapshot(true, listOf(EngineTaskSnapshot(f.engineId(id), EngineTaskState.AWAITING_SELECTION, files = files)))
        runCurrent()
        assertEquals(DownloadStatus.AWAITING_SELECTION, f.state(id).status)
        assertEquals(DesiredState.PAUSED, f.saved.getValue(id).desired)
        assertEquals(1, f.finished)
        val restored = Fixture(this, f.saved.values.toList())
        runCurrent()
        assertEquals(0, restored.schedules)
        assertEquals(DownloadStatus.AWAITING_SELECTION, restored.state(id).status)
        try { restored.controller.startSelectedFiles(id, emptyList()); fail("Empty selection") } catch (_: IllegalArgumentException) { }
        restored.controller.startSelectedFiles(id, listOf(2)); runCurrent()
        assertEquals(1, restored.schedules)
        restored.authorize(); runCurrent()
        assertEquals(listOf(2), restored.engine.requests.single().selectedFiles)
        assertEquals(listOf(false, true), restored.saved.getValue(id).files.map { it.selected })
    }

    @Test fun metadataCancellationCannotBeReactivatedByLateSnapshots() = runTest {
        val f = Fixture(this)
        val id = f.controller.enqueue("magnet:?xt=urn:btih:${"a".repeat(40)}", "Magnet")
        f.authorize(); runCurrent()
        f.engine.emit(f.engineId(id), EngineTaskState.METADATA); runCurrent()
        assertTrue(f.state(id).canPause)
        f.controller.cancelTask(id)
        f.engine.snapshots.value = EngineSnapshot(true, listOf(EngineTaskSnapshot(f.engineId(id), EngineTaskState.AWAITING_SELECTION,
            files = listOf(EngineFile(1, "a", 1)))))
        runCurrent()
        assertEquals(DownloadStatus.CANCELED, f.state(id).status)
        assertEquals(DesiredState.CANCELED, f.saved.getValue(id).desired)
    }

    @Test fun requestOverridesSurviveAdmissionRecoveryAndRetry() = runTest {
        val options = DownloadRequestOptions.fromInput(" https://example.org/page ", " Custom/1.0 ")
        assertEquals(DownloadRequestOptions(), DownloadRequestOptions.fromInput("  ", ""))
        assertThrows(IllegalArgumentException::class.java) { DownloadRequestOptions.fromInput("", "Agent\n") }
        assertThrows(IllegalArgumentException::class.java) { DownloadRequestOptions.fromInput("relative", "") }
        val f = Fixture(this)
        val id = f.controller.enqueue("https://example.org/file", "file", options = options)
        f.authorize(); runCurrent()
        assertEquals(options.referrer, f.engine.requests.single().options.referrer)
        assertEquals(options.userAgent, f.engine.requests.single().options.userAgent)
        f.controller.pauseTask(id)
        val recovered = Fixture(this, f.saved.values.toList())
        runCurrent()
        recovered.controller.resumeTask(id)
        recovered.authorize(); runCurrent()
        assertEquals(options.referrer, recovered.engine.requests.single().options.referrer)
        assertEquals(options.userAgent, recovered.engine.requests.single().options.userAgent)
        recovered.controller.cancelTask(id)
        val retry = recovered.controller.retry(id)
        assertEquals(options.referrer, recovered.saved.getValue(retry).referrer)
        assertEquals(options.userAgent, recovered.saved.getValue(retry).userAgent)
    }

    @Test fun fifoAdmissionHonorsLimitAndPauseReleasesOnlyItsSlot() = runTest {
        val f = Fixture(this)
        val ids = (1..4).map { f.add("$it.bin", DownloadSettings(concurrency = 2)) }
        val lease = f.authorize()
        runCurrent()
        assertEquals(2, f.engine.configured)
        assertEquals(ids.take(2).map { f.engineId(it) }, f.engine.requests.map { it.taskId })
        f.controller.pauseTask(ids[0])
        runCurrent()
        assertEquals(DownloadStatus.PAUSED, f.state(ids[0]).status)
        assertEquals(DownloadStatus.DOWNLOADING, f.state(ids[1]).status)
        assertEquals(f.engineId(ids[2]), f.engine.requests.last().taskId)
        assertEquals(DownloadStatus.SCHEDULED, f.state(ids[3]).status)
        assertTrue(f.controller.revoke(lease))
    }

    @Test fun completionAdvancesQueueWhileExportIsBlockedAndControlsStillWork() = runTest {
        val f = Fixture(this)
        val first = f.add("first", DownloadSettings(concurrency = 1))
        val second = f.add("second", DownloadSettings(concurrency = 1))
        f.exportGate = CompletableDeferred()
        f.authorize(); runCurrent()
        f.engine.emit(f.engineId(first), EngineTaskState.COMPLETED); runCurrent()
        assertEquals(DownloadStatus.EXPORTING, f.state(first).status)
        assertEquals(DownloadStatus.DOWNLOADING, f.state(second).status)
        f.controller.pauseTask(second)
        assertEquals(DownloadStatus.PAUSED, f.state(second).status)
        f.exportGate!!.complete(Unit); runCurrent()
        assertEquals(DownloadStatus.COMPLETED, f.state(first).status)
        assertEquals(1, f.finished)
    }

    @Test fun newVisibleAddRotatesBatchAndOldCallbacksCannotStopOrControlTasks() = runTest {
        val f = Fixture(this)
        val first = f.add("first")
        val oldToken = f.token
        val old = f.authorize(); runCurrent()
        val second = f.add("second")
        assertNotEquals(oldToken, f.token)
        f.authorize(); runCurrent()
        assertFalse(f.controller.revoke(old))
        f.controller.pauseTask(first, oldToken)
        f.controller.cancelTask(second, oldToken)
        assertEquals(DownloadStatus.DOWNLOADING, f.state(first).status)
        assertEquals(DownloadStatus.DOWNLOADING, f.state(second).status)
        assertTrue(f.controller.tasks.value.all { !it.source.contains("secret") && !it.source.contains("hidden") })
    }

    @Test fun restoresWholeBatchButLeavesPausedAndUnmappedRecordsStopped() = runTest {
        val rows = (1..4).map { n -> TransferRecord("batch", "https://example.org/$n", "$n.bin",
            if (n == 3) DesiredState.PAUSED else DesiredState.RUNNING,
            if (n == 3) DownloadStatus.PAUSED else DownloadStatus.DOWNLOADING,
            id = "$n", taskId = if (n == 4) null else "%016x".format(n), batchConcurrency = 2) }
        val f = Fixture(this, rows)
        runCurrent()
        assertEquals(0, f.schedules)
        assertEquals(0, f.engine.requests.size)
        f.controller.authorize(ExecutionLease("batch") { }); runCurrent()
        assertEquals(rows.take(2).map { it.taskId }, f.engine.requests.map { it.taskId!!.value })
        assertEquals(DownloadStatus.PAUSED, f.state("3").status)
        assertEquals(DownloadStatus.INTERRUPTED, f.state("4").status)
    }

    @Test fun exportFailureDoesNotFailOtherTransferAndSavingRetryKeepsIdentity() = runTest {
        val f = Fixture(this)
        val first = f.add("first"); val second = f.add("second")
        f.authorize(); runCurrent()
        f.failExport = true
        f.engine.emit(f.engineId(first), EngineTaskState.COMPLETED); runCurrent()
        assertEquals(DownloadStatus.SAVE_FAILED, f.state(first).status)
        assertEquals(DownloadStatus.DOWNLOADING, f.state(second).status)
        val identity = f.saved.getValue(first).taskId
        f.failExport = false
        f.controller.retrySavingTask(first)
        assertEquals(DownloadStatus.COMPLETED, f.state(first).status)
        assertEquals(identity, f.saved.getValue(first).taskId)
    }

    @Test fun retryCreatesNewIdentityAndDeleteDoesNotTouchOtherRows() = runTest {
        val f = Fixture(this)
        val first = f.add("first")
        f.controller.cancelTask(first)
        val second = f.controller.retry(first)
        assertNotEquals(first, second)
        assertNotEquals(f.saved.getValue(first).taskId, f.saved.getValue(second).taskId)
        f.controller.deleteTask(first)
        assertEquals(listOf(second), f.controller.tasks.value.map { it.id })
    }

    @Test fun completionRacingBatchReplacementIsPublishedInsteadOfResumed() = runTest {
        val f = Fixture(this)
        val first = f.add("first")
        f.authorize(); runCurrent()
        // Replace immediately, before the collector consumes the terminal snapshot.
        f.engine.emit(f.engineId(first), EngineTaskState.COMPLETED)
        val second = f.add("second")
        f.authorize(); runCurrent()
        assertEquals(DownloadStatus.COMPLETED, f.state(first).status)
        assertEquals(DownloadStatus.DOWNLOADING, f.state(second).status)
        assertFalse(f.engine.resumed.contains(f.engineId(first)))
    }

    @Test fun batchPersistenceFailureDoesNotRevokeAnExistingAuthorizedExecution() = runTest {
        val f = Fixture(this)
        val first = f.add("first")
        val execution = f.authorize(); runCurrent()
        f.batchWriteFails = true
        try { f.add("second"); fail("Expected failure") } catch (_: java.io.IOException) { }
        assertEquals(DownloadStatus.DOWNLOADING, f.state(first).status)
        assertEquals(1, f.engine.requests.size)
        assertTrue(f.controller.revoke(execution))
    }

    @Test fun schedulingFailureLeavesNoEngineWorkAndSettingsReauthorizesWithNewLimit() = runTest {
        val f = Fixture(this)
        val first = f.add("first"); val second = f.add("second")
        val old = f.authorize(); runCurrent()
        f.controller.applySettings(DownloadSettings(concurrency = 1, wifiOnly = true))
        assertFalse(f.engine.snapshots.value.started)
        assertTrue(f.controller.batchWifiOnly)
        f.authorize(); runCurrent()
        assertEquals(1, f.engine.configured)
        assertFalse(f.controller.revoke(old))
        f.schedulingSucceeds = false
        f.add("third")
        assertFalse(f.engine.snapshots.value.started)
        assertEquals(DownloadStatus.FAILED, f.state(first).status)
        assertEquals(DownloadStatus.FAILED, f.state(second).status)
    }
}
