package com.julien_wff.aria_android.downloads

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

enum class Appearance { SYSTEM, LIGHT, DARK }

data class DownloadSettings(
    val concurrency: Int = 3,
    val connections: Int = 4,
    val speedLimit: Long = 0,
    val wifiOnly: Boolean = false,
    val appearance: Appearance = Appearance.SYSTEM,
) {
    init {
        require(concurrency in 1..6) { "Choose 1–6 simultaneous downloads." }
        require(connections in 1..16) { "Choose 1–16 connections per server." }
        require(speedLimit >= 0) { "Speed limit must be zero or positive." }
    }
}

private val Context.downloadPreferences by preferencesDataStore("download-settings")

class DownloadPreferences(context: Context) {
    private val store = context.applicationContext.downloadPreferences
    private val concurrency = intPreferencesKey("concurrency")
    private val connections = intPreferencesKey("connections")
    private val speed = longPreferencesKey("speed")
    private val wifi = booleanPreferencesKey("wifi")
    private val appearance = stringPreferencesKey("appearance")
    val settings = store.data.map {
        DownloadSettings((it[concurrency] ?: 3).coerceIn(1, 6), (it[connections] ?: 4).coerceIn(1, 16),
            (it[speed] ?: 0).coerceAtLeast(0), it[wifi] ?: false,
            Appearance.entries.find { value -> value.name == it[appearance] } ?: Appearance.SYSTEM)
    }
    suspend fun read() = settings.first()
    suspend fun save(value: DownloadSettings) {
        store.edit {
            it[concurrency] = value.concurrency
            it[connections] = value.connections
            it[speed] = value.speedLimit
            it[wifi] = value.wifiOnly
            it[appearance] = value.appearance.name
        }
    }
}
