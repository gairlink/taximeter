package com.example.taximeter

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.cos
import kotlin.math.sin

private val CATEGORIES = listOf("⛽ საწვავი", "🔧 რემონტი", "🧾 სხვა")

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val store = Store(this)
        val initial = store.load()
        // Bubble visibility is tied to the Activity lifecycle, NOT to the service:
        // minimised (onPause) -> bubble appears, back in the app (onResume) -> bubble hides.
        // The service keeps counting in both cases.
        setContent {
            Box(Modifier.fillMaxSize().background(Bg).safeDrawingPadding()) {
                TaxiProScreen(
                    initial = initial,
                    // FIX (counting stops on reopen): if the file holds a STARTED ride
                    // (the service counted while minimised) but this snapshot holds a
                    // NOT-started ride (stale / empty after a recreate), NEVER let the
                    // stale snapshot wipe the running ride. Keep the file's ride.
                    // Otherwise a stale save kills the ride and counting stops.
                    onSave = { snap ->
                        val fileSnap = store.load()
                        val fileRide = fileSnap.ride
                        // FIX (Finish did not clear): the old guard restored fileRide
                        // whenever snap.ride was not started - including after a REAL
                        // Finish, so the finished ride came back to life in the file.
                        // Distinguish: after a real Finish, snap has one MORE trip than
                        // the file (the finished trip was just added) -> save as-is.
                        // After a stale recreate, trip counts are equal -> restore fileRide.
                        if (fileRide.started && !snap.ride.started && snap.trips.size <= fileSnap.trips.size) {
                            store.save(snap.copy(ride = fileRide))
                        } else {
                            store.save(snap)
                        }
                    }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Back in the app -> hide the bubble. Only if a ride is running, otherwise
        // the intent would needlessly (re)create the service.
        if (Store(this).load().ride.started) RideTrackingService.setOverlayVisible(this, false)
    }

    override fun onPause() {
        super.onPause()
        // App minimised / switched away -> show the bubble with live fare + km.
        if (Store(this).load().ride.started) RideTrackingService.setOverlayVisible(this, true)
    }
}

@Composable
fun TaxiProScreen(
    initial: Snapshot,
    onSave: (Snapshot) -> Unit
) {
    // FIX (Finish did not clear): a "zombie" ride - started=false but meters /
    // seconds / path still filled (from the old polling bug) - shows exactly the
    // screenshot: Start button, Finish greyed, but fare/km/time still there.
    // If the loaded ride is a zombie, clear it on launch instead of showing it.
    var ride by remember {
        val r = initial.ride
        val isZombie = !r.started && (r.totalMeters > 0 || r.totalSeconds > 0 || r.totalWaitSeconds > 0 || r.path.isNotEmpty())
        mutableStateOf(if (isZombie) Ride() else r)
    }
    // FIX (counting stops on reopen): on reopen, the first snapshot save happened
    // BEFORE the first poll, so the stale local ride (loaded at onCreate) overwrote
    // the file and wiped the minutes the service counted while minimised.
    // Gate: no snapshot is saved until the first poll has synced local <- file.
    // If no ride was running, there is nothing to sync, so the gate starts open.
    var initialSyncDone by remember { mutableStateOf(!initial.ride.started) }
    val trips = remember { mutableStateListOf<Trip>().apply { addAll(initial.trips) } }
    val expenses = remember { mutableStateListOf<Expense>().apply { addAll(initial.expenses) } }
    var tariffs by remember { mutableStateOf(initial.tariffs) }
    var tab by remember { mutableIntStateOf(0) }
    var period by remember { mutableStateOf(Period.TODAY) }
    var sheetOpen by remember { mutableStateOf(false) }
    var pickerOpen by remember { mutableStateOf(false) }
    var openTrip by remember { mutableStateOf<Trip?>(null) }

    // FIX (background counting):
    // BEFORE: GPS + timer lived HERE in Compose. When the app was minimised,
    // Android stopped GPS updates and paused the timer, so km/minutes/fare froze.
    // NOW: RideTrackingService owns GPS + timer (foreground service, keeps running
    // when minimised). This screen only (a) writes USER ACTIONS and (b) polls the
    // saved ride 1x/sec to refresh the UI. No double counting: the service is the
    // only writer of progress, the screen never applies GPS fixes itself.
    val context = LocalContext.current
    val store = remember(context) { Store(context) }

    // FIX (GPS never turns on):
    // BEFORE: GPS lived ONLY in RideTrackingService, which starts AFTER you pick
    // a mode. So before Start the screen never got a fix and sat on "GPS-ის ძებნა…".
    // NOW: while NO ride is running, this screen runs a light idle tracker just to
    // warm up GPS + show the green dot. When a ride starts, the idle tracker stops
    // and the service takes over (no double counting - only one tracker at a time).
    // Real road limit from OpenStreetMap (null = no data / offline -> manual limit).
    var roadLimit by remember { mutableStateOf<Double?>(null) }
    // Throttle: only ask Overpass when the car moved > 100 m and 15 s passed.
    var lastLimitQuery by remember { mutableStateOf<Pair<GpsFix, Long>?>(null) }
    var idleFix by remember { mutableStateOf<GpsFix?>(null) }
    var waitingForGps by remember { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* granted -> idle tracker picks the fix up on its next start; denied -> overlay stays */ }
    val idleTracker = remember(context) { GpsTracker(context) { fix -> idleFix = fix } }
    DisposableEffect(ride.started) {
        if (!ride.started) idleTracker.start() else idleTracker.stop()
        onDispose { idleTracker.stop() }
    }
    // GPS for the UI: idle fix before the ride, last saved route point during the ride.
    // FIX: after Start the path is still empty (no point yet), so falling back to
    // NOTHING made the dot go red again ("GPS-ის ძებნა…") right after Start.
    // Keep the last idle fix until the first route point arrives.
    val gpsFix: GpsFix? = if (ride.started) {
        ride.path.lastOrNull()?.let { GpsFix(it.lat, it.lon, 10f) } ?: idleFix
    } else idleFix

    // Road limit lookup: runs when the car moved > 100 m since the last lookup
    // and 15 s passed. A failed lookup (offline / no tagged road) keeps the
    // previous value - HomeTab falls back to the manual limit when it is null.
    LaunchedEffect(gpsFix) {
        val fix = gpsFix ?: return@LaunchedEffect
        val last = lastLimitQuery
        val now = System.currentTimeMillis()
        val movedFar = last == null || distanceMeters(last.first, fix) > 100.0
        if (movedFar && (last == null || now - last.second > 15000)) {
            lastLimitQuery = fix to now
            launch {
                val fetched = fetchRoadSpeedLimit(fix.lat, fix.lon)
                // Only accept a result for the location we asked about: if the
                // car already moved on, the next lookup will handle it.
                if (fetched != null) roadLimit = fetched
            }
        }
    }

    // Gate: "don't start until GPS is on". When the user taps Start while there is
    // no fix yet, we show the progress overlay and wait. As soon as a fix arrives,
    // the overlay closes itself and the mode picker opens - nothing else on the
    // screen changes while waiting.
    LaunchedEffect(waitingForGps, gpsFix) {
        if (waitingForGps && gpsFix != null) {
            waitingForGps = false
            pickerOpen = true
        }
    }

    // If the app was killed while a ride was running, resume the service on reopen.
    LaunchedEffect(Unit) {
        if (initial.ride.started) RideTrackingService.start(context)
    }

    // Saves after every change, including the unfinished ride, so nothing is lost on restart.
    // FIX: gated by initialSyncDone (see above) - never save before the first sync.
    val snapshot = Snapshot(trips.toList(), expenses.toList(), tariffs, ride)
    LaunchedEffect(snapshot) { if (initialSyncDone) onSave(snapshot) }

    // Poll the saved ride: picks up the km/seconds/path the SERVICE counted
    // while the app was minimised (or while this screen was in background).
    // FIX: sync ONLY progress (meters / seconds / path). NEVER take running /
    // started / boarded / mode / done from the file - the file can hold a stale
    // read from just before the Start-save landed, and that flipped a freshly
    // started ride to running=false -> button showed "გაგრძელება" (paused).
    // Local flags are the truth: only the user changes them (Start / Board / Pause).
    LaunchedEffect(ride.started) {
        // FIX: sync IMMEDIATELY on (re)open, before any snapshot save can overwrite
        // the file. Without this, the first save (stale local ride) wiped the
        // service's progress counted while minimised, and counting looked stopped.
        if (ride.started && !initialSyncDone) {
            val fresh0 = store.load().ride
            if (fresh0.boarded == ride.boarded && fresh0.done.size == ride.done.size) {
                val t0 = fresh0.seconds + fresh0.waitSeconds
                val l0 = ride.seconds + ride.waitSeconds
                if (t0 >= l0) ride = ride.copy(
                    meters = maxOf(ride.meters, fresh0.meters),
                    seconds = fresh0.seconds, waitSeconds = fresh0.waitSeconds,
                    speedKmh = fresh0.speedKmh,
                    path = if (fresh0.path.size >= ride.path.size) fresh0.path else ride.path
                )
            }
            initialSyncDone = true
        }
        while (ride.started) {
            delay(1000)
            // FIX (Finish did not clear): re-check AFTER the delay. If Finish ran
            // during the delay, ride is now Ride() (started=false) - NEVER copy
            // the old file's meters/seconds/path into it, or it becomes a zombie
            // (started=false but fare/km/time still showing). Just stop polling.
            if (!ride.started) break
            val fresh = store.load().ride
            // Boarding resets meters/seconds to 0 and moves them into done.
            // A stale pre-board read has BIGGER meters - never let it resurrect them.
            if (fresh.boarded == ride.boarded && fresh.done.size == ride.done.size) {
                // FIX: seconds and waitSeconds must be taken TOGETHER from the file.
                // The auto-wait moves 30s from seconds -> waitSeconds (total unchanged).
                // Taking maxOf() for each separately resurrected the old seconds and
                // inflated total time by 30s. Now we accept fresh seconds+wait as-is
                // whenever fresh TOTAL time is not behind local total time.
                // Meters still use maxOf (they only grow inside one leg).
                val freshTotal = fresh.seconds + fresh.waitSeconds
                val localTotal = ride.seconds + ride.waitSeconds
                val newMeters = maxOf(ride.meters, fresh.meters)
                val newSeconds = if (freshTotal >= localTotal) fresh.seconds else ride.seconds
                val newWait = if (freshTotal >= localTotal) fresh.waitSeconds else ride.waitSeconds
                val newPath = if (fresh.path.size >= ride.path.size) fresh.path else ride.path
                // FIX: sync speed too. Before, the poll copied meters/seconds/path
                // but NOT speedKmh - so the UI ride's speed stayed 0 forever and
                // the speedometer showed 0 during the whole ride.
                val newSpeed = fresh.speedKmh
                if (newMeters != ride.meters || newSeconds != ride.seconds || newWait != ride.waitSeconds || newPath !== ride.path || newSpeed != ride.speedKmh) {
                    ride = ride.copy(meters = newMeters, seconds = newSeconds, waitSeconds = newWait, speedKmh = newSpeed, path = newPath)
                }
            }
        }
    }

    // The screen stays on for the whole ride, including when it is paused.
    val view = LocalView.current
    DisposableEffect(ride.started) {
        view.keepScreenOn = ride.started
        onDispose { view.keepScreenOn = false }
    }

    // Box wrapper: the waiting overlay below must draw ON TOP of the content,
    // without moving / changing anything behind it.
    Box(Modifier.fillMaxSize()) {
    Column(
        modifier = Modifier.fillMaxSize().padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        TopBar(rideActive = ride.started, onTariffClick = { sheetOpen = true })
        // FIX: TabBar moved to the BOTTOM - before it was here, under TopBar.
        Box(Modifier.fillMaxWidth().weight(1f)) {
            when (tab) {
                0 -> HomeTab(
                    ride = ride,
                    running = ride.running,
                    tariffs = tariffs,
                    gpsFix = gpsFix,
                    // Tapping the limit sign on the map: save it, it persists.
                    onSpeedLimitChange = { tariffs = tariffs.copy(speedLimitKmh = it) },
                    // Real road limit from OSM (null -> manual limit from tariffs).
                    roadLimit = roadLimit,
                    onStart = {
                        // One-time: the bubble needs "Draw over other apps". If it is not
                        // granted yet, open Settings - the user grants it once and taps
                        // Start again. The ride itself still works without it (notification).
                        if (!Settings.canDrawOverlays(context)) {
                            Toast.makeText(context, "მცურავი ფანჯრისთვის ჩართე: Draw over other apps", Toast.LENGTH_LONG).show()
                            context.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + context.packageName)).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            })
                        }
                        if (gpsFix != null) {
                            pickerOpen = true
                        } else {
                            // No fix yet: ask permission if needed, then show progress overlay.
                            if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                                permissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                            }
                            idleTracker.start()
                            waitingForGps = true
                        }
                    },
                    onBoard = { ride = boardPassenger(ride) },
                    onToggleRun = { ride = ride.copy(running = !ride.running) },
                    onMapTap = { if (ride.running) ride = addRoutePoint(ride) },
                    onFinish = {
                        if (ride.started) {
                            if (ride.totalMeters > 0 || ride.totalSeconds > 0 || ride.totalWaitSeconds > 0) {
                                trips.add(tripFrom(ride, tariffs, System.currentTimeMillis()))
                            }
                            ride = Ride()
                            // Stop background counting - the ride is over.
                            RideTrackingService.stop(context)
                        }
                    }
                )
                1 -> HistoryTab(
                    trips = trips,
                    period = period,
                    onPeriodChange = { period = it },
                    onOpen = { openTrip = it },
                    onDelete = { trip -> trips.removeAll { it.id == trip.id } }
                )
                2 -> StatsTab(
                    trips = trips,
                    expenses = expenses,
                    period = period,
                    onPeriodChange = { period = it }
                )
                3 -> ExpensesTab(
                    expenses = expenses,
                    period = period,
                    onPeriodChange = { period = it },
                    onAdd = { category, amount, note ->
                        expenses.add(Expense(System.nanoTime(), System.currentTimeMillis(), category, amount, note))
                    }
                )
                else -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("📦 მალე", color = Muted, fontSize = 18.sp)
                }
            }
        }
        // Bottom navigation - like before: Home / History / Stats / Expenses / Box
        TabBar(tab = tab, onSelect = { tab = it })
    }

        // FIX: progress overlay while waiting for GPS. The screen behind stays exactly
        // as it was - this is the ONLY thing that appears. Cancel returns to the screen.
        if (waitingForGps) {
            Box(
                Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.clip(RoundedCornerShape(20.dp)).background(CardBg).padding(28.dp)
                ) {
                    CircularProgressIndicator(color = Yellow)
                    Text("GPS-ის ძებნა…", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    Text(
                        "მგზავრობა დაიწყება, როგორც კი GPS ჩაირთვება.",
                        color = Muted, fontSize = 14.sp, textAlign = TextAlign.Center
                    )
                    Button(
                        onClick = { waitingForGps = false },
                        colors = ButtonDefaults.buttonColors(containerColor = Tile, contentColor = Color.White),
                        shape = RoundedCornerShape(14.dp)
                    ) { Text("გაუქმება", fontWeight = FontWeight.Bold) }
                }
            }
        }
    } // end Box wrapper

    if (sheetOpen) {
        TariffSheet(
            current = tariffs,
            onDismiss = { sheetOpen = false },
            onSave = {
                tariffs = it
                sheetOpen = false
            }
        )
    }
    if (pickerOpen) {
        ModePickerSheet(
            tariffs = tariffs,
            onPick = { mode ->
                pickerOpen = false
                ride = startRide(mode)
                // Start counting in background too, not just while the screen is open.
                RideTrackingService.start(context)
            },
            onDismiss = { pickerOpen = false }
        )
    }
    openTrip?.let { trip ->
        TripDetailSheet(
            trip = trip,
            onDismiss = { openTrip = null },
            onDelete = {
                trips.removeAll { it.id == trip.id }
                openTrip = null
            }
        )
    }
}

