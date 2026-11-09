package com.protvirto.capri

import android.app.ActivityManager
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.webkit.JavascriptInterface
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder
import java.util.concurrent.Executors

/**
 * `window.host` (android.js) lands here: the same calls her renderer made to Electron's main
 * process on the PC, answered the Android way.
 */
class Bridge(private val service: CapriService) {
    private val pool = Executors.newFixedThreadPool(4) { r -> Thread(r, "capri-bridge") }
    private val config get() = service.config
    private val llm get() = service.llm
    private val apps get() = service.apps
    private val memory = File(service.filesDir, "memory.json")

    @JavascriptInterface
    fun call(method: String, args: String, id: Int) {
        pool.execute {
            val a = runCatching { JSONArray(args) }.getOrDefault(JSONArray())
            val result = try {
                handle(method, a)
            } catch (e: Exception) {
                Log.w(TAG, "$method failed", e)
                null
            }
            service.overlay?.resolve(id, result)
        }
    }

    private fun handle(method: String, a: JSONArray): Any? = when (method) {
        "getConfig" -> config.renderer()
        "setConfig" -> {
            config.update(a.optJSONObject(0) ?: JSONObject())
            config.renderer().also { service.overlay?.emit("capri:config", it) }
        }
        "screens" -> onMain { service.overlay?.screens() }
        "moveScreen" -> onMain { service.overlay?.moveTo(a.optInt(0)) }?.also { service.displayChanged() }
        "otherScreen" -> onMain { service.overlay?.otherDisplay()?.displayId }
        "goScreen" -> { service.command("goScreen", JSONObject().put("id", a.optInt(0))); true }
        "windowsNow" -> service.watch?.lastWindows
        "openMenu" -> { service.openMenu(); null }
        "hitRects" -> {
            val rects = a.optJSONArray(0) ?: JSONArray()
            val dpr = a.optDouble(1, 1.0)
            service.main.post { service.overlay?.setHitRects(rects, dpr) }
            null
        }
        "focusOverlay" -> { service.main.post { service.overlay?.setFocus(a.optBoolean(0)) }; null }
        "thorReady" -> { service.watch?.replay(); service.displayChanged(); true }
        "cpu" -> null // Android keeps /proc/stat to itself: the heat comes from the thermal status instead
        "idle" -> (SystemClock.elapsedRealtime() - (service.watch?.lastActive ?: SystemClock.elapsedRealtime())) / 1000
        "pcDo" -> JSONObject().put("ok", false).put("error", "On the Thor, Android handles that one itself.")

        // ---- her chat ----
        "chatStatus" -> chatStatus()
        "chatSend" -> chatSend(a.optJSONObject(0) ?: JSONObject())
        "chatStop" -> { llm.stop(a.optString(0)); null }
        "chatMemory" -> runCatching { JSONObject(memory.readText()).optJSONArray("history") }.getOrNull() ?: JSONArray()
        "chatRemember" -> { a.optJSONArray(0)?.let { memory.writeText(JSONObject().put("history", it).toString()) }; null }
        "lookWhere" -> JSONObject().put("local", llm.isLocal()).put("provider", llm.provider(config.string("provider"))?.label).put("model", config.string("chatModel"))

        // ---- setting up her AI ----
        "setupInfo" -> JSONObject()
            .put("models", JSONArray().put(JSONObject().put("name", "gemma3:4b").put("bytes", 3.3e9)).put(JSONObject().put("name", "llama3.2").put("bytes", 2.0e9)))
            .put("cloud", JSONArray())
            .put("ram", (service.getSystemService(ActivityManager::class.java)?.let { am -> ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }.totalMem } ?: 0L))
            .put("keyed", JSONArray(Llm.KEY_PAGES.keys.toList()))
        "ollamaState" -> ollamaState()
        "installOllama" -> {
            // no winget here: Termux it is (or Ollama on your PC). She points you to it.
            apps.open("https://github.com/termux/termux-app/releases")
            JSONObject().put("ok", false).put("error", "On the Thor: install Termux, run `pkg install ollama` then `ollama serve` — or set your PC's Ollama address in the Capri app.")
        }
        "pullModel" -> pullModel(a.optString(0))
        "cancelSetup" -> { llm.pulling?.cancel(); null }
        "useProvider" -> useProvider(a.optJSONObject(0) ?: JSONObject())
        "keyPage" -> { Llm.KEY_PAGES[a.optString(0)]?.let { apps.open(it) }; null }

        // ---- opening things ----
        "openApps" -> JSONArray()
        "findApp", "findLaunch" -> apps.find(a.optString(0))
        "launch" -> apps.launch(a.optJSONObject(0) ?: JSONObject())
        "media" -> media(a.optString(0))
        "music" -> music(a.optString(0))
        "youtube" -> { apps.open("https://www.youtube.com/results?search_query=${enc(a.optString(0))}"); JSONObject() }
        "roblox" -> {
            val q = a.optString(0)
            apps.open("https://www.roblox.com/discover/?Keyword=${enc(q)}")
            JSONObject().put("name", q).put("app", apps.installed("com.roblox.client"))
        }
        "webSearch" -> apps.open("https://www.google.com/search?q=${enc(a.optString(0))}")
        "openUrl" -> {
            val u = a.optString(0).let { if (Regex("^[a-z]+://").containsMatchIn(it)) it else "https://$it" }
            apps.open(u)
        }
        else -> null
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun <T> onMain(block: () -> T): T? {
        val latch = java.util.concurrent.CountDownLatch(1)
        var out: T? = null
        service.main.post {
            out = runCatching(block).getOrNull()
            latch.countDown()
        }
        latch.await(3, java.util.concurrent.TimeUnit.SECONDS)
        return out
    }

