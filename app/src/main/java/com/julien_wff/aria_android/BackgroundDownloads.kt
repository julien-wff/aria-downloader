package com.julien_wff.aria_android

import android.app.ActivityManager
import android.app.job.JobParameters
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.semantics
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.julien_wff.aria_android.downloads.DownloadState
import com.julien_wff.aria_android.downloads.DownloadStatus

internal data class BackgroundDownloadStatus(
    val batteryExempt: Boolean,
    val backgroundRestricted: Boolean,
    val batterySaver: Boolean,
)

internal fun readBackgroundDownloadStatus(context: Context): BackgroundDownloadStatus {
    val power = context.getSystemService(PowerManager::class.java)
    return BackgroundDownloadStatus(
        power.isIgnoringBatteryOptimizations(context.packageName),
        context.getSystemService(ActivityManager::class.java).isBackgroundRestricted,
        power.isPowerSaveMode,
    )
}

@Composable
internal fun rememberBackgroundDownloadStatus(
    read: () -> BackgroundDownloadStatus = LocalContext.current.let { context ->
        { readBackgroundDownloadStatus(context) }
    },
): BackgroundDownloadStatus {
    var status by remember { mutableStateOf(read()) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { status = read() }
    return status
}

internal fun backgroundDownloadNotice(status: BackgroundDownloadStatus, states: List<DownloadState>): String? = when {
    status.backgroundRestricted -> "Android restricts Aria’s background activity. Review Background downloads settings to reduce interruptions."
    states.any { it.status in setOf(DownloadStatus.WAITING, DownloadStatus.INTERRUPTED) &&
        it.stopReason in setOf(JobParameters.STOP_REASON_BACKGROUND_RESTRICTION, JobParameters.STOP_REASON_APP_STANDBY) } ->
        "Android previously interrupted a download because of background restrictions. Review Background downloads settings."
    else -> null
}

/** Try public settings screens in order; devices may omit or protect individual activities. */
internal fun openBackgroundSettings(battery: Boolean, launch: (Intent) -> Unit, packageName: String): Boolean {
    val appSettings = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
    val intents = if (battery) listOf(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS), appSettings, Intent(Settings.ACTION_SETTINGS))
        else listOf(appSettings, Intent(Settings.ACTION_SETTINGS))
    for (intent in intents) {
        try {
            launch(intent)
            return true
        } catch (_: ActivityNotFoundException) {
            // Fall through to the next public settings screen.
        } catch (_: SecurityException) {
            // Some device builds protect otherwise public settings screens.
        }
    }
    return false
}

@Composable
internal fun BackgroundDownloadsSettings(
    status: BackgroundDownloadStatus,
    openSettings: (Boolean) -> Boolean,
) {
    var unavailable by remember { mutableStateOf(false) }
    Section("Background downloads", R.drawable.ic_download) {
        BackgroundStatusRow("Battery optimization", if (status.batteryExempt) "Exempt" else "Enabled")
        BackgroundStatusRow("Background activity", if (status.backgroundRestricted) "Restricted" else "Allowed",
            warning = status.backgroundRestricted)
        BackgroundStatusRow("Battery Saver", if (status.batterySaver) "On" else "Off")
        HorizontalDivider()
        Text("If downloads stop, allow unrestricted battery use in Android settings.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            FilledTonalButton(onClick = { unavailable = !openSettings(true) }) { Text("Battery settings") }
            OutlinedButton(onClick = { unavailable = !openSettings(false) }) { Text("App settings") }
        }
        if (unavailable) Text("Open Android Settings → Apps → Aria manually.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    }
}

@Composable
private fun BackgroundStatusRow(label: String, value: String, warning: Boolean = false) {
    Row(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {},
        horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Surface(shape = MaterialTheme.shapes.small,
            color = if (warning) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceContainerHighest,
            contentColor = if (warning) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurfaceVariant) {
            Text(value, Modifier.padding(horizontal = 10.dp, vertical = 4.dp), style = MaterialTheme.typography.labelMedium)
        }
    }
}
