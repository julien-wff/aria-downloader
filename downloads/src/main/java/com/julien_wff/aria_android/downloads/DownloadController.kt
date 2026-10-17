package com.julien_wff.aria_android.downloads

import com.julien_wff.aria_android.aria2.DownloadEngine
import com.julien_wff.aria_android.aria2.EngineRequest
import com.julien_wff.aria_android.aria2.EngineTaskId
import com.julien_wff.aria_android.aria2.EngineTaskState
import java.util.UUID
import androidx.room.ColumnInfo
import androidx.room.Entity
import com.julien_wff.aria_android.aria2.EngineOptions
import kotlinx.coroutines.Job
import androidx.room.PrimaryKey
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class DownloadStatus {
    IDLE, SCHEDULED, DOWNLOADING, WAITING, PAUSED, STAGED, EXPORTING, SAVE_FAILED, COMPLETED, FAILED, CANCELED, INTERRUPTED,
}

data class DownloadState(
    val fileName: String = "download",
    val status: DownloadStatus = DownloadStatus.IDLE,
    val completedBytes: Long = 0,
    val totalBytes: Long = 0,
    val bytesPerSecond: Long = 0,
    val message: String? = null,
    val publishedUri: String? = null,
    val savedBytes: Long = 0,
) {
    val canStart: Boolean get() = status in setOf(
        DownloadStatus.IDLE, DownloadStatus.COMPLETED, DownloadStatus.FAILED, DownloadStatus.CANCELED,
    )
    val canPause: Boolean get() = status in setOf(
        DownloadStatus.SCHEDULED, DownloadStatus.DOWNLOADING, DownloadStatus.WAITING,
    )
    val canResume: Boolean get() = status in setOf(DownloadStatus.PAUSED, DownloadStatus.WAITING)
    val canRetrySaving: Boolean get() = status in setOf(DownloadStatus.STAGED, DownloadStatus.SAVE_FAILED)
    val canCancel: Boolean get() = !canStart && status != DownloadStatus.EXPORTING
}

internal enum class DesiredState { RUNNING, PAUSED, CANCELED }
@Entity(tableName = "transfers")
internal data class TransferRecord(
    val token: String,
    val url: String,
    val fileName: String,
    val desired: DesiredState,
    val status: DownloadStatus,
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val taskId: String? = null,
    val completedBytes: Long = 0,
    val totalBytes: Long = 0,
    val publishedUri: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(defaultValue = "4") val connections: Int = 4,
    @ColumnInfo(defaultValue = "0") val speedLimit: Long = 0,
    @ColumnInfo(defaultValue = "NULL") val failure: String? = null,
    @ColumnInfo(defaultValue = "3") val batchConcurrency: Int = 3,
    @ColumnInfo(defaultValue = "0") val batchWifiOnly: Boolean = false,
)

internal interface TransferStore {
    suspend fun read(): TransferRecord?
    suspend fun write(record: TransferRecord)
    suspend fun readAll(): List<TransferRecord> = listOfNotNull(read())
    suspend fun writeAll(records: List<TransferRecord>) { records.forEach { write(it) } }
    suspend fun delete(id: String) { error("Deleting is unavailable") }
}

internal interface TransferScheduler {
    /** Called only by a visible user action. Failure must leave no runnable job. */
    fun schedule(token: String): Boolean
    fun cancel()
}

/** Each JobService invocation gets its own lease, even when JobScheduler retries the same job. */
internal class ExecutionLease(val token: String, val finish: () -> Unit)

/** Public UI projection; URLs are deliberately stripped of credentials, query and fragment. */
data class DownloadTask(
    val id: String,
    val source: String,
    val state: DownloadState,
    val createdAt: Long,
    val connections: Int,
    val speedLimit: Long,
) {
    val isHistory get() = state.status in setOf(DownloadStatus.COMPLETED, DownloadStatus.FAILED, DownloadStatus.CANCELED)
}

