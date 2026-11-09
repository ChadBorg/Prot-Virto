package com.protvirto.redline.ui.screens

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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.border
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.protvirto.redline.data.SavedHost
import com.protvirto.redline.ui.AppViewModel
import com.protvirto.redline.ui.ConfirmDialog
import com.protvirto.redline.ui.CutShape
import com.protvirto.redline.ui.CyberPanel
import com.protvirto.redline.ui.GlitchText
import com.protvirto.redline.ui.NeonButton
import com.protvirto.redline.ui.SmallCut
import com.protvirto.redline.ui.theme.Mono
import com.protvirto.redline.ui.theme.Neon

private data class PairTarget(val address: String, val port: Int, val name: String)

@Composable
fun RigsScreen(vm: AppViewModel, firstRun: Boolean) {
    val hosts by vm.hosts.collectAsState()
    val activeId by vm.activeId.collectAsState()
    val discovered by vm.discovered.collectAsState()
    val scanning by vm.scanning.collectAsState()
    var pairing by remember { mutableStateOf<PairTarget?>(null) }
    var editing by remember { mutableStateOf<SavedHost?>(null) }
    var removing by remember { mutableStateOf<SavedHost?>(null) }
    var manualAddress by remember { mutableStateOf("") }

    LaunchedEffect(Unit) { if (firstRun) vm.scan() }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(if (firstRun) 32.dp else 0.dp).padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (firstRun) {
            GlitchText("REDLINE")
            Text(
                "1. Run the REDLINE agent on your gaming PC.  2. Scan or type its IP.  3. Enter the PIN the agent shows.",
                color = Neon.Muted, fontFamily = Mono, fontSize = 13.sp,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            CyberPanel(Modifier.weight(1f), title = "Scan local net") {
                NeonButton(if (scanning) "Scanning…" else "Scan", onClick = { vm.scan() }, enabled = !scanning,
                    icon = Icons.Filled.Refresh, filled = true)
                Spacer(Modifier.height(12.dp))
                val known = hosts.map { it.lanAddress }.toSet()
                discovered.filter { it.address !in known && it.info.role == "host" }.forEach { d ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text(d.info.name.uppercase(), color = Neon.Text, fontFamily = Mono, fontWeight = FontWeight.Bold)
                            Text("${d.address} · v${d.info.version}", color = Neon.Muted, fontFamily = Mono, fontSize = 11.sp)
                        }
                        NeonButton("Pair", onClick = { pairing = PairTarget(d.address, d.info.http_port, d.info.name) })
                    }
                }
            }
            CyberPanel(Modifier.weight(1f), title = "Manual link") {
                OutlinedTextField(
                    manualAddress, { manualAddress = it.trim() },
                    label = { Text("PC address (LAN or Tailscale)", fontFamily = Mono) },
                    singleLine = true, shape = SmallCut, colors = cyberFieldColors(), modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                )
                Spacer(Modifier.height(10.dp))
                NeonButton("Pair", onClick = { pairing = PairTarget(manualAddress, 48100, manualAddress) },
                    enabled = manualAddress.isNotBlank(), icon = Icons.Filled.Add)
            }
        }

        if (hosts.isNotEmpty()) {
            CyberPanel(title = "Paired rigs") {
                hosts.forEach { h ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 6.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                (if (h.id == activeId) "▶ " else "") + h.name.uppercase(),
                                color = if (h.id == activeId) Neon.Red else Neon.Text, fontFamily = Mono, fontWeight = FontWeight.Bold,
                            )
                            Text(
                                "LAN ${h.lanAddress}" + (if (h.remoteAddress.isNotBlank()) " · REMOTE ${h.remoteAddress}" else " · no remote route") +
                                    (if (h.mac.isNotBlank()) " · MAC ${h.mac}" else ""),
                                color = Neon.Muted, fontFamily = Mono, fontSize = 11.sp,
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (h.id != activeId) NeonButton("Use", onClick = { vm.selectHost(h.id) }, icon = Icons.Filled.Check)
                            NeonButton("Edit", onClick = { editing = h }, icon = Icons.Filled.Edit)
                            NeonButton("Forget", onClick = { removing = h }, icon = Icons.Filled.Delete)
                        }
                    }
                }
            }
        }

        CyberPanel(title = "Play from anywhere") {
            Text(
                "Install Tailscale (free) on the PC and the Thor and sign in with the same account. Put the PC's " +
                    "Tailscale address (100.x.y.z) in REMOTE ADDRESS via Edit. REDLINE uses the LAN route at home and " +
                    "the Tailscale route everywhere else — any Wi-Fi works.\n\nTo power the PC on while away, run the " +
                    "agent with --relay on an always-on device at home (Raspberry Pi, NAS, old laptop) and put its " +
                    "Tailscale address in WAKE RELAY.",
                color = Neon.Muted, fontFamily = Mono, fontSize = 12.sp,
            )
        }
    }

    pairing?.let { target -> PairDialog(target, vm) { pairing = null } }
    editing?.let { h -> EditHostDialog(h, onSave = { vm.updateHost(it); editing = null }, onDismiss = { editing = null }) }
    removing?.let { h ->
        ConfirmDialog("Forget ${h.name}", "Remove this PC from REDLINE? You'll need a new PIN to pair again.", "Forget",
            onConfirm = { vm.removeHost(h.id) }, onDismiss = { removing = null })
    }
}

