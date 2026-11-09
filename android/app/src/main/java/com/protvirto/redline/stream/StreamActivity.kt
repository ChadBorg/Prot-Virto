package com.protvirto.redline.stream

import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.view.Display
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.protvirto.redline.data.RedlineJson
import com.protvirto.redline.data.SavedHost
import com.protvirto.redline.data.StreamSettings
import com.protvirto.redline.net.AgentApi
import com.protvirto.redline.ui.StreamLaunch
import com.protvirto.redline.ui.theme.Mono
import com.protvirto.redline.ui.theme.Neon
import com.protvirto.redline.ui.theme.RedlineTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

class StreamActivity : ComponentActivity(), StreamClient.Listener, HudActions {

    companion object {
        private const val EXTRA_HOST = "host"
        private const val EXTRA_ADDRESS = "address"
        private const val EXTRA_SETTINGS = "settings"
        private const val EXTRA_FRAMEGEN_DELAY = "framegen_delay"

        fun intent(context: Context, launch: StreamLaunch): Intent =
            Intent(context, StreamActivity::class.java)
                .putExtra(EXTRA_HOST, RedlineJson.encodeToString(launch.host))
                .putExtra(EXTRA_ADDRESS, launch.address)
                .putExtra(EXTRA_SETTINGS, RedlineJson.encodeToString(launch.settings))
                .putExtra(EXTRA_FRAMEGEN_DELAY, launch.frameGenAfterMs)
    }

    private lateinit var host: SavedHost
    private lateinit var address: String
    private lateinit var settings: StreamSettings
    private val api = AgentApi()

    @Volatile private var client: StreamClient? = null
    @Volatile private var decoder: VideoDecoder? = null
    private var audio: AudioPlayer? = null
    @Volatile private var surface: Surface? = null
    @Volatile private var videoConfig: Pair<Int, ByteArray>? = null
    private val decoderLock = Any()
    private val stats = StreamStats()
    private val gamepad = GamepadInput { client?.send(Wire.GAMEPAD, it) }
    private lateinit var touch: TouchInput
    private lateinit var bottomTouch: TouchInput
    private var bottomHud: BottomScreenHud? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var userClosed = false
    private val congestion = ArrayDeque<Long>()

    // ---- UI state -------------------------------------------------------------
    private var statusText by mutableStateOf("CONNECTING")
    private var failed by mutableStateOf(false)
    private var streaming by mutableStateOf(false)
    private var menuOpen by mutableStateOf(false)
    private var keyboardOpen by mutableStateOf(false)
    private var frameGenOn by mutableStateOf(false)
    private var mouseMode by mutableStateOf("touchpad")
    private var bitrateKbps by mutableIntStateOf(20_000)
    private var hud by mutableStateOf(HudStats())
    private var notice by mutableStateOf<String?>(null)
    @Volatile private var lastRtt = 0.0
    private var encoderName = ""
    private var resolutionText = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        host = RedlineJson.decodeFromString(intent.getStringExtra(EXTRA_HOST) ?: return finish())
        address = intent.getStringExtra(EXTRA_ADDRESS) ?: host.lanAddress
        settings = RedlineJson.decodeFromString(intent.getStringExtra(EXTRA_SETTINGS) ?: return finish())
        bitrateKbps = settings.bitrateKbps
        mouseMode = settings.mouseMode
        touch = TouchInput({ client }, mouseMode)
        bottomTouch = TouchInput({ client }, "touchpad")