/** Starts a ride in the chosen mode. */
private fun startRide(mode: RideMode): Ride =
    Ride(started = true, running = true, mode = mode)

/** Pickup mode: the passenger boards, so the pickup leg is closed and the standard leg starts. */
private fun boardPassenger(ride: Ride): Ride = ride.copy(
    boarded = true,
    // FIX: the closed PICKUP leg keeps its paused time, otherwise it is lost.
    done = ride.done + Leg(LegKind.PICKUP, ride.meters, ride.seconds, ride.waitSeconds),
    meters = 0L,
    seconds = 0L,
    waitSeconds = 0L
)

/** One simulated step of 100 m. The route follows a fixed pattern until real GPS is connected. */
private fun addRoutePoint(ride: Ride): Ride {
    val i = ride.path.size
    // FIX: PathPoint is now (lat: Double, lon: Double, leg: Int), NOT (x: Float, y: Float).
    // Simulated points circle around Montale (43.73166, 10.81344).
    val point = PathPoint(
        lat = 43.73166 + 0.0038 * sin(i * 0.35),
        lon = 10.81344 + 0.0034 * cos(i * 0.23),
        leg = ride.done.size
    )
    return ride.copy(meters = ride.meters + 100, path = ride.path + point)
}

@Composable
private fun TopBar(rideActive: Boolean, onTariffClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(Yellow),
            contentAlignment = Alignment.Center
        ) {
            Text("€", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 22.sp)
        }
        Spacer(Modifier.width(10.dp))
        Text("TAXI PRO", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 22.sp)
        Spacer(Modifier.weight(1f))
        // FIX: 📋 button removed - it was dead, and copying is not needed.
        IconTile("€", onClick = onTariffClick)
        Spacer(Modifier.width(8.dp))
        Text("☀", color = if (rideActive) Yellow else Muted, fontSize = 26.sp)
    }
}

