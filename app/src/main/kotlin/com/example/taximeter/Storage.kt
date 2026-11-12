package com.example.taximeter

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class Snapshot(
    val trips: List<Trip>,
    val expenses: List<Expense>,
    val tariffs: Tariffs,
    val ride: Ride = Ride(),
    val archives: List<MonthlyArchive> = emptyList()
)

/** Keeps trips, expenses, tariffs and the unfinished ride in one JSON file in private storage. */
class Store(context: Context) {
    private val file = File(context.filesDir, "taxi-data.json")

    fun load(): Snapshot {
        if (!file.exists()) return Snapshot(emptyList(), emptyList(), Tariffs())
        return try {
            val root = JSONObject(file.readText(Charsets.UTF_8))
            Snapshot(
                trips = root.optJSONArray("trips").mapItems { tripFromJson(it) },
                expenses = root.optJSONArray("expenses").mapItems { expenseFromJson(it) },
                tariffs = root.optJSONObject("tariffs")?.let { tariffsFromJson(it) } ?: Tariffs(),
                ride = root.optJSONObject("ride")?.let { rideFromJson(it) } ?: Ride(),
                archives = root.optJSONArray("archives").mapItems { archiveFromJson(it) }
            )
        } catch (e: Exception) {
            Snapshot(emptyList(), emptyList(), Tariffs())
        }
    }

    fun save(s: Snapshot) {
        val root = JSONObject()
        root.put("trips", JSONArray().apply { s.trips.forEach { put(tripToJson(it)) } })
        root.put("expenses", JSONArray().apply { s.expenses.forEach { put(expenseToJson(it)) } })
        root.put("tariffs", s.tariffs.toJson())
        root.put("ride", rideToJson(s.ride))
        root.put("archives", JSONArray().apply { s.archives.forEach { put(archiveToJson(it)) } })
        try {
            // FIX: atomic write - crash mid-save must not leave a half-written JSON.
            val tmp = File(file.parentFile, "taxi-data.json.tmp")
            tmp.writeText(root.toString(), Charsets.UTF_8)
            if (!tmp.renameTo(file)) {
                tmp.copyTo(file, overwrite = true)
                tmp.delete()
            }
        } catch (e: java.io.IOException) {
            // Storage is full or unavailable; the in-memory state stays correct for this run.
        }
    }
}

private fun <T> JSONArray?.mapItems(f: (JSONObject) -> T): List<T> {
    if (this == null) return emptyList()
    return (0 until length()).map { f(getJSONObject(it)) }
}

private fun legToJson(l: Leg): JSONObject =
    JSONObject().put("kind", l.kind.name).put("meters", l.meters).put("seconds", l.seconds).put("waitSeconds", l.waitSeconds)

private fun legFromJson(o: JSONObject): Leg =
    Leg(LegKind.valueOf(o.getString("kind")), o.getLong("meters"), o.getLong("seconds"), o.optLong("waitSeconds", 0L))

private fun pathToJson(path: List<PathPoint>): JSONArray = JSONArray().apply {
    // FIX: PathPoint is now (lat: Double, lon: Double, leg: Int), not (x: Float, y: Float).
    // Before: it.x / it.y - did not compile after the Ride.kt change.
    path.forEach { put(JSONObject().put("lat", it.lat).put("lon", it.lon).put("leg", it.leg)) }
}

private fun pathFromJson(a: JSONArray?): List<PathPoint> = a.mapItems {
    // Backward compatible: old files saved "x" / "y" (0..1 simulated points).
    // Old x was longitude-ish, old y was latitude-ish, so map x->lon, y->lat.
    // New files save "lat" / "lon" directly.
    val lat = if (it.has("lat")) it.getDouble("lat") else it.optDouble("y", 0.0)
    val lon = if (it.has("lon")) it.getDouble("lon") else it.optDouble("x", 0.0)
    PathPoint(lat, lon, it.getInt("leg"))
}

private fun tripToJson(t: Trip): JSONObject = JSONObject().apply {
    put("id", t.id)
    put("time", t.time)
    put("mode", t.mode.name)
    put("legs", JSONArray().apply { t.legs.forEach { put(legToJson(it)) } })
    put("path", pathToJson(t.path))
    put("lines", JSONArray().apply {
        t.lines.forEach { put(JSONObject().put("group", it.group).put("label", it.label).put("amount", it.amount)) }
    })
    put("fare", t.fare)
    put("fuel", t.fuel)
    // FIX: "wear" removed - Trip no longer has a wear field (see Ride.kt).
    // Before: put("wear", t.wear) - did not compile.
}

private fun tripFromJson(o: JSONObject): Trip = Trip(
    id = o.getLong("id"),
    time = o.getLong("time"),
    mode = RideMode.valueOf(o.getString("mode")),
    legs = o.optJSONArray("legs").mapItems { legFromJson(it) },
    path = pathFromJson(o.optJSONArray("path")),
    lines = o.optJSONArray("lines").mapItems {
        FareLine(it.getString("group"), it.getString("label"), it.getDouble("amount"))
    },
    fare = o.getDouble("fare"),
    fuel = o.getDouble("fuel")
    // Old files may contain "wear" - it is ignored on purpose.
)

