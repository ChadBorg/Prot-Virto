package com.protvirto.capri

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Night City colours, as her panels wear them (capri.css). */
object Night {
    val Bg = Color(0xFF07060B)
    val Plate = Color(0xFF0E0C14)
    val Cyan = Color(0xFF2DF0FF)
    val Yellow = Color(0xFFFCEE0A)
    val Red = Color(0xFFFF2B4E)
    val Green = Color(0xFF36FF7A)
    val Text = Color(0xFFEDEAF5)
    val Muted = Color(0xFF8F8AA3)
}

val Cut = CutCornerShape(topStart = 0.dp, topEnd = 14.dp, bottomEnd = 0.dp, bottomStart = 14.dp)

/** Her panels use Chakra Petch (woff2, which Android's font loader can't read): monospace here. */
@Suppress("UNUSED_PARAMETER")
fun chakra(context: Context): FontFamily = FontFamily.Monospace

@Composable
fun Plate(title: String, font: FontFamily, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier.fillMaxWidth().background(Night.Plate, Cut).border(1.dp, Night.Cyan.copy(alpha = 0.6f), Cut).padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("///", color = Night.Yellow, fontFamily = font, fontWeight = FontWeight.Bold, fontSize = 12.sp)
            Spacer(Modifier.width(8.dp))
            Text(title.uppercase(), color = Night.Cyan, fontFamily = font, fontWeight = FontWeight.Bold, fontSize = 13.sp, letterSpacing = 2.sp)
        }
        Spacer(Modifier.height(10.dp))
        content()
    }
}

@Composable
fun Btn(text: String, font: FontFamily, onClick: () -> Unit, primary: Boolean = false, danger: Boolean = false, modifier: Modifier = Modifier) {
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    val accent = if (danger) Night.Red else if (primary) Night.Yellow else Night.Cyan
    Box(
        modifier
            .background(if (primary || focused) accent else Color.Transparent, Cut)
            .border(if (focused) 2.dp else 1.dp, accent, Cut)
            .clickable(interactionSource = source, indication = null, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text.uppercase(), color = if (primary || focused) Night.Bg else accent,
            fontFamily = font, fontWeight = FontWeight.Bold, fontSize = 13.sp, letterSpacing = 1.sp,
        )
    }
}

@Composable
fun Check(label: String, sub: String?, checked: Boolean, font: FontFamily, onChange: (Boolean) -> Unit) {
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    Row(
        Modifier.fillMaxWidth().background(if (focused) Night.Cyan.copy(alpha = 0.12f) else Color.Transparent)
            .clickable(interactionSource = source, indication = null) { onChange(!checked) }
            .padding(vertical = 8.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, color = Night.Text, fontFamily = font, fontSize = 15.sp)
            if (sub != null) Text(sub, color = Night.Muted, fontSize = 12.sp)
        }
        Box(
            Modifier.size(width = 44.dp, height = 22.dp).border(1.dp, if (checked) Night.Green else Night.Muted, Cut).padding(3.dp),
            contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart,
        ) {
            Box(Modifier.size(16.dp).background(if (checked) Night.Green else Night.Muted))
        }
    }
}

@Composable
fun Choice(label: String, options: List<Pair<String, String>>, selected: String, font: FontFamily, onPick: (String) -> Unit) {
    Column(Modifier.padding(vertical = 6.dp)) {
        Text(label.uppercase(), color = Night.Muted, fontFamily = font, fontSize = 11.sp, letterSpacing = 2.sp)
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { (value, text) -> Btn(text, font, { onPick(value) }, primary = value == selected) }
        }
    }
}
