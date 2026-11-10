package com.protvirto.capri

import android.Manifest
import android.annotation.SuppressLint
import android.app.AppOpsManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.PowerManager
import android.os.Process
import android.provider.Settings
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import org.json.JSONObject

/** Capri's control room: permissions, where she lives, her AI, and the off switch. */
class MainActivity : ComponentActivity() {
    private var tick by mutableIntStateOf(0) // bumps when we come back from a settings page

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null && wakeFromIntent(intent)) return
        setContent { Screen() }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        wakeFromIntent(intent)
    }

    /**
     * `adb shell am start -n com.protvirto.capri/.MainActivity --ez wake true` wakes her without a tap
     * (the service isn't exported, so `am start-foreground-service` from adb is refused). Used by thor-setup.ps1.
     */
    private fun wakeFromIntent(intent: Intent?): Boolean {
        if (intent?.getBooleanExtra("wake", false) != true || !canOverlay()) return false
        CapriService.start(this)
        finish()
        return true
    }

    override fun onResume() {
        super.onResume()
        tick++
    }

    // A on the Thor's pad selects, B goes back
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val mapped = when (event.keyCode) {
            KeyEvent.KEYCODE_BUTTON_A -> KeyEvent.KEYCODE_DPAD_CENTER
            KeyEvent.KEYCODE_BUTTON_B -> KeyEvent.KEYCODE_BACK
            else -> return super.dispatchKeyEvent(event)
        }
        return super.dispatchKeyEvent(KeyEvent(event.downTime, event.eventTime, event.action, mapped, event.repeatCount,
            event.metaState, event.deviceId, event.scanCode, event.flags, event.source))
    }

    private fun config(): Config = CapriService.instance?.config ?: Config(this)

    private fun save(patch: JSONObject, reload: Boolean = false) {
        if (CapriService.running) {
            CapriService.send(this, CapriService.ACTION_CONFIG, payload = patch)
            if (reload) CapriService.send(this, CapriService.ACTION_RELOAD)
        } else {
            Config(this).update(patch)
        }
        tick++
    }

    // ---- permissions ------------------------------------------------------------------------------
    private fun canOverlay() = Settings.canDrawOverlays(this)
    private fun hasUsage(): Boolean {
        val ops = getSystemService(AppOpsManager::class.java)
        return ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), packageName) == AppOpsManager.MODE_ALLOWED
    }
    private fun hasFiles() = Environment.isExternalStorageManager()
    private fun noBatteryLimit() = getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)

    private fun open(action: String, withPackage: Boolean = true) {
        runCatching {
            startActivity(Intent(action).apply { if (withPackage) data = Uri.parse("package:$packageName") })
        }.onFailure { runCatching { startActivity(Intent(action)) } }
    }

    @SuppressLint("BatteryLife")
    @Composable
    private fun Screen() {
        val font = chakra(LocalContext.current)
        val t = tick
        var running by remember { mutableStateOf(CapriService.running) }
        LaunchedEffect(t) {
            while (true) {
                running = CapriService.running
                delay(800)
            }
        }
        val cfg = remember(t, running) { config().get() }

        Column(
            Modifier.fillMaxSize().background(Night.Bg).verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("CAPRI", color = Night.Text, fontFamily = font, fontWeight = FontWeight.Bold, fontSize = 34.sp, letterSpacing = 6.sp)
                    Text(
                        if (running) "// ONLINE — LIVING IN YOUR THOR" else "// OFFLINE",
                        color = if (running) Night.Green else Night.Red, fontFamily = font, fontSize = 13.sp, letterSpacing = 2.sp,
                    )
                }
                if (running) {
                    Btn("Turn her off", font, {
                        CapriService.stop(this@MainActivity); tick++
                    }, danger = true)
                } else {
                    Btn("Wake her up", font, {
                        if (canOverlay()) CapriService.start(this@MainActivity) else open(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
                        tick++
                    }, primary = true)
                }
            }

            Plate("Jack her in", font) {
                Perm("Draw over other apps", "Required: she lives on top of everything", canOverlay(), font) {
                    open(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
                }
                Perm("Usage access", "To know when you're in ES-DE, a game, or doomscrolling", hasUsage(), font) {
                    open(Settings.ACTION_USAGE_ACCESS_SETTINGS, withPackage = false)
                }
                Perm("All files access", "To read ES-DE's log and gamelists (what you launch, what you never play)", hasFiles(), font) {
                    open(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                }
                Perm("Ignore battery optimisation", "So Android doesn't put her to sleep", noBatteryLimit(), font) {
                    open(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                }
                if (Build.VERSION.SDK_INT >= 33) {
                    Perm("Notifications", "Her on/off switch lives in the notification", checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == 0, font) {
                        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
                    }
                }
            }

            if (running) {
                Plate("Talk to her", font) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Btn("Chat", font, { CapriService.send(this@MainActivity, CapriService.ACTION_COMMAND, "chat"); moveTaskToBack(true) }, primary = true)
                        Btn("Her menu", font, { CapriService.instance?.openMenu() })
                        Btn("Pick me a game", font, { CapriService.send(this@MainActivity, CapriService.ACTION_COMMAND, "thorPick"); moveTaskToBack(true) })
                        Btn("Reload her", font, { CapriService.send(this@MainActivity, CapriService.ACTION_RELOAD) })
                    }
                }
            }

            Plate("Where she lives", font) {
                Check("Start when the Thor boots", "Off switch: notification, Quick Settings tile, or here", cfg.optBoolean("startOnBoot", true), font) {
                    save(JSONObject().put("startOnBoot", it))
                }
                Choice("Screen", listOf("bottom" to "Bottom screen", "top" to "Top screen"), cfg.optString("screen", "bottom"), font) {
                    save(JSONObject().put("screen", it), reload = true)
                }
                Choice("Size", listOf("0.4" to "Small", "0.5" to "Medium", "0.65" to "Large"), sizeKey(cfg.optDouble("scale", 0.5)), font) {
                    save(JSONObject().put("scale", it.toDouble()))
                }
                Check("Sound", null, !cfg.optBoolean("muted", false), font) { save(JSONObject().put("muted", !it)) }
                Check("Wander around", "Teleports about when nothing's going on", cfg.optBoolean("wander", true), font) {
                    save(JSONObject().put("wander", it))
                }
                Check("React to the Thor", "Battery, heat, controllers, games", cfg.optBoolean("pcReact", true), font) {
                    save(JSONObject().put("pcReact", it))
                }
                Check("Jealous of other AIs", "Open ChatGPT or Gemini and see what happens", cfg.optBoolean("jealous", true), font) {
                    save(JSONObject().put("jealous", it))
                }
            }

            Plate("Her attitude", font) {
                Choice(
                    "Sass level", listOf("chill" to "Chill", "normal" to "Normal", "unhinged" to "Unhinged"),
                    cfg.optString("sass", "normal"), font,
                ) { save(JSONObject().put("sass", it)) }
                Check("Quiet hours (23:00–08:00)", "No commentary at night — only what you ask for and a dying battery", cfg.optBoolean("quietHours", false), font) {
                    save(JSONObject().put("quietHours", it))
                }
                Check("AI-written comments", "She makes up her own remarks (launches, quits, scrolling…) with her AI brain. Needs a key (Groq is free and fast) or Ollama below; without one, or offline: her written lines.", cfg.optBoolean("aiLines", true), font) {
                    save(JSONObject().put("aiLines", it))
                }
                Check("Battery warnings", "30%, 20%, 15%… and a nag every so often under 15%. A notification if she's put away mid-game.", cfg.optBoolean("batteryWarn", true), font) {
                    save(JSONObject().put("batteryWarn", it))
                }
                Check("Shake reactions", "Shake the Thor and she gets dizzy (shake it too much and she gets mad)", cfg.optBoolean("shake", true), font) {
                    save(JSONObject().put("shake", it))
                }
            }

            Plate("ES-DE", font) {
                Field("ES-DE folder (holds logs/ and gamelists/)", cfg.optString("esdePath"), font) {
                    save(JSONObject().put("esdePath", it.trim()))
                }
                Field("What she calls you", cfg.optString("userName"), font) { save(JSONObject().put("userName", it.trim())) }
            }

            Plate("Her brain (chat)", font) {
                val provider = cfg.optString("provider", "pollinations")
                Choice(
                    "AI", listOf("pollinations" to "Online, no key", "ollama" to "Ollama", "groq" to "Groq", "gemini" to "Gemini"),
                    provider, font,
                ) { save(JSONObject().put("provider", it).put("chatModel", JSONObject.NULL)) }
                Choice(
                    "More", listOf("openrouter" to "OpenRouter", "openai" to "OpenAI", "mistral" to "Mistral", "custom" to "Custom URL"),
                    provider, font,
                ) { save(JSONObject().put("provider", it).put("chatModel", JSONObject.NULL)) }
                when (provider) {
                    "ollama" -> {
                        Field("Ollama address (Termux: http://127.0.0.1:11434, or your PC's)", cfg.optString("ollamaUrl"), font) {
                            save(JSONObject().put("ollamaUrl", it.trim()))
                        }
                        Text(
                            "The Thor Max has RAM to spare: in Termux, `pkg install ollama`, `ollama serve`, then pick a model with her (gemma3:4b is a good start).",
                            color = Night.Muted, fontSize = 12.sp,
                        )
                    }
                    "custom" -> Field("OpenAI-compatible URL (…/v1)", cfg.optString("customUrl"), font) {
                        save(JSONObject().put("customUrl", it.trim()))
                    }
                    "pollinations" -> Text("Free, nothing to set up. Your messages go through pollinations.ai.", color = Night.Muted, fontSize = 12.sp)
                    else -> {
                        val keys = config().get().optJSONObject("apiKeys") ?: JSONObject()
                        Field("API key", if (keys.optString(provider).isNotBlank()) "••••••••" else "", font, secret = true) {
                            if (it.isNotBlank() && !it.startsWith("•")) save(JSONObject().put("apiKeys", keys.put(provider, it.trim())))
                        }
                        Llm.KEY_PAGES[provider]?.let { page ->
                            Btn("Get a key", font, { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(page))) })
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
                Field("Model (blank = automatic)", cfg.optString("chatModel").takeIf { it != "null" }.orEmpty(), font) {
                    save(JSONObject().put("chatModel", it.trim().ifBlank { null } ?: JSONObject.NULL))
                }
            }

            Text(
                "Capri is a fan-made construct by mochiiwuff (capri-desktop, PolyForm Noncommercial), ported to the Thor. " +
                    "Character art belongs to its rights holders. Personal use only — not for sale.",
                color = Night.Muted, fontSize = 11.sp, modifier = Modifier.padding(bottom = 24.dp),
            )
        }
    }

    private fun sizeKey(scale: Double) = when {
        scale < 0.45 -> "0.4"
        scale < 0.58 -> "0.5"
        else -> "0.65"
    }

    @Composable
    private fun Perm(label: String, sub: String, granted: Boolean, font: FontFamily, onGrant: () -> Unit) {
        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text((if (granted) "✓ " else "✗ ") + label, color = if (granted) Night.Green else Night.Text, fontFamily = font, fontSize = 15.sp)
                Text(sub, color = Night.Muted, fontSize = 12.sp)
            }
            if (!granted) {
                Spacer(Modifier.width(10.dp))
                Btn("Grant", font, onGrant, primary = true)
            }
        }
    }

    @Composable
    private fun Field(label: String, value: String, font: FontFamily, secret: Boolean = false, onSave: (String) -> Unit) {
        var text by remember(value) { mutableStateOf(value) }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            OutlinedTextField(
                value = text, onValueChange = { text = it }, singleLine = true,
                label = { Text(label, fontSize = 12.sp) },
                shape = Cut,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Night.Cyan, unfocusedBorderColor = Night.Muted,
                    focusedTextColor = Night.Text, unfocusedTextColor = Night.Text, cursorColor = Night.Yellow,
                    focusedLabelColor = Night.Cyan, unfocusedLabelColor = Night.Muted,
                ),
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(10.dp))
            Btn("Save", font, { onSave(text) }, primary = text != value && !(secret && text.startsWith("•")))
        }
    }
}
