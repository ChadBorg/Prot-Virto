package com.protvirto.redline.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.protvirto.redline.ui.theme.Mono
import com.protvirto.redline.ui.theme.Neon

val CutShape = CutCornerShape(topStart = 0.dp, topEnd = 18.dp, bottomEnd = 0.dp, bottomStart = 18.dp)
val SmallCut = CutCornerShape(topStart = 0.dp, topEnd = 8.dp, bottomEnd = 0.dp, bottomStart = 8.dp)

/** Black void + red grid + corner glow + CRT scanlines. */
@Composable
fun CyberBackground(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    Box(
        modifier
            .fillMaxSize()
            .background(Neon.Black)
            .drawBehind {
                val step = 44.dp.toPx()
                val gridColor = Neon.Red.copy(alpha = 0.05f)
                var x = 0f
                while (x < size.width) {
                    drawLine(gridColor, Offset(x, 0f), Offset(x, size.height), 1f); x += step
                }
                var y = 0f
                while (y < size.height) {
                    drawLine(gridColor, Offset(0f, y), Offset(size.width, y), 1f); y += step
                }
                val r = size.maxDimension * 0.55f
                drawCircle(
                    Brush.radialGradient(listOf(Neon.Red.copy(alpha = 0.16f), Color.Transparent),
                        center = Offset(size.width, 0f), radius = r),
                    radius = r, center = Offset(size.width, 0f),
                )
                drawCircle(
                    Brush.radialGradient(listOf(Neon.Red.copy(alpha = 0.08f), Color.Transparent),
                        center = Offset(0f, size.height), radius = r * 0.8f),
                    radius = r * 0.8f, center = Offset(0f, size.height),
                )
            }
    ) {
        content()
        Scanlines()
    }
}

@Composable
fun Scanlines(modifier: Modifier = Modifier) {
    val t = rememberInfiniteTransition(label = "scan")
    val sweep by t.animateFloat(
        0f, 1f, infiniteRepeatable(tween(7000, easing = LinearEasing)), label = "sweep"
    )
    Canvas(modifier.fillMaxSize()) {
        val step = 3.dp.toPx()
        var y = 0f
        val line = Color.Black.copy(alpha = 0.22f)
        while (y < size.height) {
            drawLine(line, Offset(0f, y), Offset(size.width, y), 1f); y += step
        }
        val band = 120.dp.toPx()
        val sy = sweep * (size.height + band) - band
        drawRect(
            Brush.verticalGradient(
                listOf(Color.Transparent, Neon.Red.copy(alpha = 0.05f), Color.Transparent),
                startY = sy, endY = sy + band,
            ),
            topLeft = Offset(0f, sy), size = Size(size.width, band),
        )
    }
}

/** Title text that occasionally tears into red/white offset copies. */
@Composable
fun GlitchText(text: String, modifier: Modifier = Modifier, style: TextStyle = MaterialTheme.typography.headlineMedium) {
    val t = rememberInfiniteTransition(label = "glitch")
    val phase by t.animateFloat(0f, 1f, infiniteRepeatable(tween(3200, easing = LinearEasing)), label = "phase")
    val glitching = phase in 0.90f..0.96f
    val jitter = if (glitching) (((phase * 997).toInt() % 5) - 2).dp else 0.dp
    Box(modifier) {
        if (glitching) {
            Text(text, style = style, color = Neon.Red, fontWeight = FontWeight.Black,
                modifier = Modifier.offset(x = jitter + 2.dp))
            Text(text, style = style, color = Color.White.copy(alpha = 0.5f), fontWeight = FontWeight.Black,
                modifier = Modifier.offset(x = -jitter - 2.dp))
        }
        Text(text, style = style, color = Neon.Text, fontWeight = FontWeight.Black, modifier = Modifier.offset(x = jitter))
    }
}

@Composable
fun CyberPanel(
    modifier: Modifier = Modifier,
    title: String? = null,
    accent: Color = Neon.Red,
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier
            .clip(CutShape)
            .background(Brush.verticalGradient(listOf(Neon.PanelHi, Neon.Panel)))
            .border(1.dp, accent.copy(alpha = 0.65f), CutShape)
            .padding(16.dp)
    ) {
        if (title != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).background(accent))
                Spacer(Modifier.width(10.dp))
                Text(title.uppercase(), color = accent, fontFamily = Mono, fontSize = 13.sp,
                    fontWeight = FontWeight.Bold, letterSpacing = 3.sp)
                Spacer(Modifier.weight(1f))
                if (trailing != null) trailing() else
                    Text("//", color = accent.copy(alpha = 0.4f), fontFamily = Mono, fontSize = 13.sp)
            }
            Spacer(Modifier.height(12.dp))
        }
        content()
    }
}

