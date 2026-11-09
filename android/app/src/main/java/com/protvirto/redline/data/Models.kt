package com.protvirto.redline.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

val RedlineJson = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
    encodeDefaults = true
    explicitNulls = false
}

/** A PC the handheld is paired with. */
@Serializable
data class SavedHost(
    val id: String,
    val name: String,
    /** Address on your home network, e.g. 192.168.1.20 */
    val lanAddress: String,
    /** Address that works from anywhere, e.g. your Tailscale IP 100.x.y.z or MagicDNS name */
    val remoteAddress: String = "",
    val httpPort: Int = 48100,
    val streamPort: Int = 48101,
    val token: String,
    val mac: String = "",
    val broadcast: String = "255.255.255.255",
    /** Optional always-on REDLINE relay (e.g. a Raspberry Pi) that can wake the PC remotely */
    val relayAddress: String = "",
) {
    fun addresses(): List<String> =
        listOf(lanAddress, remoteAddress).map { it.trim() }.filter { it.isNotEmpty() }.distinct()
}

@Serializable
data class StreamSettings(
    /** 0 x 0 = the PC's native resolution */
    val width: Int = 1920,
    val height: Int = 1080,
    val fps: Int = 60,
    val bitrateKbps: Int = 20_000,
    val codec: String = "hevc",
    val audio: Boolean = true,
    val autoBitrate: Boolean = true,
    val latencyGuard: Boolean = true,
    /** "latency" renders the newest frame immediately, "smooth" paces every frame to vsync */
    val pacing: String = "latency",
    /** "touchpad" (relative, like a laptop) or "direct" (tap where you want to click) */
    val mouseMode: String = "touchpad",
    val dualScreenHud: Boolean = true,
    val showStats: Boolean = true,
    val frameGenOnLaunch: Boolean = false,
)

@Serializable
data class HostInfo(
    val agent: String = "",
    val version: String = "",
    val name: String = "",
    val role: String = "host",
    val mac: String = "",
    val ip: String = "",
    val os: String = "",
    val http_port: Int = 48100,
    val stream_port: Int = 48101,
)

data class DiscoveredHost(val address: String, val info: HostInfo)

@Serializable
data class PairResponse(
    val token: String,
    val name: String = "",
    val mac: String = "",
    val stream_port: Int = 48101,
)

@Serializable
data class Game(
    val id: String,
    val name: String,
    val source: String,
    val installed: Boolean,
    val image: String = "",
    val hero: String = "",
    val size_bytes: Long = 0,
    val progress: Double = -1.0,
    val can_uninstall: Boolean = false,
) {
    val downloading: Boolean get() = !installed && progress >= 0
}

@Serializable
data class GamesResponse(val games: List<Game> = emptyList())

@Serializable
data class StoreResult(
    val appid: Long,
    val name: String,
    val image: String = "",
    val hero: String = "",
    val price: Double = 0.0,
    val currency: String = "",
    val installed: Boolean = false,
)

@Serializable
data class StoreResponse(val results: List<StoreResult> = emptyList())

@Serializable
data class GpuStats(
    val name: String = "",
    val util: Double = 0.0,
    val temp: Double = 0.0,
    val mem_used_mb: Double = 0.0,
    val mem_total_mb: Double = 0.0,
)

@Serializable
data class StreamState(
    val streaming: Boolean = false,
    val encoder: String = "",
    val fps: Int = 0,
    val bitrate_kbps: Int = 0,
    val resolution: String = "",
)

@Serializable
data class FrameGenState(
    val available: Boolean = false,
    val running: Boolean = false,
    val enabled: Boolean = false,
    val hotkey: String = "",
    val provider: String = "",
    val note: String = "",
)

@Serializable
data class HostStatus(
    val name: String = "",
    val ip: String = "",
    val mac: String = "",
    val version: String = "",
    val cpu: Double? = null,
    val ram: Double? = null,
    val uptime_s: Long = 0,
    val gpu: GpuStats? = null,
    val running_game: String = "",
    val running_game_name: String = "",
    val stream: StreamState = StreamState(),
    val framegen: FrameGenState = FrameGenState(),
)

@Serializable
data class ActionResult(
    val ok: Boolean = false,
    val message: String = "",
    val error: String = "",
)
