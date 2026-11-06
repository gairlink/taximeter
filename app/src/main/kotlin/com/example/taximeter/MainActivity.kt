package com.example.taximeter

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlin.math.cos
import kotlin.math.sin

private val CATEGORIES = listOf("⛽ საწვავი", "🔧 რემონტი", "🧾 სხვა")

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Android 15 and later draw the app under the status and navigation bars.
        enableEdgeToEdge()
        val store = Store(this)
        val initial = store.load()
        val activity = this
        setContent {
            Box(Modifier.fillMaxSize().background(Bg).safeDrawingPadding()) {
                TaxiProScreen(
                    initial = initial,
                    onSave = { store.save(it) },
                    onSaveCsv = { name, content -> saveCsv(activity, name, content) }
                )
            }
        }
    }
}

@Composable
fun TaxiProScreen(
    initial: Snapshot,
    onSave: (Snapshot) -> Unit,
    onSaveCsv: (String, String) -> Unit = { _, _ -> }
) {
    var ride by remember { mutableStateOf(initial.ride) }
    val trips = remember { mutableStateListOf<Trip>().apply { addAll(initial.trips) } }
    val expenses = remember { mutableStateListOf<Expense>().apply { addAll(initial.expenses) } }
    var tariffs by remember { mutableStateOf(initial.tariffs) }
    var tab by remember { mutableIntStateOf(0) }
    var period by remember { mutableStateOf(Period.TODAY) }
    var sheetOpen by remember { mutableStateOf(false) }
    var pickerOpen by remember { mutableStateOf(false) }
    var openTrip by remember { mutableStateOf<Trip?>(null) }

    // Saves after every change, including the unfinished ride, so nothing is lost on restart.
    val snapshot = Snapshot(trips.toList(), expenses.toList(), tariffs, ride)
    LaunchedEffect(snapshot) { onSave(snapshot) }

    // Ticks once per second while the ride is running.
    LaunchedEffect(ride.running) {
        while (ride.running) {
            delay(1000)
            ride = ride.copy(seconds = ride.seconds + 1)
        }
    }

    // The screen stays on for the whole ride, including when it is paused.
    val view = LocalView.current
    DisposableEffect(ride.started) {
        view.keepScreenOn = ride.started
        onDispose { view.keepScreenOn = false }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        TopBar(rideActive = ride.started, onTariffClick = { sheetOpen = true })

        Box(Modifier.fillMaxWidth().weight(1f)) {
            when (tab) {
                0 -> HomeTab(
                    ride = ride,
                    running = ride.running,
                    tariffs = tariffs,
                    onStart = { pickerOpen = true },
                    onBoard = { ride = boardPassenger(ride) },
                    onToggleRun = { ride = ride.copy(running = !ride.running) },
                    onMapTap = { if (ride.running) ride = addRoutePoint(ride) },
                    onFinish = {
                        if (ride.started) {
                            if (ride.totalMeters > 0 || ride.totalSeconds > 0) {
                                trips.add(tripFrom(ride, tariffs, System.currentTimeMillis()))
                            }
                            ride = Ride()
                        }
                    }
                )
                1 -> HistoryTab(
                    trips = trips,
                    period = period,
                    onSaveCsv = onSaveCsv,
                    onPeriodChange = { period = it },
                    onOpen = { openTrip = it },
                    onClear = {
                        val start = periodStart(period)
                        trips.removeAll { it.time >= start }
                    }
                )
                2 -> StatsTab(
                    trips = trips,
                    expenses = expenses,
                    period = period,
                    onSaveCsv = onSaveCsv,
                    onPeriodChange = { period = it }
                )
                3 -> ExpensesTab(
                    expenses = expenses,
                    period = period,
                    onSaveCsv = onSaveCsv,
                    onPeriodChange = { period = it },
                    onAdd = { category, amount, note ->
                        expenses.add(Expense(System.nanoTime(), System.currentTimeMillis(), category, amount, note))
                    },
                    onClear = {
                        val start = periodStart(period)
                        expenses.removeAll { it.time >= start }
                    }
                )
                else -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("📦 მალე", color = Muted, fontSize = 18.sp)
                }
            }
        }

        BottomNav(tab) { tab = it }
    }

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
    done = ride.done + Leg(LegKind.PICKUP, ride.meters, ride.seconds),
    meters = 0L,
    seconds = 0L
)

/** One simulated step of 100 m. The route follows a fixed pattern until real GPS is connected. */
private fun addRoutePoint(ride: Ride): Ride {
    val i = ride.path.size
    val point = PathPoint(
        x = (0.5 + 0.38 * sin(i * 0.35)).toFloat(),
        y = (0.5 + 0.34 * cos(i * 0.23)).toFloat(),
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
        IconTile("📋")
        Spacer(Modifier.width(8.dp))
        IconTile("€", onClick = onTariffClick)
        Spacer(Modifier.width(8.dp))
        // Lit yellow while a ride is in progress, grey otherwise.
        Text("☀", color = if (rideActive) Yellow else Muted, fontSize = 26.sp)
    }
}

@Composable
private fun BottomNav(tab: Int, onSelect: (Int) -> Unit) {
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
private fun PeriodHeader(title: String, period: Period, onChange: (Period) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 20.sp)
        Spacer(Modifier.weight(1f))
        Period.values().forEach { p ->
            PeriodChip(p.label, p == period) { onChange(p) }
            Spacer(Modifier.width(6.dp))
        }
    }
}

