package com.protvirto.capri

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.input.InputManager
import android.os.BatteryManager
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.util.Xml
import android.view.InputDevice
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Her senses on the Thor (windows.ps1's job on the PC): the app in front, ES-DE's log (which game
 * it launched) and gamelists (your library, what you play, what you never touch), the screen, the
 * battery, the heat, controllers coming and going.
 */
class ThorWatch(private val service: CapriService) {
    private val apps = service.apps
    private val loop = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "capri-watch") }
    private var fgPkg: String? = null
    private var fgCandidate: String? = null
    private var fgCandidateAt = 0L
    private var logPos = -1L
    private var screenOffAt = 0L
    private var lastBattery: Pair<Int, Boolean>? = null
    private var plugCount = 0
    private var lastPlug: JSONObject? = null
    var lastWindows: JSONObject = windows(null)
        private set
    /** When the user last did something we could see (an app switch, the screen coming on). */
    @Volatile var lastActive = SystemClock.elapsedRealtime()

    fun start() {
        loop.scheduleWithFixedDelay({ safe { pollForeground() } }, 1, 1500, TimeUnit.MILLISECONDS)
        loop.scheduleWithFixedDelay({ safe { pollLog() } }, 2, 2, TimeUnit.SECONDS)
        loop.scheduleWithFixedDelay({ safe { scanLibrary() } }, 8, 30 * 60, TimeUnit.SECONDS)
        service.registerReceiver(screenReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
        })
        service.registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        service.getSystemService(PowerManager::class.java)?.addThermalStatusListener(service.mainExecutor) { status ->
            service.thor(JSONObject().put("type", "thermal").put("level", status))
        }
        service.getSystemService(InputManager::class.java)?.registerInputDeviceListener(inputListener, service.main)
    }

    fun stop() {
        loop.shutdownNow()
        runCatching { service.unregisterReceiver(screenReceiver) }
        runCatching { service.unregisterReceiver(batteryReceiver) }
        runCatching { service.getSystemService(InputManager::class.java)?.unregisterInputDeviceListener(inputListener) }
    }

    /** Everything she knows, again (her renderer just (re)loaded). */
    fun replay() {
        loop.execute {
            safe {
                fgPkg?.let { emitApp(it) }
                lastBattery?.let { (level, charging) -> emitPower(level, charging) }
                scanLibrary()
            }
        }
    }

    private inline fun safe(block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            Log.w(TAG, "watch", e)
        }
    }

    // ---- the app in front ----------------------------------------------------------------------
    private fun pollForeground() {
        val usm = service.getSystemService(UsageStatsManager::class.java) ?: return
        val now = System.currentTimeMillis()
        val events = usm.queryEvents(now - 10_000, now)
        val ev = UsageEvents.Event()
        var latest: String? = null
        while (events.hasNextEvent()) {
            events.getNextEvent(ev)
            if (ev.eventType == UsageEvents.Event.ACTIVITY_RESUMED) latest = ev.packageName
        }
        val pkg = latest ?: return
        if (pkg == service.packageName || pkg == "com.android.systemui") return
        if (pkg == fgPkg) {
            fgCandidate = null
            return
        }
        // the same app for a moment before it counts (no flicker through a launcher or a dialog)
        if (pkg != fgCandidate) {
            fgCandidate = pkg
            fgCandidateAt = SystemClock.elapsedRealtime()
            return
        }
        if (SystemClock.elapsedRealtime() - fgCandidateAt < 1200) return
        fgPkg = pkg
        fgCandidate = null
        lastActive = SystemClock.elapsedRealtime()
        emitApp(pkg)
    }

    private fun emitApp(pkg: String) {
        val info = apps.info(pkg)
        service.thor(
            JSONObject().put("type", "app").put("pkg", pkg).put("label", info.label)
                .put("kind", info.kind).put("dualScreen", info.dualScreen)
        )
        lastWindows = windows(info)
        service.overlay?.emit("windows:update", lastWindows)
    }

    // what her renderer expects from windows.ps1 on the PC: no windows to sit on, but the app in
    // front (her jealousy reads `ai`), and devices plugged in (pc.js)
    private fun windows(info: Apps.Info?): JSONObject = JSONObject()
        .put("fg", info?.pkg ?: JSONObject.NULL)
        .put("wins", JSONArray())
        .put("ai", JSONArray().apply { info?.rival?.let { put(it) } })
        .put("usb", JSONArray())
        .put("taskbar", false)
        .put("plug", lastPlug ?: JSONObject().put("n", plugCount))

    // ---- ES-DE ---------------------------------------------------------------------------------
    private fun esde() = File(service.config.string("esdePath", "/storage/emulated/0/ES-DE"))

    /** Tails ES-DE's log for the games it launches. */
    private fun pollLog() {
        val log = File(esde(), "logs/es_log.txt")
        if (!log.canRead()) return
        val len = log.length()
        if (logPos < 0 || len < logPos) {
            logPos = len // start at the end: what happened before she was here isn't news
            return
        }
        if (len == logPos) return
        val chunk = RandomAccessFile(log, "r").use { f ->
            f.seek(logPos)
            val bytes = ByteArray((len - logPos).coerceAtMost(256 * 1024).toInt())
            f.readFully(bytes)
            String(bytes, Charsets.UTF_8)
        }
        logPos = len
        val lines = chunk.lines()
        for ((i, line) in lines.withIndex()) {
            val m = LAUNCH.find(line) ?: continue
            val game = m.groupValues[1].trim()
            val around = lines.subList((i - 4).coerceAtLeast(0), (i + 5).coerceAtMost(lines.size)).joinToString("\n")
            val system = SYSTEM.find(around)?.groupValues?.get(1)?.lowercase(Locale.ROOT).orEmpty()
            lastActive = SystemClock.elapsedRealtime()
            service.thor(JSONObject().put("type", "launch").put("game", game).put("system", system))
        }
    }

    /** Reads every gamelist.xml: how big the library is, what gets played, what never does. */
    private fun scanLibrary() {
        val dir = File(esde(), "gamelists")
        val systems = dir.listFiles()?.filter { it.isDirectory } ?: return
        var total = 0
        var never = 0
        var favorites = 0
        var favNever = 0
        var lastPlayed = 0L
        var top: Pair<String, Int>? = null
        val favPicks = mutableListOf<JSONObject>()
        val picks = mutableListOf<JSONObject>()
        val stamp = SimpleDateFormat("yyyyMMdd'T'HHmmss", Locale.ROOT)
        for (sys in systems) {
            val file = File(sys, "gamelist.xml")
            if (!file.canRead()) continue
            runCatching {
                file.inputStream().buffered().use { input ->
                    val p = Xml.newPullParser()
                    p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
                    p.setInput(input, null)
                    var inGame = false
                    var tag = ""
                    var name = ""
                    var count = 0
                    var fav = false
                    var last = ""
                    var hidden = false
                    while (p.next() != XmlPullParser.END_DOCUMENT) {
                        when (p.eventType) {
                            XmlPullParser.START_TAG -> {
                                tag = p.name
                                if (tag == "game") {
                                    inGame = true; name = ""; count = 0; fav = false; last = ""; hidden = false
                                }
                            }
                            XmlPullParser.TEXT -> if (inGame) {
                                val v = p.text.trim()
                                when (tag) {
                                    "name" -> name = v
                                    "playcount" -> count = v.toIntOrNull() ?: 0
                                    "favorite" -> fav = v == "true"
                                    "lastplayed" -> last = v
                                    "hidden" -> hidden = v == "true"
                                }
                            }
                            XmlPullParser.END_TAG -> {
                                if (p.name == "game" && inGame) {
                                    inGame = false
                                    if (!hidden && name.isNotBlank()) {
                                        total++
                                        if (fav) favorites++
                                        if (count == 0) {
                                            never++
                                            val pick = JSONObject().put("name", name).put("system", sys.name)
                                            if (fav) {
                                                favNever++; favPicks += pick
                                            } else if (picks.size < 400) picks += pick
                                        }
                                        if (count > (top?.second ?: 0)) top = name to count
                                        if (last.isNotBlank()) runCatching { stamp.parse(last)?.time }.getOrNull()?.let {
                                            if (it > lastPlayed) lastPlayed = it
                                        }
                                    }
                                }
                                tag = ""
                            }
                        }
                    }
                }
            }
        }
        if (total == 0) return
        // her suggestions: unplayed favorites first, then a random handful of the rest
        val chosen = (favPicks.shuffled().take(8) + picks.shuffled().take(12 - favPicks.size.coerceAtMost(8))).take(12)
        service.thor(
            JSONObject().put("type", "library")
                .put("total", total).put("played", total - never).put("never", never)
                .put("favorites", favorites).put("favNever", favNever)
                .put("lastPlayedAt", lastPlayed)
                .put("top", top?.let { JSONObject().put("name", it.first).put("count", it.second) } ?: JSONObject.NULL)
                .put("picks", JSONArray(chosen))
        )
    }

    // ---- screen, battery, heat, controllers -------------------------------------------------------
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    screenOffAt = SystemClock.elapsedRealtime()
                    service.thor(JSONObject().put("type", "screen").put("on", false))
                }
                Intent.ACTION_USER_PRESENT, Intent.ACTION_SCREEN_ON -> {
                    if (screenOffAt == 0L) return
                    val off = SystemClock.elapsedRealtime() - screenOffAt
                    screenOffAt = 0L
                    lastActive = SystemClock.elapsedRealtime()
                    service.thor(JSONObject().put("type", "screen").put("on", true).put("offFor", off))
                }
            }
        }
    }

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
            val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
            val pct = if (level >= 0 && scale > 0) level * 100 / scale else return
            if (lastBattery == pct to charging) return
            lastBattery = pct to charging
            emitPower(pct, charging)
        }
    }

    private fun emitPower(pct: Int, charging: Boolean) {
        service.thor(JSONObject().put("type", "power").put("level", pct / 100.0).put("charging", charging))
    }

    private val inputListener = object : InputManager.InputDeviceListener {
        private val names = HashMap<Int, String>()
        override fun onInputDeviceAdded(deviceId: Int) = plug(deviceId, true)
        override fun onInputDeviceRemoved(deviceId: Int) = plug(deviceId, false)
        override fun onInputDeviceChanged(deviceId: Int) {}

        private fun plug(id: Int, added: Boolean) {
            val dev = InputDevice.getDevice(id)
            val name = dev?.name ?: names[id] ?: return
            if (dev != null) names[id] = name
            val pad = dev == null || dev.sources and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD
            if (dev != null && (dev.isVirtual || !dev.isExternal)) return // the Thor's own buttons
            plugCount++
            lastPlug = JSONObject().put("n", plugCount).put("in", added)
                .put("name", if (pad && !Regex("controller|pad", RegexOption.IGNORE_CASE).containsMatchIn(name)) "$name controller" else name)
                .put("class", if (pad) "HIDClass" else "Keyboard")
            lastWindows = lastWindows.put("plug", lastPlug)
            service.overlay?.emit("windows:update", lastWindows)
        }
    }

    companion object {
        private const val TAG = "CapriWatch"
        // ES-DE: `Launching game "Chrono Trigger"...`
        private val LAUNCH = Regex("Launching game \"(.+?)\"")
        // the ROM's system folder, from the launch command around it: .../ROMs/snes/Chrono Trigger.sfc
        private val SYSTEM = Regex("/(?:ROMs|roms|Roms)/([^/\"]+)/")
    }
}
