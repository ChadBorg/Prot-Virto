package com.protvirto.capri

import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Who she talks with (llm.js, ported): Ollama (in Termux on the Thor, or on your PC over
 * LAN/Tailscale), a keyless gateway (Pollinations), or a provider with a key.
 * Everything speaks the OpenAI chat API with streaming.
 */
class Llm(private val config: Config) {
    data class Provider(val label: String, val url: String = "", val local: Boolean = false, val needsKey: Boolean = false, val modelsUrl: String? = null)

    class LlmError(message: String, val code: String? = null) : IOException(message)

    private val http = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()
    private val quick = http.newBuilder().readTimeout(8, TimeUnit.SECONDS).build()
    private val running = ConcurrentHashMap<String, Call>()
    @Volatile var pulling: Call? = null

    fun provider(id: String): Provider? = PROVIDERS[id]

    private fun base(id: String): String = when (id) {
        "ollama" -> config.string("ollamaUrl", "http://127.0.0.1:11434").trimEnd('/') + "/v1"
        "custom" -> config.string("customUrl").trimEnd('/')
        else -> PROVIDERS[id]?.url.orEmpty()
    }

    private fun request(url: String, key: String) = Request.Builder().url(url).apply {
        if (key.isNotBlank()) header("Authorization", "Bearer $key")
    }

    private fun failure(code: Int, body: String): LlmError {
        var message = body.take(200).ifBlank { "answered $code" }
        runCatching {
            val parsed = if (body.trimStart().startsWith("[")) JSONArray(body).getJSONObject(0) else JSONObject(body)
            val err = parsed.opt("error")
            message = when (err) {
                is String -> err
                is JSONObject -> err.optString("message", message)
                else -> message
            }
        }
        val c = when {
            code == 429 || code == 503 || Regex("overloaded|high demand|unavailable", RegexOption.IGNORE_CASE).containsMatchIn(message) -> "busy"
            code == 404 -> "gone"
            code == 401 || code == 403 -> "nokey"
            else -> null
        }
        return LlmError(message, c)
    }

    /** The provider's models; throws LlmError (code nokey/nourl/offline) when it can't say. */
    fun models(id: String): List<String> {
        val p = PROVIDERS[id] ?: throw LlmError("unknown provider")
        if (id == "ollama") {
            val url = config.string("ollamaUrl", "http://127.0.0.1:11434").trimEnd('/') + "/api/tags"
            try {
                quick.newCall(Request.Builder().url(url).build()).execute().use { r ->
                    if (!r.isSuccessful) throw failure(r.code, r.body?.string().orEmpty())
                    val arr = JSONObject(r.body!!.string()).optJSONArray("models") ?: JSONArray()
                    return (0 until arr.length()).map { arr.getJSONObject(it).optString("name") }.filter { it.isNotBlank() }
                }
            } catch (e: LlmError) {
                throw e
            } catch (e: IOException) {
                throw LlmError("offline", "offline")
            }
        }
        val base = base(id)
        if (base.isBlank()) throw LlmError("no address set for this server", "nourl")
        val key = config.apiKey(id)
        if (p.needsKey && key.isBlank()) throw LlmError("no API key", "nokey")
        quick.newCall(request(p.modelsUrl ?: "$base/models", key).build()).execute().use { r ->
            val body = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw failure(r.code, body)
            val list: JSONArray = if (body.trimStart().startsWith("[")) JSONArray(body)
            else JSONObject(body).let { it.optJSONArray("data") ?: it.optJSONArray("models") ?: JSONArray() }
            val names = (0 until list.length()).mapNotNull {
                when (val m = list.get(it)) {
                    is String -> m
                    is JSONObject -> m.optString("id").ifBlank { m.optString("name") }.ifBlank { null }
                    else -> null
                }
            }
            return if (id == "gemini") names.filter { "gemini" in it && !Regex("embed|tts|image|audio|live|veo|imagen|robotics|aqa").containsMatchIn(it) }
                .sortedBy { (if (Regex("preview|exp").containsMatchIn(it)) 4 else 0) + (if ("flash" in it) 0 else 2) }
            // only models that can chat: Groq's list starts with speech ones (whisper-large-v3-turbo was picked
            // on the Thor: "does not support chat completions")
            else names.filterNot { NOT_CHAT.containsMatchIn(it) }
        }
    }

    fun pickModel(models: List<String>): String? {
        val wanted = config.string("chatModel")
        if (wanted.isNotBlank() && wanted in models) return wanted
        val provider = config.string("provider", "pollinations")
        val preferred = when (provider) {
            "pollinations" -> listOf("openai", "openai-fast", "mistral")
            "groq" -> listOf("llama-3.3-70b-versatile", "llama-3.1-8b-instant")
            "openai" -> listOf("gpt-4o-mini", "gpt-4.1-mini")
            "openrouter" -> listOf("meta-llama/llama-3.3-70b-instruct:free")
            else -> emptyList()
        }
        val chat = models.filterNot { NOT_CHAT.containsMatchIn(it) }
        // exact favourites first; else by family (exact names get retired), else the first that can chat
        return preferred.firstOrNull { it in chat }
            ?: chat.minByOrNull { m -> GOOD_CHAT.indexOfFirst { it.containsMatchIn(m) }.let { if (it < 0) GOOD_CHAT.size else it } }
    }

