package com.example.taximeter

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun HomeTab(
    ride: Ride,
    running: Boolean,
    tariffs: Tariffs,
    // gpsFix has a DEFAULT (null), so both old and new MainActivity calls compile.
    gpsFix: GpsFix? = null,
    onStart: () -> Unit,
    onBoard: () -> Unit,
    onToggleRun: () -> Unit,
    onMapTap: () -> Unit,
    onFinish: () -> Unit
) {
    val fare = ride.fare(tariffs)
    val km = ride.totalMeters / 1000.0
    val fuelCost = km * fuelEurPerKm(tariffs)
    // FIX: net profit = fare - fuel. Shown under the main fare (was €/სთ under time).
    val netProfit = fare - fuelCost
    val waitingForClient = ride.started && ride.mode == RideMode.PICKUP && !ride.boarded

    val mainLabel = when {
        !ride.started -> "▶ დაწყება"
        waitingForClient -> "🧍 ჩაჯდომა"
        running -> "⏸ პაუზა"
        else -> "▶ გაგრძელება"
    }
    val mainAction: () -> Unit = when {
        !ride.started -> onStart
        waitingForClient -> onBoard
        else -> onToggleRun
    }

    val gpsText = if (gpsFix == null) "GPS-ის ძებნა…" else "%.5f, %.5f".format(gpsFix.lat, gpsFix.lon)
    val accuracyText = if (gpsFix == null) "სიზუსტე: —" else "სიზუსტე: %.0fმ".format(gpsFix.accuracy)
    val gpsActive = gpsFix != null

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Map card.
        Column(
            modifier = Modifier.fillMaxWidth().weight(1f).card(),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).clip(CircleShape).background(if (gpsActive) Green else Red))
                Spacer(Modifier.width(8.dp))
                Text(
                    if (gpsActive) "GPS აქტიური ✓" else "GPS-ის ძებნა…",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp
                )
                Spacer(Modifier.weight(1f))
                Text(gpsText, color = Muted, fontSize = 12.sp)
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .clip(RoundedCornerShape(16.dp))
                    .background(MapBg)
                    .clickable(onClick = onMapTap)
            ) {
                // Real route if we have one, otherwise an empty grid.
                if (ride.path.size >= 2) {
                    RouteMap(path = ride.path, legs = ride.legs(), modifier = Modifier.fillMaxSize())
                } else {
                    // Empty map placeholder: keep the old centred dot so the screen
                    // does not look blank before the first GPS fix arrives.
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Box(
                            Modifier.size(28.dp).clip(CircleShape).background(Green),
                            contentAlignment = Alignment.Center
                        ) {
                            Box(Modifier.size(10.dp).clip(CircleShape).background(MapBg))
                        }
                    }
                }
                Text(
                    accuracyText,
                    color = Muted,
                    fontSize = 12.sp,
                    modifier = Modifier.align(Alignment.TopStart).padding(10.dp)
                )
            }
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                onClick = mainAction,
                modifier = Modifier.weight(1f).height(52.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Yellow, contentColor = Color.Black)
            ) {
                Text(mainLabel, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            }
            Button(
                onClick = onFinish,
                enabled = ride.started,
                modifier = Modifier.weight(1f).height(52.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Tile, contentColor = Color.White)
            ) {
                Text("⏹ დასრულება", fontWeight = FontWeight.Bold, fontSize = 16.sp)
            }
        }

        // Fare summary card.
        Row(
            modifier = Modifier.fillMaxWidth().card(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                // FIX: mode label removed - user does not want "ახლობლები" etc. here.
                Text("ფასი • EUR", color = Muted, fontSize = 13.sp)
                Row(verticalAlignment = Alignment.Bottom) {
                    Text("€", color = Yellow, fontSize = 40.sp, fontWeight = FontWeight.Bold)
                    Text("%.2f".format(fare), color = Color.White, fontSize = 48.sp, fontWeight = FontWeight.Bold)
                }
                // FIX: label removed - only the amount, bigger font, single line.
                Text("💰 €%.2f".format(netProfit), color = Green, fontSize = 22.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            }
            Box(Modifier.width(1.dp).height(120.dp).background(GridLine))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1.2f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                // FIX: StatTile replaced - its font/align live in another file.
                // These tiles are centered + bigger. Km = 1 decimal, time = H:MM:SS.
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Tile).padding(vertical = 12.dp, horizontal = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("%.1f კმ".format(km), color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, maxLines = 1)
                    Text("⛽ €%.2f".format(fuelCost), color = Muted, fontSize = 16.sp, textAlign = TextAlign.Center, maxLines = 1)
                }
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Tile).padding(vertical = 12.dp, horizontal = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        "%d:%02d:%02d".format(
                            (ride.totalSeconds + ride.totalWaitSeconds) / 3600,
                            ((ride.totalSeconds + ride.totalWaitSeconds) % 3600) / 60,
                            (ride.totalSeconds + ride.totalWaitSeconds) % 60
                        ),
                        color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, maxLines = 1
                    )
                }
            }
        }
    }
}
