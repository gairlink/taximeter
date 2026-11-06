package com.example.taximeter

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class Snapshot(
    val trips: List<Trip>,
    val expenses: List<Expense>,
    val tariffs: Tariffs,
    val ride: Ride = Ride()
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
                ride = root.optJSONObject("ride")?.let { rideFromJson(it) } ?: Ride()
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
        try {
            file.writeText(root.toString(), Charsets.UTF_8)
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
    JSONObject().put("kind", l.kind.name).put("meters", l.meters).put("seconds", l.seconds)

private fun legFromJson(o: JSONObject): Leg =
    Leg(LegKind.valueOf(o.getString("kind")), o.getLong("meters"), o.getLong("seconds"))

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
    .put("done", JSONArray().apply { r.done.forEach { put(legToJson(it)) } })
    .put("path", pathToJson(r.path))

/** A restored ride is paused, so the timer does not run while the app is closed. */
private fun rideFromJson(o: JSONObject): Ride = Ride(
    started = o.optBoolean("started"),
    running = false,
    mode = RideMode.valueOf(o.optString("mode", RideMode.STANDARD.name)),
    boarded = o.optBoolean("boarded"),
    meters = o.optLong("meters"),
    seconds = o.optLong("seconds"),
    done = o.optJSONArray("done").mapItems { legFromJson(it) },
    path = pathFromJson(o.optJSONArray("path"))
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
