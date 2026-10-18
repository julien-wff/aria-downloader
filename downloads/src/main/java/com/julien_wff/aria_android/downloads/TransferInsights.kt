package com.julien_wff.aria_android.downloads

/** Display durations consistently, without overflowing when rounding large estimates. */
fun formatTransferDuration(seconds: Long, spoken: Boolean = false): String {
    var remaining = seconds.coerceAtLeast(0)
    val units = listOf(86400L to ("day" to "day"), 3600L to ("hr" to "hour"),
        60L to ("min" to "minute"), 1L to ("sec" to "second"))
    val first = units.indexOfFirst { remaining >= it.first }.let { if (it < 0) 3 else it }
    return units.drop(first).take(2).mapNotNull { (size, names) ->
        val count = remaining / size
        remaining %= size
        if (count == 0L && seconds > 0) null else {
            val name = if (spoken) names.second else names.first
            "$count $name${if ((spoken || name == "day") && count != 1L) "s" else ""}"
        }
    }.joinToString(" ")
}

fun formatTransferDurationMillis(milliseconds: Long, spoken: Boolean = false): String =
    if (milliseconds in 1..999) "Less than 1 ${if (spoken) "second" else "sec"}"
    else formatTransferDuration(milliseconds.coerceAtLeast(0) / 1000 + if (milliseconds % 1000 > 0) 1 else 0, spoken)

fun transferEtaText(state: DownloadState, spoken: Boolean = false): String? {
    val seconds = transferEta(state) ?: return null
    return if (state.totalBytes - state.completedBytes < state.bytesPerSecond)
        "Less than 1 ${if (spoken) "second" else "sec"}"
    else formatTransferDuration(seconds, spoken)
}

fun transferEta(state: DownloadState): Long? {
    if (state.status != DownloadStatus.DOWNLOADING || state.bytesPerSecond <= 0 ||
        state.totalBytes <= state.completedBytes || state.completedBytes < 0) return null
    val remaining = state.totalBytes - state.completedBytes
    return remaining / state.bytesPerSecond + if (remaining % state.bytesPerSecond != 0L) 1 else 0
}

data class SpeedSample(val elapsedMillis: Long, val bytesPerSecond: Long?)
data class PieceOverview(val count: Long, val pieceBytes: Long, val cells: List<PieceOverviewCell>, val fileName: String?)
data class PieceOverviewCell(val completed: Long, val total: Long)
data class TransferInsights(val samples: List<SpeedSample> = emptyList(), val pieces: PieceOverview? = null)

internal class InsightHistory {
    private val histories = mutableMapOf<String, TransferInsights>()
    fun get(id: String) = histories[id] ?: TransferInsights()
    fun remove(id: String) { histories.remove(id) }
    fun stop(id: String, now: Long) {
        val previous = get(id)
        val last = previous.samples.lastOrNull() ?: return
        if (last.bytesPerSecond == null) return
        histories[id] = previous.copy(samples = previous.samples + SpeedSample(maxOf(now, last.elapsedMillis + 1), null))
    }
    fun update(id: String, now: Long, speed: Long?, pieces: PieceOverview? = get(id).pieces) {
        val previous = get(id)
        val samples = previous.samples.filter { now - it.elapsedMillis <= 120_000 }.toMutableList()
        if (samples.isEmpty() || now - samples.last().elapsedMillis >= 1000) {
            if (samples.isNotEmpty() && now - samples.last().elapsedMillis > 2000)
                samples.add(SpeedSample(now - 1, null))
            samples.add(SpeedSample(now, speed?.coerceAtLeast(0)))
        }
        histories[id] = TransferInsights(samples, pieces)
    }
}

fun transferExplanation(state: DownloadState): String = when (state.status) {
    DownloadStatus.SCHEDULED -> "Queued; waiting for a download slot or Android to allow execution."
    DownloadStatus.WAITING -> state.message ?: "Waiting for the required network or Android to allow execution."
    DownloadStatus.PAUSED -> "Paused by you. Resume when you are ready."
    DownloadStatus.METADATA -> "Resolving metadata. File contents have not started downloading."
    DownloadStatus.AWAITING_SELECTION -> "Review and select files to begin downloading their contents."
    DownloadStatus.VERIFYING -> "Checking downloaded files before saving them publicly."
    DownloadStatus.STAGED -> "Download finished; files still need to be saved to Downloads."
    DownloadStatus.EXPORTING -> "Saving files to Downloads. They are not publicly saved yet."
    DownloadStatus.SAVE_FAILED -> "Downloaded files are not fully saved publicly. Retry saving."
    DownloadStatus.INTERRUPTED -> state.message ?: "Transfer interrupted. Open the app and Resume."
    DownloadStatus.DOWNLOADING -> "Receiving file contents. Downloaded files become publicly available after verification and publication."
    DownloadStatus.COMPLETED -> "Selected files have been saved to Downloads."
    DownloadStatus.FAILED -> state.message ?: "Transfer failed. Review the error and retry."
    DownloadStatus.CANCELED -> "Canceled. Partial files remain until the entry is deleted."
    DownloadStatus.IDLE -> "Ready to start."
}
