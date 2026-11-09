package com.protvirto.redline.ui

import android.app.Application
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.protvirto.redline.data.DiscoveredHost
import com.protvirto.redline.data.Game
import com.protvirto.redline.data.HostStatus
import com.protvirto.redline.data.Prefs
import com.protvirto.redline.data.SavedHost
import com.protvirto.redline.data.StoreResult
import com.protvirto.redline.data.StreamSettings
import com.protvirto.redline.net.AgentApi
import com.protvirto.redline.net.ApiException
import com.protvirto.redline.net.Discovery
import com.protvirto.redline.net.WakeOnLan
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.UUID
import kotlin.math.max
import kotlin.math.min

enum class Tab(val label: String) { HOME("HOME"), LIBRARY("LIBRARY"), STORE("STORE"), TUNING("TUNING"), RIGS("RIGS") }

/** Everything StreamActivity needs to connect. */
data class StreamLaunch(
    val host: SavedHost,
    val address: String,
    val settings: StreamSettings,
    val frameGenAfterMs: Long,
)

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = Prefs(app)
    val api = AgentApi()

    val hosts = MutableStateFlow(prefs.hosts())
    val activeId = MutableStateFlow(prefs.activeHostId ?: hosts.value.firstOrNull()?.id)
    val tab = MutableStateFlow(Tab.HOME)

    val status = MutableStateFlow<HostStatus?>(null)
    /** null = unknown yet, true = agent answered, false = unreachable */
    val reachable = MutableStateFlow<Boolean?>(null)
    val route = MutableStateFlow("")

    val games = MutableStateFlow<List<Game>>(emptyList())
    val gamesLoading = MutableStateFlow(false)
    val storeResults = MutableStateFlow<List<StoreResult>>(emptyList())
    val storeLoading = MutableStateFlow(false)
    val discovered = MutableStateFlow<List<DiscoveredHost>>(emptyList())
    val scanning = MutableStateFlow(false)
    val settings = MutableStateFlow(prefs.streamSettings)
    val busy = MutableStateFlow<String?>(null)

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val messages = _messages.asSharedFlow()
    private val _launches = MutableSharedFlow<StreamLaunch>(extraBufferCapacity = 1)
    val launches = _launches.asSharedFlow()

    init {
        viewModelScope.launch {
            while (isActive) {
                refreshStatus()
                delay(if (reachable.value == true) 3000 else 6000)
            }
        }
    }

    fun activeHost(): SavedHost? = hosts.value.firstOrNull { it.id == activeId.value } ?: hosts.value.firstOrNull()

    private fun say(msg: String) {
        _messages.tryEmit(msg)
    }

    private fun errorText(e: Throwable): String = when (e) {
        is ApiException -> e.message ?: "error"
        is java.io.IOException -> "PC unreachable (${e.javaClass.simpleName})"
        else -> e.message ?: e.javaClass.simpleName
    }

    /** Runs [block] against the active host and reports failures as a toast. */
    private fun withHost(label: String? = null, block: suspend (SavedHost) -> Unit) {
        val host = activeHost() ?: return say("Pair a PC first")
        viewModelScope.launch {
            if (label != null) busy.value = label
            try {
                block(host)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                say(errorText(e))
            } finally {
                if (label != null) busy.value = null
            }
        }
    }

    // ---- hosts ---------------------------------------------------------------
    private fun persistHosts(list: List<SavedHost>) {
        hosts.value = list
        prefs.saveHosts(list)
    }

    fun updateHost(host: SavedHost) {
        persistHosts(hosts.value.map { if (it.id == host.id) host else it })
    }

    fun selectHost(id: String) {
        activeId.value = id
        prefs.activeHostId = id
        status.value = null
        reachable.value = null
        games.value = emptyList()
        viewModelScope.launch { refreshStatus() }
    }

    fun removeHost(id: String) {
        persistHosts(hosts.value.filterNot { it.id == id })
        if (activeId.value == id) hosts.value.firstOrNull()?.let { selectHost(it.id) } ?: run {
            activeId.value = null
            prefs.activeHostId = null
        }
    }

    fun scan() {
        viewModelScope.launch {
            scanning.value = true
            try {
                discovered.value = Discovery.scan()
                if (discovered.value.isEmpty()) say("No PCs answered. Is the agent running? You can also add one by IP.")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                say("Scan failed: ${errorText(e)}")
            } finally {
                scanning.value = false
            }
        }
    }

    fun pair(address: String, port: Int, pin: String, remoteAddress: String, onDone: () -> Unit) {
        viewModelScope.launch {
            busy.value = "PAIRING"
            try {
                val info = api.info(address.trim(), port)
                val resp = api.pair(address.trim(), port, pin.trim(), "${Build.MANUFACTURER} ${Build.MODEL}")
                val host = SavedHost(
                    id = UUID.randomUUID().toString(),
                    name = resp.name.ifBlank { info.name },
                    lanAddress = address.trim(),
                    remoteAddress = remoteAddress.trim(),
                    httpPort = port,
                    streamPort = info.stream_port,
                    token = resp.token,
                    mac = resp.mac.ifBlank { info.mac },
                )
                persistHosts(hosts.value + host)
                selectHost(host.id)
                say("Linked with ${host.name}")
                onDone()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                say("Pairing failed: ${errorText(e)}")
            } finally {
                busy.value = null
            }
        }
    }

    // ---- status ----------------------------------------------------------------
    suspend fun refreshStatus() {
        val host = activeHost() ?: run {
            status.value = null; reachable.value = null; return
        }
        try {
            val s = api.status(host)
            status.value = s
            reachable.value = true
            route.value = api.routeLabel(host)
            if (s.mac.isNotBlank() && s.mac != host.mac) updateHost(host.copy(mac = s.mac))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            status.value = null
            reachable.value = false
            route.value = ""
        }
    }

    fun refreshNow() {
        viewModelScope.launch { refreshStatus() }
    }

    // ---- power -------------------------------------------------------------------
    fun wake() = withHost("WAKING") { host ->
        say(WakeOnLan.wake(host, api))
        // Give the PC time to boot, then start probing more aggressively.
        repeat(20) {
            delay(5000)
            api.resolve(host, force = true)?.let {
                refreshStatus()
                say("${host.name} is online")
                return@withHost
            }
        }
    }

    fun power(action: String) = withHost { host ->
        api.power(host, action)
        say(
            when (action) {
                "shutdown" -> "Shutting down ${host.name}"
                "restart" -> "Restarting ${host.name}"
                "sleep" -> "${host.name} going to sleep"
                "hibernate" -> "${host.name} hibernating"
                else -> "${host.name} locked"
            }
        )
        if (action != "lock") {
            reachable.value = false
            status.value = null
        }
    }

    // ---- library -----------------------------------------------------------------
    fun loadGames(silent: Boolean = false) = withHost { host ->
        if (!silent) gamesLoading.value = true
        try {
            games.value = api.games(host).games
        } finally {
            gamesLoading.value = false
        }
    }

    fun uninstall(game: Game) = withHost("DELETING") { host ->
        say(api.uninstall(host, game.id).message.ifBlank { "Uninstalled ${game.name}" })
        loadGames(silent = true)
    }

    fun closeGame() = withHost { host -> say(api.closeGame(host).message) }

    fun search(query: String) = withHost { host ->
        storeLoading.value = true
        try {
            storeResults.value = api.search(host, query).results
        } finally {
            storeLoading.value = false
        }
    }

    fun install(result: StoreResult) = withHost("QUEUEING") { host ->
        say("${result.name}: ${api.install(host, result.appid, result.name).message}")
    }

    // ---- frame gen -----------------------------------------------------------------
    fun setFrameGen(enabled: Boolean) = withHost { host ->
        val st = api.setFrameGen(host, enabled)
        status.value = status.value?.copy(framegen = st)
        say(if (st.enabled) "Frame generation ON" else "Frame generation OFF")
    }

    // ---- settings ------------------------------------------------------------------
    fun updateSettings(s: StreamSettings) {
        settings.value = s
        prefs.streamSettings = s
    }

    // ---- streaming -------------------------------------------------------------------
    /** Optionally launches [game], measures the link and opens the stream. */
    fun play(game: Game?) = withHost("LINKING") { host ->
        val address = api.resolve(host, force = true) ?: throw ApiException("PC unreachable — power it on first")
        if (game != null) {
            api.launch(host, game.id)
            say("Launching ${game.name}")
        }
        var s = settings.value
        if (s.autoBitrate) {
            busy.value = "MEASURING LINK"
            val mbps = runCatching { api.speedTest(host) }.getOrNull()
            if (mbps != null) {
                // Leave ~40% headroom for Wi-Fi jitter.
                val cap = (mbps * 0.6 * 1000).toInt()
                val chosen = max(3_000, min(s.bitrateKbps, cap))
                if (chosen < s.bitrateKbps) say("Link ≈ %.0f Mbps → bitrate %d Mbps".format(mbps, chosen / 1000))
                s = s.copy(bitrateKbps = chosen)
            }
        }
        val fgDelay = when {
            !s.frameGenOnLaunch -> -1L
            game != null -> 20_000L // give the game time to reach the foreground
            else -> 1_500L
        }
        _launches.tryEmit(StreamLaunch(host, address, s, fgDelay))
    }
}
