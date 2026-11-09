package com.protvirto.redline.ui.screens

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.protvirto.redline.ui.AppViewModel
import com.protvirto.redline.ui.CyberPanel
import com.protvirto.redline.ui.NeonButton
import com.protvirto.redline.ui.SmallCut
import com.protvirto.redline.ui.theme.Mono
import com.protvirto.redline.ui.theme.Neon

@Composable
fun cyberFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = Neon.Red,
    unfocusedBorderColor = Neon.RedDim,
    focusedTextColor = Neon.Text,
    unfocusedTextColor = Neon.Text,
    cursorColor = Neon.Red,
    focusedLabelColor = Neon.Red,
    unfocusedLabelColor = Neon.Muted,
)

@Composable
fun StoreScreen(vm: AppViewModel) {
    val results by vm.storeResults.collectAsState()
    val loading by vm.storeLoading.collectAsState()
    val reachable by vm.reachable.collectAsState()
    var query by rememberSaveable { mutableStateOf("") }

    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = query, onValueChange = { query = it },
                label = { Text("Search Steam", fontFamily = Mono) },
                singleLine = true, shape = SmallCut, colors = cyberFieldColors(),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { if (query.isNotBlank()) vm.search(query) }),
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(12.dp))
            NeonButton("Scan", onClick = { vm.search(query) }, filled = true, icon = Icons.Filled.Search,
                enabled = query.isNotBlank() && reachable == true)
        }
        Spacer(Modifier.height(6.dp))
        Text(
            "Installs download on the PC through Steam (games you own). Progress shows in LIBRARY → Downloading.",
            color = Neon.Muted, fontFamily = Mono, fontSize = 11.sp,
        )
        Spacer(Modifier.height(12.dp))
        if (loading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Neon.Red) }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
                items(results, key = { it.appid }) { r ->
                    CyberPanel {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CoverArt(r.hero, r.image, r.name,
                                Modifier.width(184.dp).aspectRatio(460f / 215f).clip(SmallCut).border(1.dp, Neon.RedDim, SmallCut))
                            Spacer(Modifier.width(16.dp))
                            Column(Modifier.weight(1f)) {
                                Text(r.name.uppercase(), color = Neon.Text, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    if (r.price > 0) "%.2f %s".format(r.price, r.currency) else "FREE / OWNED",
                                    color = Neon.Muted, fontFamily = Mono, fontSize = 12.sp,
                                )
                            }
                            if (r.installed) {
                                Text("IN LIBRARY", color = Neon.Red, fontFamily = Mono, fontSize = 12.sp)
                            } else {
                                NeonButton("Install", onClick = { vm.install(r) }, icon = Icons.Filled.Add)
                            }
                        }
                    }
                }
            }
        }
    }
}
