package com.example.taximeter

import java.util.UUID

enum class LegKind { PICKUP, STANDARD, FRIENDS }

/** One billed part of a ride. A pickup ride has a PICKUP leg, then a STANDARD leg after boarding. */
// waitSeconds = time spent PAUSED (⏸ პაუზა) inside this leg. Driving time is `seconds`.
// BEFORE this field did not exist, so fareLines() used `seconds` (the WHOLE ride time)
// as waiting - that is why a 20 min drive showed 20 min of waiting.
data class Leg(val kind: LegKind, val meters: Long, val seconds: Long, val waitSeconds: Long = 0L)

/** A fare row. Rows with the same [group] are shown together in the details sheet. */
data class FareLine(val group: String, val label: String, val amount: Double)

/** A point on the real route: real GPS lat/lon. [leg] is the index of the leg it belongs to. */
// FIX: was (x: Float, y: Float) - but Route.kt stores real lat/lon (Double) here.
// That is why Route.kt had "Double, but Float was expected" + "it.lat unresolved".
// Drawing code must PROJECT lat/lon to pixels (see project() in Route.kt), not use them raw.
data class PathPoint(val lat: Double, val lon: Double, val leg: Int)

/** The ride in progress. Immutable: every change makes a copy. */
data class Ride(
    val started: Boolean = false,
    val running: Boolean = false,
    val mode: RideMode = RideMode.STANDARD,
    val boarded: Boolean = false,
    val meters: Long = 0L,
    val seconds: Long = 0L,
    val waitSeconds: Long = 0L,
    val done: List<Leg> = emptyList(),
    val path: List<PathPoint> = emptyList(),
    // Last instant GPS speed (km/h). NOT billed - only shown on the speedometer.
    // The service updates it on every fix; the UI polls it from the saved ride.
    val speedKmh: Double = 0.0
) {
    val legKind: LegKind
        get() = when {
            mode == RideMode.FRIENDS -> LegKind.FRIENDS
            mode == RideMode.PICKUP && !boarded -> LegKind.PICKUP
            else -> LegKind.STANDARD
        }

    /** True while driving to the client, before the passenger boards. */
    val waitingForClient: Boolean get() = started && mode == RideMode.PICKUP && !boarded

    val totalMeters: Long get() = done.sumOf { it.meters } + meters
    val totalSeconds: Long get() = done.sumOf { it.seconds } + seconds
    val totalWaitSeconds: Long get() = done.sumOf { it.waitSeconds } + waitSeconds

    fun legs(): List<Leg> = done + Leg(legKind, meters, seconds, waitSeconds)

    // Minimum fare applies once the ride has started - a started ride
    // never costs less than minFare (€10). Before starting, fareLines runs
    // with applyMin=false, so the idle screen still shows the real fare.
    fun fare(t: Tariffs): Double = fareLines(legs(), t, applyMin = started).sumOf { it.amount }
}

/**
 * Builds the fare rows for each leg. Each standard band starts from 0 km within its own leg.
 *
 * FIXED:
 * - Boarding fee is charged only once per ride (first STANDARD / first FRIENDS leg),
 *   not once per leg.
 * - Free wait minutes are a per-ride allowance: unused free minutes carry over
 *   to the next leg instead of resetting on every leg.
 */
