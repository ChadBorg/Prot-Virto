package com.protvirto.capri

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale

/**
 * Her settings, as config.js keeps them on the PC (same keys, so her renderer reads them as is),
 * plus the Thor's own: where she lives, ES-DE's folder, starting on boot.
 */
class Config(context: Context) {
    private val file = File(context.filesDir, "config.json")
    private val lock = Any()
    private var data: JSONObject = load()

    private fun defaults() = JSONObject(
        """
        {
          "scale": 0.5, "muted": false, "volume": 0.7, "lang": "auto", "wander": true, "possess": false,
          "visible": true, "welcomed": true, "assistant": "claude", "claude": false, "linked": false,
          "chatModel": null, "provider": "pollinations", "apiKeys": {}, "customUrl": "",
          "userName": "", "persona": "", "myLines": [], "memories": [], "rest": "corner",
          "theme": "nightcity", "motion": "auto", "motionAsked": true, "render": "auto", "follow": false,
          "jealous": true, "closePages": false, "reminders": [], "bond": null, "pcReact": true,
          "watchScreen": false, "lookAsked": false, "breaks": true, "setups": {}, "gameClose": [], "gameMode": null,

          "startOnBoot": true, "screen": "bottom", "esdePath": "/storage/emulated/0/ES-DE",
          "ollamaUrl": "http://127.0.0.1:11434", "running": true,
          "sass": "normal", "quietHours": false, "batteryWarn": true, "shake": true, "aiLines": true
        }
        """.trimIndent()
    )

    private fun load(): JSONObject {
        val base = defaults()
        runCatching { JSONObject(file.readText()) }.getOrNull()?.let { saved ->
            saved.keys().forEach { base.put(it, saved.get(it)) }
        }
        return base
    }

    fun get(): JSONObject = synchronized(lock) { JSONObject(data.toString()) }

    fun string(key: String, def: String = ""): String = synchronized(lock) { data.optString(key, def).let { if (it == "null") def else it } }
    fun bool(key: String, def: Boolean = false): Boolean = synchronized(lock) { data.optBoolean(key, def) }

    fun apiKey(provider: String): String = synchronized(lock) { data.optJSONObject("apiKeys")?.optString(provider).orEmpty() }

    fun update(patch: JSONObject): JSONObject = synchronized(lock) {
        patch.keys().forEach { data.put(it, patch.get(it)) }
        runCatching {
            val tmp = File(file.parentFile, "config.json.tmp")
            tmp.writeText(data.toString(2))
            tmp.renameTo(file)
        }
        JSONObject(data.toString())
    }

    fun put(key: String, value: Any?) = update(JSONObject().put(key, value ?: JSONObject.NULL))

    /** What the overlay gets: the keys stay here, it only learns which providers have one. */
    fun renderer(): JSONObject {
        val cfg = get()
        val keys = cfg.optJSONObject("apiKeys") ?: JSONObject()
        cfg.remove("apiKeys")
        cfg.put("keys", JSONArray(keys.keys().asSequence().filter { keys.optString(it).isNotBlank() }.toList()))
        val setting = cfg.optString("lang", "auto")
        cfg.put("langSetting", setting)
        cfg.put("lang", resolveLang(setting))
        cfg.put("floorGap", 0)
        cfg.put("assistant", "claude")
        cfg.put("claude", false)
        return cfg
    }

    companion object {
        val LANGS = listOf("fr", "en", "es", "pt", "ru")
        fun resolveLang(setting: String): String =
            if (setting in LANGS) setting else Locale.getDefault().language.takeIf { it in LANGS } ?: "en"
    }
}
