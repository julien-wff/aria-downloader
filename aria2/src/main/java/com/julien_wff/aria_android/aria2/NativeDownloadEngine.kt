package com.julien_wff.aria_android.aria2

import android.content.Context
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** In-process engine only: callers must supply background execution authorization later. */
class NativeDownloadEngine internal constructor(
    private val root: File,
    private val environment: () -> SessionEnvironment,
    private val native: NativeBackend,
) : DownloadEngine {
    constructor(context: Context) : this(
        File(context.applicationContext.noBackupFilesDir, "aria2"),
        AndroidEnvironment(context, File(context.applicationContext.noBackupFilesDir, "aria2"))::load,
        JniBackend(),
    )

    companion object {
        private val sessionOwner = AtomicBoolean(false)
        private val runnableStates = setOf(EngineTaskState.DOWNLOADING, EngineTaskState.QUEUED)
    }

    private data class Task(
        val id: EngineTaskId,
        var request: EngineRequest,
        val directory: File,
        var snapshot: EngineTaskSnapshot = EngineTaskSnapshot(id, EngineTaskState.QUEUED),
    )
    private class Command(val action: () -> Any?, val result: CompletableDeferred<Any?>)
    private val dispatcher = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "aria2-engine")
    }.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val commands = Channel<Command>(Channel.UNLIMITED)
    private val mutableSnapshots = MutableStateFlow(EngineSnapshot())
    override val snapshots = mutableSnapshots.asStateFlow()
    private val tasks = linkedMapOf<EngineTaskId, Task>()
    private var started = false
    private var opened = false
    private val closing = AtomicBoolean(false)

    private val worker = scope.launch {
        try {
            while (true) {
                val command = if (opened && tasks.values.any { it.snapshot.state in runnableStates }) {
                    commands.tryReceive().getOrNull()
                } else {
                    commands.receiveCatching().getOrNull() ?: break
                }
                if (command != null) {
                    try {
                        val value = command.action()
                        publish()
                        command.result.complete(value)
                    } catch (error: Exception) {
                        publish()
                        command.result.completeExceptionally(error)
                    }
                }
                if (opened) {
                    try {
                        native.tick()
                        refresh()
                        if (tasks.values.none { it.snapshot.state in runnableStates }) finishSession()
                    } catch (_: Exception) {
                        tasks.values.filter { it.snapshot.state in runnableStates }.forEach {
                            it.snapshot = it.snapshot.copy(state = EngineTaskState.FAILED,
                                bytesPerSecond = 0, errorMessage = "Native engine failed")
                        }
                        runCatching { finishSession() }
                    }
                    publish()
                }
            }
        } finally {
            runCatching { finishSession() }
            if (started) sessionOwner.set(false)
            started = false
            publish()
            while (true) {
                val pending = commands.tryReceive().getOrNull() ?: break
                pending.result.completeExceptionally(EngineException("Engine is closed"))
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private suspend fun <T> execute(action: () -> T): T {
        check(!closing.get()) { "Engine is closed" }
        val result = CompletableDeferred<Any?>()
        commands.send(Command(action, result))
        return result.await() as T
    }

    override suspend fun start(): Unit = execute {
        if (!started) {
            check(sessionOwner.compareAndSet(false, true)) { "Another engine is started" }
            started = true
        }
    }

    override suspend fun add(request: EngineRequest): EngineTaskId = execute {
        requireStarted()
        val id = EngineTaskId(UUID.randomUUID().toString().replace("-", "").take(16))
        check(root.isDirectory || root.mkdirs()) { "Cannot create engine directory" }
        val directory = File(root, id.value)
        check(directory.mkdir()) { "Cannot create task directory" }
        check(directory.canonicalFile.parentFile == root.canonicalFile)
        val task = Task(id, request, directory)
        ensureSession()
        addNative(task)
        tasks[id] = task
        id
    }

    override suspend fun pause(id: EngineTaskId): Unit = execute {
        requireStarted()
        val task = task(id)
        check(task.snapshot.state in runnableStates || task.snapshot.state == EngineTaskState.PAUSED)
        if (opened && task.snapshot.state != EngineTaskState.PAUSED) native.pause(id.value)
        task.snapshot = task.snapshot.copy(state = EngineTaskState.PAUSED, bytesPerSecond = 0)
        // Finalization flushes control files before pause returns.
        restartSession()
    }

    override suspend fun resume(id: EngineTaskId): Unit = execute {
        requireStarted()
        val task = task(id)
        check(task.snapshot.state == EngineTaskState.PAUSED) { "Task is not paused" }
        if (opened) {
            native.resume(id.value)
            task.snapshot = task.snapshot.copy(state = EngineTaskState.QUEUED)
        } else {
            task.snapshot = task.snapshot.copy(state = EngineTaskState.QUEUED)
            try { ensureSession() } catch (error: Exception) {
                task.snapshot = task.snapshot.copy(state = EngineTaskState.PAUSED)
                throw error
            }
        }
    }

    override suspend fun remove(id: EngineTaskId): Unit = execute {
        requireStarted()
        val task = task(id)
        if (opened && task.snapshot.state in runnableStates + EngineTaskState.PAUSED) {
            native.remove(id.value)
        }
        task.snapshot = task.snapshot.copy(state = EngineTaskState.REMOVED, bytesPerSecond = 0)
        // Removal cancels transfer work; deleting staged data belongs to :downloads.
        restartSession()
    }

    override suspend fun updateOptions(id: EngineTaskId, options: EngineOptions): Unit = execute {
        requireStarted()
        val task = task(id)
        check(task.snapshot.state in runnableStates + EngineTaskState.PAUSED)
        if (opened && task.snapshot.state in runnableStates &&
            task.request.options.connectionsPerServer != options.connectionsPerServer) {
            // libaria2 can change connection counts only for reserved tasks.
            // Recreate the session so a running task receives the new count too.
            task.request = task.request.copy(options = options)
            restartSession()
        } else {
            if (opened) native.update(id.value, options.nativePairs())
            task.request = task.request.copy(options = options)
        }
    }

    override suspend fun checkpoint(): Unit = execute {
        requireStarted()
        restartSession()
    }

    override suspend fun stop(): Unit = execute { stopInternal() }

    override suspend fun close() {
        if (closing.compareAndSet(false, true)) {
            val stopped = CompletableDeferred<Any?>()
            commands.send(Command({ stopInternal() }, stopped))
            commands.close()
            worker.join()
            scope.cancel()
            dispatcher.close()
        } else {
            worker.join()
        }
    }

    private fun stopInternal() {
        if (!started) return
        finishSession()
        tasks.values.filter { it.snapshot.state in runnableStates }.forEach {
            it.snapshot = it.snapshot.copy(state = EngineTaskState.PAUSED, bytesPerSecond = 0)
        }
        started = false
        sessionOwner.set(false)
    }

    private fun requireStarted() = check(started) { "Engine is not started" }
    private fun task(id: EngineTaskId) = tasks[id] ?: throw IllegalArgumentException("Unknown task")
    private fun publish() {
        mutableSnapshots.value = EngineSnapshot(started, tasks.values.map { it.snapshot })
    }

    private fun ensureSession() {
        if (opened) return
        val config = environment()
        val options = arrayOf(
            "dir", root.canonicalPath, "enable-rpc", "false", "check-certificate", "true",
            "no-conf", "true", "no-netrc", "true",
            "ca-certificate", config.caFile.canonicalPath, "async-dns", "true",
            "save-session", File(root, "session.txt").absolutePath,
            "save-session-interval", "5", "auto-save-interval", "5",
            "max-concurrent-downloads", "3", "file-allocation", "none",
            "console-log-level", "error", "quiet", "true", "log-level", "error",
            "follow-torrent", "false", "follow-metalink", "false", "enable-dht", "false",
            "enable-dht6", "false", "enable-peer-exchange", "false", "seed-time", "0",
            "enable-mmap", "false", "max-download-result", "1000",
        ) + if (config.dnsServers.isEmpty()) emptyArray() else arrayOf(
            "async-dns-server", config.dnsServers.joinToString(","),
        )
        native.open(options)
        opened = true
        try {
            tasks.values.filter { it.snapshot.state in runnableStates + EngineTaskState.PAUSED }
                .forEach { addNative(it) }
        } catch (error: Exception) {
            finishSession()
            throw error
        }
    }

    private fun addNative(task: Task) {
        check(File(task.directory, task.request.fileName).canonicalFile.parentFile == task.directory.canonicalFile)
        native.add(task.id.value, java.net.URI(task.request.url).toASCIIString(), task.request.options.nativePairs() + arrayOf(
            "dir", task.directory.canonicalPath, "out", task.request.fileName,
            "continue", "true", "auto-file-renaming", "false", "allow-overwrite", "false",
            "content-disposition-default-utf8", "true", "use-head", "false",
            "pause", (task.snapshot.state == EngineTaskState.PAUSED).toString(),
        ))
    }

    private fun refresh() {
        tasks.values.filter { it.snapshot.state in runnableStates + EngineTaskState.PAUSED }.forEach { task ->
            val data = native.snapshot(task.id.value)
            require(data.size == 5)
            val state = EngineTaskState.entries[data[0].toInt()]
            // A requested pause must remain paused while native cancellation is being processed.
            task.snapshot = EngineTaskSnapshot(task.id,
                if (task.snapshot.state == EngineTaskState.PAUSED && state in runnableStates)
                    EngineTaskState.PAUSED else state,
                data[1], data[2], if (state == EngineTaskState.PAUSED) 0 else data[3],
                data[4].toInt().takeIf { state == EngineTaskState.FAILED },
                if (state == EngineTaskState.FAILED) {
                    if (data[4] == 8L) "Server rejected the resume request; partial data is preserved"
                    else "Download failed (aria2 code ${data[4]})"
                } else null)
        }
    }

    private fun finishSession() {
        if (opened) {
            try { native.shutdown() } finally { opened = false }
        }
    }

    private fun restartSession() {
        finishSession()
        if (tasks.values.any { it.snapshot.state in runnableStates }) ensureSession()
    }
}
