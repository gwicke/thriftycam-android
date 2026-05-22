package com.camerauploader

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class SunriseSunsetTest {

    private val PDT = ZoneId.of("America/Los_Angeles")
    private val UTC = ZoneId.of("UTC")

    @Test
    fun westernTimezone_sunsetOnNextUtcDay() {
        // 48.7°N, 122.5°W, May 21 — sunset crosses UTC midnight (t≈142.09, dayOffset=+1).
        // Before the fix the code anchored to the input date (May 21 UTC), placing sunset
        // 24 h too early. Regression guard: sunset must be on May 22 UTC.
        val date = LocalDate.of(2025, 5, 21)
        val (sunrise, sunset) = SunriseSunset.compute(date, 48.7, -122.5)!!

        val sunriseUtcDate = Instant.ofEpochMilli(sunrise).atZone(UTC).toLocalDate()
        val sunsetUtcDate  = Instant.ofEpochMilli(sunset).atZone(UTC).toLocalDate()
        assertEquals(LocalDate.of(2025, 5, 21), sunriseUtcDate)  // same UTC day
        assertEquals(LocalDate.of(2025, 5, 22), sunsetUtcDate)   // next UTC day (regression)

        // Sanity: ~5:21am PDT sunrise, ~8:52pm PDT sunset
        val sunrisePDT = Instant.ofEpochMilli(sunrise).atZone(PDT)
        val sunsetPDT  = Instant.ofEpochMilli(sunset).atZone(PDT)
        assertTrue("Sunrise before noon PDT", sunrisePDT.hour < 12)
        assertTrue("Sunset after 6pm PDT",    sunsetPDT.hour >= 18)
        assertTrue("Sunrise < sunset",         sunrise < sunset)
    }

    @Test fun polarDay_returnsNull() =
        assertNull(SunriseSunset.compute(LocalDate.of(2025, 6, 21), 89.0, 0.0))

    @Test fun polarNight_returnsNull() =
        assertNull(SunriseSunset.compute(LocalDate.of(2025, 12, 21), 89.0, 0.0))

    @Test
    fun sunriseAlwaysBeforeSunset() {
        val locations = listOf(0.0 to 0.0, 48.7 to -122.5, -33.9 to 151.2, 51.5 to -0.1)
        val date = LocalDate.of(2025, 3, 21)  // equinox: non-polar everywhere
        for ((lat, lon) in locations) {
            val pair = SunriseSunset.compute(date, lat, lon) ?: continue
            assertTrue("sunrise < sunset at ($lat, $lon)", pair.first < pair.second)
        }
    }
}
