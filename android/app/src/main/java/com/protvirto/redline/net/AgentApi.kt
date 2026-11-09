package com.protvirto.redline.net

import com.protvirto.redline.data.ActionResult
import com.protvirto.redline.data.FrameGenState
import com.protvirto.redline.data.GamesResponse
import com.protvirto.redline.data.HostInfo
import com.protvirto.redline.data.HostStatus
import com.protvirto.redline.data.PairResponse
import com.protvirto.redline.data.RedlineJson
import com.protvirto.redline.data.SavedHost
import com.protvirto.redline.data.StoreResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class ApiException(message: String) : Exception(message)

/** Talks to the REDLINE agent running on the PC. */
class AgentApi {
    private val client = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
    private val probeClient = client.newBuilder()
        .connectTimeout(1500, TimeUnit.MILLISECONDS)
        .readTimeout(2, TimeUnit.SECONDS)
        .build()

    /** host id -> the address that answered last (LAN or remote). */
    private val routes = ConcurrentHashMap<String, String>()

    fun baseUrl(address: String, port: Int): String {
        val a = address.trim()
        val hostPart = if (a.contains(':') && !a.startsWith("[")) "[$a]" else a
        return "http://$hostPart:$port"
    }

    /** Finds an address that answers, preferring the LAN one. */
    suspend fun resolve(host: SavedHost, force: Boolean = false): String? = withContext(Dispatchers.IO) {
        if (!force) routes[host.id]?.let { return@withContext it }
        for (addr in host.addresses()) {
            if (probe(addr, host.httpPort)) {
                routes[host.id] = addr
                return@withContext addr
            }
        }
        routes.remove(host.id)
        null
    }

    fun routeLabel(host: SavedHost): String = when (routes[host.id]) {
        null -> ""
        host.lanAddress.trim() -> "LAN"
        else -> "REMOTE"
    }

    private fun probe(address: String, port: Int): Boolean = try {
        probeClient.newCall(Request.Builder().url("${baseUrl(address, port)}/api/info").build())
            .execute().use { it.isSuccessful }
    } catch (e: IOException) {
        false
    } catch (e: IllegalArgumentException) {
        false
    }

    private fun execute(request: Request, http: OkHttpClient = client): String {
        http.newCall(request).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                val err = runCatching {
                    RedlineJson.parseToJsonElement(text).jsonObject["error"]?.jsonPrimitive?.content
                }.getOrNull()
                throw ApiException(err ?: "HTTP ${resp.code}")
            }
            return text
        }
    }

    private suspend fun call(host: SavedHost, path: String, body: String? = null): String =
        withContext(Dispatchers.IO) {
            val address = resolve(host) ?: throw ApiException("PC unreachable")
            val builder = Request.Builder()
                .url("${baseUrl(address, host.httpPort)}$path")
                .header("X-Redline-Token", host.token)
            if (body != null) builder.post(body.toRequestBody(JSON))
            try {
                execute(builder.build())
            } catch (e: IOException) {
                routes.remove(host.id) // try the other route next time
                throw e
            }
        }

    // ---- pairing (no token yet) -------------------------------------------
    suspend fun info(address: String, port: Int): HostInfo = withContext(Dispatchers.IO) {
        val text = execute(Request.Builder().url("${baseUrl(address, port)}/api/info").build(), probeClient)
        RedlineJson.decodeFromString<HostInfo>(text)
    }

    suspend fun pair(address: String, port: Int, pin: String, device: String): PairResponse =
        withContext(Dispatchers.IO) {
            val body = buildJsonObject { put("pin", pin); put("device", device) }.toString()
            val text = execute(
                Request.Builder().url("${baseUrl(address, port)}/api/pair").post(body.toRequestBody(JSON)).build()
            )
            RedlineJson.decodeFromString<PairResponse>(text)
        }

    /** Ask an always-on relay agent to send the Wake-on-LAN packet from inside the home network. */
    suspend fun relayWake(relayAddress: String, mac: String, port: Int = 48100) = withContext(Dispatchers.IO) {
        val body = buildJsonObject { put("mac", mac) }.toString()
        execute(Request.Builder().url("${baseUrl(relayAddress, port)}/api/wake").post(body.toRequestBody(JSON)).build())
    }

    // ---- authenticated ----------------------------------------------------
    suspend fun status(host: SavedHost): HostStatus =
        RedlineJson.decodeFromString(call(host, "/api/status"))

    suspend fun games(host: SavedHost): GamesResponse =
        RedlineJson.decodeFromString(call(host, "/api/games"))

    suspend fun launch(host: SavedHost, gameId: String): ActionResult =
        RedlineJson.decodeFromString(call(host, "/api/games/launch", buildJsonObject { put("id", gameId) }.toString()))

    suspend fun closeGame(host: SavedHost): ActionResult =
        RedlineJson.decodeFromString(call(host, "/api/games/close", "{}"))

    suspend fun install(host: SavedHost, appid: Long, name: String): ActionResult =
        RedlineJson.decodeFromString(call(host, "/api/games/install",
            buildJsonObject { put("appid", appid); put("name", name) }.toString()))

    suspend fun uninstall(host: SavedHost, gameId: String): ActionResult =
        RedlineJson.decodeFromString(call(host, "/api/games/uninstall", buildJsonObject { put("id", gameId) }.toString()))

    suspend fun search(host: SavedHost, query: String): StoreResponse {
        val url = "http://x/api/store/search".toHttpUrl().newBuilder().addQueryParameter("q", query).build()
        return RedlineJson.decodeFromString(call(host, "/api/store/search?${url.encodedQuery}"))
    }

    suspend fun power(host: SavedHost, action: String): ActionResult =
        RedlineJson.decodeFromString(call(host, "/api/power/$action", "{}"))

    suspend fun frameGen(host: SavedHost): FrameGenState =
        RedlineJson.decodeFromString(call(host, "/api/framegen"))

    suspend fun setFrameGen(host: SavedHost, enabled: Boolean): FrameGenState =
        RedlineJson.decodeFromString(call(host, "/api/framegen", buildJsonObject { put("enabled", enabled) }.toString()))

    /** Downloads a blob from the PC and returns the measured throughput in Mbit/s. */
    suspend fun speedTest(host: SavedHost, bytes: Int = 3_000_000): Double = withContext(Dispatchers.IO) {
        val address = resolve(host) ?: throw ApiException("PC unreachable")
        val req = Request.Builder()
            .url("${baseUrl(address, host.httpPort)}/api/speedtest?bytes=$bytes")
            .header("X-Redline-Token", host.token)
            .build()
        val start = System.nanoTime()
        var total = 0L
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw ApiException("speed test failed (${resp.code})")
            val source = resp.body!!.byteStream()
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = source.read(buf)
                if (n < 0) break
                total += n
            }
        }
        val seconds = (System.nanoTime() - start) / 1e9
        total * 8 / 1e6 / seconds.coerceAtLeast(0.001)
    }

    private companion object {
        val JSON = "application/json".toMediaType()
    }
}
