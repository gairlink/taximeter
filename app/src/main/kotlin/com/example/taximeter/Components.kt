package com.example.taximeter

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal val Bg = Color(0xFF0B0B0D)
internal val CardBg = Color(0xFF16161A)
internal val Tile = Color(0xFF1F1F25)
internal val MapBg = Color(0xFF111114)
internal val GridLine = Color(0xFF26262C)
internal val Yellow = Color(0xFFFFD600)
internal val Green = Color(0xFF00E676)
internal val Red = Color(0xFFFF4D4D)
internal val Muted = Color(0xFF8A8A93)
internal val Blue = Color(0xFF4DA3FF)
internal val BlueBg = Color(0xFF13233D)

internal fun Modifier.card(): Modifier =
    this.clip(RoundedCornerShape(24.dp)).background(CardBg).padding(14.dp)

@Composable
internal fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedContainerColor = CardBg,
    unfocusedContainerColor = CardBg,
    focusedBorderColor = Color.Transparent,
    unfocusedBorderColor = Color.Transparent,
    focusedTextColor = Color.White,
    unfocusedTextColor = Color.White,
    cursorColor = Yellow
)

@Composable
internal fun IconTile(label: String, onClick: (() -> Unit)? = null) {
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Tile)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
    }
}

@Composable
internal fun SheetHeader(title: String, onClose: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 20.sp)
        Spacer(Modifier.weight(1f))
        IconTile("✕", onClick = onClose)
    }
}

@Composable
internal fun StatTile(value: String, sub: String, subColor: Color) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Tile)
            .padding(vertical = 12.dp, horizontal = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(value, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 22.sp)
        Text(sub, color = subColor, fontSize = 14.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
internal fun MiniStat(label: String, value: String, valueColor: Color, sub: String, modifier: Modifier) {
    Column(modifier.clip(RoundedCornerShape(14.dp)).background(Tile).padding(12.dp)) {
        Text(label, color = Muted, fontSize = 12.sp)
        Text(value, color = valueColor, fontWeight = FontWeight.Bold, fontSize = 20.sp)
        if (sub.isNotEmpty()) Text(sub, color = Muted, fontSize = 12.sp)
    }
}

@Composable
internal fun ProfitLine(label: String, value: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label + " ", color = Color.White, fontSize = 14.sp)
        Text(value, color = color, fontWeight = FontWeight.Bold, fontSize = 14.sp)
    }
}

@Composable
internal fun ActionChip(label: String, color: Color, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(Tile)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Text(label, color = color, fontWeight = FontWeight.Bold, fontSize = 15.sp)
    }
}

@Composable
internal fun PeriodChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) Yellow else Tile)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Text(
            label,
            color = if (selected) Color.Black else Color.White,
            fontWeight = FontWeight.Bold,
            fontSize = 14.sp
        )
    }
}