@Composable
private fun TabBar(tab: Int, onSelect: (Int) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(CardBg).padding(6.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        listOf("🏠", "📜", "📊", "⛽", "📦").forEachIndexed { i, icon ->
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(14.dp))
                    .background(if (i == tab) Tile else Color.Transparent)
                    .clickable { onSelect(i) }
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(icon, fontSize = 22.sp)
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String, period: Period, onChange: (Period) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 20.sp)
        Spacer(Modifier.weight(1f))
        Period.entries.forEach { p ->
            PeriodChip(p.label, p == period) { onChange(p) }
            Spacer(Modifier.width(6.dp))
        }
    }
}

@Composable
private fun HistoryTab(
    trips: List<Trip>,
    period: Period,
    onPeriodChange: (Period) -> Unit,
    onOpen: (Trip) -> Unit,
    onDelete: (Trip) -> Unit
) {
    val start = periodStart(period)
    val list = trips.filter { it.time >= start }.sortedByDescending { it.time }
    // Pending delete: shows the confirm dialog below. Only one at a time.
    var pendingDelete by remember { mutableStateOf<Trip?>(null) }
    // Mode filter: null = all. The summary + list below both follow the filter.
    var modeFilter by remember { mutableStateOf<RideMode?>(null) }
    val filtered = if (modeFilter == null) list else list.filter { it.mode == modeFilter }
    val dayMs = 24L * 60 * 60 * 1000
    val todayStart = periodStart(Period.TODAY)
    // Group by day, newest day first (list is already sorted desc, groupBy keeps order).
    val grouped = filtered.groupBy { fmtDate(it.time) }

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionHeader("📜 ისტორია", period, onPeriodChange)

        // SUMMARY STRIP: what the period (or the active filter) adds up to.
        if (filtered.isNotEmpty()) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                // FIX: sub-label removed (marked on the screenshot) - it said nothing.
                MiniStat("მგზავრები", "${filtered.size}", Color.White, "", Modifier.weight(1f))
                MiniStat("შემოსავალი", "€%.2f".format(filtered.sumOf { it.fare }), Yellow, "", Modifier.weight(1f))
                MiniStat("მოგება", "€%.2f".format(filtered.sumOf { it.profit }), Green, "", Modifier.weight(1f))
            }
        }

        // MODE FILTER CHIPS: one tap narrows the list. "ყველა" clears it.
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // FIX: labels shortened so all 4 chips fit in one row.
            // "ყველა" stays full, the rest are abbreviated - the full names
            // ("სტანდარტული" / "ახლობლები" / "კლიენტამდე") still show on the trip cards.
            HistoryChip("ყველა", modeFilter == null) { modeFilter = null }
            HistoryChip("სტანდ.", modeFilter == RideMode.STANDARD) { modeFilter = RideMode.STANDARD }
            HistoryChip("ახლობ.", modeFilter == RideMode.FRIENDS) { modeFilter = RideMode.FRIENDS }
            HistoryChip("კლიენტ.", modeFilter == RideMode.PICKUP) { modeFilter = RideMode.PICKUP }
        }

        Column(
            modifier = Modifier.fillMaxWidth().weight(1f).card().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (filtered.isEmpty()) {
                Column(
                    Modifier.fillMaxWidth().padding(vertical = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text("📜", fontSize = 36.sp)
                    Text(
                        if (list.isEmpty()) "ისტორია ცარიელია" else "ამ ფილტრში მგზავრობა არაა",
                        color = Color.White, fontWeight = FontWeight.Bold, fontSize = 17.sp
                    )
                    Text(
                        if (list.isEmpty()) "დაასრულე პირველი მგზავრობა და ის აქ გამოჩნდება." else "აირჩიე სხვა რეჟიმი ან „ყველა“.",
                        color = Muted, fontSize = 14.sp, textAlign = TextAlign.Center
                    )
                }
            }
            grouped.forEach { (_, dayTrips) ->
                val first = dayTrips.first()
                // Day label: "დღეს" / "გუშინ" when it is one of those, else the date.
                val cal = Calendar.getInstance().apply {
                    timeInMillis = first.time
                    set(Calendar.HOUR_OF_DAY, 0)
                    set(Calendar.MINUTE, 0)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }
                val dayLabel = when (cal.timeInMillis) {
                    todayStart -> "დღეს"
                    todayStart - dayMs -> "გუშინ"
                    else -> fmtDate(first.time)
                }
                // DAY HEADER: date + that day's totals, so a day is readable at a glance.
                Row(
                    Modifier.fillMaxWidth().padding(top = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(dayLabel, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    Box(Modifier.weight(1f).height(1.dp).background(GridLine))
                    Text(
                        "${dayTrips.size} მგზ. • €%.2f • 💰 €%.2f".format(
                            dayTrips.sumOf { it.fare }, dayTrips.sumOf { it.profit }
                        ),
                        color = Muted, fontSize = 12.sp
                    )
                }
                dayTrips.forEach { trip ->
                    TripRow(trip, onClick = { onOpen(trip) }, onDelete = { pendingDelete = trip })
                }
            }
        }
    }
    // Confirm before deleting - a stray tap on ✕ must not erase a trip.
    pendingDelete?.let { trip ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("მგზავრობის წაშლა?", color = Color.White, fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    "${trip.mode.label} • ${fmtDateTime(trip.time)}\n€%.2f • %.3f კმ".format(trip.fare, trip.km),
                    color = Muted
                )
            },
            confirmButton = {
                TextButton(onClick = { onDelete(trip); pendingDelete = null }) {
                    Text("✕ წაშლა", color = Red, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text("გაუქმება", color = Color.White)
                }
            },
            containerColor = CardBg
        )
    }
}

