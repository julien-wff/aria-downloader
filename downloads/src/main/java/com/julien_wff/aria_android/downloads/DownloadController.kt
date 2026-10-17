package com.julien_wff.aria_android.downloads

import com.julien_wff.aria_android.aria2.DownloadEngine
import com.julien_wff.aria_android.aria2.EngineRequest
import com.julien_wff.aria_android.aria2.EngineTaskId
import com.julien_wff.aria_android.aria2.EngineTaskState
import java.util.UUID
import androidx.room.Entity
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
)

internal interface TransferStore {
    suspend fun read(): TransferRecord?
    suspend fun write(record: TransferRecord)
}

internal interface TransferScheduler {
    /** Called only by a visible user action. Failure must leave no runnable job. */
    fun schedule(token: String): Boolean
    fun cancel()
}

/** Each JobService invocation gets its own lease, even when JobScheduler retries the same job. */
internal class ExecutionLease(val token: String, val finish: () -> Unit)

/** One controller per process. All engine commands, user actions and cleanup are serialized. */
class DownloadController internal constructor(
    private val engine: DownloadEngine,
    private val store: TransferStore,
    private val scheduler: TransferScheduler,
    private val scope: CoroutineScope,
    private val exporter: TransferExporter? = null,
) {
    private val mutex = Mutex()
    @Volatile private var record: TransferRecord? = null
    internal val authorizationToken: String get() = record?.token.orEmpty()
    private val ready = CompletableDeferred<Unit>()
    private var loadFailure: Exception? = null
    private var engineId: EngineTaskId? = null
    private var finishing = false
    private var lastProgressWrite = 0L
    @Volatile private var lease: ExecutionLease? = null
    private val mutableState = MutableStateFlow(DownloadState(message = "Loading saved download…"))
    val state: StateFlow<DownloadState> = mutableState.asStateFlow()

    init {
        scope.launch {
            try {
                mutex.withLock {
                    record = store.read()
                    record?.let { saved ->
                        val status = when {
                            saved.taskId == null && saved.status !in setOf(DownloadStatus.CANCELED, DownloadStatus.FAILED) ->
                                DownloadStatus.INTERRUPTED
                            saved.status == DownloadStatus.EXPORTING -> DownloadStatus.SAVE_FAILED
                            saved.status in setOf(DownloadStatus.SCHEDULED, DownloadStatus.DOWNLOADING, DownloadStatus.WAITING) ->
                                if (saved.desired == DesiredState.PAUSED) DownloadStatus.PAUSED else DownloadStatus.WAITING
                            else -> saved.status
                        }
                        mutableState.value = DownloadState(saved.fileName, status,
                            saved.completedBytes, saved.totalBytes, publishedUri = saved.publishedUri,
                            message = if (status == DownloadStatus.INTERRUPTED)
                                "Legacy staging has no recovery mapping. Data is preserved; cancel to start a new download."
                            else if (status == DownloadStatus.WAITING) "Resume to continue, or wait for Android to retry the existing job."
                            else null)
                        if (status == DownloadStatus.COMPLETED) runCatching { exporter?.cleanup(saved) }
                    } ?: run { mutableState.value = DownloadState() }
                }
                ready.complete(Unit)
            } catch (canceled: CancellationException) {
                throw canceled
            } catch (error: Exception) {
                loadFailure = error
                mutableState.value = DownloadState(status = DownloadStatus.INTERRUPTED,
                    message = "Unable to load saved downloads. Reopen the app after checking storage.")
                ready.complete(Unit)
            }
        }
        scope.launch {
            engine.snapshots.collect { snapshot ->
                ready.await()
                if (loadFailure != null) return@collect
                mutex.withLock {
                    val currentLease = lease ?: return@withLock
                    val task = snapshot.tasks.find { it.id == engineId } ?: return@withLock
                    if (record?.desired != DesiredState.RUNNING) return@withLock
                    val status = when (task.state) {
                        EngineTaskState.DOWNLOADING -> DownloadStatus.DOWNLOADING
                        EngineTaskState.QUEUED -> DownloadStatus.SCHEDULED
                        EngineTaskState.COMPLETED -> DownloadStatus.STAGED
                        EngineTaskState.FAILED -> DownloadStatus.FAILED
                        else -> return@withLock
                    }
                    mutableState.value = mutableState.value.copy(status = status,
                        completedBytes = task.completedBytes, totalBytes = task.totalBytes,
                        bytesPerSecond = if (status == DownloadStatus.DOWNLOADING) task.bytesPerSecond else 0,
                        message = if (status == DownloadStatus.FAILED) task.errorMessage ?: "Download failed (code ${task.errorCode})." else null)
                    val next = record!!.copy(status = status, completedBytes = task.completedBytes,
                        totalBytes = task.totalBytes)
                    val terminal = status in setOf(DownloadStatus.STAGED, DownloadStatus.FAILED)
                    try {
                        val now = System.nanoTime()
                        if (terminal || record!!.status != status || now - lastProgressWrite >= 3_000_000_000L) {
                            save(next)
                            lastProgressWrite = now
                        }
                        if (terminal) {
                            finishing = true
                            stopEngine()
                            if (status == DownloadStatus.STAGED) export(currentLease)
                        }
                    } catch (canceled: CancellationException) {
                        throw canceled
                    } catch (_: Exception) {
                        if (terminal) {
                            if (status == DownloadStatus.STAGED) record = next
                            stopEngine()
                            mutableState.value = state.value.copy(status =
                                if (status == DownloadStatus.STAGED) DownloadStatus.SAVE_FAILED else DownloadStatus.FAILED,
                                message = "Unable to save download status. Staged data has been preserved.")
                        } else {
                            // Persistence failure must not let a transfer run indefinitely without checkpoints.
                            stopEngine()
                            mutableState.value = state.value.copy(status = DownloadStatus.WAITING,
                                bytesPerSecond = 0, message = "Unable to save progress. Check storage and Resume.")
                            lease = null
                            currentLease.finish()
                        }
                    } finally {
                        if (terminal) {
                            if (lease === currentLease) lease = null
                            currentLease.finish()
                            finishing = false
                        }
                    }
                }
            }
        }
    }

    private suspend fun export(execution: ExecutionLease?) {
        val current = record ?: return
        mutableState.value = state.value.copy(status = DownloadStatus.EXPORTING, savedBytes = 0,
            bytesPerSecond = 0, message = "Saving to Downloads…")
        try {
            val result = requireNotNull(exporter) { "Saving is unavailable" }.export(current,
                persist = { save(it) },
                progress = { copied, _ -> mutableState.value = state.value.copy(savedBytes = copied) },
                authorized = { execution == null || lease === execution })
            mutableState.value = state.value.copy(status = DownloadStatus.COMPLETED,
                publishedUri = result.publishedUri, message = "Saved to Downloads")
        } catch (canceled: CancellationException) {
            mutableState.value = state.value.copy(status = DownloadStatus.SAVE_FAILED,
                message = "Saving interrupted. Staged data is preserved; retry saving to continue.")
            throw canceled
        } catch (_: Exception) {
            record?.let { runCatching { save(it.copy(status = DownloadStatus.SAVE_FAILED)) } }
            mutableState.value = state.value.copy(status = DownloadStatus.SAVE_FAILED,
                message = "Unable to save to Downloads. Staged data is preserved. Retry saving after checking storage.")
        }
    }

    suspend fun retrySaving() {
        afterReady {
            check(state.value.canRetrySaving) { "There is no completed file to save." }
            export(null) // Visible user action; interruption is safe and can be retried on next launch.
        }
    }

    suspend fun start(url: String, fileName: String = "download") = afterReady {
        check(state.value.canStart) { "Finish or cancel the current download first." }
        val request = EngineRequest(url.trim(), fileName.trim())
        engineId = null
        val next = TransferRecord(UUID.randomUUID().toString(), request.url, request.fileName,
            DesiredState.RUNNING, DownloadStatus.SCHEDULED,
            taskId = UUID.randomUUID().toString().replace("-", "").take(16),
            createdAt = maxOf(System.currentTimeMillis(), (record?.createdAt ?: 0) + 1))
        save(next)
        mutableState.value = DownloadState(fileName = request.fileName, status = DownloadStatus.SCHEDULED)
        schedule(next)
    }

    suspend fun resume() = afterReady {
        check(state.value.canResume) { "This download cannot be resumed." }
        val next = record!!.copy(token = UUID.randomUUID().toString(),
            desired = DesiredState.RUNNING, status = DownloadStatus.SCHEDULED)
        save(next)
        mutableState.value = state.value.copy(status = DownloadStatus.SCHEDULED, message = null)
        schedule(next)
    }

    suspend fun pause(expectedToken: String? = null) = afterReady {
        if (expectedToken != null && record?.token != expectedToken) return@afterReady
        if (!state.value.canPause) return@afterReady
        save(record!!.copy(desired = DesiredState.PAUSED, status = DownloadStatus.PAUSED))
        val previous = lease
        lease = null
        scheduler.cancel()
        stopEngine() // Finalizes native control files before reporting Paused.
        previous?.finish?.invoke()
        mutableState.value = state.value.copy(status = DownloadStatus.PAUSED, bytesPerSecond = 0)
    }

    suspend fun cancel(expectedToken: String? = null) = afterReady {
        if (expectedToken != null && record?.token != expectedToken) return@afterReady
        if (!state.value.canCancel) return@afterReady
        val current = record ?: return@afterReady
        save(current.copy(desired = DesiredState.CANCELED, status = DownloadStatus.CANCELED))
        val previous = lease
        lease = null
        scheduler.cancel()
        stopEngine() // Cancel preserves partial data; only successful publication removes staging.
        previous?.finish?.invoke()
        engineId = null
        mutableState.value = state.value.copy(status = DownloadStatus.CANCELED, bytesPerSecond = 0, message = null)
    }

    private suspend fun schedule(next: TransferRecord) {
        val scheduled = try { scheduler.schedule(next.token) } catch (_: RuntimeException) { false }
        if (!scheduled) {
            scheduler.cancel()
            save(next.copy(desired = DesiredState.PAUSED, status = DownloadStatus.FAILED))
            mutableState.value = state.value.copy(status = DownloadStatus.FAILED,
                message = "Android could not schedule the transfer. Start again while the app is visible.")
        }
    }

    /** Called synchronously from onStartJob; a subsequent stop invalidates even queued startup. */
    internal fun authorize(execution: ExecutionLease) {
        if (finishing || (ready.isCompleted && (record?.token != execution.token ||
                record?.desired != DesiredState.RUNNING || !state.value.canPause))) {
            execution.finish()
            return
        }
        lease = execution
        scope.launch {
            ready.await()
            if (loadFailure != null) {
                if (lease === execution) lease = null
                execution.finish()
                return@launch
            }
            mutex.withLock {
                if (lease !== execution) return@withLock
                val current = record
                if (current == null || current.token != execution.token ||
                    current.desired != DesiredState.RUNNING || !state.value.canPause || current.taskId == null) {
                    lease = null
                    execution.finish()
                    return@withLock
                }
                try {
                    engine.stop()
                    if (lease !== execution) return@withLock
                    engine.start()
                    if (lease !== execution) { engine.stop(); return@withLock }
                    val id = engineId
                    if (id == null) {
                        engineId = engine.add(EngineRequest(current.url, current.fileName, taskId = EngineTaskId(current.taskId)))
                    } else {
                        engine.resume(id)
                    }
                    if (lease !== execution) engine.stop()
                } catch (canceled: CancellationException) {
                    throw canceled
                } catch (_: Exception) {
                    failExecution(execution, "Unable to start the native transfer.")
                }
            }
        }
    }

    /** Revoke immediately; queued cleanup must never stop a newer job invocation. */
    internal fun revoke(execution: ExecutionLease): Boolean {
        if (lease !== execution) return false
        lease = null
        scope.launch {
            mutex.withLock {
                if (lease == null) {
                    stopEngine()
                    if (record?.desired == DesiredState.RUNNING && state.value.canPause) {
                        mutableState.value = state.value.copy(status = DownloadStatus.WAITING,
                            bytesPerSecond = 0, message = "Waiting for Android to allow the transfer.")
                    }
                }
            }
        }
        return record?.desired == DesiredState.RUNNING || !ready.isCompleted
    }

    internal fun networkChanged(execution: ExecutionLease) {
        scope.launch {
            mutex.withLock {
                if (lease !== execution || engineId == null) return@withLock
                try { engine.checkpoint() } // Recreates the native session with current Android DNS.
                catch (canceled: CancellationException) { throw canceled }
                catch (_: Exception) { failExecution(execution, "Unable to reconnect the transfer.") }
            }
        }
    }

    private suspend fun failExecution(execution: ExecutionLease, message: String) {
        if (lease !== execution) return
        finishing = true
        lease = null
        stopEngine()
        record?.let { runCatching { save(it.copy(status = DownloadStatus.FAILED)) } }
        mutableState.value = state.value.copy(status = DownloadStatus.FAILED, bytesPerSecond = 0, message = message)
        finishing = false
        execution.finish()
    }

    private suspend fun <T> afterReady(action: suspend () -> T): T {
        ready.await()
        check(loadFailure == null) { "Unable to load saved downloads" }
        return mutex.withLock { action() }
    }

    private suspend fun save(next: TransferRecord) {
        store.write(next)
        record = next
    }

    private suspend fun stopEngine() {
        try { engine.stop() }
        catch (canceled: CancellationException) { throw canceled }
        catch (_: Exception) {
            // A failed native shutdown must not leave an engine running without a job.
            engine.close()
        }
    }
}