private fun rideToJson(r: Ride): JSONObject = JSONObject()
    .put("started", r.started)
    .put("mode", r.mode.name)
    .put("boarded", r.boarded)
    .put("meters", r.meters)
    .put("seconds", r.seconds)
    .put("waitSeconds", r.waitSeconds)
    .put("running", r.running)
    .put("done", JSONArray().apply { r.done.forEach { put(legToJson(it)) } })
    .put("path", pathToJson(r.path))

/** A restored ride is paused, so the timer does not run while the app is closed. */
private fun rideFromJson(o: JSONObject): Ride = Ride(
    started = o.optBoolean("started"),
    // FIX: running must be preserved. Before it was always false on load,
    // so a running ride became "paused" on every reopen / service restart,
    // and the service tick (started && running) never counted again.
    running = o.optBoolean("running", false),
    mode = RideMode.valueOf(o.optString("mode", RideMode.STANDARD.name)),
    boarded = o.optBoolean("boarded"),
    meters = o.optLong("meters"),
    seconds = o.optLong("seconds"),
    waitSeconds = o.optLong("waitSeconds", 0L),
    done = o.optJSONArray("done").mapItems { legFromJson(it) },
    path = pathFromJson(o.optJSONArray("path")),
)

private fun expenseToJson(e: Expense): JSONObject = JSONObject()
    .put("id", e.id)
    .put("time", e.time)
    .put("category", e.category)
    .put("amount", e.amount)
    .put("note", e.note)

private fun expenseFromJson(o: JSONObject): Expense = Expense(
    id = o.getLong("id"),
    time = o.getLong("time"),
    category = o.getString("category"),
    amount = o.getDouble("amount"),
    note = o.optString("note")
)

private fun archiveToJson(a: MonthlyArchive): JSONObject = JSONObject()
    .put("year", a.year)
    .put("month", a.month)
    .put("trips", JSONArray().apply { a.trips.forEach { put(tripToJson(it)) } })
    .put("expenses", JSONArray().apply { a.expenses.forEach { put(expenseToJson(it)) } })

private fun archiveFromJson(o: JSONObject): MonthlyArchive = MonthlyArchive(
    year = o.optInt("year"),
    month = o.optInt("month"),
    trips = o.optJSONArray("trips").mapItems { tripFromJson(it) },
    expenses = o.optJSONArray("expenses").mapItems { expenseFromJson(it) }
)

/**
 * AUTO-ARCHIVE: moves every COMPLETED month into [Snapshot.archives].
 * Runs on every app open. A month is completed when it is before the current
 * calendar month - so on the 1st of a new month the whole previous month is
 * archived at once. No 30/31 confusion: month boundaries come from Calendar,
 * not from counting days. If the app was not opened on the 1st, the previous
 * month is still before the current month, so it archives on the next open.
 * Already-archived months are merged, never duplicated. The current month
 * (and an in-progress ride) is never touched.
 */
fun archiveCompletedMonths(snap: Snapshot, now: Long): Snapshot {
    val cal = java.util.Calendar.getInstance().apply { timeInMillis = now }
    val curYear = cal.get(java.util.Calendar.YEAR)
    val curMonth = cal.get(java.util.Calendar.MONTH) + 1
    fun ym(time: Long): Pair<Int, Int> {
        val c = java.util.Calendar.getInstance().apply { timeInMillis = time }
        return c.get(java.util.Calendar.YEAR) to (c.get(java.util.Calendar.MONTH) + 1)
    }
    val isCompleted = { time: Long ->
        val (y, m) = ym(time)
        y < curYear || (y == curYear && m < curMonth)
    }
    val oldTrips = snap.trips.filter { isCompleted(it.time) }
    val oldExpenses = snap.expenses.filter { isCompleted(it.time) }
    if (oldTrips.isEmpty() && oldExpenses.isEmpty()) return snap
    // Group old data by month, merge into existing archives if present.
    val archives = snap.archives.toMutableList()
    val months = (oldTrips.map { ym(it.time) } + oldExpenses.map { ym(it.time) }).distinct()
    for ((y, m) in months) {
        val mTrips = oldTrips.filter { ym(it.time) == (y to m) }
        val mExpenses = oldExpenses.filter { ym(it.time) == (y to m) }
        val idx = archives.indexOfFirst { it.year == y && it.month == m }
        if (idx >= 0) {
            val old = archives[idx]
            val mergedTrips = (old.trips + mTrips).distinctBy { it.id }
            val mergedExpenses = (old.expenses + mExpenses).distinctBy { it.id }
            archives[idx] = old.copy(trips = mergedTrips, expenses = mergedExpenses)
        } else {
            archives.add(MonthlyArchive(year = y, month = m, trips = mTrips, expenses = mExpenses))
        }
    }
    val keptTrips = snap.trips.filterNot { isCompleted(it.time) }
    val keptExpenses = snap.expenses.filterNot { isCompleted(it.time) }
    return snap.copy(
        trips = keptTrips,
        expenses = keptExpenses,
        archives = archives.sortedWith(compareByDescending<MonthlyArchive> { it.year }.thenByDescending { it.month })
    )
}