    // ---- chat --------------------------------------------------------------------------------------
    private fun chatStatus(): JSONObject {
        val provider = config.string("provider", "pollinations")
        return try {
            val models = llm.models(provider)
            if (models.isEmpty()) return JSONObject().put("ok", false).put("error", "nomodel").put("provider", provider)
            val model = llm.pickModel(models)
            JSONObject().put("ok", true).put("provider", provider).put("model", model)
                .put("agent", false).put("local", llm.isLocal()).put("warmup", false)
        } catch (e: Llm.LlmError) {
            JSONObject().put("ok", false).put("error", e.message).put("code", e.code ?: JSONObject.NULL).put("provider", provider)
        } catch (e: Exception) {
            JSONObject().put("ok", false).put("error", "offline").put("provider", provider)
        }
    }

    private fun chatSend(req: JSONObject): JSONObject {
        val id = req.optString("id")
        val messages = req.optJSONArray("messages") ?: JSONArray()
        return try {
            val provider = config.string("provider", "pollinations")
            val model = llm.pickModel(runCatching { llm.models(provider) }.getOrDefault(emptyList()))
                ?: config.string("chatModel").ifBlank { "openai" }
            val text = llm.chat(id, messages, model) { piece ->
                service.overlay?.emit("chat:delta", JSONObject().put("id", id).put("text", piece))
            }
            JSONObject().put("ok", true).put("text", text)
        } catch (e: Llm.LlmError) {
            JSONObject().put("ok", false).put("error", e.message).put("code", e.code ?: JSONObject.NULL)
        } catch (e: java.io.IOException) {
            JSONObject().put("ok", false).put("error", if (e.message == "Canceled") "stopped" else (e.message ?: "offline"))
        }
    }

    private fun ollamaState(): JSONObject = try {
        val models = llm.models("ollama")
        JSONObject().put("running", true).put("installed", true).put("models", JSONArray(models)).put("winget", false)
    } catch (e: Exception) {
        JSONObject().put("running", false).put("installed", apps.installed("com.termux")).put("models", JSONObject.NULL).put("winget", false)
    }

    private fun pullModel(name: String): JSONObject = try {
        llm.pull(name) { done, total ->
            service.overlay?.emit("setup:progress", JSONObject().put("kind", "pull").put("completed", done).put("total", total))
        }
        config.update(JSONObject().put("provider", "ollama").put("chatModel", name))
        JSONObject().put("ok", true).put("model", name)
    } catch (e: Exception) {
        JSONObject().put("ok", false).put("error", if (e.message == "Canceled") "stopped" else e.message)
    }

    private fun useProvider(choice: JSONObject): JSONObject {
        val provider = choice.optString("provider")
        if (Llm.PROVIDERS[provider] == null) return JSONObject().put("ok", false).put("error", "unknown provider")
        val before = config.get()
        val patch = JSONObject().put("provider", provider)
        choice.optString("key").takeIf { it.isNotBlank() }?.let {
            patch.put("apiKeys", (before.optJSONObject("apiKeys") ?: JSONObject()).put(provider, it.trim()))
        }
        choice.optString("url").takeIf { it.isNotBlank() }?.let { patch.put("customUrl", it.trim()) }
        config.update(patch)
        return try {
            val models = llm.models(provider)
            if (models.isEmpty()) throw Llm.LlmError("no model")
            service.overlay?.emit("capri:config", config.renderer())
            JSONObject().put("ok", true).put("model", llm.pickModel(models))
        } catch (e: Exception) {
            config.update(
                JSONObject().put("provider", before.optString("provider"))
                    .put("apiKeys", before.optJSONObject("apiKeys") ?: JSONObject())
                    .put("customUrl", before.optString("customUrl"))
            )
            JSONObject().put("ok", false).put("error", e.message ?: "offline")
        }
    }

    // ---- media ----------------------------------------------------------------------------------------
    private fun media(action: String): Boolean {
        val am = service.getSystemService(AudioManager::class.java) ?: return false
        fun key(code: Int) {
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
        }
        when (action) {
            "pause" -> key(KeyEvent.KEYCODE_MEDIA_PAUSE)
            "play" -> key(KeyEvent.KEYCODE_MEDIA_PLAY)
            "next" -> key(KeyEvent.KEYCODE_MEDIA_NEXT)
            "previous" -> key(KeyEvent.KEYCODE_MEDIA_PREVIOUS)
            "volup" -> am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
            "voldown" -> am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
            "mute" -> am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_TOGGLE_MUTE, AudioManager.FLAG_SHOW_UI)
            else -> return false
        }
        return true
    }

    private fun music(query: String): Boolean {
        if (apps.installed("com.spotify.music")) {
            return apps.start(Intent(Intent.ACTION_VIEW, Uri.parse("spotify:search:${Uri.encode(query)}"))).optBoolean("ok")
        }
        return apps.open("https://www.youtube.com/results?search_query=${enc(query)}")
    }

    fun shutdown() {
        pool.shutdownNow()
    }

    companion object {
        private const val TAG = "CapriBridge"
    }
}