@Composable
private fun PairDialog(target: PairTarget, vm: AppViewModel, onDismiss: () -> Unit) {
    var pin by remember { mutableStateOf("") }
    var remote by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Neon.Panel, shape = CutShape, modifier = Modifier.border(1.dp, Neon.Red, CutShape),
        title = { Text("JACK INTO ${target.name.uppercase()}", color = Neon.Red, fontFamily = Mono, fontWeight = FontWeight.Bold) },
        text = {
            Column {
                Text("Enter the 6-digit PIN shown in the agent window on the PC.", color = Neon.Text, fontFamily = Mono, fontSize = 13.sp)
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    pin, { pin = it.filter(Char::isDigit).take(6) }, label = { Text("PIN", fontFamily = Mono) },
                    singleLine = true, shape = SmallCut, colors = cyberFieldColors(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    remote, { remote = it.trim() }, label = { Text("Remote address (optional, Tailscale)", fontFamily = Mono) },
                    singleLine = true, shape = SmallCut, colors = cyberFieldColors(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                )
            }
        },
        confirmButton = {
            NeonButton("Link", filled = true, enabled = pin.length == 6, onClick = {
                vm.pair(target.address, target.port, pin, remote, onDone = onDismiss)
            })
        },
        dismissButton = { NeonButton("Cancel", onClick = onDismiss) },
    )
}

@Composable
private fun EditHostDialog(host: SavedHost, onSave: (SavedHost) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(host.name) }
    var lan by remember { mutableStateOf(host.lanAddress) }
    var remote by remember { mutableStateOf(host.remoteAddress) }
    var mac by remember { mutableStateOf(host.mac) }
    var relay by remember { mutableStateOf(host.relayAddress) }
    var broadcast by remember { mutableStateOf(host.broadcast) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Neon.Panel, shape = CutShape, modifier = Modifier.border(1.dp, Neon.Red, CutShape),
        title = { Text("RIG CONFIG", color = Neon.Red, fontFamily = Mono, fontWeight = FontWeight.Bold) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                ConfigField("Name", name) { name = it }
                ConfigField("LAN address", lan) { lan = it.trim() }
                ConfigField("Remote address (Tailscale IP / name)", remote) { remote = it.trim() }
                ConfigField("MAC (Wake-on-LAN)", mac) { mac = it.trim() }
                ConfigField("Wake relay address", relay) { relay = it.trim() }
                ConfigField("LAN broadcast", broadcast) { broadcast = it.trim() }
            }
        },
        confirmButton = {
            NeonButton("Save", filled = true, onClick = {
                onSave(host.copy(name = name, lanAddress = lan, remoteAddress = remote, mac = mac,
                    relayAddress = relay, broadcast = broadcast.ifBlank { "255.255.255.255" }))
            })
        },
        dismissButton = { NeonButton("Cancel", onClick = onDismiss) },
    )
}

@Composable
private fun ConfigField(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(value, onChange, label = { Text(label, fontFamily = Mono) }, singleLine = true,
        shape = SmallCut, colors = cyberFieldColors(), modifier = Modifier.fillMaxWidth())
    Spacer(Modifier.height(6.dp))
}
