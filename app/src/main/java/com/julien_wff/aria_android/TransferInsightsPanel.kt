package com.julien_wff.aria_android

import android.text.format.Formatter
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.julien_wff.aria_android.downloads.*
import kotlinx.coroutines.delay
import kotlin.math.ceil

@Composable
internal fun TransferInsightsPanel(task: DownloadTask) {
    val state = task.state
    var wallTime by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(state.retryAt) {
        while (state.retryAt > 0) {
            wallTime = System.currentTimeMillis()
            delay(1000)
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Transfer insights", style = MaterialTheme.typography.titleLarge)
        if (state.retryAt > 0) {
            val seconds = ((state.retryAt - wallTime).coerceAtLeast(0) + 999) / 1000
            Text(if (seconds > 0) "Automatic retry in ${formatTransferDuration(seconds)}" else "Retry ready; waiting for execution permission.",
                modifier = Modifier.semantics { contentDescription = if (seconds > 0) "Automatic retry in ${formatTransferDuration(seconds, true)}" else "Retry ready; waiting for execution permission." })
        } else Text(transferExplanation(state), style = MaterialTheme.typography.bodyMedium)
        SpeedGraph(task.insights.samples)
        PieceMatrix(task)
    }
}

@Composable
private fun SpeedGraph(samples: List<SpeedSample>) {
    val context = LocalContext.current
    val speeds = samples.mapNotNull { it.bytesPerSecond }
    val peak = speeds.maxOrNull() ?: 0L
    val average = if (speeds.isEmpty()) 0L else speeds.map { it.toDouble() }.average().toLong()
    val current = samples.lastOrNull()?.bytesPerSecond ?: 0L
    fun speed(value: Long) = "${Formatter.formatShortFileSize(context, value)}/s"
    Text("Speed · last 2 minutes", style = MaterialTheme.typography.titleMedium)
    if (speeds.isEmpty()) {
        Text("Speed history will appear when file contents start downloading.")
        return
    }
    val summary = "Latest ${speed(current)} · Average ${speed(average)} · Peak ${speed(peak)}"
    Text(summary, style = MaterialTheme.typography.bodySmall)
    Text(speed(peak), style = MaterialTheme.typography.labelSmall)
    val lineColor = MaterialTheme.colorScheme.primary
    val axisColor = MaterialTheme.colorScheme.outlineVariant
    Canvas(Modifier.fillMaxWidth().height(112.dp).semantics { contentDescription = "Download speed over the last two minutes. $summary" }) {
        drawLine(axisColor, Offset(0f, size.height), Offset(size.width, size.height))
        drawLine(axisColor, Offset.Zero, Offset(0f, size.height))
        val end = samples.last().elapsedMillis
        val path = Path()
        var connected = false
        samples.forEach { sample ->
            val value = sample.bytesPerSecond
            if (value == null) connected = false else {
                val x = ((sample.elapsedMillis - (end - 120_000)).toFloat() / 120_000 * size.width).coerceIn(0f, size.width)
                val y = size.height * (1f - (value.toDouble() / peak.coerceAtLeast(1)).toFloat().coerceIn(0f, 1f))
                if (connected) path.lineTo(x, y) else path.moveTo(x, y)
                drawCircle(lineColor, 2.dp.toPx(), Offset(x, y))
                connected = true
            }
        }
        drawPath(path, lineColor, style = androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx()))
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text("2 min ago · 0 B/s", style = MaterialTheme.typography.labelSmall)
        Text("Latest sample", style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun PieceMatrix(task: DownloadTask) {
    Text("Downloaded pieces", style = MaterialTheme.typography.titleMedium)
    val pieces = task.insights.pieces
    if (pieces == null) {
        Text("Piece map unavailable. It appears once piece information is available. Resume a stopped transfer to refresh it.")
        return
    }
    if (task.protocol in setOf(DownloadProtocol.TORRENT, DownloadProtocol.MAGNET))
        Text("Whole torrent · Unselected files can leave gaps after selected files finish.", style = MaterialTheme.typography.bodySmall)
    pieces.fileName?.let { Text("Current file: $it", style = MaterialTheme.typography.bodySmall) }
    val done = pieces.cells.sumOf { it.completed }
    val summary = "$done of ${pieces.count} pieces complete"
    Text("$summary · ${Formatter.formatShortFileSize(LocalContext.current, pieces.pieceBytes)} per piece", style = MaterialTheme.typography.bodySmall)
    val empty = MaterialTheme.colorScheme.surfaceVariant
    val complete = MaterialTheme.colorScheme.primary
    val outline = MaterialTheme.colorScheme.outline
    Canvas(Modifier.fillMaxWidth().height(136.dp).semantics { contentDescription = "$summary. Empty, partially complete, and complete cells. Each cell groups consecutive pieces." }) {
        val columns = minOf(32, maxOf(8, (size.width / 12.dp.toPx()).toInt()))
        val rows = ceil(pieces.cells.size.toDouble() / columns).toInt().coerceAtLeast(1)
        val width = size.width / columns
        val height = minOf(size.height / rows, width)
        pieces.cells.forEachIndexed { index, cell ->
            val origin = Offset((index % columns) * width, (index / columns) * height)
            val cellSize = Size((width - 2.dp.toPx()).coerceAtLeast(1f), (height - 2.dp.toPx()).coerceAtLeast(1f))
            drawRect(empty, origin, cellSize)
            val fraction = (cell.completed.toDouble() / cell.total).toFloat()
            if (fraction > 0) drawRect(complete, origin, Size(cellSize.width * fraction, cellSize.height))
            drawRect(outline, origin, cellSize, style = androidx.compose.ui.graphics.drawscope.Stroke(0.5.dp.toPx()))
        }
    }
    Text("Empty = missing · Filled portion = completed pieces · Full = complete", style = MaterialTheme.typography.bodySmall)
    if (pieces.count > pieces.cells.size) Text("Each cell groups consecutive pieces (${pieces.cells.minOf { it.total }}–${pieces.cells.maxOf { it.total }} per cell).", style = MaterialTheme.typography.bodySmall)
    Text("Piece completion describes private download data, not public saving.", style = MaterialTheme.typography.bodySmall)
}