@Composable
private fun HistoryChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(if (selected) Yellow else Tile)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = if (selected) Color.Black else Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp, maxLines = 1)
    }
}

@Composable
private fun TripRow(trip: Trip, onClick: () -> Unit, onDelete: () -> Unit) {
    // Total time = driving + waiting (legs carry both). Before, the row showed
    // only driving seconds, so a ride with long waiting looked shorter than it was.
    val totalSec = trip.legs.sumOf { it.seconds + it.waitSeconds }
    val waitMin = trip.legs.sumOf { it.waitSeconds } / 60.0
    val modeColor = when (trip.mode) {
        RideMode.STANDARD -> Yellow
        RideMode.FRIENDS -> Green
        RideMode.PICKUP -> Blue
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Tile)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // LEFT: clock time + mode dot. Time is the anchor - the eye reads it first.
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                SimpleDateFormat("HH:mm", Locale.US).format(Date(trip.time)),
                color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(4.dp))
            Box(Modifier.size(8.dp).clip(CircleShape).background(modeColor))
        }
        Spacer(Modifier.width(12.dp))
        // MIDDLE: tapping the text still opens the details.
        Column(
            modifier = Modifier.weight(1f).clickable(onClick = onClick),
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            Text(trip.mode.label, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Text(
                "%.3f კმ • %.1f წთ".format(trip.km, totalSec / 60.0),
                color = Muted, fontSize = 13.sp
            )
            if (waitMin >= 0.5) {
                Text("ლოდინი %.0f წთ".format(waitMin), color = Muted, fontSize = 12.sp)
            }
        }
        // RIGHT: fare on top, net profit under it - the two numbers that matter.
        Column(
            modifier = Modifier.clickable(onClick = onClick),
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text("€%.2f".format(trip.fare), color = Yellow, fontSize = 17.sp, fontWeight = FontWeight.Bold)
            Text("💰 €%.2f".format(trip.profit), color = Green, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(8.dp))
        // Delete button EXACTLY like the screenshot: small dark-grey rounded
        // square with a red ✕ inside - no 🗑 emoji, no red background.
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color(0xFF2B2B2B))
                .border(1.dp, Color(0xFF3D3D3D), RoundedCornerShape(8.dp))
                .clickable(onClick = onDelete),
            contentAlignment = Alignment.Center
        ) {
            Text("✕", color = Color(0xFFFF4444), fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun StatsTab(
    trips: List<Trip>,
    expenses: List<Expense>,
    period: Period,
    onPeriodChange: (Period) -> Unit
) {
    val start = periodStart(period)
    val t = trips.filter { it.time >= start }
    val e = expenses.filter { it.time >= start }
    val revenue = t.sumOf { it.fare }
    val km = t.sumOf { it.km }
    val fuel = t.sumOf { it.fuel }
    val expTotal = e.sumOf { it.amount }
    val profit = revenue - fuel - expTotal
    val avg = if (t.isEmpty()) 0.0 else revenue / t.size
    // Total time = driving + waiting (legs carry both). Before, StatsTab did not
    // show time at all, so €/hour was impossible to judge.
    val totalSec = t.sumOf { trip -> trip.legs.sumOf { it.seconds + it.waitSeconds } }
    val waitSec = t.sumOf { trip -> trip.legs.sumOf { it.waitSeconds } }
    val hours = totalSec / 3600.0
    // €/hour and €/km are on NET profit - what you actually keep, not revenue.
    val perHour = if (hours > 0) profit / hours else 0.0
    val perKm = if (km > 0) profit / km else 0.0
    val best = t.maxByOrNull { it.fare }
    // Last 7 days, oldest -> newest, for the bar chart. Profit per day =
    // that day's fares - fuel - recorded expenses.
    val dayMs = 24L * 60 * 60 * 1000
    val todayStart = periodStart(Period.TODAY)
    val days = (6 downTo 0).map { back ->
        val dStart = todayStart - back * dayMs
        val dEnd = dStart + dayMs
        val dRev = t.filter { it.time in dStart until dEnd }.sumOf { it.fare }
        val dFuel = t.filter { it.time in dStart until dEnd }.sumOf { it.fuel }
        val dExp = e.filter { it.time in dStart until dEnd }.sumOf { it.amount }
        val dTrips = t.count { it.time in dStart until dEnd }
        Triple(dStart, dRev - dFuel - dExp, dTrips)
    }
    val maxDay = days.maxOfOrNull { kotlin.math.abs(it.second) } ?: 0.0

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        SectionHeader("📊 სტატისტიკა", period, onPeriodChange)

        if (t.isEmpty() && e.isEmpty()) {
            Column(
                Modifier.fillMaxWidth().card(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text("📊", fontSize = 36.sp)
                Text("ამ პერიოდში ჩანაწერი არაა", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                Text("დაასრულე პირველი მგზავრობა და სტატისტიკა აქ გამოჩნდება.", color = Muted, fontSize = 14.sp, textAlign = TextAlign.Center)
            }
        } else {

        // HERO: net profit first - it is the number that matters. Breakdown on
        // the right, €/hour + €/km at the bottom so you see the rate at a glance.
        Column(Modifier.fillMaxWidth().card(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Tile).padding(14.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column {
                        Text("წმინდა მოგება", color = Muted, fontSize = 14.sp)
                        Text("€%.2f".format(profit), color = if (profit >= 0) Green else Red, fontSize = 36.sp, fontWeight = FontWeight.Bold)
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        ProfitLine("შემოსავალი", "€%.2f".format(revenue), Yellow)
                        ProfitLine("- საწვავი", "€%.2f".format(fuel), Red)
                        ProfitLine("- ხარჯი", "€%.2f".format(expTotal), Red)
                    }
                }
                Spacer(Modifier.height(6.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Column(Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).background(Bg).padding(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("€%.2f / სთ".format(perHour), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                        Text("მოგება საათში", color = Muted, fontSize = 12.sp)
                    }
                    Column(Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).background(Bg).padding(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("€%.2f / კმ".format(perKm), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                        Text("მოგება კილომეტრზე", color = Muted, fontSize = 12.sp)
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MiniStat("მგზავრები", "${t.size}", Color.White, "საშ. €%.2f".format(avg), Modifier.weight(1f))
                MiniStat("შემოსავალი", "€%.2f".format(revenue), Yellow, "საშ. €%.2f".format(avg), Modifier.weight(1f))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MiniStat("კმ", "%.1fკმ".format(km), Color.White, "⛽ ავტო €%.2f".format(fuel), Modifier.weight(1f))
                MiniStat("დრო", "%.1fსთ".format(hours), Color.White, "ლოდინი %.0f წთ".format(waitSec / 60.0), Modifier.weight(1f))
            }
        }

        // 7-DAY BAR CHART: daily net profit. The tallest bar is full height,
        // the rest are proportional. Green = profit, red = loss that day.
        Column(Modifier.fillMaxWidth().card(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("ბოლო 7 დღე — წმინდა მოგება", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Row(
                Modifier.fillMaxWidth().height(140.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.Bottom
            ) {
                days.forEach { (dStart, dProfit, dTrips) ->
                    val h = if (maxDay > 0) (kotlin.math.abs(dProfit) / maxDay * 96).toFloat().coerceAtLeast(if (dProfit != 0.0) 4f else 0f) else 0f
                    Column(
                        Modifier.weight(1f).fillMaxHeight(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Bottom
                    ) {
                        if (dProfit != 0.0 || dTrips > 0) {
                            Text(
                                "€%.0f".format(dProfit),
                                color = if (dProfit >= 0) Green else Red,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1
                            )
                            Spacer(Modifier.height(2.dp))
                        } else {
                            Spacer(Modifier.height(16.dp))
                        }
                        Box(
                            Modifier.fillMaxWidth().height(h.dp)
                                .clip(RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp))
                                .background(if (dProfit >= 0) Green else Red)
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            SimpleDateFormat("EEE", Locale.forLanguageTag("ka")).format(Date(dStart)),
                            color = Muted,
                            fontSize = 11.sp,
                            maxLines = 1
                        )
                    }
                }
            }
        }

        // BEST TRIP + EXPENSES: the single best ride, and where the money went.
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(Modifier.weight(1f).card(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("🏆 საუკეთესო მგზავრობა", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                if (best != null) {
                    Text("€%.2f".format(best.fare), color = Yellow, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                    Text(
                        "%.1f კმ • %.0f წთ".format(best.km, best.legs.sumOf { it.seconds + it.waitSeconds } / 60.0),
                        color = Muted, fontSize = 13.sp
                    )
                    Text(fmtDate(best.time), color = Muted, fontSize = 13.sp)
                } else {
                    Text("—", color = Muted, fontSize = 16.sp)
                }
            }
            Column(Modifier.weight(1f).card(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("🧾 ხარჯები", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                if (e.isEmpty()) {
                    Text("ხარჯი არაა", color = Muted, fontSize = 14.sp)
                } else {
                    CATEGORIES.forEach { cat ->
                        val catTotal = e.filter { it.category == cat }.sumOf { it.amount }
                        if (catTotal > 0) ProfitLine(cat, "€%.2f".format(catTotal), Red)
                    }
                    ProfitLine("სულ", "€%.2f".format(expTotal), Color.White)
                }
            }
        }
        } // else: there is data
    }
}

@Composable
private fun ExpensesTab(
    expenses: List<Expense>,
    period: Period,
    onPeriodChange: (Period) -> Unit,
    onAdd: (String, Double, String) -> Unit
) {
    val start = periodStart(period)
    val list = expenses.filter { it.time >= start }.sortedByDescending { it.time }
    val total = list.sumOf { it.amount }
    val fuelTotal = list.filter { it.category == CATEGORIES[0] }.sumOf { it.amount }
    val repairTotal = list.filter { it.category == CATEGORIES[1] }.sumOf { it.amount }
    var category by remember { mutableStateOf(CATEGORIES[0]) }
    var categoryExpanded by remember { mutableStateOf(false) }
    var amountText by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionHeader("⛽ ხარჯები", period, onPeriodChange)
        Column(Modifier.fillMaxWidth().card(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(56.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(Tile)
                        .clickable { categoryExpanded = true }
                        .padding(horizontal = 14.dp),
                    contentAlignment = Alignment.CenterStart
                ) {
                    Text("$category ▾", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    DropdownMenu(expanded = categoryExpanded, onDismissRequest = { categoryExpanded = false }) {
                        CATEGORIES.forEach { c ->
                            DropdownMenuItem(
                                text = { Text(c) },
                                onClick = {
                                    category = c
                                    categoryExpanded = false
                                }
                            )
                        }
                    }
                }
                OutlinedTextField(
                    value = amountText,
                    onValueChange = { amountText = it },
                    placeholder = { Text("€", color = Muted) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.weight(1f),
                    colors = fieldColors()
                )
            }
            OutlinedTextField(
                value = note,
                onValueChange = { note = it },
                placeholder = { Text("შენიშვნა", color = Muted) },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth(),
                colors = fieldColors()
            )
            Button(
                onClick = {
                    val value = amountText.replace(',', '.').toDoubleOrNull()
                    if (value != null && value > 0) {
                        onAdd(category, value, note.trim())
                        amountText = ""
                        note = ""
                    }
                },
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Yellow, contentColor = Color.Black)
            ) {
                Text("+ დამატება", fontWeight = FontWeight.Bold, fontSize = 16.sp)
            }
        }
        Column(Modifier.fillMaxWidth().card(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MiniStat("ჩანაწერები", "${list.size}", Color.White, "", Modifier.weight(1f))
                MiniStat("ხარჯი", "€%.2f".format(total), Red, "⛽ €%.2f • 🔧 €%.2f".format(fuelTotal, repairTotal), Modifier.weight(1.4f))
            }
        }
        Column(
            modifier = Modifier.fillMaxWidth().weight(1f).card().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (list.isEmpty()) {
                Text(
                    "ხარჯები არაა",
                    color = Muted,
                    fontSize = 16.sp,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                    textAlign = TextAlign.Center
                )
            }
            list.forEach { ex ->
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            ex.category + if (ex.note.isNotEmpty()) " • ${ex.note}" else "",
                            color = Color.White,
                            fontSize = 15.sp
                        )
                        Text(fmtDateTime(ex.time), color = Muted, fontSize = 12.sp)
                    }
                    Text("€%.2f".format(ex.amount), color = Red, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                }
            }
            // FIX: ზედა ზოლი + CSV + წაშლა წაშლილია (სქრინზე მონიშნული იყო).
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF0B0B0D)
@Composable
fun TaxiProPreview() {
    Box(Modifier.fillMaxSize().background(Bg).safeDrawingPadding()) {
        TaxiProScreen(initial = Snapshot(emptyList(), emptyList(), Tariffs()), onSave = {})
    }
}