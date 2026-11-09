package com.protvirto.redline.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.protvirto.redline.data.Game
import com.protvirto.redline.ui.AppViewModel
import com.protvirto.redline.ui.ConfirmDialog
import com.protvirto.redline.ui.CutShape
import com.protvirto.redline.ui.NeonButton
import com.protvirto.redline.ui.SmallCut
import com.protvirto.redline.ui.StatBar
import com.protvirto.redline.ui.formatBytes
import com.protvirto.redline.ui.theme.Mono
import com.protvirto.redline.ui.theme.Neon
import kotlinx.coroutines.delay

private enum class Filter(val label: String) { ALL("All"), INSTALLED("Installed"), DOWNLOADING("Downloading") }

@Composable
fun LibraryScreen(vm: AppViewModel) {
    val games by vm.games.collectAsState()
    val loading by vm.gamesLoading.collectAsState()
    val reachable by vm.reachable.collectAsState()
    var filter by remember { mutableStateOf(Filter.INSTALLED) }
    var selected by remember { mutableStateOf<Game?>(null) }
    var confirmDelete by remember { mutableStateOf<Game?>(null) }

    LaunchedEffect(reachable) {
        if (reachable == true) vm.loadGames(silent = games.isNotEmpty())
    }
    // Keep download progress fresh while something is downloading.
    LaunchedEffect(games.any { it.downloading }) {
        while (games.any { it.downloading }) {
            delay(5000); vm.loadGames(silent = true)
        }
    }

    val shown = games.filter {
        when (filter) {
            Filter.ALL -> true
            Filter.INSTALLED -> it.installed
            Filter.DOWNLOADING -> it.downloading
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Filter.entries.forEach { f -> NeonButton(f.label, onClick = { filter = f }, filled = f == filter) }
            Spacer(Modifier.weight(1f))
            Text("${shown.size} TITLES", color = Neon.Muted, fontFamily = Mono, fontSize = 12.sp)
            NeonButton("Sync", onClick = { vm.loadGames() }, icon = Icons.Filled.Refresh, enabled = reachable == true)
        }
        Spacer(Modifier.height(12.dp))
        when {
            reachable != true && games.isEmpty() -> Empty("Rig offline. Power it on from HOME.")
            loading && games.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Neon.Red)
            }
            shown.isEmpty() -> Empty("Nothing here. Grab something from the STORE tab.")
            else -> LazyVerticalGrid(
                columns = GridCells.Adaptive(150.dp),
                contentPadding = PaddingValues(bottom = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                items(shown, key = { it.id }) { g -> GameCard(g, onClick = { selected = g }) }
            }
        }
    }

    selected?.let { g ->
        GameDialog(
            g,
            onPlay = { selected = null; vm.play(g) },
            onDelete = { selected = null; confirmDelete = g },
            onDismiss = { selected = null },
        )
    }
    confirmDelete?.let { g ->
        ConfirmDialog(
            "Delete ${g.name}",
            "This removes ${formatBytes(g.size_bytes)} of game files from the PC. Steam will be restarted.",
            "Delete",
            onConfirm = { vm.uninstall(g) },
            onDismiss = { confirmDelete = null },
        )
    }
}

@Composable
private fun Empty(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text.uppercase(), color = Neon.Muted, fontFamily = Mono, letterSpacing = 2.sp)
    }
}

@Composable
fun CoverArt(image: String, fallback: String, name: String, modifier: Modifier = Modifier) {
    var url by remember(image) { mutableStateOf(image.ifBlank { fallback }) }
    Box(modifier.background(Neon.Blood)) {
        Text(
            name.uppercase(), color = Neon.Red.copy(alpha = 0.7f), fontFamily = Mono, fontWeight = FontWeight.Bold,
            fontSize = 13.sp, modifier = Modifier.align(Alignment.Center).padding(10.dp),
        )
        if (url.isNotBlank()) {
            AsyncImage(
                model = url, contentDescription = name, contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
                onError = { if (url != fallback && fallback.isNotBlank()) url = fallback },
            )
        }
    }
}

@Composable
private fun GameCard(game: Game, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Column(
        Modifier.clip(CutShape)
            .border(if (focused) 3.dp else 1.dp, if (focused) Neon.Red else Neon.RedDim, CutShape)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(2f / 3f)) {
            CoverArt(game.image, game.hero, game.name, Modifier.fillMaxSize())
            Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(listOf(Color.Transparent, Color.Transparent, Neon.Black.copy(alpha = 0.9f)))
                )
            )
            Text(
                game.source.uppercase(), color = Neon.Black, fontFamily = Mono, fontSize = 9.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.TopStart).background(Neon.Red).padding(horizontal = 6.dp, vertical = 2.dp),
            )
            if (game.downloading) {
                Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(6.dp).background(Neon.Blood)) {
                    Box(Modifier.fillMaxHeight().fillMaxWidth(game.progress.toFloat().coerceIn(0f, 1f)).background(Neon.Red))
                }
            }
        }
        Text(
            game.name, color = if (focused) Neon.Red else Neon.Text, fontFamily = Mono, fontSize = 12.sp,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth().background(Neon.Panel).padding(horizontal = 8.dp, vertical = 8.dp),
        )
    }
}

@Composable
private fun GameDialog(game: Game, onPlay: () -> Unit, onDelete: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Neon.Panel,
        shape = CutShape,
        modifier = Modifier.border(1.dp, Neon.Red, CutShape),
        title = null,
        text = {
            Row {
                CoverArt(game.image, game.hero, game.name, Modifier.width(130.dp).aspectRatio(2f / 3f).clip(SmallCut))
                Spacer(Modifier.width(18.dp))
                Column(Modifier.weight(1f)) {
                    Text(game.name.uppercase(), color = Neon.Text, fontFamily = Mono, fontWeight = FontWeight.Black, fontSize = 20.sp)
                    Spacer(Modifier.height(6.dp))
                    Text("${game.source.uppercase()} · ${formatBytes(game.size_bytes)}", color = Neon.Muted, fontFamily = Mono, fontSize = 12.sp)
                    Spacer(Modifier.height(14.dp))
                    when {
                        game.downloading -> StatBar("Downloading", game.progress.toFloat(), "%.0f%%".format(game.progress * 100))
                        !game.installed -> Text("Not installed", color = Neon.Muted, fontFamily = Mono)
                        else -> Text("Ready to run", color = Neon.Red, fontFamily = Mono)
                    }
                }
            }
        },
        confirmButton = {
            NeonButton("Play", onClick = onPlay, filled = true, icon = Icons.Filled.PlayArrow, enabled = game.installed)
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (game.can_uninstall && (game.installed || game.downloading)) {
                    NeonButton("Delete", onClick = onDelete, icon = Icons.Filled.Delete)
                }
                NeonButton("Back", onClick = onDismiss)
            }
        },
    )
}
