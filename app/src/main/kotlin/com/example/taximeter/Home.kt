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
    // Real road limit from OSM (null -> use the manual limit from tariffs).
    roadLimit: Double? = null,
    // Tapping the limit sign cycles 30/50/70/90/110/130 and saves it to tariffs.
    onSpeedLimitChange: (Double) -> Unit = {},
    onStart: () -> Unit,
    onBoard: () -> Unit,
    onToggleRun: () -> Unit,
    onMapTap: () -> Unit,
    onFinish: () -> Unit
) {
    val fare = ride.fare(tariffs)
    val km = ride.totalMeters / 1000.0
    val hours = ride.totalSeconds / 3600.0
    // Average speed (km / hours) - shown small, top-right of the map.
    val speedKmh = if (hours > 0) km / hours else 0.0
    val fuelCost = km * fuelEurPerKm(tariffs)
    val perHour = if (hours > 0) fare / hours else 0.0
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
                Text(
                    "%.1f კმ/სთ".format(speedKmh),
                    color = Muted,
                    fontSize = 12.sp,
                    modifier = Modifier.align(Alignment.TopEnd).padding(10.dp)
                )
                // SPEEDOMETER - exactly like Waze: SMALL, bottom-left corner.
                // Circle 72dp, limit sign 50dp (both x1.25), sign pulled onto the circle.
                val shownLimit = roadLimit ?: tariffs.speedLimitKmh
                // Instant GPS speed: live fix if we have one (works even before Start),
                // else the speed the service saved into the ride.
                val instantSpeed = gpsFix?.speedKmh ?: ride.speedKmh
                val overLimit = instantSpeed > shownLimit
                Box(
                    Modifier.align(Alignment.BottomStart).padding(10.dp).size(100.dp)
                ) {
                    // Dark speed circle, bottom-left. Circle = 72dp.
                    Box(
                        Modifier.size(72.dp).align(Alignment.BottomStart)
                            .clip(CircleShape).background(Color(0xFF1E1E1E)),
                        contentAlignment = Alignment.Center
                    ) {
                        // Segmented ring: tick marks around the edge, like Waze.
                        // Red when over the limit, grey otherwise.
                        Canvas(Modifier.fillMaxSize()) {
                            val cx = size.width / 2
                            val cy = size.height / 2
                            val outerR = size.minDimension / 2 - 0.5.dp.toPx()
                            val innerR = outerR - 2.5.dp.toPx()
                            val tickColor = if (overLimit) Color.Red else Color(0xFF777777)
                            for (deg in 0 until 360 step 9) {
                                val rad = deg * Math.PI / 180.0
                                val x1 = cx + (innerR * cos(rad)).toFloat()
                                val y1 = cy + (innerR * sin(rad)).toFloat()
                                val x2 = cx + (outerR * cos(rad)).toFloat()
                                val y2 = cy + (outerR * sin(rad)).toFloat()
                                drawLine(tickColor, Offset(x1, y1), Offset(x2, y2), strokeWidth = 2.5.dp.toPx())
                            }
                        }
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                "%.0f".format(instantSpeed),
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 30.sp
                            )
                            Text("კმ/სთ", color = Color.White, fontSize = 11.sp)
                        }
                    }
                    // Limit sign overlapping the top-right edge of the circle.
                    // Sign = 50dp, pulled 5dp left + 5dp down onto the circle.
                    // Thin white rim outside the thick red ring, like the road sign.
                    // Tap to cycle 30/50/70/90/110/130.
                    Box(
                        Modifier.size(50.dp).align(Alignment.TopEnd)
                            .offset(x = (-5).dp, y = 5.dp)
                            .clip(CircleShape).background(Color.White)
                            .padding(1.dp)
                            .clip(CircleShape).background(Color.White)
                            .border(8.dp, Color.Red, CircleShape)
                            .clickable {
                                val limits = listOf(30.0, 50.0, 70.0, 90.0, 110.0, 130.0)
                                val next = limits.firstOrNull { it > shownLimit } ?: limits.first()
                                onSpeedLimitChange(next)
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Text("%.0f".format(shownLimit), color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    }
                }
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
                val modeTitle = if (ride.started) " • ${ride.mode.label}" else ""
                Text("ფასი • EUR$modeTitle", color = Muted, fontSize = 13.sp)
                Row(verticalAlignment = Alignment.Bottom) {
                    Text("€", color = Yellow, fontSize = 40.sp, fontWeight = FontWeight.Bold)
                    Text("%.2f".format(fare), color = Color.White, fontSize = 48.sp, fontWeight = FontWeight.Bold)
                }
            }
            Box(Modifier.width(1.dp).height(120.dp).background(GridLine))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1.2f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                StatTile(value = "%.3f კმ".format(km), sub = "⛽ €%.2f".format(fuelCost), subColor = Muted)
                StatTile(
                    value = "%.1f წთ".format(ride.totalSeconds / 60.0),
                    sub = "💰 €%.2f/სთ".format(perHour),
                    subColor = Green
                )
            }
        }
    }
}
