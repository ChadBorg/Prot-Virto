package com.protvirto.capri

import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONObject
import java.io.File

/** Her menu (the tray menu on the PC): long-press her, or from the notification. */
class MenuActivity : ComponentActivity() {
    private data class Item(val label: String, val run: () -> Unit)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { Menu() }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val mapped = when (event.keyCode) {
            KeyEvent.KEYCODE_BUTTON_A -> KeyEvent.KEYCODE_DPAD_CENTER
            KeyEvent.KEYCODE_BUTTON_B -> KeyEvent.KEYCODE_BACK
            else -> return super.dispatchKeyEvent(event)
        }
        return super.dispatchKeyEvent(KeyEvent(event.downTime, event.eventTime, event.action, mapped, event.repeatCount,
            event.metaState, event.deviceId, event.scanCode, event.flags, event.source))
    }

    private fun cmd(name: String, payload: JSONObject? = null) {
        CapriService.send(this, CapriService.ACTION_COMMAND, name, payload)
        finish()
    }

    private fun set(patch: JSONObject) {
        CapriService.send(this, CapriService.ACTION_CONFIG, payload = patch)
        finish()
    }

    private fun items(cfg: JSONObject): List<Item> {
        val visible = cfg.optBoolean("visible", true)
        return listOf(
            Item("Talk to her") { cmd("chat") },
            Item("Pick me a game") { cmd("thorPick") },
            Item("How am I doing today?") { cmd("thorStats") },
            Item("Battery check") { cmd("thorBattery") },
            Item("Teleport") { cmd("teleport") },
            Item("Look at the satellite") { cmd("satellite") },
            Item("Make her yours…") { cmd("customize") },
            Item(if (visible) "Put her away" else "Bring her back") {
                CapriService.send(this, CapriService.ACTION_TOGGLE); finish()
            },
            Item("Switch screen") {
                val other = CapriService.instance?.overlay?.otherDisplay()?.displayId
                if (other != null) cmd("goScreen", JSONObject().put("id", other)) else finish()
            },
            Item(if (cfg.optBoolean("muted")) "Sound on" else "Mute") { set(JSONObject().put("muted", !cfg.optBoolean("muted"))) },
            Item(if (cfg.optBoolean("wander", true)) "Stop wandering" else "Wander around") { set(JSONObject().put("wander", !cfg.optBoolean("wander", true))) },
            Item("AI model: set it up with her…") { cmd("setupChat") },
            Item("Forget our conversations") {
                File(filesDir, "memory.json").delete()
                cmd("forget")
            },
            Item("Settings") {
                startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); finish()
            },
            Item("Turn her off") { CapriService.stop(this); finish() },
        )
    }

    @Composable
    private fun Menu() {
        val font = chakra(LocalContext.current)
        val cfg = remember { CapriService.instance?.config?.get() ?: Config(this).get() }
        val xp = cfg.optJSONObject("bond")?.optInt("xp") ?: 0
        val stage = listOf(0, 100, 300, 550, 850).count { xp >= it } - 1
        val stages = listOf("Faint signal", "Contact", "In sync", "Linked", "Fused")
        val first = remember { FocusRequester() }
        LaunchedEffect(Unit) { runCatching { first.requestFocus() } }

        Box(Modifier.fillMaxSize().clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { finish() }, contentAlignment = Alignment.Center) {
            Column(
                Modifier.widthIn(max = 420.dp).background(Night.Plate, Cut).border(1.dp, Night.Cyan, Cut)
                    .verticalScroll(rememberScrollState()).padding(16.dp)
            ) {
                Text("/// CAPRI", color = Night.Cyan, fontFamily = font, fontWeight = FontWeight.Bold, fontSize = 15.sp, letterSpacing = 3.sp)
                Text("You and her: ${(xp / 10).coerceAtMost(100)}% · ${stages[stage.coerceIn(0, 4)]}", color = Night.Yellow, fontFamily = font, fontSize = 12.sp)
                Spacer(Modifier.height(10.dp))
                items(cfg).forEachIndexed { i, item ->
                    MenuRow(item, font, if (i == 0) Modifier.focusRequester(first) else Modifier)
                }
            }
        }
    }

    @Composable
    private fun MenuRow(item: Item, font: FontFamily, modifier: Modifier) {
        val source = remember { MutableInteractionSource() }
        val focused by source.collectIsFocusedAsState()
        val danger = item.label.startsWith("Turn her off")
        Text(
            item.label,
            color = when {
                focused -> Night.Bg
                danger -> Night.Red
                else -> Night.Text
            },
            fontFamily = font, fontSize = 15.sp,
            modifier = modifier.fillMaxWidth()
                .background(if (focused) (if (danger) Night.Red else Night.Cyan) else Color.Transparent)
                .clickable(interactionSource = source, indication = null) { item.run() }
                .padding(horizontal = 10.dp, vertical = 10.dp),
        )
    }
}
