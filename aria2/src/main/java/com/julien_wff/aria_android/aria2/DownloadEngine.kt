package com.julien_wff.aria_android.aria2

import java.net.URI
import kotlinx.coroutines.flow.StateFlow

interface DownloadEngine {
    val snapshots: StateFlow<EngineSnapshot>
    /** Configure only while stopped. Admission is also enforced by the download coordinator. */
    suspend fun configureConcurrency(limit: Int) { require(limit in 1..6) }
    suspend fun start()
    suspend fun add(request: EngineRequest): EngineTaskId
    suspend fun pause(id: EngineTaskId)
    suspend fun resume(id: EngineTaskId)
    /** Retry a failed task using its existing staging directory and request. */
    suspend fun restart(id: EngineTaskId) { error("Restart is unavailable") }
    suspend fun remove(id: EngineTaskId)
    suspend fun updateOptions(id: EngineTaskId, options: EngineOptions)
    suspend fun checkpoint()
    suspend fun stop()
    /** Releases the owner thread. The engine cannot be restarted after this call. */
    suspend fun close()
}

@JvmInline
value class EngineTaskId(val value: String)

data class EngineOptions(
    val connectionsPerServer: Int = 4,
    val downloadLimitBytesPerSecond: Long = 0,
    val referrer: String? = null,
    val userAgent: String? = null,
) {
    init {
        require(connectionsPerServer in 1..16)
        require(downloadLimitBytesPerSecond >= 0)
        require(userAgent == null || (userAgent.isNotBlank() && userAgent.none { it.isISOControl() })) {
            "User-Agent must not contain control characters."
        }
        if (referrer != null) {
            val uri = runCatching { URI(referrer) }.getOrNull()
            require(referrer.none { it.isISOControl() } && uri != null &&
                uri.scheme?.lowercase() in setOf("http", "https") && !uri.host.isNullOrEmpty() && uri.rawUserInfo == null) {
                "HTTP referrer must be an absolute HTTP or HTTPS URL without credentials."
            }
        }
    }

    internal fun nativePairs() = arrayOf(
        "max-connection-per-server", connectionsPerServer.toString(),
        "split", connectionsPerServer.toString(),
        "max-download-limit", downloadLimitBytesPerSecond.toString(),
    ) + listOfNotNull(
        referrer?.let { arrayOf("referer", it) },
        userAgent?.let { arrayOf("user-agent", it) },
    ).flatMap { it.toList() }.toTypedArray()
}

data class EngineRequest(
    val url: String,
    val fileName: String = "download",
    val options: EngineOptions = EngineOptions(),
    /** Persist before adding; reusing it restores the same private staging directory. */
    val taskId: EngineTaskId? = null,
) {
    init {
        require(taskId == null || taskId.value.matches(Regex("[0-9a-f]{16}"))) { "Invalid staging identity" }
        val uri = try { URI(url) } catch (_: Exception) {
            throw IllegalArgumentException("An absolute HTTP or HTTPS URL is required")
        }
        require(uri.scheme?.lowercase() in setOf("http", "https") && !uri.host.isNullOrEmpty()) {
            "An absolute HTTP or HTTPS URL is required"
        }
        require(uri.rawUserInfo == null) { "URL credentials are not supported" }
        require(fileName.isNotBlank() && fileName != "." && fileName != ".." &&
            fileName.none { it == '/' || it == '\\' || it.isISOControl() }) {
            "A safe single filename is required"
        }
    }
}

enum class EngineTaskState { DOWNLOADING, QUEUED, PAUSED, COMPLETED, FAILED, REMOVED }

data class EngineTaskSnapshot(
    val id: EngineTaskId,
    val state: EngineTaskState,
    val completedBytes: Long = 0,
    val totalBytes: Long = 0,
    val bytesPerSecond: Long = 0,
    val errorCode: Int? = null,
    val errorMessage: String? = null,
)

data class EngineSnapshot(
    val started: Boolean = false,
    val tasks: List<EngineTaskSnapshot> = emptyList(),
)

class EngineException(message: String) : IllegalStateException(message)
