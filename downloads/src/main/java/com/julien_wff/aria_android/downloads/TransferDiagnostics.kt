package com.julien_wff.aria_android.downloads

import android.app.job.JobParameters

internal fun failureCategory(code: Int?): String = when (code) {
    2, 5, 6, 19 -> "NETWORK"
    29 -> "SERVER_BUSY"
    3, 4 -> "NOT_FOUND"
    24 -> "AUTHENTICATION"
    8, 10 -> "RESUME_REJECTED"
    9, 13, 14, 15, 16, 17, 18 -> "STORAGE"
    32 -> "INTEGRITY"
    else -> "TRANSFER"
}

internal fun failureAdvice(code: Int?): String = when (failureCategory(code)) {
    "NETWORK" -> "Connection failed. Check your network and Retry."
    "SERVER_BUSY" -> "The server is busy. Try again later."
    "NOT_FOUND" -> "The file was not found. Check the download link."
    "AUTHENTICATION" -> "The server refused access. Check the download link and access requirements."
    "RESUME_REJECTED" -> "The server rejected resume or the resource changed. Partial data is preserved; Retry starts a fresh transfer."
    "STORAGE" -> "Unable to write the file. Check available storage; partial data is preserved."
    "INTEGRITY" -> "File verification failed. Retry with a trusted source."
    else -> "Transfer failed. Check the link and secure connection, then Retry."
}

/** Allowlisted structured fields only: never serialize requests or exception messages. */
fun transferDiagnostics(state: DownloadState, appVersion: String, apiLevel: Int): String = buildString {
    appendLine("Aria Downloader diagnostics")
    // Package version is constrained too, so callers cannot accidentally inject free text.
    appendLine("App: ${appVersion.filter { it.isDigit() || it in ".-" }.take(40)}")
    appendLine("Android API: $apiLevel")
    appendLine("Status: ${state.status.name}")
    appendLine("Bytes: ${state.completedBytes}/${state.totalBytes}")
    appendLine("Automatic retries: ${state.retryCount}/3")
    appendLine("Retry pending: ${state.retryAt > 0}")
    appendLine("Failure category: ${state.engineCode?.let(::failureCategory) ?: "NONE"}")
    appendLine("Engine code: ${state.engineCode ?: "NONE"}")
    appendLine("Android stop reason: ${state.stopReason ?: "UNKNOWN"}")
}

internal fun interruptionAdvice(reason: Int?, wifiOnly: Boolean): String = when (reason) {
    JobParameters.STOP_REASON_CONSTRAINT_CONNECTIVITY ->
        if (wifiOnly) "Waiting for unmetered Wi-Fi." else "Waiting for a network connection."
    JobParameters.STOP_REASON_DEVICE_STATE -> "Android paused the transfer for device health. Resume or wait for Android."
    JobParameters.STOP_REASON_BACKGROUND_RESTRICTION, JobParameters.STOP_REASON_APP_STANDBY ->
        "Android restricted background activity. Open the app to Resume."
    JobParameters.STOP_REASON_TIMEOUT, JobParameters.STOP_REASON_TIMEOUT_ABANDONED ->
        "Android ended this execution window. Resume or wait for Android."
    JobParameters.STOP_REASON_CONSTRAINT_STORAGE_NOT_LOW -> "Waiting for available device storage."
    JobParameters.STOP_REASON_USER -> "Stopped by the user. Resume to continue."
    else -> "Android interrupted the transfer. Resume or wait for Android to allow it."
}
