package com.example.taximeter

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Real road speed limit from OpenStreetMap (the same data Waze / OsmAnd use).
 *
 * How it works: asks the Overpass API for roads with a "maxspeed" tag within
 * 50 m of the car, picks the NEAREST one, and returns its limit in km/h.
 * Returns null when there is no data (no tagged road nearby), no internet,
 * or the request fails - the caller then falls back to the manual limit
 * (the one you set by tapping the sign).
 *
 * Throttling lives in MainActivity: this function is only called when the car
 * moved > 100 m since the last lookup and at least 15 s passed, so it does
 * not hammer the API every second.
 */
suspend fun fetchRoadSpeedLimit(lat: Double, lon: Double): Double? = withContext(Dispatchers.IO) {
    try {
        val query = "[out:json][timeout:10];" +
            "way(around:50,$lat,$lon)[\"maxspeed\"];out center tags;"
        val body = "data=" + URLEncoder.encode(query, "UTF-8")
        val conn = (URL("https://overpass-api.de/api/interpreter").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            connectTimeout = 8000
            readTimeout = 8000
        }
        OutputStreamWriter(conn.outputStream, "UTF-8").use { it.write(body) }
        if (conn.responseCode != 200) return@withContext null
        val text = BufferedReader(InputStreamReader(conn.inputStream, "UTF-8")).use { it.readText() }
        val elements = JSONObject(text).optJSONArray("elements") ?: return@withContext null
        val here = GpsFix(lat, lon, 0f)
        var best: Double? = null
        var bestDist = Double.MAX_VALUE
        for (i in 0 until elements.length()) {
            val el = elements.getJSONObject(i)
            val tags = el.optJSONObject("tags") ?: continue
            // "maxspeed" first, then direction-specific tags.
            val raw = tags.optString("maxspeed").ifEmpty { tags.optString("maxspeed:forward") }
                .ifEmpty { tags.optString("maxspeed:backward") }
            val limit = parseMaxSpeed(raw) ?: continue
            // Nearest road wins: a parallel road / side street nearby may
            // carry a different limit, and we want OUR road's limit.
            val center = el.optJSONObject("center") ?: continue
            val dist = distanceMeters(here, GpsFix(center.optDouble("lat"), center.optDouble("lon"), 0f))
            if (dist < bestDist) { bestDist = dist; best = limit }
        }
        best
    } catch (e: Exception) {
        // No internet / API down / bad response - caller falls back to manual limit.
        null
    }
}

/** Parses an OSM maxspeed value to km/h. Null = no usable limit. */
private fun parseMaxSpeed(raw: String): Double? {
    val v = raw.trim().lowercase()
    if (v.isEmpty()) return null
    // Country codes: "IT:urban" = city 50, "IT:rural" = 90, "IT:motorway" = 130,
    // "IT:trunk" = 110, living street = 30. Same words other countries use too.
    if ("living" in v) return 30.0
    if ("motorway" in v) return 130.0
    if ("trunk" in v) return 110.0
    if ("urban" in v) return 50.0
    if ("rural" in v || "national" in v) return 90.0
    if ("none" in v || "walk" in v) return null
    // Plain number, maybe with a unit: "50", "50 mph", "50;30" (take the first).
    val number = v.split(";", ",", " ").firstOrNull { it.isNotEmpty() } ?: return null
    val kmh = number.toDoubleOrNull() ?: return null
    // mph -> km/h.
    return if ("mph" in v) kmh * 1.60934 else kmh
}
