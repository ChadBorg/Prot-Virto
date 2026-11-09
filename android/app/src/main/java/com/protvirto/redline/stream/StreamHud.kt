package com.protvirto.redline.stream

import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.protvirto.redline.ui.CutShape
import com.protvirto.redline.ui.CyberPanel
import com.protvirto.redline.ui.GlitchText
import com.protvirto.redline.ui.NeonButton
import com.protvirto.redline.ui.SmallCut
import com.protvirto.redline.ui.StatBar
import com.protvirto.redline.ui.screens.cyberFieldColors
import com.protvirto.redline.ui.theme.Mono
import com.protvirto.redline.ui.theme.Neon

data class HudStats(
    val fps: Int = 0,
    val mbps: Double = 0.0,
    val rttMs: Double = 0.0,
    val decodeMs: Double = 0.0,
    val dropped: Int = 0,
    val bitrateKbps: Int = 0,
    val encoder: String = "",
    val resolution: String = "",
)

/** Actions the HUD (overlay menu and bottom screen) can trigger. */
interface HudActions {
    fun resume()
    fun toggleFrameGen()
    fun openKeyboard()
    fun toggleMouseMode()
    fun changeBitrate(deltaKbps: Int)
    fun closeGame()
    fun sleepPc()
    fun disconnect()
}

@Composable
fun StatsOverlay(s: HudStats, modifier: Modifier = Modifier) {
    Column(
        modifier.background(Color.Black.copy(alpha = 0.55f), SmallCut).border(1.dp, Neon.RedDim, SmallCut)
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        Text(
            "${s.fps} FPS · %.1f Mbps · RTT %.0f ms · DEC %.1f ms · DROP ${s.dropped}".format(s.mbps, s.rttMs, s.decodeMs),
            color = Neon.Red, fontFamily = Mono, fontSize = 11.sp, fontWeight = FontWeight.Bold,
        )
        if (s.encoder.isNotBlank()) {
            Text("${s.encoder.uppercase()} · ${s.resolution} · ${s.bitrateKbps / 1000} Mbps cap",
                color = Neon.Muted, fontFamily = Mono, fontSize = 10.sp)
        }
    }
}

@Composable
private fun MenuButtons(frameGenOn: Boolean, mouseMode: String, bitrateKbps: Int, a: HudActions, focusFirst: Boolean) {
    val first = remember { FocusRequester() }
    LaunchedEffect(focusFirst) { if (focusFirst) runCatching { first.requestFocus() } }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            NeonButton("Resume", onClick = a::resume, filled = true, modifier = Modifier.focusRequester(first))
            NeonButton(if (frameGenOn) "Frame gen: ON" else "Frame gen: OFF", onClick = a::toggleFrameGen)
            NeonButton("Keyboard", onClick = a::openKeyboard)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            NeonButton("Touch: $mouseMode", onClick = a::toggleMouseMode)
            NeonButton("− bitrate", onClick = { a.changeBitrate(-5000) })
            Text("${bitrateKbps / 1000} Mbps", color = Neon.Text, fontFamily = Mono, fontSize = 13.sp)
            NeonButton("+ bitrate", onClick = { a.changeBitrate(5000) })
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            NeonButton("Close game", onClick = a::closeGame)
            NeonButton("Sleep PC", onClick = a::sleepPc)
            NeonButton("Disconnect", onClick = a::disconnect)
        }
    }
}

@Composable
fun StreamMenu(frameGenOn: Boolean, mouseMode: String, bitrateKbps: Int, stats: HudStats, a: HudActions) {
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.72f)), contentAlignment = Alignment.Center) {
        CyberPanel(Modifier.widthIn(max = 720.dp), title = "REDLINE // menu") {
            StatsOverlay(stats)
            Spacer(Modifier.height(14.dp))
            MenuButtons(frameGenOn, mouseMode, bitrateKbps, a, focusFirst = true)
            Spacer(Modifier.height(10.dp))
            Text("B / BACK to resume", color = Neon.Muted, fontFamily = Mono, fontSize = 11.sp)
        }
    }
}

@Composable
fun KeyboardDialog(onSend: (String) -> Unit, onClose: () -> Unit) {
    var text by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)), contentAlignment = Alignment.TopCenter) {
        CyberPanel(Modifier.padding(top = 24.dp).widthIn(max = 640.dp), title = "Type to PC") {
            OutlinedTextField(
                text, { text = it }, singleLine = true, shape = SmallCut, colors = cyberFieldColors(),
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { onSend(text + "\n"); text = "" }),
            )
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                NeonButton("Send", onClick = { onSend(text); text = "" }, filled = true)
                NeonButton("Send + Enter", onClick = { onSend(text + "\n"); text = "" })
                NeonButton("Close", onClick = onClose)
            }
        }
    }
}

/** Content for the Thor's bottom screen: telemetry, quick actions and a touchpad. */
@Composable
fun BottomHud(
    stats: HudStats,
    frameGenOn: Boolean,
    mouseMode: String,
    bitrateKbps: Int,
    a: HudActions,
    touchpad: TouchInput,
) {
    Row(Modifier.fillMaxSize().background(Neon.Black).padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            GlitchText("REDLINE")
            StatBar("FPS", stats.fps / 120f, "${stats.fps}")
            StatBar("Link", (stats.mbps * 1000 / bitrateKbps.coerceAtLeast(1)).toFloat(), "%.1f Mbps".format(stats.mbps))
            StatBar("Ping", (stats.rttMs / 100).toFloat(), "%.0f ms".format(stats.rttMs))
            Text("DROP ${stats.dropped} · DEC %.1f ms".format(stats.decodeMs), color = Neon.Muted, fontFamily = Mono, fontSize = 11.sp)
            MenuButtons(frameGenOn, mouseMode, bitrateKbps, a, focusFirst = false)
        }
        Box(
            Modifier.weight(0.8f).fillMaxSize().background(Neon.Blood, CutShape).border(1.dp, Neon.Red, CutShape),
            contentAlignment = Alignment.Center,
        ) {
            Text("TOUCHPAD", color = Neon.Red.copy(alpha = 0.3f), fontFamily = Mono, fontWeight = FontWeight.Black, fontSize = 20.sp)
            AndroidView(
                factory = { ctx -> View(ctx).apply { setOnTouchListener { v, e -> touchpad.onTouch(v, e) } } },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
fun ConnectingOverlay(status: String, failed: Boolean, onRetry: () -> Unit, onExit: () -> Unit) {
    Box(Modifier.fillMaxSize().background(Neon.Black), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            GlitchText(status)
            Spacer(Modifier.height(18.dp))
            if (failed) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    NeonButton("Retry", onClick = onRetry, filled = true)
                    NeonButton("Exit", onClick = onExit)
                }
            } else {
                Text("ESTABLISHING NEURAL LINK", color = Neon.Muted, fontFamily = Mono, fontSize = 12.sp, letterSpacing = 3.sp)
                Spacer(Modifier.width(1.dp))
            }
        }
    }
}