/** Serialized queue owner. Only a matching UIDT execution can admit network work. */
class DownloadController internal constructor(
    private val engine: DownloadEngine,
    private val store: TransferStore,
    private val scheduler: TransferScheduler,
    private val scope: CoroutineScope,
    private val exporter: TransferExporter? = null,
) {
    private val mutex = Mutex()
    private val exportMutex = Mutex()
    private val records = linkedMapOf<String, TransferRecord>()
    private val views = linkedMapOf<String, DownloadState>()
    private val registered = mutableMapOf<String, EngineTaskId>()
    private val active = mutableSetOf<String>()
    private val exporting = mutableMapOf<String, Job>()
    private val lastWrites = mutableMapOf<String, Long>()
    private val ready = CompletableDeferred<Unit>()
    private var loadFailure: Exception? = null
    @Volatile private var lease: ExecutionLease? = null
    @Volatile internal var authorizationToken: String = ""
        private set
    internal var batchConcurrency = 3
        private set
    internal var batchWifiOnly = false
        private set
    private val mutableState = MutableStateFlow(DownloadState(message = "Loading saved downloads…"))
    /** Compatibility projection of the newest task, used by the host recovery harness. */
    val state: StateFlow<DownloadState> = mutableState.asStateFlow()
    private val mutableLoaded = MutableStateFlow(false)
    val loaded: StateFlow<Boolean> = mutableLoaded.asStateFlow()
    private val mutableTasks = MutableStateFlow<List<DownloadTask>>(emptyList())
    val tasks: StateFlow<List<DownloadTask>> = mutableTasks.asStateFlow()

    init {
        scope.launch {
            try {
                mutex.withLock {
                    val saved = store.readAll().sortedWith(compareBy<TransferRecord> { it.createdAt }.thenBy { it.id })
                    saved.forEach { record ->
                        records[record.id] = record
                        val status = when {
                            record.status in setOf(DownloadStatus.COMPLETED, DownloadStatus.CANCELED, DownloadStatus.FAILED) -> record.status
                            record.taskId == null -> DownloadStatus.INTERRUPTED
                            record.status == DownloadStatus.EXPORTING -> DownloadStatus.SAVE_FAILED
                            record.status in runnable -> if (record.desired == DesiredState.PAUSED) DownloadStatus.PAUSED else DownloadStatus.WAITING
                            else -> record.status
                        }
                        views[record.id] = record.view(status).copy(message = when (status) {
                            DownloadStatus.INTERRUPTED -> "Legacy staging has no recovery mapping. Data is preserved; cancel to remove the task."
                            DownloadStatus.WAITING -> "Resume to continue, or wait for Android to retry the existing job."
                            DownloadStatus.SAVE_FAILED -> "Saving interrupted. Staged data is preserved; retry saving."
                            else -> record.failure
                        })
                    }
                    saved.lastOrNull { it.token.isNotEmpty() && it.desired == DesiredState.RUNNING && it.status in runnable }?.let {
                        authorizationToken = it.token
                        batchConcurrency = it.batchConcurrency
                        batchWifiOnly = it.batchWifiOnly
                    }
                    publish()
                }
                records.values.filter { it.status == DownloadStatus.COMPLETED }.forEach { runCatching { exporter?.cleanup(it) } }
            } catch (canceled: CancellationException) { throw canceled }
            catch (error: Exception) {
                loadFailure = error
                mutableState.value = DownloadState(status = DownloadStatus.INTERRUPTED,
                    message = "Unable to load saved downloads. Reopen the app after checking storage.")
            } finally { mutableLoaded.value = true; ready.complete(Unit) }
        }
        scope.launch {
            engine.snapshots.collect { snapshot ->
                ready.await()
                if (loadFailure != null) return@collect
                mutex.withLock {
                    val execution = lease ?: return@withLock
                    try {
                        for (id in active.toList()) {
                            val task = snapshot.tasks.find { it.id == registered[id] } ?: continue
                            val current = records.getValue(id)
                            if (current.desired != DesiredState.RUNNING) continue
                            val status = when (task.state) {
                                EngineTaskState.DOWNLOADING -> DownloadStatus.DOWNLOADING
                                EngineTaskState.QUEUED -> DownloadStatus.SCHEDULED
                                EngineTaskState.COMPLETED -> DownloadStatus.STAGED
                                EngineTaskState.FAILED -> DownloadStatus.FAILED
                                else -> continue
                            }
                            val next = current.copy(status = status, completedBytes = task.completedBytes,
                                totalBytes = task.totalBytes, failure = if (status == DownloadStatus.FAILED)
                                    "Download failed (engine code ${task.errorCode ?: 0}). Retry to start a new transfer." else null)
                            val terminal = status in setOf(DownloadStatus.STAGED, DownloadStatus.FAILED)
                            val now = System.nanoTime()
                            try {
                                if (terminal || current.status != status || now - (lastWrites[id] ?: 0) >= 3_000_000_000L) {
                                    store.write(next)
                                    lastWrites[id] = now
                                }
                            } catch (error: Exception) {
                                if (terminal) {
                                    records[id] = next
                                    views[id] = next.view(if (status == DownloadStatus.STAGED) DownloadStatus.SAVE_FAILED else status)
                                        .copy(message = "Unable to save status. Staged data is preserved.")
                                }
                                throw error
                            }
                            records[id] = next
                            views[id] = next.view().copy(bytesPerSecond = if (status == DownloadStatus.DOWNLOADING) task.bytesPerSecond else 0)
                            if (terminal) {
                                active.remove(id)
                                if (status == DownloadStatus.STAGED) launchExport(id, execution)
                            }
                        }
                        drain(execution)
                        finishIfIdle(execution)
                    } catch (canceled: CancellationException) { throw canceled }
                    catch (_: Exception) { interrupt(execution, "Unable to save progress or start a transfer. Check storage and Resume.") }
                    publish()
                }
            }
        }
    }

    private fun TransferRecord.view(status: DownloadStatus = this.status) = DownloadState(fileName, status,
        completedBytes, totalBytes, message = failure, publishedUri = publishedUri)

    private fun publish() {
        mutableTasks.value = records.values.sortedByDescending { it.createdAt }.map { record ->
            val source = runCatching {
                val uri = java.net.URI(record.url)
                java.net.URI(uri.scheme, null, uri.host, uri.port, uri.path, null, null).toString()
            }.getOrDefault("Source unavailable")
            DownloadTask(record.id, source, views.getValue(record.id), record.createdAt, record.connections, record.speedLimit)
        }
        mutableState.value = mutableTasks.value.firstOrNull()?.state ?: DownloadState()
    }

    private suspend fun save(next: TransferRecord) {
        store.write(next)
        records[next.id] = next
        views[next.id] = next.view()
        publish()
    }

    /** Adds a task while visible. The scheduler authorizes the captured runnable queue as a batch. */
    suspend fun enqueue(url: String, fileName: String = "download", settings: DownloadSettings = DownloadSettings()): String = afterReady {
        val request = EngineRequest(url.trim(), fileName.trim(), EngineOptions(settings.connections, settings.speedLimit))
        val next = TransferRecord("", request.url, request.fileName, DesiredState.RUNNING, DownloadStatus.SCHEDULED,
            taskId = UUID.randomUUID().toString().replace("-", "").take(16),
            createdAt = maxOf(System.currentTimeMillis(), (records.values.maxOfOrNull { it.createdAt } ?: 0) + 1),
            connections = settings.connections, speedLimit = settings.speedLimit)
        save(next)
        replaceBatch(settings)
        next.id
    }

    /** Legacy single-task start. New UI uses enqueue. */
    suspend fun start(url: String, fileName: String = "download") {
        ready.await()
        check(state.value.canStart) { "Finish or cancel the current download first." }
        enqueue(url, fileName)
    }

    suspend fun resumeTask(id: String, settings: DownloadSettings = DownloadSettings()) = afterReady {
        val current = records.getValue(id)
        check(views.getValue(id).canResume && current.taskId != null) { "This download cannot be resumed." }
        save(current.copy(desired = DesiredState.RUNNING, status = DownloadStatus.SCHEDULED, failure = null))
        replaceBatch(settings)
    }
    suspend fun resume() = resumeTask(latestId())

    suspend fun pauseTask(id: String, expectedToken: String? = null) = afterReady {
        val current = records.getValue(id)
        if (expectedToken != null && current.token != expectedToken) return@afterReady
        if (!views.getValue(id).canPause) return@afterReady
        save(current.copy(desired = DesiredState.PAUSED, status = DownloadStatus.PAUSED))
        if (active.remove(id)) try { registered[id]?.let { engine.pause(it) } }
        catch (error: Exception) { lease?.let { interrupt(it, "Unable to pause safely. Resume the remaining queue.") }; throw error }
        lease?.let { drain(it); finishIfIdle(it) }
        if (lease == null) { scheduler.cancel(); stopEngine() }
    }
    suspend fun pause(expectedToken: String? = null) = pauseTask(latestId(), expectedToken)

    suspend fun cancelTask(id: String, expectedToken: String? = null) = afterReady {
        val current = records.getValue(id)
        if (expectedToken != null && current.token != expectedToken) return@afterReady
        if (!views.getValue(id).canCancel) return@afterReady
        save(current.copy(desired = DesiredState.CANCELED, status = DownloadStatus.CANCELED, failure = null))
        if (active.remove(id)) try { registered[id]?.let { engine.remove(it) } }
        catch (error: Exception) { lease?.let { interrupt(it, "Unable to cancel safely. Resume the remaining queue.") }; throw error }
        lease?.let { drain(it); finishIfIdle(it) }
        if (lease == null) { scheduler.cancel(); stopEngine() }
    }
    suspend fun cancel(expectedToken: String? = null) {
        ready.await()
        if (records.isNotEmpty()) cancelTask(latestId(), expectedToken)
    }

    suspend fun retry(id: String, settings: DownloadSettings = DownloadSettings()): String {
        val request = afterReady {
            val current = records.getValue(id)
            check(current.status in setOf(DownloadStatus.FAILED, DownloadStatus.CANCELED))
            current.url to current.fileName
        }
        return enqueue(request.first, request.second, settings)
    }

    suspend fun retrySavingTask(id: String) {
        val job = afterReady {
            check(views.getValue(id).canRetrySaving) { "There is no completed file to save." }
            launchExport(id, null)
        }
        job.join()
    }
    suspend fun retrySaving() = retrySavingTask(latestId())

    /** Caller removes private/public files first; a failure must preserve the record. */
    suspend fun deleteTask(id: String) = afterReady {
        check(views.getValue(id).status in setOf(DownloadStatus.COMPLETED, DownloadStatus.FAILED, DownloadStatus.CANCELED))
        check(id !in exporting)
        store.delete(id)
        records.remove(id); views.remove(id); registered.remove(id)
        publish()
    }
    internal suspend fun recordForDeletion(id: String): TransferRecord = afterReady { records.getValue(id) }

    suspend fun applySettings(settings: DownloadSettings) = afterReady {
        if ((batchConcurrency != settings.concurrency || batchWifiOnly != settings.wifiOnly) &&
            records.values.any { it.desired == DesiredState.RUNNING && views.getValue(it.id).status in runnable }) replaceBatch(settings)
    }

    private suspend fun replaceBatch(settings: DownloadSettings) {
        val token = UUID.randomUUID().toString()
        val members = records.values.filter { it.desired == DesiredState.RUNNING && views.getValue(it.id).status in runnable }
            .map { it.copy(token = token, status = DownloadStatus.SCHEDULED, batchConcurrency = settings.concurrency, batchWifiOnly = settings.wifiOnly) }
        // Atomic in Room: a crash must never expose a partly updated authorization set.
        store.writeAll(members)
        val previous = lease
        lease = null
        stopEngine()
        previous?.finish?.invoke()
        members.forEach { records[it.id] = it; views[it.id] = it.view() }
        authorizationToken = token
        batchConcurrency = settings.concurrency
        batchWifiOnly = settings.wifiOnly
        publish()
        val scheduled = try { scheduler.schedule(token) } catch (_: RuntimeException) { false }
        if (!scheduled) {
            scheduler.cancel()
            members.forEach {
                save(it.copy(desired = DesiredState.PAUSED, status = DownloadStatus.FAILED,
                    failure = "Android could not schedule the transfer. Retry while the app is visible."))
            }
        }
    }

    private fun eligible(execution: ExecutionLease) = records.values.filter {
        it.token == execution.token && it.desired == DesiredState.RUNNING && views.getValue(it.id).status in runnable && it.taskId != null
    }.sortedBy { it.createdAt }

    private suspend fun drain(execution: ExecutionLease) {
        if (lease !== execution) return
        for (current in eligible(execution)) {
            if (active.size >= batchConcurrency || lease !== execution) break
            if (current.id in active) continue
            val existing = registered[current.id]
            if (existing == null) {
                registered[current.id] = engine.add(EngineRequest(current.url, current.fileName,
                    EngineOptions(current.connections, current.speedLimit), EngineTaskId(current.taskId!!)))
            } else {
                // Completion can race a visible batch replacement while the snapshot collector
                // waits for this lock. A completed native task cannot be resumed.
                val snapshot = engine.snapshots.value.tasks.find { it.id == existing }
                if (snapshot?.state in setOf(EngineTaskState.COMPLETED, EngineTaskState.FAILED)) {
                    val completed = snapshot!!.state == EngineTaskState.COMPLETED
                    val next = current.copy(status = if (completed) DownloadStatus.STAGED else DownloadStatus.FAILED,
                        completedBytes = snapshot.completedBytes, totalBytes = snapshot.totalBytes,
                        failure = if (completed) null else "Download failed. Retry to start a new transfer.")
                    save(next)
                    if (completed) launchExport(current.id, execution)
                    continue
                }
                engine.resume(existing)
            }
            active.add(current.id)
            if (lease !== execution) { stopEngine(); break }
        }
    }

    internal fun authorize(execution: ExecutionLease) {
        if (ready.isCompleted && (loadFailure != null || execution.token != authorizationToken || eligible(execution).isEmpty())) {
            execution.finish(); return
        }
        lease = execution
        scope.launch {
            ready.await()
            mutex.withLock {
                if (lease !== execution) return@withLock
                if (loadFailure != null || execution.token != authorizationToken || eligible(execution).isEmpty()) {
                    lease = null; execution.finish(); return@withLock
                }
                try {
                    stopEngine()
                    if (lease !== execution) return@withLock
                    engine.configureConcurrency(batchConcurrency)
                    engine.start()
                    drain(execution)
                    finishIfIdle(execution)
                } catch (canceled: CancellationException) { throw canceled }
                catch (_: Exception) { interrupt(execution, "Unable to start the native transfer. Resume to try again.") }
            }
        }
    }

    internal fun revoke(execution: ExecutionLease): Boolean {
        if (lease !== execution) return false
        lease = null
        scope.launch {
            ready.await()
            mutex.withLock {
                if (lease == null) {
                    stopEngine()
                    records.values.filter { it.token == execution.token && it.desired == DesiredState.RUNNING && views.getValue(it.id).status in runnable }
                        .forEach { views[it.id] = it.view(DownloadStatus.WAITING).copy(message = "Waiting for Android to allow the transfer.") }
                    publish()
                }
            }
        }
        return !ready.isCompleted || records.values.any { it.token == execution.token && it.desired == DesiredState.RUNNING && it.status in runnable }
    }

    internal fun networkChanged(execution: ExecutionLease) {
        scope.launch {
            mutex.withLock {
                if (lease !== execution || active.isEmpty()) return@withLock
                try { engine.checkpoint() }
                catch (canceled: CancellationException) { throw canceled }
                catch (_: Exception) { interrupt(execution, "Unable to reconnect. Resume to try again.") }
            }
        }
    }

    private suspend fun interrupt(execution: ExecutionLease, message: String) {
        if (lease !== execution) return
        lease = null
        stopEngine()
        records.values.filter { it.token == execution.token && it.desired == DesiredState.RUNNING && views.getValue(it.id).status in runnable }
            .forEach { views[it.id] = it.view(DownloadStatus.WAITING).copy(message = message) }
        publish()
        execution.finish()
    }

    private suspend fun finishIfIdle(execution: ExecutionLease) {
        if (lease === execution && eligible(execution).isEmpty() && exporting.isEmpty()) {
            lease = null
            stopEngine()
            execution.finish()
        }
    }

    private fun launchExport(id: String, execution: ExecutionLease?): Job {
        exporting[id]?.let { return it }
        views[id] = views.getValue(id).copy(status = DownloadStatus.EXPORTING, savedBytes = 0)
        publish()
        val job = scope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
            try {
                exportMutex.withLock {
                    val current = mutex.withLock { records.getValue(id) }
                    var lastProgress = 0L
                    requireNotNull(exporter).export(current,
                        persist = { next -> mutex.withLock { save(next) } },
                        progress = { copied, total ->
                            val now = System.nanoTime()
                            if (copied == total || now - lastProgress >= 100_000_000L) {
                                lastProgress = now
                                scope.launch {
                                    mutex.withLock {
                                        if (views[id]?.status == DownloadStatus.EXPORTING) {
                                            views[id] = views.getValue(id).copy(savedBytes = copied)
                                            publish()
                                        }
                                    }
                                }
                            }
                        }, authorized = { execution == null || lease === execution })
                }
            } catch (canceled: CancellationException) { throw canceled }
            catch (_: Exception) {
                mutex.withLock {
                    val failed = records.getValue(id).copy(status = DownloadStatus.SAVE_FAILED,
                        failure = "Unable to save to Downloads. Staged data is preserved; retry saving after checking storage.")
                    runCatching { store.write(failed) }
                    records[id] = failed; views[id] = failed.view(); publish()
                }
            } finally {
                mutex.withLock {
                    exporting.remove(id)
                    lease?.let { finishIfIdle(it) }
                }
            }
        }
        exporting[id] = job
        job.start()
        return job
    }

    private suspend fun latestId(): String { ready.await(); return records.values.maxBy { it.createdAt }.id }
    private suspend fun <T> afterReady(action: suspend () -> T): T {
        ready.await()
        check(loadFailure == null) { "Unable to load saved downloads" }
        return mutex.withLock { action() }
    }
    private suspend fun stopEngine() {
        try { engine.stop() }
        catch (canceled: CancellationException) { throw canceled }
        catch (_: Exception) { engine.close() }
        active.clear()
    }
    private companion object {
        val runnable = setOf(DownloadStatus.SCHEDULED, DownloadStatus.DOWNLOADING, DownloadStatus.WAITING)
    }
}
