package com.example.taximeter

import org.json.JSONObject

enum class RideMode(val label: String) {
    STANDARD("სტანდარტული"),
    FRIENDS("ახლობლები"),
    PICKUP("კლიენტამდე")
}

enum class Period(val label: String) {
    TODAY("დღეს"), WEEK("კვირა"), MONTH("თვე")
}

/** Tariff values in EUR. Edited in the tariff sheet and saved between runs. */
data class Tariffs(
    val km0to5: Double = 0.9,
    val km5to15: Double = 0.8,
    val km15plus: Double = 0.7,
    val waitPerMinute: Double = 0.1,
    val boarding: Double = 2.0,
    val freeWaitMinutes: Double = 0.0,
    val friendPerKm: Double = 0.35,
    val friendWaitPerMinute: Double = 0.0,
    val friendBoarding: Double = 0.0,
    val pickupPerKm: Double = 0.35,
    val pickupWaitPerMinute: Double = 0.0,
    val fuelLitersPer100Km: Double = 6.0,
    val fuelPricePerLiter: Double = 2.25,
    val wearPercent: Double = 0.0
)

fun Tariffs.toMap(): Map<String, String> = mapOf(
    "km0to5" to km0to5.toString(),
    "km5to15" to km5to15.toString(),
    "km15plus" to km15plus.toString(),
    "waitPerMinute" to waitPerMinute.toString(),
    "boarding" to boarding.toString(),
    "freeWaitMinutes" to freeWaitMinutes.toString(),
    "friendPerKm" to friendPerKm.toString(),
    "friendWaitPerMinute" to friendWaitPerMinute.toString(),
    "friendBoarding" to friendBoarding.toString(),
    "pickupPerKm" to pickupPerKm.toString(),
    "pickupWaitPerMinute" to pickupWaitPerMinute.toString(),
    "fuelLitersPer100Km" to fuelLitersPer100Km.toString(),
    "fuelPricePerLiter" to fuelPricePerLiter.toString(),
    "wearPercent" to wearPercent.toString()
)

fun parseEur(text: String?, fallback: Double): Double =
    text?.replace(',', '.')?.toDoubleOrNull() ?: fallback

/** Reads text values back into [Tariffs]; an invalid number keeps the value from [old]. */
fun tariffsFromMap(m: Map<String, String?>, old: Tariffs): Tariffs = Tariffs(
    km0to5 = parseEur(m["km0to5"], old.km0to5),
    km5to15 = parseEur(m["km5to15"], old.km5to15),
    km15plus = parseEur(m["km15plus"], old.km15plus),
    waitPerMinute = parseEur(m["waitPerMinute"], old.waitPerMinute),
    boarding = parseEur(m["boarding"], old.boarding),
    freeWaitMinutes = parseEur(m["freeWaitMinutes"], old.freeWaitMinutes),
    friendPerKm = parseEur(m["friendPerKm"], old.friendPerKm),
    friendWaitPerMinute = parseEur(m["friendWaitPerMinute"], old.friendWaitPerMinute),
    friendBoarding = parseEur(m["friendBoarding"], old.friendBoarding),
    pickupPerKm = parseEur(m["pickupPerKm"], old.pickupPerKm),
    pickupWaitPerMinute = parseEur(m["pickupWaitPerMinute"], old.pickupWaitPerMinute),
    fuelLitersPer100Km = parseEur(m["fuelLitersPer100Km"], old.fuelLitersPer100Km),
    fuelPricePerLiter = parseEur(m["fuelPricePerLiter"], old.fuelPricePerLiter),
    wearPercent = parseEur(m["wearPercent"], old.wearPercent)
)

fun Tariffs.toJson(): JSONObject = JSONObject(toMap())

fun tariffsFromJson(o: JSONObject): Tariffs {
    val m = HashMap<String, String>()
    o.keys().forEach { m[it] = o.optString(it) }
    return tariffsFromMap(m, Tariffs())
}