        goImmersive()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val pm = getSystemService(PowerManager::class.java)
        if (pm?.isSustainedPerformanceModeSupported == true) window.setSustainedPerformanceMode(true)
        wifiLock = getSystemService(WifiManager::class.java)
            ?.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "redline:stream")
            ?.apply { setReferenceCounted(false); acquire() }

        setContent { RedlineTheme { StreamScreen() } }
        connect()
        startStatsLoop()
        scheduleFrameGen(intent.getLongExtra(EXTRA_FRAMEGEN_DELAY, -1L))
        if (settings.dualScreenHud) showBottomHud()
    }

    private fun goImmersive() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) goImmersive()
    }

    // ---- UI -------------------------------------------------------------------
    @Composable
    private fun StreamScreen() {
        val aspect = if (settings.width > 0 && settings.height > 0) settings.width.toFloat() / settings.height else 16f / 9f
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            AndroidView(
                factory = { ctx ->
                    SurfaceView(ctx).apply {
                        holder.addCallback(surfaceCallback)
                        setOnTouchListener { v, e -> if (streaming && !menuOpen) touch.onTouch(v, e) else false }
                    }
                },
                modifier = Modifier.align(Alignment.Center).aspectRatio(aspect),
            )
            if (streaming && settings.showStats) {
                StatsOverlay(hud, Modifier.align(Alignment.TopStart).padding(10.dp))
            }
            if (streaming) {
                // Touch-friendly menu handle (top-right corner)
                Box(
                    Modifier.align(Alignment.TopEnd).padding(8.dp).size(44.dp)
                        .background(Color.Black.copy(alpha = 0.35f)).clickable { menuOpen = true },
                    contentAlignment = Alignment.Center,
                ) { Text("≡", color = Neon.Red.copy(alpha = 0.8f), fontFamily = Mono, fontSize = 22.sp) }
            }
            notice?.let {
                Text(
                    "> $it", color = Neon.Text, fontFamily = Mono, fontSize = 13.sp,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(18.dp)
                        .background(Color.Black.copy(alpha = 0.75f)).padding(horizontal = 14.dp, vertical = 8.dp),
                )
            }
            if (!streaming) {
                ConnectingOverlay(statusText, failed, onRetry = { reconnect() }, onExit = { disconnect() })
            }
            if (menuOpen) StreamMenu(frameGenOn, mouseMode, bitrateKbps, hud, this@StreamActivity)
            if (keyboardOpen) KeyboardDialog(onSend = { client?.text(it) }, onClose = { keyboardOpen = false })
        }
    }

    private fun showBottomHud() {
        val dm = getSystemService(DisplayManager::class.java) ?: return
        val target = dm.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION).firstOrNull { it.displayId != Display.DEFAULT_DISPLAY }
            ?: dm.displays.firstOrNull { it.displayId != Display.DEFAULT_DISPLAY && it.state == Display.STATE_ON }
            ?: return
        try {
            bottomHud = BottomScreenHud(this, target) {
                BottomHud(hud, frameGenOn, mouseMode, bitrateKbps, this, bottomTouch)
            }.also { it.show() }
        } catch (e: Exception) {
            bottomHud = null // display not usable for presentations
        }
    }

    private fun flash(msg: String) {
        notice = msg
        lifecycleScope.launch {
            delay(3000)
            if (notice == msg) notice = null
        }
    }

    // ---- connection -------------------------------------------------------------
    private fun hello(): String = buildJsonObject {
        put("token", host.token)
        put("width", settings.width)
        put("height", settings.height)
        put("fps", settings.fps)
        put("bitrate_kbps", bitrateKbps)
        put("codec", settings.codec)
        put("audio", settings.audio)
    }.toString()

    private fun connect() {
        failed = false
        streaming = false
        statusText = "CONNECTING"
        if (settings.audio && audio == null) audio = AudioPlayer().also { it.start() }
        client = StreamClient(address, host.streamPort, hello(), this).also { it.start() }
    }

    private fun teardown() {
        client?.stop()
        client = null
        synchronized(decoderLock) {
            decoder?.stop()
            decoder = null
            videoConfig = null
        }
    }

    private fun reconnect() {
        teardown()
        connect()
    }

    private fun startDecoderLocked() {
        val s = surface ?: return
        val (codec, csd) = videoConfig ?: return
        decoder?.stop()
        decoder = VideoDecoder(s, codec, settings.width, settings.height, settings.pacing == "smooth",
            settings.latencyGuard, stats).also { it.start(csd) }
    }

    private val surfaceCallback = object : SurfaceHolder.Callback {
        override fun surfaceCreated(holder: SurfaceHolder) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                // Lock the panel to the stream rate (e.g. 120 Hz) for judder-free motion.
                runCatching { holder.surface.setFrameRate(settings.fps.toFloat(), Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE) }
            }
            synchronized(decoderLock) {
                surface = holder.surface
                startDecoderLocked()
            }
        }

        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}

        override fun surfaceDestroyed(holder: SurfaceHolder) {
            synchronized(decoderLock) {
                decoder?.stop()
                decoder = null
                surface = null
            }
        }
    }

    // ---- StreamClient.Listener (network thread) -----------------------------------
    override fun onInfo(json: String) {
        runCatching {
            val o = RedlineJson.parseToJsonElement(json).jsonObject
            encoderName = o["encoder"]?.jsonPrimitive?.content.orEmpty()
            val w = o["width"]?.jsonPrimitive?.int ?: 0
            val h = o["height"]?.jsonPrimitive?.int ?: 0
            resolutionText = if (w > 0) "${w}x$h" else "native"
            val padErr = o["gamepad_error"]?.jsonPrimitive?.content.orEmpty()
            if (padErr.isNotBlank()) runOnUiThread { flash("Controller: install ViGEmBus on the PC") }
        }
    }

    override fun onVideoConfig(codec: Int, data: ByteArray) {
        synchronized(decoderLock) {
            videoConfig = codec to data
            startDecoderLocked()
        }
        runOnUiThread { streaming = true }
    }

    override fun onVideoFrame(keyframe: Boolean, ptsMs: Long, data: ByteArray, offset: Int) {
        decoder?.submit(keyframe, ptsMs, data, offset)
    }

    override fun onAudio(pcm: ByteArray) {
        audio?.submit(pcm)
    }

    override fun onPong(rttMs: Double) {
        lastRtt = rttMs
    }

    override fun onCongestion(droppedFrames: Int) {
        runOnUiThread {
            if (!settings.autoBitrate) return@runOnUiThread
            val now = System.currentTimeMillis()
            congestion.addLast(now)
            while (congestion.isNotEmpty() && now - congestion.first() > 10_000) congestion.removeFirst()
            if (congestion.size >= 2 && bitrateKbps > 4_000) {
                congestion.clear()
                bitrateKbps = maxOf(3_000, (bitrateKbps * 0.7).toInt())
                flash("Network congested → bitrate ${bitrateKbps / 1000} Mbps")
                reconnect()
            }
        }
    }

    override fun onHostError(message: String) {
        runOnUiThread { statusText = message.uppercase().take(80) }
    }

    override fun onClosed(source: StreamClient, error: Throwable?) {
        runOnUiThread {
            if (userClosed || isFinishing || source !== client) return@runOnUiThread
            if (error == null && streaming) {
                // Host ended the session cleanly (e.g. encoder restart) — try again.
                reconnect(); return@runOnUiThread
            }
            streaming = false
            failed = true
            if (statusText == "CONNECTING") statusText = "LINK LOST"
            error?.message?.let { flash(it) }
        }
    }

    // ---- stats ------------------------------------------------------------------------
    private fun startStatsLoop() {
        lifecycleScope.launch {
            var lastFrames = stats.framesRendered.get()
            var lastBytes = 0L
            var lastClient: StreamClient? = null
            while (isActive) {
                delay(1000)
                val c = client
                c?.ping()
                val frames = stats.framesRendered.get()
                val bytes = c?.bytesReceived?.get() ?: 0L
                if (c !== lastClient) {
                    lastBytes = 0L; lastClient = c
                }
                val samples = stats.decodeSamples.getAndSet(0)
                val nanos = stats.decodeNanosTotal.getAndSet(0)
                hud = HudStats(
                    fps = frames - lastFrames,
                    mbps = (bytes - lastBytes) * 8 / 1e6,
                    rttMs = lastRtt,
                    decodeMs = if (samples > 0) nanos / samples / 1e6 else 0.0,
                    dropped = stats.framesDropped.get(),
                    bitrateKbps = bitrateKbps,
                    encoder = encoderName,
                    resolution = resolutionText,
                )
                lastFrames = frames
                lastBytes = bytes
            }
        }
    }

    private fun scheduleFrameGen(delayMs: Long) {
        if (delayMs < 0) return
        lifecycleScope.launch {
            delay(delayMs)
            if (!frameGenOn) toggleFrameGen()
        }
    }

    // ---- input ------------------------------------------------------------------------
    private fun uiKey(event: KeyEvent): KeyEvent? {
        val code = when (event.keyCode) {
            KeyEvent.KEYCODE_BUTTON_A -> KeyEvent.KEYCODE_DPAD_CENTER
            KeyEvent.KEYCODE_BUTTON_B -> KeyEvent.KEYCODE_BACK
            else -> return null
        }
        return KeyEvent(event.downTime, event.eventTime, event.action, code, event.repeatCount, event.metaState,
            event.deviceId, event.scanCode, event.flags, event.source)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (menuOpen || keyboardOpen || !streaming) {
            val mapped = uiKey(event) ?: event
            if (mapped.keyCode == KeyEvent.KEYCODE_BACK && mapped.action == KeyEvent.ACTION_UP) {
                when {
                    keyboardOpen -> keyboardOpen = false
                    menuOpen -> menuOpen = false
                    else -> disconnect()
                }
                return true
            }
            if (mapped.keyCode == KeyEvent.KEYCODE_BACK) return true
            return super.dispatchKeyEvent(mapped)
        }
        if (gamepad.onKey(event)) {
            if (gamepad.menuCombo) openMenu()
            return true
        }
        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_UP) openMenu()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun dispatchGenericMotionEvent(ev: MotionEvent): Boolean {
        if (streaming && !menuOpen && !keyboardOpen && gamepad.onMotion(ev)) return true
        return super.dispatchGenericMotionEvent(ev)
    }

    private fun openMenu() {
        gamepad.releaseAll() // don't leave buttons stuck down in the game
        menuOpen = true
    }

    // ---- HudActions ----------------------------------------------------------------------
    override fun resume() {
        menuOpen = false
    }

    override fun toggleFrameGen() {
        val target = !frameGenOn
        lifecycleScope.launch {
            try {
                frameGenOn = api.setFrameGen(host, target).enabled
                flash(if (frameGenOn) "Frame generation ON" else "Frame generation OFF")
            } catch (e: Exception) {
                flash("Frame gen: ${e.message}")
            }
        }
    }

    override fun openKeyboard() {
        menuOpen = false
        keyboardOpen = true
    }

    override fun toggleMouseMode() {
        mouseMode = if (mouseMode == "touchpad") "direct" else "touchpad"
        touch.mode = mouseMode
    }

    override fun changeBitrate(deltaKbps: Int) {
        bitrateKbps = (bitrateKbps + deltaKbps).coerceIn(3_000, 100_000)
        flash("Bitrate ${bitrateKbps / 1000} Mbps")
        reconnect()
    }

    override fun closeGame() {
        lifecycleScope.launch {
            runCatching { api.closeGame(host) }.onSuccess { flash(it.message) }.onFailure { flash("${it.message}") }
        }
    }

    override fun sleepPc() {
        lifecycleScope.launch {
            runCatching { api.power(host, "sleep") }
            disconnect()
        }
    }

    override fun disconnect() {
        userClosed = true
        finish()
    }

    // ---- lifecycle ------------------------------------------------------------------------
    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) {
            userClosed = true
            finish()
        }
    }

    override fun onDestroy() {
        userClosed = true
        gamepad.releaseAll()
        teardown()
        audio?.stop()
        audio = null
        runCatching { bottomHud?.dismiss() }
        bottomHud = null
        runCatching { wifiLock?.release() }
        super.onDestroy()
    }
}
