package com.protvirto.redline.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.protvirto.redline.stream.StreamActivity
import com.protvirto.redline.ui.AppViewModel
import com.protvirto.redline.ui.CutShape
import com.protvirto.redline.ui.CyberBackground
import com.protvirto.redline.ui.GlitchText
import com.protvirto.redline.ui.PulseDot
import com.protvirto.redline.ui.SmallCut
import com.protvirto.redline.ui.Tab
import com.protvirto.redline.ui.theme.Mono
import com.protvirto.redline.ui.theme.Neon
import kotlinx.coroutines.delay

@Composable
fun RootScreen(vm: AppViewModel) {
    val context = LocalContext.current
    val hosts by vm.hosts.collectAsState()
    val tab by vm.tab.collectAsState()
    val busy by vm.busy.collectAsState()
    var toast by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        vm.launches.collect { launch ->
            context.startActivity(StreamActivity.intent(context, launch))
        }
    }
    LaunchedEffect(Unit) {
        vm.messages.collect { toast = it }
    }
    LaunchedEffect(toast) {
        if (toast != null) {
            delay(3500); toast = null
        }
    }

    CyberBackground {
        if (hosts.isEmpty()) {
            RigsScreen(vm, firstRun = true)
        } else {
            Row(Modifier.fillMaxSize()) {
                NavRail(tab) { vm.tab.value = it }
                Column(Modifier.fillMaxSize().padding(start = 4.dp, end = 20.dp, top = 14.dp)) {
                    Header(vm)
                    Spacer(Modifier.height(12.dp))
                    Box(Modifier.fillMaxSize()) {
                        when (tab) {
                            Tab.HOME -> DashboardScreen(vm)
                            Tab.LIBRARY -> LibraryScreen(vm)
                            Tab.STORE -> StoreScreen(vm)
                            Tab.TUNING -> TuningScreen(vm)
                            Tab.RIGS -> RigsScreen(vm, firstRun = false)
                        }
                    }
                }
            }
        }

        AnimatedVisibility(busy != null, enter = fadeIn(), exit = fadeOut()) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.7f)), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = Neon.Red, strokeWidth = 3.dp, modifier = Modifier.size(54.dp))
                    Spacer(Modifier.height(16.dp))
                    GlitchText("${busy ?: ""}…")
                }
            }
        }

        toast?.let { msg ->
            Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.BottomCenter) {
                Text(
                    "> $msg",
                    color = Neon.Text, fontFamily = Mono, fontSize = 14.sp,
                    modifier = Modifier.background(Neon.Panel, SmallCut).border(1.dp, Neon.Red, SmallCut)
                        .padding(horizontal = 18.dp, vertical = 12.dp),
                )
            }
        }
    }
}

@Composable
private fun Header(vm: AppViewModel) {
    val hosts by vm.hosts.collectAsState()
    val activeId by vm.activeId.collectAsState()
    val host = hosts.firstOrNull { it.id == activeId } ?: hosts.firstOrNull()
    val reachable by vm.reachable.collectAsState()
    val route by vm.route.collectAsState()
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        GlitchText("REDLINE")
        Spacer(Modifier.width(10.dp))
        Text("// remote play", color = Neon.Red.copy(alpha = 0.6f), fontFamily = Mono, fontSize = 12.sp)
        Spacer(Modifier.weight(1f))
        if (host != null) {
            Row(
                Modifier.background(Neon.Panel, SmallCut).border(1.dp, Neon.RedDim, SmallCut)
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PulseDot(reachable == true)
                Spacer(Modifier.width(10.dp))
                Text(host.name.uppercase(), color = Neon.Text, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                Spacer(Modifier.width(10.dp))
                Text(
                    when (reachable) {
                        true -> "ONLINE${if (route.isNotEmpty()) " · $route" else ""}"
                        false -> "OFFLINE"
                        null -> "SEARCHING"
                    },
                    color = if (reachable == true) Neon.Red else Neon.Muted, fontFamily = Mono, fontSize = 12.sp,
                )
            }
        }
    }
}

@Composable
private fun NavRail(selected: Tab, onSelect: (Tab) -> Unit) {
    Column(
        Modifier.fillMaxHeight().width(92.dp).background(Neon.Panel.copy(alpha = 0.85f))
            .padding(vertical = 18.dp, horizontal = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("R//", color = Neon.Red, fontFamily = Mono, fontWeight = FontWeight.Black, fontSize = 22.sp)
        Spacer(Modifier.height(10.dp))
        Tab.entries.forEach { t ->
            val icon = when (t) {
                Tab.HOME -> Icons.Filled.Home
                Tab.LIBRARY -> Icons.Filled.List
                Tab.STORE -> Icons.Filled.Search
                Tab.TUNING -> Icons.Filled.Settings
                Tab.RIGS -> Icons.Filled.Build
            }
            val interaction = remember { MutableInteractionSource() }
            val focused by interaction.collectIsFocusedAsState()
            val active = t == selected
            Column(
                Modifier.fillMaxWidth().background(if (active) Neon.Red else if (focused) Neon.Blood else Color.Transparent, CutShape)
                    .border(1.dp, if (active || focused) Neon.Red else Color.Transparent, CutShape)
                    .clickable(interactionSource = interaction, indication = null) { onSelect(t) }
                    .padding(vertical = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(icon, null, tint = if (active) Neon.Black else Neon.Red, modifier = Modifier.size(22.dp))
                Spacer(Modifier.height(4.dp))
                Text(t.label, color = if (active) Neon.Black else Neon.Muted, fontFamily = Mono, fontSize = 10.sp,
                    fontWeight = FontWeight.Bold)
            }
        }
        Spacer(Modifier.weight(1f))
        Text("L1/R1", color = Neon.Muted.copy(alpha = 0.6f), fontFamily = Mono, fontSize = 9.sp)
    }
}
