package com.protvirto.redline.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.protvirto.redline.ui.AppViewModel
import com.protvirto.redline.ui.CyberPanel
import com.protvirto.redline.ui.CyberToggle
import com.protvirto.redline.ui.OptionRow
import com.protvirto.redline.ui.SectionLabel
import com.protvirto.redline.ui.theme.Mono
import com.protvirto.redline.ui.theme.Neon

@Composable
fun TuningScreen(vm: AppViewModel) {
    val s by vm.settings.collectAsState()
    val status by vm.status.collectAsState()

    Row(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            CyberPanel(title = "Video signal") {
                OptionRow(
                    "Resolution",
                    listOf("720p" to (1280 to 720), "900p" to (1600 to 900), "1080p" to (1920 to 1080), "Native" to (0 to 0)),
                    selected = s.width to s.height,
                ) { (w, h) -> vm.updateSettings(s.copy(width = w, height = h)) }
                OptionRow("Frame rate", listOf("30" to 30, "60" to 60, "90" to 90, "120" to 120), s.fps) {
                    vm.updateSettings(s.copy(fps = it))
                }
                OptionRow("Codec", listOf("HEVC" to "hevc", "H.264" to "h264"), s.codec) {
                    vm.updateSettings(s.copy(codec = it))
                }
                Spacer(Modifier.height(8.dp))
                SectionLabel("Max bitrate · ${s.bitrateKbps / 1000} Mbps")
                Slider(
                    value = s.bitrateKbps / 1000f,
                    onValueChange = { vm.updateSettings(s.copy(bitrateKbps = (it.toInt() * 1000).coerceIn(3000, 100_000))) },
                    valueRange = 3f..100f,
                    colors = SliderDefaults.colors(thumbColor = Neon.Red, activeTrackColor = Neon.Red, inactiveTrackColor = Neon.Blood),
                )
                Text(
                    "HEVC looks better at the same bitrate. Home Wi-Fi: 30–50 Mbps. Hotel / phone hotspot: 8–15 Mbps.",
                    color = Neon.Muted, fontFamily = Mono, fontSize = 11.sp,
                )
            }
            CyberPanel(title = "Link stability") {
                CyberToggle("Auto bitrate", s.autoBitrate, { vm.updateSettings(s.copy(autoBitrate = it)) },
                    "Measures the link before each session and drops bitrate if the network chokes")
                CyberToggle("Latency guard", s.latencyGuard, { vm.updateSettings(s.copy(latencyGuard = it)) },
                    "Skips ahead to the newest frame instead of letting lag build up")
                CyberToggle("Game audio", s.audio, { vm.updateSettings(s.copy(audio = it)) })
            }
        }

        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            CyberPanel(title = "Frame generation") {
                val fg = status?.framegen
                CyberToggle(
                    "Host frame gen (live)",
                    checked = fg?.enabled == true,
                    onChange = { vm.setFrameGen(it) },
                    sub = when {
                        fg == null -> "Rig offline"
                        !fg.available -> fg.note.ifBlank { "Lossless Scaling not found on the PC" }
                        else -> "Lossless Scaling LSFG · doubles/triples game FPS on the PC"
                    },
                )
                CyberToggle("Auto-enable when I start a game", s.frameGenOnLaunch,
                    { vm.updateSettings(s.copy(frameGenOnLaunch = it)) },
                    "Fires the Lossless Scaling hotkey once the game is in front")
                OptionRow("Frame pacing", listOf("Lowest latency" to "latency", "Smooth" to "smooth"), s.pacing) {
                    vm.updateSettings(s.copy(pacing = it))
                }
                Text(
                    "How it fits together: the PC generates the in-between frames (LSFG), the stream carries them " +
                        "at up to 120 fps, and the Thor locks its display to the stream rate. Use Smooth pacing " +
                        "for even frame times, Lowest latency for competitive games.",
                    color = Neon.Muted, fontFamily = Mono, fontSize = 11.sp,
                )
            }
            CyberPanel(title = "Controls & screens") {
                OptionRow("Touch screen", listOf("Touchpad" to "touchpad", "Direct" to "direct"), s.mouseMode) {
                    vm.updateSettings(s.copy(mouseMode = it))
                }
                CyberToggle("Bottom screen HUD", s.dualScreenHud, { vm.updateSettings(s.copy(dualScreenHud = it)) },
                    "Uses the Thor's second screen for stats, quick actions and a touchpad")
                CyberToggle("Performance overlay", s.showStats, { vm.updateSettings(s.copy(showStats = it)) })
                Spacer(Modifier.height(6.dp))
                Text("IN STREAM: SELECT + START opens the menu", color = Neon.Red, fontFamily = Mono,
                    fontWeight = FontWeight.Bold, fontSize = 11.sp)
            }
        }
    }
}
