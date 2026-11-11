package com.example.taximeter

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.math.sqrt

/** One GPS reading. [accuracy] is the radius in metres. */
data class GpsFix(val lat: Double, val lon: Double, val accuracy: Float)

/** Readings less accurate than this are ignored. */
private const val MAX_ACCURACY_M = 20f

/** Distance is counted only after the car moves this far from the last counted point, which filters GPS jitter. */
private const val MIN_STEP_M = 5.0

/** Smallest map span in degrees (about 55 m), so a short route is not stretched over the whole map. */
private const val MIN_SPAN_DEG = 0.0005

private const val PAD_PX = 24f

fun hasLocationPermission(context: Context): Boolean =
    context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

/** Great-circle distance between two readings, in metres. */
fun distanceMeters(a: GpsFix, b: GpsFix): Double {
    val dLat = Math.toRadians(b.lat - a.lat)
    val dLon = Math.toRadians(b.lon - a.lon)
    val sLat = sin(dLat / 2)
    val sLon = sin(dLon / 2)
    val h = sLat * sLat + cos(Math.toRadians(a.lat)) * cos(Math.toRadians(b.lat)) * sLon * sLon
    return 2 * 6371000.0 * asin(sqrt(h))
}

/**
 * Applies one GPS reading to the ride. [anchor] is the last point the distance was counted from.
 * Returns the updated ride and the new anchor. While the ride is paused only the anchor moves,
 * so the distance driven during a pause is not billed.
 */
fun applyFix(ride: Ride, anchor: GpsFix?, fix: GpsFix): Pair<Ride, GpsFix?> {
    if (fix.accuracy > MAX_ACCURACY_M) return ride to anchor
    if (!ride.running) return ride to fix
    // FIX: this line now compiles - PathPoint is (lat: Double, lon: Double, leg: Int) in Ride.kt.
    // Before, PathPoint was (x: Float, y: Float) and this was "Double, but Float was expected".
    val point = PathPoint(fix.lat, fix.lon, ride.done.size)
    if (anchor == null) return ride.copy(path = ride.path + point) to fix
    val d = distanceMeters(anchor, fix)
    if (d < MIN_STEP_M) return ride to anchor
    return ride.copy(meters = ride.meters + d.roundToLong(), path = ride.path + point) to fix
}

/** Receives GPS readings from the system location service, on the main thread. */
class GpsTracker(private val context: Context, private val onFix: (GpsFix) -> Unit) {
    private val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    private val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            onFix(GpsFix(location.latitude, location.longitude, location.accuracy))
        }

        // FIX: onStatusChanged removed - it is deprecated in LocationListener and was empty.
        // Before, overriding it produced: "This declaration overrides a deprecated member..."
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }

    @SuppressLint("MissingPermission")
    fun start() {
        if (!hasLocationPermission(context)) return
        // FIX: GPS alone never fixes indoors / takes minutes. Use NETWORK too
        // (Wi-Fi / cell) for a fast first fix, GPS for accuracy afterwards.
        // + deliver the last known location immediately, so the UI does not
        // sit on "GPS-ის ძებნა…" while waiting for the first fresh fix.
        try {
            val last = manager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                ?: manager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            if (last != null) {
                onFix(GpsFix(last.latitude, last.longitude, last.accuracy))
            }
        } catch (e: Exception) { /* no last location yet - wait for the first update */ }
        if (manager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            manager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, listener, Looper.getMainLooper())
        }
        if (manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
            manager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 1000L, 0f, listener, Looper.getMainLooper())
        }
    }

    fun stop() {
        manager.removeUpdates(listener)
    }
}

private fun legColor(kind: LegKind?): Color = when (kind) {
    LegKind.PICKUP -> Green
    LegKind.FRIENDS -> Yellow
    else -> Blue
}

/** Converts GPS points to canvas pixels, keeping the proportions and fitting all points. */
// FIX: was `private` - Detail.kt RoutePreview needs the same projection,
// otherwise it would have to guess and draw the route off-screen.
internal fun project(path: List<PathPoint>, w: Float, h: Float): List<Offset> {
    if (path.isEmpty()) return emptyList()
    // FIX: type written out explicitly - before, minOf { it.lat } was ambiguous
    // (and it.lat did not even exist on the old Float PathPoint).
    val minLat: Double = path.minOf { it.lat }
    val maxLat: Double = path.maxOf { it.lat }
    val minLon: Double = path.minOf { it.lon }
    val maxLon: Double = path.maxOf { it.lon }
    val cLat = (minLat + maxLat) / 2
    val cLon = (minLon + maxLon) / 2
    // A degree of longitude is shorter than a degree of latitude by cos(latitude).
    val kx = cos(Math.toRadians(cLat))
    val span = max(max((maxLon - minLon) * kx, maxLat - minLat), MIN_SPAN_DEG)
    val scale = (min(w, h) - 2 * PAD_PX) / span
    return path.map {
        Offset(
            w / 2 + ((it.lon - cLon) * kx * scale).toFloat(),
            h / 2 - ((it.lat - cLat) * scale).toFloat()
        )
    }
}

private fun DrawScope.drawGrid() {
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
}

/** Map zoom and pan state. zoom 1 means the whole route fits the map. */
private data class MapView(val zoom: Float = 1f, val pan: Offset = Offset.Zero)

private const val MAX_ZOOM = 20f

