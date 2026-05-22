package com.camerauploader

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

class AlarmSchedulerThresholdTest {

    private val PDT = ZoneId.of("America/Los_Angeles")
    private val UTC = ZoneId.of("UTC")

    private val LAT = 48.7
    private val LON = -122.5

    private fun pdtMs(year: Int, month: Int, day: Int, hour: Int, min: Int = 0): Long =
        LocalDateTime.of(year, month, day, hour, min).atZone(PDT).toInstant().toEpochMilli()

    private fun utcMs(year: Int, month: Int, day: Int, hour: Int, min: Int = 0, sec: Int = 0): Long =
        LocalDateTime.of(year, month, day, hour, min, sec).atZone(UTC).toInstant().toEpochMilli()

    // ── Normal-day cases ─────────────────────────────────────────────────

    @Test
    fun insideWindow_returnsSunset() {
        // 9am PDT May 21: after sunrise (~5:21am), before sunset (~8:52pm) → SUNSET is next
        val nowMs = pdtMs(2025, 5, 21, 9)
        val threshold = findNextThreshold(nowMs, LAT, LON, 0L, PDT)!!
        assertEquals(ThresholdType.SUNSET, threshold.type)
        assertTrue("SUNSET must be in future", threshold.ms > nowMs)
        val sunsetHour = Instant.ofEpochMilli(threshold.ms).atZone(PDT).hour
        assertTrue("Sunset after 6pm PDT", sunsetHour >= 18)
    }

    @Test
    fun beforeSunrise_returnsSunrise() {
        // 3am PDT May 21: before sunrise (~5:21am) → SUNRISE is next
        val threshold = findNextThreshold(pdtMs(2025, 5, 21, 3), LAT, LON, 0L, PDT)!!
        assertEquals(ThresholdType.SUNRISE, threshold.type)
        val sunriseHour = Instant.ofEpochMilli(threshold.ms).atZone(PDT).hour
        assertTrue("Sunrise in early morning (4–7am)", sunriseHour in 4..7)
    }

    @Test
    fun afterSunset_returnsNextDaySunrise() {
        // 10pm PDT May 21: after sunset → next sunrise on May 22
        val nowMs = pdtMs(2025, 5, 21, 22)
        val threshold = findNextThreshold(nowMs, LAT, LON, 0L, PDT)!!
        assertEquals(ThresholdType.SUNRISE, threshold.type)
        assertTrue("Next sunrise is in the future", threshold.ms > nowMs)
        assertEquals(
            LocalDate.of(2025, 5, 22),
            Instant.ofEpochMilli(threshold.ms).atZone(PDT).toLocalDate(),
        )
    }

    // ── Positive offset: window overlaps next day's sunrise ───────────────

    @Test
    fun positiveOffset_overlap_returnsDayChangeAtSolarMidnight() {
        val offsetMs = 8 * 3600_000L  // +8 h
        val date     = LocalDate.of(2025, 5, 21)
        val pair     = SunriseSunset.compute(date, LAT, LON)!!
        val tomorrow = SunriseSunset.compute(date.plusDays(1), LAT, LON)!!
        val set          = pair.second    + offsetMs
        val tomorrowRise = tomorrow.first - offsetMs
        // Pre-condition: verify the +8h offset actually creates an overlap for this date/location.
        assertTrue("pre-condition: +8h creates overlap (set >= tomorrowRise)", set >= tomorrowRise)
        val expectedSolarMidnight = (set + tomorrowRise) / 2

        val threshold = findNextThreshold(pdtMs(2025, 5, 21, 9), LAT, LON, offsetMs, PDT)!!
        assertEquals(ThresholdType.DAY_CHANGE, threshold.type)
        assertEquals("DAY_CHANGE must be exactly at solar midnight", expectedSolarMidnight, threshold.ms)
    }

    // ── Negative offset: window shrunk to zero ────────────────────────────

    @Test
    fun negativeOffset_zeroWindow_returnsNull() {
        val offsetMs = -6 * 3600_000L  // −6 h
        val date = LocalDate.of(2025, 1, 21)
        val pair = SunriseSunset.compute(date, LAT, LON)!!
        val rise = pair.first  - offsetMs  // sunrise + 6 h
        val set  = pair.second + offsetMs  // sunset  − 6 h
        // Pre-condition: verify Jan 21 is genuinely a zero-window day with −6h.
        assertTrue("pre-condition: −6h in January gives zero window (rise >= set)", rise >= set)

        // All 9 January days at this latitude have similar day-length → all zero-window.
        val result = findNextThreshold(utcMs(2025, 1, 21, 0, 0), LAT, LON, offsetMs, PDT)
        assertNull("All zero-window days → null → caller falls back to interval mode", result)
    }

    // ── Polar cases ──────────────────────────────────────────────────────

    @Test
    fun polarDay_returnsDayChangeAtCivilMidnight() {
        // 89°N, June 21 (midsummer): sun never sets → DAY_CHANGE at next civil midnight UTC
        val nowMs = utcMs(2025, 6, 21, 9)
        val threshold = findNextThreshold(nowMs, 89.0, 0.0, 0L, UTC)!!
        assertEquals(ThresholdType.DAY_CHANGE, threshold.type)
        val t = Instant.ofEpochMilli(threshold.ms).atZone(UTC)
        assertEquals(0, t.hour);  assertEquals(0, t.minute);  assertEquals(0, t.second)
        assertEquals(LocalDate.of(2025, 6, 22), t.toLocalDate())
    }

    @Test
    fun polarDay_dayChangeRestartsRecording() {
        // After DAY_CHANGE fires at Jun 22 00:00 UTC, the very next call must return
        // DAY_CHANGE at Jun 23 00:00 UTC. This proves recording restarts after each
        // polar-day boundary instead of stopping.
        val afterMidnight = utcMs(2025, 6, 22, 0, 0, 1)  // 1 second after midnight
        val threshold = findNextThreshold(afterMidnight, 89.0, 0.0, 0L, UTC)!!
        assertEquals(ThresholdType.DAY_CHANGE, threshold.type)
        assertEquals(
            LocalDate.of(2025, 6, 23),
            Instant.ofEpochMilli(threshold.ms).atZone(UTC).toLocalDate(),
        )
    }
}
