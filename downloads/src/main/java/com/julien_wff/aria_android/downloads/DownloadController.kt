package com.julien_wff.aria_android.downloads

import com.julien_wff.aria_android.aria2.DownloadEngine
import com.julien_wff.aria_android.aria2.EngineRequest
import com.julien_wff.aria_android.aria2.EngineTaskId
import com.julien_wff.aria_android.aria2.EngineTaskState
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class DownloadStatus {
    IDLE, SCHEDULED, DOWNLOADING, WAITING, PAUSED, COMPLETED, FAILED, CANCELED, INTERRUPTED,
}

data class DownloadState(
    val fileName: String = "download",
    val status: DownloadStatus = DownloadStatus.IDLE,
    val completedBytes: Long = 0,
    val totalBytes: Long = 0,
    val bytesPerSecond: Long = 0,
    val message: String? = null,
) {
    val canStart: Boolean get() = status in setOf(
        DownloadStatus.IDLE, DownloadStatus.COMPLETED, DownloadStatus.FAILED, DownloadStatus.CANCELED,
    )
    val canPause: Boolean get() = status in setOf(
        DownloadStatus.SCHEDULED, DownloadStatus.DOWNLOADING, DownloadStatus.WAITING,
    )
    val canResume: Boolean get() = status == DownloadStatus.PAUSED
    val canCancel: Boolean get() = !canStart
}

internal enum class DesiredState { RUNNING, PAUSED, CANCELED }
internal data class TransferRecord(
    val token: String,
    val url: String,
    val fileName: String,
    val desired: DesiredState,
    val status: DownloadStatus,
)

internal interface TransferStore {
    fun read(): TransferRecord?
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
) {
    private val mutex = Mutex()
    private var record = store.read()
    private var engineId: EngineTaskId? = null
    private var finishing = false
    @Volatile private var lease: ExecutionLease? = null
    private val mutableState = MutableStateFlow(record?.let {
        DownloadState(fileName = it.fileName, status = when (it.status) {
            DownloadStatus.COMPLETED, DownloadStatus.CANCELED, DownloadStatus.FAILED -> it.status
            else -> DownloadStatus.INTERRUPTED
        }, message = if (it.status in setOf(DownloadStatus.COMPLETED, DownloadStatus.CANCELED,
                DownloadStatus.FAILED)) null else "Interrupted. Cancel to start a new download; recovery is not available yet.")
    } ?: DownloadState())
    val state: StateFlow<DownloadState> = mutableState.asStateFlow()

    init {
        scope.launch {
            engine.snapshots.collect { snapshot ->
                mutex.withLock {
                    val currentLease = lease ?: return@withLock
                    val task = snapshot.tasks.find { it.id == engineId } ?: return@withLock
                    if (record?.desired != DesiredState.RUNNING) return@withLock
                    var status = when (task.state) {
                        EngineTaskState.DOWNLOADING -> DownloadStatus.DOWNLOADING
                        EngineTaskState.QUEUED -> DownloadStatus.SCHEDULED
                        EngineTaskState.COMPLETED -> DownloadStatus.COMPLETED
                        EngineTaskState.FAILED -> DownloadStatus.FAILED
                        else -> return@withLock
                    }
                    var message = when (status) {
                        DownloadStatus.COMPLETED -> "Downloaded to private staging; saving to Downloads is not available yet."
                        DownloadStatus.FAILED -> task.errorMessage ?: "Download failed (code ${task.errorCode})."
                        else -> null
                    }
                    if (status in setOf(DownloadStatus.COMPLETED, DownloadStatus.FAILED)) {
                        finishing = true
                        lease = null
                        try { save(record!!.copy(status = status)) }
                        catch (_: java.io.IOException) {
                            status = DownloadStatus.FAILED
                            message = "Unable to save download status. Staged data has been preserved."
                        }
                        stopEngine()
                        currentLease.finish()
                    }
                    mutableState.value = mutableState.value.copy(status = status,
                        completedBytes = task.completedBytes, totalBytes = task.totalBytes,
                        bytesPerSecond = if (status == DownloadStatus.DOWNLOADING) task.bytesPerSecond else 0,
                        message = message)
                    finishing = false
                }
            }
        }
    }

    suspend fun start(url: String, fileName: String = "download") = mutex.withLock {
        check(state.value.canStart) { "Finish or cancel the current download first." }
        val request = EngineRequest(url.trim(), fileName.trim())
        engineId = null
        val next = TransferRecord(UUID.randomUUID().toString(), request.url, request.fileName,
            DesiredState.RUNNING, DownloadStatus.SCHEDULED)
        save(next)
        mutableState.value = DownloadState(fileName = request.fileName, status = DownloadStatus.SCHEDULED)
        schedule(next)
    }

    suspend fun resume() = mutex.withLock {
        check(state.value.canResume) { "This download cannot be resumed." }
        val next = record!!.copy(token = UUID.randomUUID().toString(),
            desired = DesiredState.RUNNING, status = DownloadStatus.SCHEDULED)
        save(next)
        mutableState.value = state.value.copy(status = DownloadStatus.SCHEDULED, message = null)
        schedule(next)
    }

    suspend fun pause() = mutex.withLock {
        if (!state.value.canPause) return@withLock
        save(record!!.copy(desired = DesiredState.PAUSED, status = DownloadStatus.PAUSED))
        val previous = lease
        lease = null
        scheduler.cancel()
        stopEngine() // Finalizes native control files before reporting Paused.
        previous?.finish?.invoke()
        mutableState.value = state.value.copy(status = DownloadStatus.PAUSED, bytesPerSecond = 0)
    }

    suspend fun cancel() = mutex.withLock {
        val current = record ?: return@withLock
        save(current.copy(desired = DesiredState.CANCELED, status = DownloadStatus.CANCELED))
        val previous = lease
        lease = null
        scheduler.cancel()
        stopEngine() // Keep staged data; deletion belongs to the later storage milestone.
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
        if (finishing || record?.token != execution.token || record?.desired != DesiredState.RUNNING || !state.value.canPause) {
            execution.finish()
            return
        }
        lease = execution
        scope.launch {
            mutex.withLock {
                if (lease !== execution) return@withLock
                val current = record
                if (current == null || current.token != execution.token ||
                    current.desired != DesiredState.RUNNING || state.value.status == DownloadStatus.INTERRUPTED) {
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
                        engineId = engine.add(EngineRequest(current.url, current.fileName))
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
        return record?.desired == DesiredState.RUNNING
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