/** Live map: follows the car, fixed scale - the route NEVER compresses.
 *
 * BEFORE: project() fitted the WHOLE route into the box, so the longer the ride,
 * the smaller the scale got - after a few km the whole route was a tiny squiggle
 * and you could not tell streets apart.
 *
 * NOW:
 * - The camera is centred on the car (last point) and shows a FIXED area around it
 *   (~600 m across at zoom 1x), no matter how long the ride is. Old parts of the
 *   route simply scroll off the screen, like in Waze / Google Maps.
 * - Pinch to zoom, +/- buttons to zoom, drag to look around.
 * - Dragging turns follow OFF (the camera stays where you left it); the ◎ button
 *   (or double-tap) turns follow back ON and re-centres on the car.
 * - project() is still used by Detail.kt's RoutePreview, which SHOULD show the
 *   whole saved route fitted in the box. Only this live map changed.
 */
@Composable
fun RouteMap(path: List<PathPoint>, legs: List<Leg>, modifier: Modifier) {
    // Zoom: pixels per metre. 0.5 px/m at 1x -> ~600 m across a 300px box.
    // Buttons / pinch multiply it, clamped so you cannot zoom into nothing
    // or out to the whole city.
    var zoom by remember { mutableStateOf(1f) }
    var follow by remember { mutableStateOf(true) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    // Centre frozen at the moment follow was turned off (by dragging).
    var frozenCenter by remember { mutableStateOf<PathPoint?>(null) }
    val lastPoint = path.lastOrNull()
    // Effective centre: the car when following, the frozen point when looking around.
    val center = if (follow) lastPoint else (frozenCenter ?: lastPoint)

    Box(modifier.clip(RoundedCornerShape(16.dp)).background(MapBg)) {
        Canvas(
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTransformGestures { _, panChange, zoomChange, _ ->
                        // Any drag = "I want to look around" -> follow OFF.
                        if (panChange != Offset.Zero) {
                            if (follow) { frozenCenter = lastPoint; follow = false }
                            pan += panChange
                        }
                        if (zoomChange != 1f) {
                            zoom = (zoom * zoomChange).coerceIn(0.25f, 8f)
                        }
                    }
                }
                .pointerInput(Unit) {
                    // Double-tap = back to the car, zoom 1x, follow ON.
                    detectTapGestures(onDoubleTap = {
                        zoom = 1f; pan = Offset.Zero; frozenCenter = null; follow = true
                    })
                }
        ) {
            drawGrid()
            if (center != null) {
                val c = Offset(size.width / 2f, size.height / 2f)
                val kx = cos(Math.toRadians(center.lat))
                val pxPerM = 0.5f * zoom
                fun toScreen(pt: PathPoint): Offset {
                    val dxM = (pt.lon - center.lon) * kx * 111320.0
                    val dyM = (pt.lat - center.lat) * 111320.0
                    return Offset(
                        (c.x + dxM.toFloat() * pxPerM + pan.x),
                        (c.y - dyM.toFloat() * pxPerM + pan.y)
                    )
                }
                // Same leg colours as before: green = pickup, yellow = friends, blue = ride.
                path.indices.groupBy { path[it].leg }.forEach { (leg, idx) ->
                    if (idx.size < 2) return@forEach
                    val line = Path()
                    idx.forEachIndexed { i, k ->
                        val p = toScreen(path[k])
                        if (i == 0) line.moveTo(p.x, p.y) else line.lineTo(p.x, p.y)
                    }
                    drawPath(line, color = legColor(legs.getOrNull(leg)?.kind), style = Stroke(width = 5f, cap = StrokeCap.Round))
                }
                toScreen(path.first()).let { drawCircle(Green, radius = 9.dp.toPx(), center = it) }
                toScreen(lastPoint!!).let { drawCircle(Red, radius = 9.dp.toPx(), center = it) }
            }
        }
        // Zoom buttons: always visible, top-right. They sit ON TOP of the map,
        // taps on them do NOT hit the map underneath.
        androidx.compose.foundation.layout.Column(
            Modifier.align(androidx.compose.ui.Alignment.TopEnd)
                .padding(10.dp),
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)
        ) {
            listOf("＋" to { zoom = (zoom * 1.5f).coerceIn(0.25f, 8f) },
                   "－" to { zoom = (zoom / 1.5f).coerceIn(0.25f, 8f) }).forEach { (label, action) ->
                androidx.compose.foundation.layout.Box(
                    Modifier.size(40.dp).clip(RoundedCornerShape(12.dp))
                        .background(Color(0xFF2B2B2B))
                        .clickable(onClick = action),
                    contentAlignment = androidx.compose.ui.Alignment.Center
                ) {
                    androidx.compose.material3.Text(label, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                }
            }
            // Follow button: only shows when you dragged away from the car.
            if (!follow) {
                androidx.compose.foundation.layout.Box(
                    Modifier.size(40.dp).clip(RoundedCornerShape(12.dp))
                        .background(Color(0xFF2196F3))
                        .clickable(onClick = { zoom = 1f; pan = Offset.Zero; frozenCenter = null; follow = true }),
                    contentAlignment = androidx.compose.ui.Alignment.Center
                ) {
                    androidx.compose.material3.Text("◎", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
        // Current zoom, bottom-RIGHT.
        androidx.compose.material3.Text(
            "🔍 %.1fx".format(zoom),
            color = Muted,
            fontSize = 12.sp,
            modifier = Modifier.align(androidx.compose.ui.Alignment.BottomEnd).padding(10.dp)
        )
    }
}
