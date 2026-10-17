package com.julien_wff.aria_android.downloads

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.text.format.Formatter

internal class TransferNotifications(
    private val context: Context,
    private val appIntent: Intent = requireNotNull(context.packageManager.getLaunchIntentForPackage(context.packageName)),
) {
    companion object {
        const val ID = 1001
        const val ACTIVE_CHANNEL = "active_transfers"
        const val RESULT_CHANNEL = "transfer_results"
        const val PAUSE = "pause"
        const val CANCEL = "cancel"
    }

    private val manager = context.getSystemService(NotificationManager::class.java)

    init {
        manager.createNotificationChannels(listOf(
            NotificationChannel(ACTIVE_CHANNEL, "Active downloads", NotificationManager.IMPORTANCE_LOW),
            NotificationChannel(RESULT_CHANNEL, "Download results", NotificationManager.IMPORTANCE_DEFAULT),
        ))
    }

    fun build(state: DownloadState, token: String, taskId: String? = null): Notification {
        val active = state.canPause || state.status in setOf(DownloadStatus.EXPORTING, DownloadStatus.VERIFYING)
        val builder = Notification.Builder(context, if (active) ACTIVE_CHANNEL else RESULT_CHANNEL)
            .setSmallIcon(R.drawable.ic_download)
            .setContentTitle(state.fileName)
            .setContentText(description(state))
            .setContentIntent(openApp(taskId))
            .setCategory(if (active) Notification.CATEGORY_PROGRESS else Notification.CATEGORY_STATUS)
            .setOnlyAlertOnce(true)
            .setOngoing(active)
            .setShowWhen(false)
            .setAutoCancel(!active)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setPublicVersion(Notification.Builder(context, ACTIVE_CHANNEL)
                .setSmallIcon(R.drawable.ic_download).setContentTitle("Aria Downloader")
                .setContentText("Download status").build())
        if (active) {
            val saving = state.status in setOf(DownloadStatus.EXPORTING, DownloadStatus.VERIFYING)
            val unknown = if (saving) state.completedBytes <= 0 else state.totalBytes <= 0 || state.status != DownloadStatus.DOWNLOADING
            val progress = if (saving) progressValue(state.savedBytes, state.completedBytes)
                else progressValue(state.completedBytes, state.totalBytes)
            if (Build.VERSION.SDK_INT >= 36) {
                builder.setStyle(Notification.ProgressStyle()
                    .setProgressSegments(listOf(Notification.ProgressStyle.Segment(1000)))
                    .setProgress(progress)
                    .setProgressIndeterminate(unknown)
                    .setStyledByProgress(true))
            } else {
                builder.setProgress(1000, progress, unknown)
            }
            if (!saving) builder.addAction(action("Pause", PAUSE, token, 1, taskId))
                .addAction(action("Cancel", CANCEL, token, 2, taskId))
        } else if (state.canRetrySaving) {
            builder.addAction(Notification.Action.Builder(null, "Retry saving", openApp(taskId)).build())
        } else if (state.canResume) {
            builder.addAction(Notification.Action.Builder(null, "Resume", openApp(taskId)).build())
        }
        if (state.status == DownloadStatus.COMPLETED && state.publishedUri != null && taskId != null) {
            builder.addAction(Notification.Action.Builder(null, "Open", openApp(taskId, "open")).build())
                .addAction(Notification.Action.Builder(null, "Share", openApp(taskId, "share")).build())
        }
        if (taskId != null) builder.setGroup("downloads")
        return builder.build()
    }

    fun update(state: DownloadState, token: String) {
        if (state.status == DownloadStatus.IDLE) return
        if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
            // A disabled channel/denied permission does not stop a UIDT job. The service still
            // attaches its required notification through JobService.setNotification().
            manager.notify(ID, build(state, token))
        }
    }

    fun controlFailed(state: DownloadState, token: String) {
        if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
            manager.notify(ID, Notification.Builder.recoverBuilder(context, build(state, token))
                .setContentText("Unable to apply that action. Open the app and try again.").build())
        }
    }

    fun summary(tasks: List<DownloadTask>, token: String): Notification {
        val active = tasks.filter { it.state.canPause || it.state.status in setOf(DownloadStatus.EXPORTING, DownloadStatus.VERIFYING) }
        if (active.size == 1) return Notification.Builder.recoverBuilder(context,
            build(active.single().state, token, active.single().id)).setGroupSummary(true).build()
        return Notification.Builder(context, ACTIVE_CHANNEL).setSmallIcon(R.drawable.ic_download)
            .setContentTitle("Aria Downloader").setContentText(if (active.isEmpty()) "Download activity · Open Aria for details" else "${active.size} active or queued downloads")
            .setContentIntent(openApp()).setGroup("downloads").setGroupSummary(true)
            .setOnlyAlertOnce(true).setOngoing(active.isNotEmpty()).setAutoCancel(active.isEmpty()).build()
    }

    private var previousStates = emptyMap<String, DownloadState>()
    private var previousToken = ""
    fun updateTasks(tasks: List<DownloadTask>, token: String) {
        if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val ids = tasks.map { it.id }.toSet()
        (previousStates.keys - ids).forEach { manager.cancel(it, 1002) }
        tasks.filter { (previousStates[it.id] != it.state || previousToken != token) && (!it.isHistory || it.id in previousStates) }
            .forEach { manager.notify(it.id, 1002, build(it.state, token, it.id)) }
        // Removing a group summary also removes its children on Android. Retain a detached
        // non-ongoing summary when the last task pauses/completes so Resume/results survive.
        if (tasks.isNotEmpty() && (tasks.any { !it.isHistory } || previousStates.values.any {
                it.canPause || it.status in setOf(DownloadStatus.EXPORTING, DownloadStatus.VERIFYING)
            })) manager.notify(ID, summary(tasks, token))
        else if (tasks.isEmpty()) manager.cancel(ID)
        previousStates = tasks.associate { it.id to it.state }
        previousToken = token
    }

    private fun openApp(taskId: String? = null, fileAction: String? = null): PendingIntent {
        val intent = Intent(appIntent)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .setData(android.net.Uri.parse("aria-download://task/${taskId.orEmpty()}/${fileAction.orEmpty()}"))
            .putExtra("taskId", taskId).putExtra("fileAction", fileAction)
        return PendingIntent.getActivity(context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun action(label: String, action: String, token: String, requestCode: Int, taskId: String?): Notification.Action {
        val intent = Intent(context, TransferActionReceiver::class.java).setAction(action)
            .setData(android.net.Uri.parse("aria-transfer://action/$token/${taskId.orEmpty()}/$action"))
            .putExtra("token", token).putExtra("taskId", taskId)
        val pending = PendingIntent.getBroadcast(context, requestCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return Notification.Action.Builder(null, label, pending).build()
    }

    private fun description(state: DownloadState): String {
        if (state.status != DownloadStatus.DOWNLOADING) return when (state.status) {
            DownloadStatus.SCHEDULED -> "Waiting to start"
            DownloadStatus.VERIFYING -> "Verifying staged files…"
            DownloadStatus.METADATA -> "Resolving metadata · Contents have not started"
            DownloadStatus.AWAITING_SELECTION -> "Review files in Aria to start downloading"
            DownloadStatus.WAITING -> state.message ?: "Waiting for network or system permission to run"
            DownloadStatus.PAUSED -> "Paused · Tap Resume to open the app"
            DownloadStatus.STAGED -> "Ready to save to Downloads"
            DownloadStatus.EXPORTING -> "Saving to Downloads…"
            DownloadStatus.SAVE_FAILED -> "Saving failed · Open the app to retry saving"
            DownloadStatus.COMPLETED -> "Saved to Downloads"
            DownloadStatus.FAILED -> state.message ?: "Download failed"
            DownloadStatus.CANCELED -> "Canceled"
            DownloadStatus.INTERRUPTED -> "Interrupted · Open the app"
            else -> "Ready"
        }
        val transferred = Formatter.formatShortFileSize(context, state.completedBytes)
        val size = if (state.totalBytes > 0) {
            "$transferred / ${Formatter.formatShortFileSize(context, state.totalBytes)}"
        } else transferred
        val speed = if (state.bytesPerSecond > 0) {
            " · ${Formatter.formatShortFileSize(context, state.bytesPerSecond)}/s"
        } else ""
        val eta = if (state.totalBytes > state.completedBytes && state.bytesPerSecond > 0) {
            val seconds = (state.totalBytes - state.completedBytes) / state.bytesPerSecond
            " · ${if (seconds >= 60) "${seconds / 60} min" else "$seconds s"} left"
        } else ""
        return size + speed + eta
    }
}

/** Scaling via Double avoids overflowing Long for large byte counts. */
internal fun progressValue(completed: Long, total: Long): Int =
    if (total <= 0) 0 else ((completed.coerceAtLeast(0).toDouble() / total) * 1000)
        .coerceIn(0.0, 1000.0).toInt()
