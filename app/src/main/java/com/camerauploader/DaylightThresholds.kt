package com.camerauploader

import java.time.Instant
import java.time.ZoneId

internal enum class ThresholdType { SUNRISE, SUNSET, DAY_CHANGE }
internal data class DaylightThreshold(val ms: Long, val type: ThresholdType)

/**
 * Pure threshold scanner: finds the first solar event strictly after [nowMs],
 * starting from the calendar date implied by [nowMs] in [tz], looking up to 9 days ahead.
 *
 * Three-way logic per day:
 * - Polar (compute returns null): DAY_CHANGE at next civil midnight.
 * - Negative offset shrinks window to zero (rise >= set): skip day (like polar night).
 * - Large positive offset overlaps next day's rise (set >= tomorrowRise): DAY_CHANGE
 *   at solar midnight (set + tomorrowRise) / 2.
 * - Normal: SUNRISE then SUNSET.
 *
 * Returns null after 9 consecutive unproductive days, signalling interval-mode fallback.
 */
internal fun findNextThreshold(
    nowMs: Long,
    lat: Double,
    lon: Double,
    offsetMs: Long,
    tz: ZoneId,
): DaylightThreshold? {
    var date = Instant.ofEpochMilli(nowMs).atZone(tz).toLocalDate()
    for (i in 0 until 9) {
        val pair = SunriseSunset.compute(date, lat, lon)
        val candidates = if (pair == null) {
            // True polar day/night: advance to civil midnight of next day.
            val midnight = date.plusDays(1).atStartOfDay(tz).toInstant().toEpochMilli()
            listOf(DaylightThreshold(midnight, ThresholdType.DAY_CHANGE))
        } else {
            val rise = pair.first - offsetMs
            val set  = pair.second + offsetMs
            when {
                rise >= set -> emptyList()  // negative offset zeroes/inverts window
                else -> {
                    val tomorrowRise = SunriseSunset.compute(date.plusDays(1), lat, lon)
                        ?.first?.let { it - offsetMs }
                    if (tomorrowRise != null && set >= tomorrowRise) {
                        // Large positive offset: window spans midnight → solar midnight boundary.
                        listOf(DaylightThreshold((set + tomorrowRise) / 2, ThresholdType.DAY_CHANGE))
                    } else {
                        listOf(
                            DaylightThreshold(rise, ThresholdType.SUNRISE),
                            DaylightThreshold(set,  ThresholdType.SUNSET),
                        )
                    }
                }
            }
        }
        val threshold = candidates.firstOrNull { it.ms > nowMs }
        if (threshold != null) return threshold
        date = date.plusDays(1)
    }
    return null
}
