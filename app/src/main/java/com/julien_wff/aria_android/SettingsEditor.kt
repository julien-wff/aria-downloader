package com.julien_wff.aria_android

import com.julien_wff.aria_android.downloads.DownloadSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal enum class SettingsSaveStatus { LOADING, SAVED, PENDING, ERROR }

internal data class SettingsEditorState(
    val settings: DownloadSettings = DownloadSettings(),
    val speed: String = "0",
    val status: SettingsSaveStatus = SettingsSaveStatus.LOADING,
    val message: String? = null,
) {
    val validSpeed: Boolean get() = speed.toLongOrNull()?.let { it >= 0 } == true
}

/** One writer survives screen disposal and never cancels a save already applying to the queue. */
internal class SettingsEditor(
    private val scope: CoroutineScope,
    private val read: suspend () -> DownloadSettings,
    private val persist: suspend (DownloadSettings) -> Unit,
) {
    private val mutableState = MutableStateFlow(SettingsEditorState())
    val state = mutableState.asStateFlow()
    private val writes = Channel<Unit>(Channel.CONFLATED)
    private var speedJob: Job? = null
    private var revision = 0L
    private var loaded = false
    private var appliedRevision = 0L
    private var speedPending = false

    init {
        scope.launch {
            load()
            for (ignored in writes) {
                if (!loaded) { load(); continue }
                val version = revision
                val value = mutableState.value.settings
                try {
                    persist(value)
                    appliedRevision = version
                    if (version == revision) mutableState.value = mutableState.value.copy(
                        status = if (speedPending) SettingsSaveStatus.PENDING else SettingsSaveStatus.SAVED,
                        message = null,
                    )
                } catch (canceled: CancellationException) {
                    throw canceled
                } catch (_: Exception) {
                    if (version == revision) mutableState.value = mutableState.value.copy(
                        status = SettingsSaveStatus.ERROR,
                        message = "Couldn't save or apply settings. Your changes are kept. Try again.",
                    )
                }
            }
        }
    }

    private suspend fun load() {
        try {
            val settings = read()
            loaded = true
            mutableState.value = SettingsEditorState(settings, settings.speedLimit.toString(), SettingsSaveStatus.SAVED)
        } catch (canceled: CancellationException) {
            throw canceled
        } catch (_: Exception) {
            mutableState.value = mutableState.value.copy(status = SettingsSaveStatus.ERROR,
                message = "Couldn't load settings. Retry before making changes.")
        }
    }

    fun update(change: (DownloadSettings) -> DownloadSettings) {
        if (!loaded) return
        val value = change(mutableState.value.settings)
        if (value == mutableState.value.settings) return
        revision++
        mutableState.value = mutableState.value.copy(settings = value, status = SettingsSaveStatus.PENDING, message = null)
        writes.trySend(Unit)
    }

    fun editSpeed(text: String) {
        if (!loaded) return
        speedJob?.cancel()
        speedPending = text.toLongOrNull()?.let { it >= 0 && it != mutableState.value.settings.speedLimit } == true
        mutableState.value = mutableState.value.copy(speed = text,
            status = when {
                !speedPending && mutableState.value.message != null -> SettingsSaveStatus.ERROR
                speedPending || revision != appliedRevision -> SettingsSaveStatus.PENDING
                else -> SettingsSaveStatus.SAVED
            })
        if (speedPending) speedJob = scope.launch { delay(400); commitSpeed() }
    }

    fun commitSpeed() {
        speedJob?.cancel()
        speedJob = null
        speedPending = false
        val value = mutableState.value.speed.toLongOrNull()?.takeIf { it >= 0 } ?: return
        update { it.copy(speedLimit = value) }
        if (mutableState.value.status == SettingsSaveStatus.PENDING && revision == appliedRevision)
            mutableState.value = mutableState.value.copy(status = SettingsSaveStatus.SAVED)
    }

    fun retry() {
        commitSpeed()
        mutableState.value = mutableState.value.copy(status = SettingsSaveStatus.PENDING, message = null)
        writes.trySend(Unit)
    }

    val editable: Boolean get() = loaded
}