@Composable
private fun HistoryTab(
    trips: List<Trip>,
    period: Period,
    onSaveCsv: (String, String) -> Unit,
    onPeriodChange: (Period) -> Unit,
    onOpen: (Trip) -> Unit,
    onClear: () -> Unit
) {
    val start = periodStart(period)
    val list = trips.filter { it.time >= start }.sortedByDescending { it.time }

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        PeriodHeader("📜 ისტორია", period, onPeriodChange)

        Column(Modifier.fillMaxWidth().card()) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Spacer(Modifier.weight(1f))
                ActionChip("CSV", Color.White) {
                    onSaveCsv("trips.csv", tripsCsv(list))
                }
                ActionChip("წაშლა", Red, onClick = onClear)
            }
        }

        Column(
            modifier = Modifier.fillMaxWidth().weight(1f).card().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (list.isEmpty()) {
                Text("ისტორია ცარიელია", color = Muted, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
            }
            list.forEach { trip -> TripRow(trip) { onOpen(trip) } }
        }
    }
}

@Composable
private fun TripRow(trip: Trip, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Tile)
            .clickable(onClick = onClick)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(trip.mode.label + "  •  " + fmtDateTime(trip.time), color = Muted, fontSize = 12.sp)
        Text(
            buildAnnotatedString {
                withStyle(SpanStyle(color = Color.White)) {
                    append("%.3f კმ • %.1f წთ • ".format(trip.km, trip.seconds / 60.0))
                }
                withStyle(SpanStyle(color = Yellow, fontWeight = FontWeight.Bold)) {
                    append("€%.2f".format(trip.fare))
                }
                withStyle(SpanStyle(color = Green)) {
                    append("  💰€%.2f".format(trip.profit))
                }
            },
            fontSize = 15.sp
        )
    }
}

@Composable
private fun StatsTab(
    trips: List<Trip>,
    expenses: List<Expense>,
    period: Period,
    onSaveCsv: (String, String) -> Unit,
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

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        PeriodHeader("📊 ანგარიში", period, onPeriodChange)

        Column(Modifier.fillMaxWidth().card(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MiniStat("მგზავრები", "${t.size}", Color.White, "საშ. €%.2f".format(avg), Modifier.weight(1f))
                MiniStat("შემოსავალი", "€%.2f".format(revenue), Yellow, "", Modifier.weight(1f))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MiniStat("კმ", "%.1fკმ".format(km), Color.White, "⛽ ავტო €%.2f".format(fuel), Modifier.weight(1f))
                MiniStat("ხარჯი", "€%.2f".format(expTotal), Red, "${e.size} ჩან.", Modifier.weight(1f))
            }
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Tile).padding(14.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column {
                        Text("წმინდა მოგება", color = Muted, fontSize = 14.sp)
                        Text("€%.2f".format(profit), color = Green, fontSize = 36.sp, fontWeight = FontWeight.Bold)
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        ProfitLine("შემოსავალი", "€%.2f".format(revenue), Yellow)
                        ProfitLine("- საწვავი", "€%.2f".format(fuel), Red)
                        ProfitLine("- ხარჯი", "€%.2f".format(expTotal), Red)
                    }
                }
            }
            Text("პერიოდი: ${periodLabel(period)}", color = Muted, fontSize = 12.sp)
        }

        Column(Modifier.fillMaxWidth().card()) {
            ActionChip("📥 CSV", Color.White) {
                onSaveCsv("trips.csv", tripsCsv(t))
            }
        }
    }
}

@Composable
private fun ExpensesTab(
    expenses: List<Expense>,
    period: Period,
    onSaveCsv: (String, String) -> Unit,
    onPeriodChange: (Period) -> Unit,
    onAdd: (String, Double, String) -> Unit,
    onClear: () -> Unit
) {
    val start = periodStart(period)
    val list = expenses.filter { it.time >= start }.sortedByDescending { it.time }
    val total = list.sumOf { it.amount }
    val fuelTotal = list.filter { it.category == CATEGORIES[0] }.sumOf { it.amount }
    val repairTotal = list.filter { it.category == CATEGORIES[1] }.sumOf { it.amount }

    var category by remember { mutableStateOf(CATEGORIES[0]) }
    var menuOpen by remember { mutableStateOf(false) }
    var amountText by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        PeriodHeader("⛽ ხარჯები", period, onPeriodChange)

        Column(Modifier.fillMaxWidth().card(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(56.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(Tile)
                        .clickable { menuOpen = true }
                        .padding(horizontal = 14.dp),
                    contentAlignment = Alignment.CenterStart
                ) {
                    Text("$category ▾", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        CATEGORIES.forEach { c ->
                            DropdownMenuItem(
                                text = { Text(c) },
                                onClick = {
                                    category = c
                                    menuOpen = false
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
            Spacer(Modifier.height(8.dp))
            Text("პერიოდი: ${periodLabel(period)}", color = Muted, fontSize = 12.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ActionChip("📥 CSV", Color.White) {
                    onSaveCsv("expenses.csv", expensesCsv(list))
                }
                ActionChip("🗑 წაშლა", Red, onClick = onClear)
            }
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