@Composable
fun NeonButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    filled: Boolean = false,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    accent: Color = Neon.Red,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val pressed by interaction.collectIsPressedAsState()
    val hot = enabled && (focused || pressed)
    val bg = when {
        !enabled -> Color.Transparent
        filled && hot -> Neon.RedHot
        filled || hot -> accent
        else -> Color.Transparent
    }
    val fg = when {
        !enabled -> Neon.Muted
        filled || hot -> Neon.Black
        else -> accent
    }
    Row(
        modifier
            .clip(SmallCut)
            .background(bg)
            .border(if (focused) 2.dp else 1.dp, if (enabled) accent else Neon.RedDim, SmallCut)
            .clickable(interactionSource = interaction, indication = null, enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text.uppercase(), color = fg, fontFamily = Mono, fontWeight = FontWeight.Bold,
            letterSpacing = 2.sp, fontSize = 13.sp, maxLines = 1)
    }
}

@Composable
fun StatBar(label: String, fraction: Float, valueText: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Row {
            Text(label.uppercase(), color = Neon.Muted, fontFamily = Mono, fontSize = 11.sp, letterSpacing = 2.sp)
            Spacer(Modifier.weight(1f))
            Text(valueText, color = Neon.Red, fontFamily = Mono, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(5.dp))
        Box(Modifier.fillMaxWidth().height(7.dp).background(Neon.Blood)) {
            Box(
                Modifier.fillMaxHeight().fillMaxWidth(fraction.coerceIn(0f, 1f))
                    .background(Brush.horizontalGradient(listOf(Neon.RedDim, Neon.Red)))
            )
            // segment ticks
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.SpaceEvenly) {
                repeat(9) { Box(Modifier.width(2.dp).fillMaxHeight().background(Neon.Black)) }
            }
        }
    }
}

@Composable
fun PulseDot(active: Boolean, modifier: Modifier = Modifier) {
    val t = rememberInfiniteTransition(label = "pulse")
    val a by t.animateFloat(0.35f, 1f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "a")
    Box(modifier.size(10.dp).alpha(if (active) a else 1f).background(if (active) Neon.Red else Neon.Muted, SmallCut))
}

@Composable
fun CyberToggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit, sub: String? = null) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Row(
        Modifier.fillMaxWidth()
            .background(if (focused) Neon.Blood else Color.Transparent)
            .clickable(interactionSource = interaction, indication = null) { onChange(!checked) }
            .padding(vertical = 8.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, color = Neon.Text, fontFamily = Mono, fontSize = 14.sp)
            if (sub != null) Text(sub, color = Neon.Muted, fontFamily = Mono, fontSize = 11.sp)
        }
        Spacer(Modifier.width(12.dp))
        Box(
            Modifier.size(width = 48.dp, height = 24.dp).clip(SmallCut)
                .background(if (checked) Neon.Red.copy(alpha = 0.25f) else Color.Transparent)
                .border(1.dp, if (checked) Neon.Red else Neon.RedDim, SmallCut).padding(4.dp)
        ) {
            Box(
                Modifier.align(if (checked) Alignment.CenterEnd else Alignment.CenterStart).size(16.dp)
                    .background(if (checked) Neon.Red else Neon.Muted)
            )
        }
    }
}

@Composable
fun <T> OptionRow(label: String, options: List<Pair<String, T>>, selected: T, onSelect: (T) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(label.uppercase(), color = Neon.Muted, fontFamily = Mono, fontSize = 11.sp, letterSpacing = 2.sp)
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { (text, value) ->
                NeonButton(text, onClick = { onSelect(value) }, filled = value == selected)
            }
        }
    }
}

@Composable
fun ConfirmDialog(
    title: String,
    message: String,
    confirm: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Neon.Panel,
        shape = CutShape,
        modifier = Modifier.border(1.dp, Neon.Red, CutShape),
        title = { Text(title.uppercase(), color = Neon.Red, fontFamily = Mono, fontWeight = FontWeight.Bold, letterSpacing = 2.sp) },
        text = { Text(message, color = Neon.Text, fontFamily = Mono, fontSize = 14.sp) },
        confirmButton = { NeonButton(confirm, onClick = { onConfirm(); onDismiss() }, filled = true) },
        dismissButton = { NeonButton("Cancel", onClick = onDismiss) },
    )
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(text.uppercase(), modifier = modifier, color = Neon.Muted, fontFamily = Mono, fontSize = 11.sp, letterSpacing = 3.sp)
}

fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "—"
    val gb = bytes / 1_073_741_824.0
    return if (gb >= 1) "%.1f GB".format(gb) else "%.0f MB".format(bytes / 1_048_576.0)
}

fun formatUptime(seconds: Long): String {
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    return if (h > 0) "${h}h ${m}m" else "${m}m"
}
