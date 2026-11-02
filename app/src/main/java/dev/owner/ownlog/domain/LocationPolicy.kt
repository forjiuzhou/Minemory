package dev.owner.ownlog.domain

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

data class GeoFix(
    val lat: Double,
    val lng: Double,
    val accuracyMeters: Double,
    val timeMillis: Long,
)

object LocationPolicy {
    const val MIN_GAP_MS = 45_000L
    const val STATIONARY_MS = 8 * 60 * 1000L
    const val MIN_MOVE_METERS = 100.0

    fun shouldStore(
        previous: GeoFix?,
        next: GeoFix,
        minMoveMeters: Double = MIN_MOVE_METERS,
        stationaryMs: Long = STATIONARY_MS,
        minGapMs: Long = MIN_GAP_MS,
    ): Boolean {
        if (!valid(next)) return false
        if (previous == null || !valid(previous)) return true
        val elapsed = next.timeMillis - previous.timeMillis
        if (elapsed < minGapMs) return false
        val distance = distanceMeters(previous.lat, previous.lng, next.lat, next.lng)
        val accuracy = if (next.accuracyMeters.isFinite() && next.accuracyMeters > 0) {
            next.accuracyMeters
        } else {
            0.0
        }
        if (distance >= minMoveMeters && distance >= accuracy) return true
        return elapsed >= stationaryMs
    }

    fun distanceMeters(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val earth = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val a = sin(dLat / 2).pow(2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLng / 2).pow(2)
        return 2 * earth * asin(sqrt(a.coerceIn(0.0, 1.0)))
    }

    private fun valid(fix: GeoFix): Boolean {
        return fix.lat.isFinite() && fix.lng.isFinite() &&
            fix.lat in -90.0..90.0 && fix.lng in -180.0..180.0
    }
}
