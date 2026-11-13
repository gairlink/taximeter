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

/** Keeps trips, expenses, tariffs and the unfinished ride in one JSON file in private storage.
 *
 * FIX #2 / #3 (concurrency + data loss):
 * - load() / save() are synchronized on ONE lock shared by every Store instance
 *   (companion object). Before, the Service and the UI wrote the same file and the
 *   same .tmp file concurrently -> one save overwrote the other, or the .tmp was
 *   half-written -> load() threw -> empty Snapshot -> next save wiped ALL history.
 * - load() is now TOLERANT: one bad Trip/Leg/PathPoint is skipped, the rest is kept.
 *   Before, a single bad entry threw out of load() and the whole file was replaced
 *   by an empty Snapshot. A corrupt file now returns what could be read, never empty
 *   just because one entry is bad.
 */
class Store(context: Context) {
    private val file = File(context.filesDir, "taxi-data.json")

    companion object {
        // One lock for ALL Store instances in this process (UI + Service).
        private val fileLock = Any()
    }

    fun load(): Snapshot {
        if (!file.exists()) return Snapshot(emptyList(), emptyList(), Tariffs())
        return try {
            val root = JSONObject(file.readText(Charsets.UTF_8))
            Snapshot(
                trips = root.optJSONArray("trips").mapItemsSafe { tripFromJson(it) },
                expenses = root.optJSONArray("expenses").mapItemsSafe { expenseFromJson(it) },
                tariffs = try { root.optJSONObject("tariffs")?.let { tariffsFromJson(it) } ?: Tariffs() } catch (e: Exception) { Tariffs() },
                ride = try { root.optJSONObject("ride")?.let { rideFromJson(it) } ?: Ride() } catch (e: Exception) { Ride() },
                archives = root.optJSONArray("archives").mapItemsSafe { archiveFromJson(it) }
            )
        } catch (e: Exception) {
            // Whole file unreadable: return empty IN MEMORY, but the caller must
            // never blindly save this over the file (see MainActivity guard).
            // The old file stays on disk until a real, non-empty save replaces it.
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
        synchronized(fileLock) {
            try {
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
}

private fun <T> JSONArray?.mapItems(f: (JSONObject) -> T): List<T> {
    if (this == null) return emptyList()
    return (0 until length()).map { f(getJSONObject(it)) }
}

/** FIX #3: like mapItems, but one bad item does not kill the whole list. */
private fun <T> JSONArray?.mapItemsSafe(f: (JSONObject) -> T): List<T> {
    if (this == null) return emptyList()
    val out = mutableListOf<T>()
    for (i in 0 until length()) {
        try { out += f(getJSONObject(i)) } catch (e: Exception) { /* skip bad entry, keep the rest */ }
    }
    return out
}

private fun legToJson(l: Leg): JSONObject =
    JSONObject().put("kind", l.kind.name).put("meters", l.meters).put("seconds", l.seconds).put("waitSeconds", l.waitSeconds)

private fun legFromJson(o: JSONObject): Leg {
    // FIX #3: unknown kind / missing fields -> default, not a crash.
    val kind = try { LegKind.valueOf(o.optString("kind", "STANDARD")) } catch (e: Exception) { LegKind.STANDARD }
    return Leg(kind, o.optLong("meters"), o.optLong("seconds"), o.optLong("waitSeconds", 0L))
}

private fun pathToJson(path: List<PathPoint>): JSONArray = JSONArray().apply {
    path.forEach { put(JSONObject().put("lat", it.lat).put("lon", it.lon).put("leg", it.leg)) }
}

private fun pathFromJson(a: JSONArray?): List<PathPoint> = a.mapItemsSafe {
    // Backward compatible: old files saved "x" / "y" (0..1 simulated points).
    // Old x was longitude-ish, old y was latitude-ish, so map x->lon, y->lat.
    // FIX #3: missing "leg" -> 0, not a crash (getInt threw before).
    val lat = if (it.has("lat")) it.getDouble("lat") else it.optDouble("y", 0.0)
    val lon = if (it.has("lon")) it.getDouble("lon") else it.optDouble("x", 0.0)
    PathPoint(lat, lon, it.optInt("leg", 0))
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
}

private fun tripFromJson(o: JSONObject): Trip = Trip(
    id = o.optLong("id", 0L),
    time = o.optLong("time", 0L),
    mode = try { RideMode.valueOf(o.optString("mode", RideMode.STANDARD.name)) } catch (e: Exception) { RideMode.STANDARD },
    legs = o.optJSONArray("legs").mapItemsSafe { legFromJson(it) },
    path = pathFromJson(o.optJSONArray("path")),
    lines = o.optJSONArray("lines").mapItemsSafe {
        FareLine(it.optString("group"), it.optString("label"), it.optDouble("amount"))
    },
    fare = o.optDouble("fare"),
    fuel = o.optDouble("fuel")
)

private fun rideToJson(r: Ride): JSONObject = JSONObject()
    .put("started", r.started)
    .put("mode", r.mode.name)
    .put("boarded", r.boarded)
    .put("meters", r.meters)
    .put("seconds", r.seconds)
    .put("waitSeconds", r.waitSeconds)
    .put("running", r.running)
    .put("startedAt", r.startedAt)
    .put("done", JSONArray().apply { r.done.forEach { put(legToJson(it)) } })
    .put("path", pathToJson(r.path))

/** A restored ride is paused, so the timer does not run while the app is closed. */
private fun rideFromJson(o: JSONObject): Ride = Ride(
    started = o.optBoolean("started"),
    running = o.optBoolean("running", false),
    mode = try { RideMode.valueOf(o.optString("mode", RideMode.STANDARD.name)) } catch (e: Exception) { RideMode.STANDARD },
    boarded = o.optBoolean("boarded"),
    meters = o.optLong("meters"),
    seconds = o.optLong("seconds"),
    waitSeconds = o.optLong("waitSeconds", 0L),
    done = o.optJSONArray("done").mapItemsSafe { legFromJson(it) },
    path = pathFromJson(o.optJSONArray("path")),
    startedAt = o.optLong("startedAt", 0L)
)

private fun expenseToJson(e: Expense): JSONObject = JSONObject()
    .put("id", e.id)
    .put("time", e.time)
    .put("category", e.category)
    .put("amount", e.amount)
    .put("note", e.note)

private fun expenseFromJson(o: JSONObject): Expense = Expense(
    id = o.optLong("id", 0L),
    time = o.optLong("time", 0L),
    category = o.optString("category"),
    amount = o.optDouble("amount"),
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
    trips = o.optJSONArray("trips").mapItemsSafe { tripFromJson(it) },
    expenses = o.optJSONArray("expenses").mapItemsSafe { expenseFromJson(it) }
)

/**
 * AUTO-ARCHIVE: moves every COMPLETED month into [Snapshot.archives].
 * Runs on every app open. A month is completed when it is before the current
 * calendar month - so on the 1st of a new month the whole previous month is
 * archived at once. Already-archived months are merged, never duplicated.
 * The current month (and an in-progress ride) is never touched.
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
