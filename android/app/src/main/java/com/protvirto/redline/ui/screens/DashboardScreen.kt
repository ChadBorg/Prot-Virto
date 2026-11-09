package com.protvirto.redline.ui.screens

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.protvirto.redline.ui.AppViewModel
import com.protvirto.redline.ui.ConfirmDialog
import com.protvirto.redline.ui.CyberPanel
import com.protvirto.redline.ui.CyberToggle
import com.protvirto.redline.ui.NeonButton
import com.protvirto.redline.ui.StatBar
import com.protvirto.redline.ui.Tab
import com.protvirto.redline.ui.formatUptime
import com.protvirto.redline.ui.theme.Mono
import com.protvirto.redline.ui.theme.Neon

@Composable
fun DashboardScreen(vm: AppViewModel) {
    val status by vm.status.collectAsState()
    val reachable by vm.reachable.collectAsState()
    val hosts by vm.hosts.collectAsState()
    val activeId by vm.activeId.collectAsState()
    val host = hosts.firstOrNull { it.id == activeId } ?: hosts.firstOrNull() ?: return
    var confirm by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val moonlight = remember { context.packageManager.getLaunchIntentForPackage("com.limelight") }

    Row(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(Modifier.weight(1.25f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            // ---- power / main action -----------------------------------------
            CyberPanel(title = "Rig // ${host.name}") {
                if (reachable == true && status != null) {
                    val s = status!!
                    Text(
                        if (s.running_game_name.isNotBlank()) "NOW RUNNING" else "SYSTEM IDLE",
                        color = Neon.Muted, fontFamily = Mono, fontSize = 11.sp, letterSpacing = 3.sp,
                    )
                    Text(
                        s.running_game_name.ifBlank { "Desktop" }.uppercase(),
                        color = Neon.Text, fontFamily = Mono, fontWeight = FontWeight.Black, fontSize = 26.sp,
                    )
                    Spacer(Modifier.height(14.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        NeonButton(
                            if (s.running_game_name.isNotBlank()) "Jack in" else "Stream desktop",
                            onClick = { vm.play(null) }, filled = true, icon = Icons.Filled.PlayArrow,
                        )
                        NeonButton("Library", onClick = { vm.tab.value = Tab.LIBRARY })
                        if (s.running_game.isNotBlank()) {
                            NeonButton("Close game", onClick = { confirm = "close" }, icon = Icons.Filled.Close)
                        }
                    }
                } else {
                    Text(
                        if (reachable == null) "SEARCHING FOR RIG…" else "RIG OFFLINE",
                        color = Neon.Text, fontFamily = Mono, fontWeight = FontWeight.Black, fontSize = 24.sp,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        if (host.relayAddress.isBlank() && host.remoteAddress.isNotBlank())
                            "Away from home? Wake-on-LAN needs a relay — set one in RIGS."
                        else "Send a Wake-on-LAN pulse to boot it up.",
                        color = Neon.Muted, fontFamily = Mono, fontSize = 12.sp,
                    )
                    Spacer(Modifier.height(14.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        NeonButton("Power on", onClick = { vm.wake() }, filled = true, icon = Icons.Filled.Star,
                            enabled = host.mac.isNotBlank())
                        NeonButton("Retry", onClick = { vm.refreshNow() }, icon = Icons.Filled.Refresh)
                    }
                }
            }

            // ---- power controls -------------------------------------------------
            CyberPanel(title = "Power grid") {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    val on = reachable == true
                    NeonButton("Wake", onClick = { vm.wake() }, enabled = !on && host.mac.isNotBlank())
                    NeonButton("Sleep", onClick = { confirm = "sleep" }, enabled = on)
                    NeonButton("Restart", onClick = { confirm = "restart" }, enabled = on)
                    NeonButton("Shut down", onClick = { confirm = "shutdown" }, enabled = on, icon = Icons.Filled.Warning)
                    NeonButton("Lock", onClick = { vm.power("lock") }, enabled = on, icon = Icons.Filled.Lock)
                }
            }

            if (moonlight != null) {
                CyberPanel(title = "Alt engine") {
                    Text("Moonlight is installed. Hand off to it if you also run Sunshine on the PC.",
                        color = Neon.Muted, fontFamily = Mono, fontSize = 12.sp)
                    Spacer(Modifier.height(10.dp))
                    NeonButton("Open Moonlight", onClick = {
                        context.startActivity(moonlight.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    })
                }
            }
        }

        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            CyberPanel(title = "Telemetry") {
                val s = status
                if (s == null) {
                    Text("NO SIGNAL", color = Neon.Muted, fontFamily = Mono)
                } else {
                    s.cpu?.let { StatBar("CPU", (it / 100).toFloat(), "%.0f%%".format(it)); Spacer(Modifier.height(10.dp)) }
                    s.ram?.let { StatBar("RAM", (it / 100).toFloat(), "%.0f%%".format(it)); Spacer(Modifier.height(10.dp)) }
                    s.gpu?.let { g ->
                        StatBar("GPU", (g.util / 100).toFloat(), "%.0f%%".format(g.util)); Spacer(Modifier.height(10.dp))
                        StatBar("GPU temp", (g.temp / 100).toFloat(), "%.0f°C".format(g.temp)); Spacer(Modifier.height(10.dp))
                        if (g.mem_total_mb > 0) {
                            StatBar("VRAM", (g.mem_used_mb / g.mem_total_mb).toFloat(),
                                "%.1f/%.0f GB".format(g.mem_used_mb / 1024, g.mem_total_mb / 1024))
                            Spacer(Modifier.height(10.dp))
                        }
                        Text(g.name, color = Neon.Muted, fontFamily = Mono, fontSize = 11.sp)
                    }
                    Spacer(Modifier.height(6.dp))
                    Text("UPTIME ${formatUptime(s.uptime_s)}  ·  ${s.ip}", color = Neon.Muted, fontFamily = Mono, fontSize = 11.sp)
                }
            }

            CyberPanel(title = "Frame gen") {
                val fg = status?.framegen
                CyberToggle(
                    "Host frame generation",
                    checked = fg?.enabled == true,
                    onChange = { vm.setFrameGen(it) },
                    sub = when {
                        fg == null -> "Rig offline"
                        !fg.available -> fg.note.ifBlank { "Lossless Scaling not found" }
                        else -> "${fg.provider} · hotkey ${fg.hotkey}"
                    },
                )
                Spacer(Modifier.height(4.dp))
                Text("Tip: set stream FPS to 120 in TUNING so generated frames reach the Thor's 120 Hz panel.",
                    color = Neon.Muted, fontFamily = Mono, fontSize = 11.sp)
            }
        }
    }

    confirm?.let { action ->
        val (title, msg, label) = when (action) {
            "close" -> Triple("Close game", "Force-close the running game on ${host.name}? Unsaved progress is lost.", "Close")
            "sleep" -> Triple("Sleep", "Put ${host.name} to sleep?", "Sleep")
            "restart" -> Triple("Restart", "Restart ${host.name}?", "Restart")
            else -> Triple("Shut down", "Shut down ${host.name}? You'll need Wake-on-LAN to bring it back.", "Shut down")
        }
        ConfirmDialog(title, msg, label, onConfirm = {
            if (action == "close") vm.closeGame() else vm.power(action)
        }, onDismiss = { confirm = null })
    }
}