fun fareLines(legs: List<Leg>, t: Tariffs, applyMin: Boolean = false): List<FareLine> {
    val lines = mutableListOf<FareLine>()

    var standardBoardingCharged = false
    var friendBoardingCharged = false
    // No more free waiting: every waiting minute is billed in full.

    legs.forEach { leg ->
        val km = leg.meters / 1000.0
        // FIX: waiting = ONLY the paused time (waitSeconds), NOT the whole leg time.
        // Before, `minutes` was leg.seconds / 60 - the full driving time - so the
        // whole ride was billed as waiting on top of the km price.
        val minutes = leg.waitSeconds / 60.0

        when (leg.kind) {
            LegKind.PICKUP -> {
                val group = "🚗 კლიენტამდე"
                lines += FareLine(group, "%.3f კმ × €%.2f".format(km, t.pickupPerKm), km * t.pickupPerKm)
                if (t.pickupWaitPerMinute > 0 && minutes > 0) {
                    lines += FareLine(group, "ლოდინი %.1f წთ × €%.2f".format(minutes, t.pickupWaitPerMinute), minutes * t.pickupWaitPerMinute)
                }
            }

            LegKind.FRIENDS -> {
                val group = "👥 ახლობლები"
                lines += FareLine(group, "%.3f კმ × €%.2f".format(km, t.friendPerKm), km * t.friendPerKm)
                // FIX: charge friend boarding only on the first FRIENDS leg.
                if (!friendBoardingCharged && t.friendBoarding > 0) {
                    lines += FareLine(group, "ჩაჯდომა", t.friendBoarding)
                    friendBoardingCharged = true
                }
                if (t.friendWaitPerMinute > 0 && minutes > 0) {
                    lines += FareLine(group, "ლოდინი %.1f წთ × €%.2f".format(minutes, t.friendWaitPerMinute), minutes * t.friendWaitPerMinute)
                }
            }

            LegKind.STANDARD -> {
                val group = "📊 ძირითადი (%.2fკმ)".format(km)
                val band1 = minOf(km, 5.0)
                val band2 = (minOf(km, 15.0) - 5.0).coerceAtLeast(0.0)
                val band3 = (km - 15.0).coerceAtLeast(0.0)

                if (band1 > 0) lines += FareLine(group, "0-5კმ: %.3f × €%.2f".format(band1, t.km0to5), band1 * t.km0to5)
                if (band2 > 0) lines += FareLine(group, "5-15კმ: %.3f × €%.2f".format(band2, t.km5to15), band2 * t.km5to15)
                if (band3 > 0) lines += FareLine(group, "15+კმ: %.3f × €%.2f".format(band3, t.km15plus), band3 * t.km15plus)

                // All waiting is billed - no free minutes anymore.
                val wait = minutes
                if (wait > 0 && t.waitPerMinute > 0) {
                    lines += FareLine(group, "ლოდინი %.1f წთ × €%.2f".format(wait, t.waitPerMinute), wait * t.waitPerMinute)
                }

                // FIX: charge boarding only on the first STANDARD leg.
                if (!standardBoardingCharged && t.boarding > 0) {
                    lines += FareLine(group, "ჩაჯდომა", t.boarding)
                    standardBoardingCharged = true
                }
            }
        }
    }
    // Minimum fare. If the ride's fare is below minFare (€10), top it up
    // so the total is never less than the minimum. Shown as its own line, so
    // the breakdown still sums to the total. Only when applyMin (started ride /
    // finished trip) - the idle screen before Start is not topped up.
    if (applyMin) {
        val total = lines.sumOf { it.amount }
        if (total < t.minFare) {
            lines += FareLine(
                "💰 მინიმალური ტარიფი",
                "მინიმუმ €%.2f".format(t.minFare),
                t.minFare - total
            )
        }
    }
    return lines
}

data class Trip(
    val id: Long,
    val time: Long,
    val mode: RideMode,
    val legs: List<Leg>,
    val path: List<PathPoint>,
    val lines: List<FareLine>,
    val fare: Double,
    val fuel: Double
) {
    val meters: Long get() = legs.sumOf { it.meters }
    val seconds: Long get() = legs.sumOf { it.seconds }
    val km: Double get() = meters / 1000.0
    val profit: Double get() = fare - fuel
}

/** Turns a finished ride into a [Trip], snapshotting the fare with the tariffs in use now. */
fun tripFrom(ride: Ride, t: Tariffs, now: Long): Trip {
    val legs = ride.legs()
    // Finished trip: minimum fare applies here too.
    val fare = fareLines(legs, t, applyMin = true)
    val total = fare.sumOf { it.amount }
    val km = ride.totalMeters / 1000.0
    return Trip(
        // FIX: UUID-based id instead of System.nanoTime() - unique even across restarts.
        id = UUID.randomUUID().mostSignificantBits,
        time = now,
        mode = ride.mode,
        legs = legs,
        path = ride.path,
        lines = fare,
        fare = total,
        fuel = km * fuelEurPerKm(t)
    )
}

data class Expense(val id: Long, val time: Long, val category: String, val amount: Double, val note: String)

/** Fuel cost per km: liters per 100 km divided by 100, times the price per liter. */
fun fuelEurPerKm(t: Tariffs): Double = t.fuelLitersPer100Km / 100.0 * t.fuelPricePerLiter