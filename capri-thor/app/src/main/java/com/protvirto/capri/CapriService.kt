package com.protvirto.capri

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.service.quicksettings.TileService
import android.content.ComponentName
import android.view.Display
import org.json.JSONObject

/**
 * Capri's home on the Thor: a foreground service that keeps her overlay alive from boot until you
 * tell her to go (the notification, the Quick Settings tile, or the app).
 */
class CapriService : Service() {
    val main = Handler(Looper.getMainLooper())
    lateinit var config: Config
        private set
    lateinit var llm: Llm
        private set
    lateinit var apps: Apps
        private set
    var overlay: Overlay? = null
        private set
    var watch: ThorWatch? = null
        private set
    private lateinit var bridge: Bridge

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        config = Config(this)
        llm = Llm(config)
        apps = Apps(this)
        bridge = Bridge(this)
        startForeground(NOTE_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        config.put("running", true)
        if (Settings.canDrawOverlays(this)) {
            overlay = Overlay(this, bridge).also { it.create() }
        }
        watch = ThorWatch(this).also { it.start() }
        getSystemService(DisplayManager::class.java)?.registerDisplayListener(displayListener, main)
        TileService.requestListeningState(this, ComponentName(this, CapriTile::class.java))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_QUIT -> {
                config.put("running", false)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_TOGGLE -> {
                val visible = !config.bool("visible", true)
                config.put("visible", visible)
                command("stow", JSONObject().put("on", !visible))
                overlay?.emit("capri:config", config.renderer())
                refreshNotification()
            }
            ACTION_COMMAND -> intent.getStringExtra(EXTRA_NAME)?.let { name ->
                command(name, intent.getStringExtra(EXTRA_PAYLOAD)?.let { JSONObject(it) } ?: JSONObject())
            }
            ACTION_CONFIG -> {
                intent.getStringExtra(EXTRA_PAYLOAD)?.let { config.update(JSONObject(it)) }
                overlay?.emit("capri:config", config.renderer())
                refreshNotification()
            }
            ACTION_RELOAD -> {
                overlay?.destroy()
                overlay = if (Settings.canDrawOverlays(this)) Overlay(this, bridge).also { it.create() } else null
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        instance = null
        getSystemService(DisplayManager::class.java)?.unregisterDisplayListener(displayListener)
        watch?.stop()
        overlay?.destroy()
        bridge.shutdown()
        TileService.requestListeningState(this, ComponentName(this, CapriTile::class.java))
        super.onDestroy()
    }

    // ---- to her renderer -------------------------------------------------------------------------
    fun command(name: String, payload: JSONObject = JSONObject()) {
        overlay?.emit("capri:command", payload.put("name", name))
    }

    fun thor(event: JSONObject) {
        overlay?.emit("thor:event", event)
    }

    /** thor.js keeps track of which screen she's on (to dodge games on the top one). */
    fun displayChanged() {
        main.post {
            val o = overlay ?: return@post
            if (!o.ready) return@post
            val count = getSystemService(DisplayManager::class.java)?.displays?.size ?: 1
            thor(JSONObject().put("type", "display").put("current", o.display.displayId).put("count", count).put("top", Display.DEFAULT_DISPLAY))
        }
    }

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = displayChanged()
        override fun onDisplayRemoved(displayId: Int) {
            val o = overlay ?: return
            if (o.ready && o.display.displayId == displayId) {
                o.moveTo(Display.DEFAULT_DISPLAY)?.let { overlay?.emit("overlay:resize", it) }
            }
            displayChanged()
        }
        override fun onDisplayChanged(displayId: Int) {}
    }

    fun openMenu() {
        startActivity(Intent(this, MenuActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION))
    }

    // ---- notification --------------------------------------------------------------------------------
    private fun pending(action: String, code: Int): PendingIntent =
        PendingIntent.getService(this, code, Intent(this, CapriService::class.java).setAction(action), PendingIntent.FLAG_IMMUTABLE)

    private fun notification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Capri", NotificationManager.IMPORTANCE_MIN).apply {
                description = "Keeps Capri running on your Thor"
                setShowBadge(false)
            }
        )
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val menu = PendingIntent.getActivity(
            this, 1, Intent(this, MenuActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE,
        )
        val visible = if (::config.isInitialized) config.bool("visible", true) else true
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_tile)
            .setContentTitle("Capri is online")
            .setContentText(if (visible) "Haunting your Thor. Long-press her for her menu." else "Put away in her biochip.")
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Menu", menu).build())
            .addAction(Notification.Action.Builder(null, if (visible) "Put away" else "Bring back", pending(ACTION_TOGGLE, 2)).build())
            .addAction(Notification.Action.Builder(null, "Turn off", pending(ACTION_QUIT, 3)).build())
            .build()
    }

    private fun refreshNotification() {
        getSystemService(NotificationManager::class.java).notify(NOTE_ID, notification())
    }

    companion object {
        private const val CHANNEL = "capri"
        private const val NOTE_ID = 77
        const val ACTION_QUIT = "capri.QUIT"
        const val ACTION_TOGGLE = "capri.TOGGLE"
        const val ACTION_COMMAND = "capri.COMMAND"
        const val ACTION_CONFIG = "capri.CONFIG"
        const val ACTION_RELOAD = "capri.RELOAD"
        const val EXTRA_NAME = "name"
        const val EXTRA_PAYLOAD = "payload"

        @Volatile var instance: CapriService? = null
            private set

        val running: Boolean get() = instance != null

        fun start(context: Context) {
            context.startForegroundService(Intent(context, CapriService::class.java))
        }

        fun stop(context: Context) {
            Config(context).put("running", false)
            context.stopService(Intent(context, CapriService::class.java))
        }

        fun send(context: Context, action: String, name: String? = null, payload: JSONObject? = null) {
            if (instance == null) return
            context.startService(
                Intent(context, CapriService::class.java).setAction(action)
                    .apply {
                        name?.let { putExtra(EXTRA_NAME, it) }
                        payload?.let { putExtra(EXTRA_PAYLOAD, it.toString()) }
                    }
            )
        }
    }
}
