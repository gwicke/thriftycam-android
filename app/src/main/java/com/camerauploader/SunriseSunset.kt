package com.camerauploader

import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.math.*

/**
 * Sunrise/sunset calculator using the USNO/Ed Williams algorithm.
 * Accurate to within ~1 minute. No external dependencies.
 *
 * Reference: https://web.archive.org/web/20161202180207/http://williams.best.vwh.net/sunrise_sunset_algorithm.htm
 */
object SunriseSunset {

    // Solar zenith at sunrise/sunset: 90° + 50' refraction + 16' half-disk = 90.833°
    private const val ZENITH = 90.833

    /**
     * Returns (sunrise, sunset) as UTC epoch-milliseconds for [date] at [latitude]/[longitude],
     * or null if the sun does not rise or set on that day (polar day/night).
     */
    fun compute(date: LocalDate, latitude: Double, longitude: Double): Pair<Long, Long>? {
        val sunrise = moment(date, latitude, longitude, rising = true)  ?: return null
        val sunset  = moment(date, latitude, longitude, rising = false) ?: return null
        return Pair(sunrise, sunset)
    }

    private fun moment(date: LocalDate, lat: Double, lon: Double, rising: Boolean): Long? {
        val deg = PI / 180.0
        val doy = date.dayOfYear.toDouble()
        val lngHour = lon / 15.0
        val t = doy + ((if (rising) 6.0 else 18.0) - lngHour) / 24.0

        // Sun's mean anomaly
        val M = (0.9856 * t - 3.289).mod(360.0)

        // Sun's true longitude
        var L = (M + 1.916 * sin(M * deg) + 0.020 * sin(2.0 * M * deg) + 282.634).mod(360.0)

        // Sun's right ascension, adjusted to same quadrant as L
        var RA = (atan(0.91764 * tan(L * deg)) / deg).mod(360.0)
        RA += floor(L / 90.0) * 90.0 - floor(RA / 90.0) * 90.0
        RA /= 15.0  // convert degrees to hours

        // Sun's declination
        val sinDec = 0.39782 * sin(L * deg)
        val cosDec = cos(asin(sinDec))

        // Sun's local hour angle
        val cosH = (cos(ZENITH * deg) - sinDec * sin(lat * deg)) / (cosDec * cos(lat * deg))
        if (cosH > 1.0) return null  // sun never rises
        if (cosH < -1.0) return null  // sun never sets

        val H = if (rising) (360.0 - acos(cosH) / deg) / 15.0
                else        (acos(cosH) / deg) / 15.0

        // Local mean time, then convert to UTC
        val UT = (H + RA - 0.06571 * t - 6.622 - lngHour).mod(24.0)

        val dayOffset = t.toLong() - date.dayOfYear
        val anchorDate = date.plusDays(dayOffset)
        val midnight = anchorDate.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        return midnight + (UT * 3_600_000.0).toLong()
    }

    private fun Double.mod(other: Double): Double {
        val r = this % other
        return if (r < 0.0) r + other else r
    }
}
