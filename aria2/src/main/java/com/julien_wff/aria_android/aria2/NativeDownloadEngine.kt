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
        private val runnableStates = setOf(EngineTaskState.DOWNLOADING, EngineTaskState.QUEUED, EngineTaskState.METADATA)
    }

    private data class Task(
        val id: EngineTaskId,
        var request: EngineRequest,
        val directory: File,
        var snapshot: EngineTaskSnapshot = EngineTaskSnapshot(id, EngineTaskState.QUEUED),
        var files: List<EngineFile> = emptyList(),
        var nativeId: String = id.value,
        var childIndex: Int = 0,
        var fetchingMetadata: Boolean = false,
        var preparing: Boolean = false,

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
    private var concurrency = 3
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

    override suspend fun configureConcurrency(limit: Int): Unit = execute {
        require(limit in 1..6)
        check(!started) { "Stop the engine before configuring concurrency" }
        concurrency = limit
    }

    override suspend fun start(): Unit = execute {
        if (!started) {
            check(sessionOwner.compareAndSet(false, true)) { "Another engine is started" }
            started = true
        }
    }

    override suspend fun add(request: EngineRequest): EngineTaskId = execute {
        requireStarted()
        val id = request.taskId ?: EngineTaskId(UUID.randomUUID().toString().replace("-", "").take(16))
        check(id !in tasks) { "Task is already registered" }
        check(root.isDirectory || root.mkdirs()) { "Cannot create engine directory" }
        val directory = File(root, id.value)
        check(directory.mkdir() || (request.taskId != null && directory.isDirectory)) { "Cannot create task directory" }
        check(directory.canonicalFile.parentFile == root.canonicalFile)
        val task = Task(id, request, directory)
        ensureSession()
        try { addNative(task) } catch (error: Exception) {
            if (request.kind == EngineSourceKind.HTTP) throw error
            runCatching { native.remove(task.nativeId) }
            task.snapshot = EngineTaskSnapshot(id, EngineTaskState.FAILED,
                errorMessage = "Metadata is invalid, unsafe, too large or unsupported. Check the source and choose supported files.")
        }
        tasks[id] = task
        id
    }

    override suspend fun pause(id: EngineTaskId): Unit = execute {
        requireStarted()
        val task = task(id)
        check(task.snapshot.state in runnableStates || task.snapshot.state == EngineTaskState.PAUSED)
        if (opened && task.snapshot.state != EngineTaskState.PAUSED) native.pause(task.nativeId)
        task.snapshot = task.snapshot.copy(state = EngineTaskState.PAUSED, bytesPerSecond = 0)
        // Finalization flushes control files before pause returns.
        restartSession()
    }

    override suspend fun resume(id: EngineTaskId): Unit = execute {
        requireStarted()
        val task = task(id)
        check(task.snapshot.state == EngineTaskState.PAUSED) { "Task is not paused" }
        if (task.preparing && task.request.selectedFiles != null) {
            task.snapshot = task.snapshot.copy(state = EngineTaskState.QUEUED)
            restartSession()
            return@execute
        }
        if (opened) {
            native.resume(task.nativeId)
            task.snapshot = task.snapshot.copy(state = EngineTaskState.QUEUED)
        } else {
            task.snapshot = task.snapshot.copy(state = EngineTaskState.QUEUED)
            try { ensureSession() } catch (error: Exception) {
                task.snapshot = task.snapshot.copy(state = EngineTaskState.PAUSED)
                throw error
            }
        }
    }

    override suspend fun restart(id: EngineTaskId): Unit = execute {
        requireStarted()
        val task = task(id)
        check(task.snapshot.state == EngineTaskState.FAILED) { "Task has not failed" }
        // Finalize before changing the state, so the failed GID can be registered again.
        finishSession()
        task.snapshot = task.snapshot.copy(state = EngineTaskState.QUEUED,
            bytesPerSecond = 0, errorCode = null, errorMessage = null)
        try { ensureSession() } catch (error: Exception) {
            task.snapshot = task.snapshot.copy(state = EngineTaskState.FAILED)
            throw error
        }
    }

    override suspend fun remove(id: EngineTaskId): Unit = execute {
        val task = task(id)
        check(started || task.snapshot.state == EngineTaskState.AWAITING_SELECTION) { "Engine is not started" }
        if (opened && !task.preparing && task.snapshot.state in runnableStates + EngineTaskState.PAUSED) {
            native.remove(task.nativeId)
        }
        task.snapshot = task.snapshot.copy(state = EngineTaskState.REMOVED, bytesPerSecond = 0)
        // Removal cancels transfer work; deleting staged data belongs to :downloads.
        restartSession()
    }

    override suspend fun updateOptions(id: EngineTaskId, options: EngineOptions): Unit = execute {
        requireStarted()
        val task = task(id)
        check(task.snapshot.state in runnableStates + EngineTaskState.PAUSED)
        val previous = task.request.options
        val headersChanged = previous.referrer != options.referrer || previous.userAgent != options.userAgent
        if (opened && (headersChanged || (task.snapshot.state in runnableStates &&
            previous.connectionsPerServer != options.connectionsPerServer))) {
            // Recreate to apply running connection changes and restore default headers when cleared.
            task.request = task.request.copy(options = options)
            restartSession()
        } else {
            if (opened) native.update(task.nativeId, options.nativePairs())
            task.request = task.request.copy(options = options)
        }
    }

    override suspend fun selectFiles(id: EngineTaskId, indexes: List<Int>): Unit = execute {
        val task = task(id)
        check(task.snapshot.state == EngineTaskState.AWAITING_SELECTION)
        require(indexes.isNotEmpty() && indexes.distinct().size == indexes.size &&
            indexes.all { index -> task.files.any { it.index == index && it.supported } })
        task.request = task.request.copy(selectedFiles = indexes)
        // No payload GID exists yet. Keep refresh away from the completed metadata GID.
        task.preparing = true
        task.snapshot = task.snapshot.copy(state = EngineTaskState.PAUSED)
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
            "max-concurrent-downloads", concurrency.toString(), "file-allocation", "none",
            "console-log-level", "error", "quiet", "true", "log-level", "error",
            "follow-torrent", "false", "follow-metalink", "false", "enable-dht", "true",
            "enable-dht6", "true", "enable-peer-exchange", "true",
            "bt-enable-lpd", "false", "bt-force-encryption", "false",
            "dht-file-path", File(root, "dht.dat").absolutePath,
            "dht-file-path6", File(root, "dht6.dat").absolutePath, "seed-time", "0",
            "enable-mmap", "false", "max-download-result", "1000",
            "max-tries", "1", "retry-wait", "0",
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

    private fun atomicMetadataCopy(source: File, destination: File) {
        val temporary = File(destination.parentFile, destination.name + ".importing")
        source.inputStream().use { input -> java.io.FileOutputStream(temporary).use { output ->
            input.copyTo(output); output.fd.sync()
        } }
        check(temporary.renameTo(destination)) { "Unable to retain metadata" }
    }

    private fun metadata(task: Task) = File(task.directory, if (task.request.kind == EngineSourceKind.METALINK) ".source.meta4" else ".source.torrent")

    private fun addNative(task: Task) {
        val request = task.request
        task.nativeId = task.id.value
        task.preparing = false
        if (request.kind == EngineSourceKind.HTTP) {
            check(File(task.directory, request.fileName).canonicalFile.parentFile == task.directory.canonicalFile)
            native.add(task.nativeId, java.net.URI(request.url).toASCIIString(), commonOptions(task) + arrayOf(
                "out", request.fileName, "content-disposition-default-utf8", "true", "use-head", "false"))
            return
        }
        val source = metadata(task)
        if (!source.isFile && request.url.startsWith("file:")) {
            val imported = File(java.net.URI(request.url)).canonicalFile
            val imports = File(root, "imports").canonicalFile
            require(imported.parentFile == imports && imported.isFile && imported.length() in 1..ProtocolMetadata.MAX_BYTES.toLong()) {
                "Imported metadata is unavailable. Share the file again."
            }
            atomicMetadataCopy(imported, source)
        }
        if (!source.isFile || File(task.directory, source.name + ".aria2").exists()) {
            task.fetchingMetadata = true
            task.snapshot = task.snapshot.copy(state = if (task.snapshot.state == EngineTaskState.PAUSED) EngineTaskState.PAUSED else EngineTaskState.METADATA)
            native.add(task.nativeId, request.url, commonOptions(task) + if (request.kind == EngineSourceKind.MAGNET) arrayOf(
                "bt-metadata-only", "true", "bt-save-metadata", "true", "bt-stop-timeout", "60") else arrayOf("out", source.name, "max-download-limit", "1048576"))
            return
        }
        task.fetchingMetadata = false
        task.files = if (request.kind == EngineSourceKind.METALINK) ProtocolMetadata.metalink(source) else ProtocolMetadata.torrent(source)
        val selection = request.selectedFiles
        if (selection == null) {
            task.preparing = true
            task.snapshot = EngineTaskSnapshot(task.id, EngineTaskState.AWAITING_SELECTION, files = task.files)
            return
        }
        require(selection.all { index -> task.files.any { it.index == index && it.supported } }) { "Select supported files from the metadata." }
        task.files = task.files.map { it.copy(selected = it.index in selection) }
        task.preparing = false
        if (request.kind == EngineSourceKind.METALINK) {
            task.childIndex = 0
            addMetalinkChild(task)
        } else {
            native.addTorrent(task.nativeId, source.canonicalPath, commonOptions(task) + arrayOf(
                "select-file", selection.joinToString(","), "check-integrity", "true", "seed-time", "0"))
            validateNativeFiles(task)
        }
    }

    private fun commonOptions(task: Task, directory: File = task.directory) = task.request.options.nativePairs() + arrayOf(
        "dir", directory.canonicalPath, "continue", "true", "auto-file-renaming", "false", "allow-overwrite", "false",
        "pause", (task.snapshot.state == EngineTaskState.PAUSED).toString(),
    )

    private fun addMetalinkChild(task: Task) {
        val file = task.files.filter { it.selected }[task.childIndex]
        val destination = File(task.directory, file.path)
        require(destination.canonicalPath.startsWith(task.directory.canonicalPath + File.separator))
        check(destination.parentFile!!.isDirectory || destination.parentFile!!.mkdirs())
        task.nativeId = java.security.MessageDigest.getInstance("SHA-256").digest("${task.id.value}:${file.index}".toByteArray())
            .take(8).joinToString("") { "%02x".format(it) }
        native.addMirrors(task.nativeId, file.mirrors.toTypedArray(), commonOptions(task, destination.parentFile!!) + arrayOf(
            "out", destination.name,
            "check-integrity", "true") + (file.checksum?.let { arrayOf("checksum", it) } ?: emptyArray()))
    }

    private fun validateNativeFiles(task: Task): List<EngineFile> {
        val nativeFiles = native.files(task.nativeId).toList().chunked(4)
        require(nativeFiles.size == task.files.size)
        val originals = task.files.associateBy { it.index }
        return nativeFiles.map { values ->
            val original = originals.getValue(values[0].toInt())
            val actual = File(values[1]).canonicalFile
            require(actual == File(task.directory, original.path).canonicalFile && actual.path.startsWith(task.directory.canonicalPath + File.separator))
            require(values[2].toLong() == original.length)
            original.copy(completedBytes = values[3].toLong())
        }
    }

    private fun refresh() {
        tasks.values.filter { it.snapshot.state in runnableStates + EngineTaskState.PAUSED }.forEach { task ->
            if (task.preparing) return@forEach
            try {
                val data = native.snapshot(task.nativeId)
                require(data.size == 5)
                val nativeState = EngineTaskState.entries[data[0].toInt()]
                if (task.fetchingMetadata) {
                    if (task.request.kind != EngineSourceKind.MAGNET && data[1] > ProtocolMetadata.MAX_BYTES) {
                        native.remove(task.nativeId)
                        task.snapshot = EngineTaskSnapshot(task.id, EngineTaskState.FAILED, errorMessage = "Metadata exceeds the 8 MiB limit.")
                        return@forEach
                    }
                    if (nativeState == EngineTaskState.COMPLETED) {
                        if (task.request.kind == EngineSourceKind.MAGNET) {
                            val torrent = task.directory.listFiles()?.singleOrNull { it.extension == "torrent" && it.length() <= ProtocolMetadata.MAX_BYTES }
                                ?: error("Magnet metadata unavailable")
                            atomicMetadataCopy(torrent, metadata(task))
                        }
                        // Resolve metadata offline on the next session; no payload can start here.
                        task.fetchingMetadata = false
                        task.files = if (task.request.kind == EngineSourceKind.METALINK) ProtocolMetadata.metalink(metadata(task)) else ProtocolMetadata.torrent(metadata(task))
                        task.preparing = true
                        task.snapshot = EngineTaskSnapshot(task.id, EngineTaskState.AWAITING_SELECTION, files = task.files)
                    } else task.snapshot = EngineTaskSnapshot(task.id,
                        if (nativeState == EngineTaskState.FAILED) nativeState else if (task.snapshot.state == EngineTaskState.PAUSED) EngineTaskState.PAUSED else EngineTaskState.METADATA,
                        errorCode = data[4].toInt().takeIf { nativeState == EngineTaskState.FAILED }, files = task.files)
                    return@forEach
                }
                if (task.request.kind == EngineSourceKind.METALINK) {
                    val selected = task.files.filter { it.selected }
                    val current = selected[task.childIndex]
                    task.files = task.files.map { if (it.index == current.index) it.copy(
                        completedBytes = data[1], length = if (it.length == 0L) data[2] else it.length) else it }
                    if (nativeState == EngineTaskState.COMPLETED && task.childIndex < selected.lastIndex) {
                        task.childIndex++
                        addMetalinkChild(task)
                        return@forEach
                    }
                } else if (task.request.kind != EngineSourceKind.HTTP) task.files = validateNativeFiles(task)
                if (nativeState == EngineTaskState.COMPLETED) task.files = task.files.map {
                    if (it.selected) it.copy(completedBytes = it.length) else it
                }
                val selected = task.files.filter { it.selected }
                task.snapshot = EngineTaskSnapshot(task.id,
                    if (task.snapshot.state == EngineTaskState.PAUSED && nativeState in runnableStates) EngineTaskState.PAUSED else nativeState,
                    if (selected.isEmpty()) data[1] else selected.sumOf { it.completedBytes },
                    if (selected.isEmpty()) data[2] else selected.sumOf { it.length },
                    if (nativeState == EngineTaskState.PAUSED) 0 else data[3],
                    data[4].toInt().takeIf { nativeState == EngineTaskState.FAILED },
                    if (nativeState == EngineTaskState.FAILED) {
                        if (data[4] == 8L) "Server rejected the resume request; partial data is preserved"
                        else "Download failed (aria2 code ${data[4]})"
                    } else null,
                    task.files)
            } catch (_: Exception) {
                runCatching { native.remove(task.nativeId) }
                task.snapshot = EngineTaskSnapshot(task.id, EngineTaskState.FAILED,
                    errorMessage = "Metadata is invalid, unsafe, too large or unsupported. Check the source and choose supported files.", files = task.files)
            }
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
