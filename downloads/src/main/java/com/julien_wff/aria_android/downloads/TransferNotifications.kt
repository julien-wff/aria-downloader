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

    fun build(state: DownloadState, token: String): Notification {
        val active = state.canPause
        val builder = Notification.Builder(context, if (active) ACTIVE_CHANNEL else RESULT_CHANNEL)
            .setSmallIcon(R.drawable.ic_download)
            .setContentTitle(state.fileName)
            .setContentText(description(state))
            .setContentIntent(openApp())
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
            val unknown = state.totalBytes <= 0 || state.status != DownloadStatus.DOWNLOADING
            val progress = progressValue(state.completedBytes, state.totalBytes)
            if (Build.VERSION.SDK_INT >= 36) {
                builder.setStyle(Notification.ProgressStyle()
                    .setProgressSegments(listOf(Notification.ProgressStyle.Segment(1000)))
                    .setProgress(progress)
                    .setProgressIndeterminate(unknown)
                    .setStyledByProgress(true))
            } else {
                builder.setProgress(1000, progress, unknown)
            }
            builder.addAction(action("Pause", PAUSE, token, 1))
                .addAction(action("Cancel", CANCEL, token, 2))
        } else if (state.canResume) {
            builder.addAction(Notification.Action.Builder(null, "Resume", openApp()).build())
        }
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

    private fun openApp(): PendingIntent {
        val intent = Intent(appIntent)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun action(label: String, action: String, token: String, requestCode: Int): Notification.Action {
        val intent = Intent(context, TransferActionReceiver::class.java).setAction(action)
            .setData(android.net.Uri.parse("aria-transfer://action/$token/$action"))
            .putExtra("token", token)
        val pending = PendingIntent.getBroadcast(context, requestCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return Notification.Action.Builder(null, label, pending).build()
    }

    private fun description(state: DownloadState): String {
        if (state.status != DownloadStatus.DOWNLOADING) return when (state.status) {
            DownloadStatus.SCHEDULED -> "Waiting to start"
            DownloadStatus.WAITING -> "Waiting for network or system permission to run"
            DownloadStatus.PAUSED -> "Paused · Tap Resume to open the app"
            DownloadStatus.COMPLETED -> "Downloaded to private staging"
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