    fun isLocal(): Boolean {
        val provider = config.string("provider")
        if (provider == "ollama") return Regex("127\\.0\\.0\\.1|localhost").containsMatchIn(config.string("ollamaUrl"))
        return PROVIDERS[provider]?.local == true
    }

    /** OpenAI-style streaming chat: onDelta(piece) as it arrives, returns the whole text. */
    fun chat(id: String, messages: JSONArray, model: String, onDelta: (String) -> Unit): String {
        val provider = config.string("provider", "pollinations")
        val base = base(provider)
        val body = JSONObject().put("model", model).put("messages", messages).put("stream", true)
        val call = http.newCall(
            request("$base/chat/completions", config.apiKey(provider))
                .post(body.toString().toRequestBody(JSON))
                .build()
        )
        running[id] = call
        try {
            call.execute().use { r ->
                if (!r.isSuccessful) throw failure(r.code, r.body?.string().orEmpty())
                val source = r.body!!.source()
                val text = StringBuilder()
                while (!source.exhausted()) {
                    val line = source.readUtf8Line()?.trim() ?: break
                    if (!line.startsWith("data:")) continue
                    val data = line.removePrefix("data:").trim()
                    if (data == "[DONE]") break
                    val msg = runCatching { JSONObject(data) }.getOrNull() ?: continue
                    msg.optJSONObject("error")?.let { throw LlmError(it.optString("message", "error")) }
                    val piece = msg.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("delta")?.optString("content").orEmpty()
                    if (piece.isNotEmpty() && piece != "null") {
                        text.append(piece)
                        onDelta(piece)
                    }
                }
                return text.toString()
            }
        } finally {
            running.remove(id)
        }
    }

    fun stop(id: String) {
        running.remove(id)?.cancel()
    }

    /** Ollama: fetches a model (on the Thor in Termux, or on your PC), with its progress. */
    fun pull(name: String, onProgress: (Long, Long) -> Unit) {
        val url = config.string("ollamaUrl", "http://127.0.0.1:11434").trimEnd('/') + "/api/pull"
        val call = http.newBuilder().readTimeout(0, TimeUnit.SECONDS).build()
            .newCall(Request.Builder().url(url).post(JSONObject().put("model", name).put("stream", true).toString().toRequestBody(JSON)).build())
        pulling = call
        try {
            call.execute().use { r ->
                if (!r.isSuccessful) throw failure(r.code, r.body?.string().orEmpty())
                val source = r.body!!.source()
                while (!source.exhausted()) {
                    val line = source.readUtf8Line() ?: break
                    val msg = runCatching { JSONObject(line) }.getOrNull() ?: continue
                    if (msg.has("error")) throw LlmError(msg.optString("error"))
                    val total = msg.optLong("total")
                    if (total > 0) onProgress(msg.optLong("completed"), total)
                }
            }
        } finally {
            pulling = null
        }
    }

    companion object {
        private val JSON = "application/json".toMediaType()

        // models in a provider's list that can't chat: speech-to-text, text-to-speech, moderation, embeddings
        val NOT_CHAT = Regex("whisper|tts|playai|orpheus|guard|embed|moderation|audio|transcri|speech|image|vision-only|rerank", RegexOption.IGNORE_CASE)

        // good chat models by family, best first (exact names get retired); fast non-reasoning ones lead,
        // since her remarks have to come in a few seconds (gpt-oss thinks before it answers)
        val GOOD_CHAT = listOf(
            Regex("llama-?4-maverick|llama-?3\\.3-70b", RegexOption.IGNORE_CASE),
            Regex("kimi|llama-?4", RegexOption.IGNORE_CASE),
            Regex("qwen3?-?32b|70b", RegexOption.IGNORE_CASE),
            Regex("gpt-oss-120b|deepseek", RegexOption.IGNORE_CASE),
            Regex("gpt-oss|llama|qwen|gemma|mistral|mixtral", RegexOption.IGNORE_CASE),
        )

        val PROVIDERS = linkedMapOf(
            "pollinations" to Provider("Pollinations (no key)", "https://text.pollinations.ai/openai", modelsUrl = "https://text.pollinations.ai/models"),
            "ollama" to Provider("Ollama (Termux on the Thor, or your PC)", local = true),
            "groq" to Provider("Groq", "https://api.groq.com/openai/v1", needsKey = true),
            "gemini" to Provider("Google Gemini", "https://generativelanguage.googleapis.com/v1beta/openai", needsKey = true),
            "openrouter" to Provider("OpenRouter", "https://openrouter.ai/api/v1", needsKey = true),
            "openai" to Provider("OpenAI", "https://api.openai.com/v1", needsKey = true),
            "mistral" to Provider("Mistral", "https://api.mistral.ai/v1", needsKey = true),
            "deepseek" to Provider("DeepSeek", "https://api.deepseek.com/v1", needsKey = true),
            "custom" to Provider("Custom (OpenAI-compatible address)"),
        )

        val KEY_PAGES = mapOf(
            "groq" to "https://console.groq.com/keys",
            "gemini" to "https://aistudio.google.com/apikey",
            "openrouter" to "https://openrouter.ai/keys",
            "openai" to "https://platform.openai.com/api-keys",
            "mistral" to "https://console.mistral.ai/api-keys",
            "deepseek" to "https://platform.deepseek.com/api_keys",
        )
    }
}
