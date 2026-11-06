package com.example.taximeter

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

private fun legColor(kind: LegKind?): Color = when (kind) {
    LegKind.PICKUP -> Green
    LegKind.FRIENDS -> Yellow
    else -> Blue
}

/** Draws a saved route: each leg in its own colour, start in green and finish in red. */
@Composable
fun RoutePreview(path: List<PathPoint>, legs: List<Leg>, modifier: Modifier) {
    Box(modifier.clip(RoundedCornerShape(16.dp)).background(MapBg)) {
        Canvas(Modifier.fillMaxSize()) {
            val step = 40.dp.toPx()
            var x = 0f
            while (x <= size.width) {
                drawLine(GridLine, Offset(x, 0f), Offset(x, size.height), strokeWidth = 1f)
                x += step
            }
            var y = 0f
            while (y <= size.height) {
                drawLine(GridLine, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
                y += step
            }
            // FIX: PathPoint now holds real lat/lon (Double), NOT 0..1 x/y.
            // Before: pt.x * width with x = 43.7 gave a point far off-screen,
            // so the saved route never showed. Now we use the same project()
            // as RouteMap in Route.kt, so the route fits the box.
            val pts = project(path, size.width, size.height)
            pts.indices.groupBy { path[it].leg }.forEach { (leg, idx) ->
                if (idx.size < 2) return@forEach
                val line = Path()
                idx.forEachIndexed { i, k ->
                    if (i == 0) line.moveTo(pts[k].x, pts[k].y) else line.lineTo(pts[k].x, pts[k].y)
                }
                drawPath(line, color = legColor(legs.getOrNull(leg)?.kind), style = Stroke(width = 5f, cap = StrokeCap.Round))
            }
            pts.firstOrNull()?.let {
                drawCircle(Green, radius = 9.dp.toPx(), center = it)
            }
            pts.lastOrNull()?.let {
                drawCircle(Red, radius = 9.dp.toPx(), center = it)
            }
        }
    }
}

// FIX: old comment listed a field that no longer exists. Now: "fuel and profit".
/** Details of a finished trip: route, fare breakdown, fuel and profit, with delete. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TripDetailSheet(trip: Trip, onDismiss: () -> Unit, onDelete: () -> Unit) {
    val sheetState = rememberModalBottomSheetState()
    val scope = rememberCoroutineScope()
    val close: () -> Unit = {
        scope.launch { sheetState.hide() }.invokeOnCompletion { onDismiss() }
    }
    ModalBottomSheet(
        onDismissRequest = close,
        sheetState = sheetState,
        containerColor = CardBg,
        contentColor = Color.White,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SheetHeader("დეტალები", close)
            RoutePreview(trip.path, trip.legs, Modifier.fillMaxWidth().height(220.dp))
            // FIX: legend was missing FRIENDS (yellow). Before it said only
            // green/blue/red, so a friends trip route had no explanation.
            Text(
                "🟢 კლიენტამდე 🟡 ახლობლები 🔵 მგზავრობა 🔴 დასრულება",
                color = Muted,
                fontSize = 12.sp,
                modifier = Modifier.fillMaxWidth(),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MiniStat("თარიღი", fmtDateTime(trip.time), Color.White, "", Modifier.weight(1f))
                MiniStat("რეჟიმი", trip.mode.label, Color.White, "", Modifier.weight(1f))
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MiniStat("კმ", "%.3f".format(trip.km), Color.White, "%.1f წთ".format(trip.seconds / 60.0), Modifier.weight(1f))
                MiniStat("ფასი", "€%.2f".format(trip.fare), Yellow, "", Modifier.weight(1f))
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MiniStat("მოგება", "€%.2f".format(trip.profit), Green, "", Modifier.weight(1f))
            }

            Column(Modifier.fillMaxWidth().card(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("📋 ჩაშლილობა", color = Blue, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                trip.lines.groupBy { it.group }.forEach { (group, rows) ->
                    val isPickup = group.startsWith("🚗")
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(if (isPickup) BlueBg else Tile)
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(group, color = if (isPickup) Blue else Yellow, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                        rows.forEach { row -> AmountRow(row.label, row.amount, Color.White, false) }
                        AmountRow("სულ", rows.sumOf { it.amount }, if (isPickup) Blue else Yellow, true)
                    }
                }
                AmountRow("საწვავი ⛽", trip.fuel, Color.White, false)
                Box(Modifier.fillMaxWidth().height(1.dp).background(GridLine))
                AmountRow("სულ", trip.fare, Yellow, true)
                Row(
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color(0xFF0F2A1A)).padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("💰 წმინდა მოგება", color = Green, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Spacer(Modifier.weight(1f))
                    Text("€%.2f".format(trip.profit), color = Green, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                }
            }

            Button(
                // FIX: delete now closes the sheet too. Before, onDelete() was called
                // but the sheet stayed open unless the parent dismissed it.
                onClick = {
                    close()
                    onDelete()
                },
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Tile, contentColor = Red)
            ) {
                Text("🗑 წაშლა", fontWeight = FontWeight.Bold, fontSize = 16.sp)
            }
        }
    }
}

@Composable
private fun AmountRow(label: String, amount: Double, color: Color, bold: Boolean) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = color, fontSize = 14.sp, fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal)
        Text("€%.2f".format(amount), color = color, fontSize = 14.sp, fontWeight = FontWeight.Bold)
    }
}

/** Asks which tariff to start the ride with. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModePickerSheet(tariffs: Tariffs, onPick: (RideMode) -> Unit, onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState()
    val scope = rememberCoroutineScope()
    val close: () -> Unit = {
        scope.launch { sheetState.hide() }.invokeOnCompletion { onDismiss() }
    }
    ModalBottomSheet(
        onDismissRequest = close,
        sheetState = sheetState,
        containerColor = CardBg,
        contentColor = Color.White,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            ModeRow(
                "📊 სტანდარტული",
                "0-5კმ €%.2f • 5-15კმ €%.2f • 15+კმ €%.2f • ჩაჯდომა €%.2f".format(
                    tariffs.km0to5, tariffs.km5to15, tariffs.km15plus, tariffs.boarding
                ),
                Tile, Color.White
            ) {
                // FIX: picking a mode now closes the sheet too.
                close()
                onPick(RideMode.STANDARD)
            }
            ModeRow(
                "👥 ახლობლები",
                "1 კმ €%.2f • ლოდინი €%.2f/წთ • ჩაჯდომა €%.2f".format(
                    tariffs.friendPerKm, tariffs.friendWaitPerMinute, tariffs.friendBoarding
                ),
                Tile, Color.White
            ) {
                close()
                onPick(RideMode.FRIENDS)
            }
            ModeRow(
                "🚗 კლიენტამდე",
                "კმ €%.2f • ლოდინი €%.2f/წთ • ჩაჯდომის შემდეგ სტანდარტული".format(
                    tariffs.pickupPerKm, tariffs.pickupWaitPerMinute
                ),
                BlueBg, Blue
            ) {
                close()
                onPick(RideMode.PICKUP)
            }
        }
    }
}

@Composable
private fun ModeRow(title: String, subtitle: String, background: Color, titleColor: Color, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(background)
            .clickable(onClick = onClick)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(title, color = titleColor, fontWeight = FontWeight.Bold, fontSize = 16.sp)
        Text(subtitle, color = Muted, fontSize = 12.sp)
    }
}
