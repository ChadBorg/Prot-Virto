package com.protvirto.redline.data

import android.content.Context
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("redline", Context.MODE_PRIVATE)

    fun hosts(): List<SavedHost> = sp.getString(KEY_HOSTS, null)?.let {
        runCatching { RedlineJson.decodeFromString<List<SavedHost>>(it) }.getOrNull()
    } ?: emptyList()

    fun saveHosts(hosts: List<SavedHost>) {
        sp.edit().putString(KEY_HOSTS, RedlineJson.encodeToString(hosts)).apply()
    }

    var activeHostId: String?
        get() = sp.getString(KEY_ACTIVE, null)
        set(value) = sp.edit().putString(KEY_ACTIVE, value).apply()

    var streamSettings: StreamSettings
        get() = sp.getString(KEY_SETTINGS, null)?.let {
            runCatching { RedlineJson.decodeFromString<StreamSettings>(it) }.getOrNull()
        } ?: StreamSettings()
        set(value) = sp.edit().putString(KEY_SETTINGS, RedlineJson.encodeToString(value)).apply()

    private companion object {
        const val KEY_HOSTS = "hosts"
        const val KEY_ACTIVE = "active_host"
        const val KEY_SETTINGS = "stream_settings"
    }
}
