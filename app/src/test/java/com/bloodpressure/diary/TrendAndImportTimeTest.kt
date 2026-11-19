package com.bloodpressure.diary

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrendAndImportTimeTest {
    @Test
    fun breaksLinesOnlyWhenElapsedTimeExceedsSevenDays() {
        val start = Instant.parse("2026-10-29T12:30:00Z")
        val exactlySevenDays = start.plusSeconds(7 * 24 * 60 * 60)
        assertFalse(isMeasurementGap(start.toEpochMilli(), exactlySevenDays.minusMillis(1).toEpochMilli()))
        assertFalse(isMeasurementGap(start.toEpochMilli(), exactlySevenDays.toEpochMilli()))
        assertTrue(isMeasurementGap(start.toEpochMilli(), exactlySevenDays.plusMillis(1).toEpochMilli()))
    }

    @Test
    fun pressureScaleUsesTenMillimetersBetweenTicks() {
        assertEquals(
            listOf(140, 130, 120, 110, 100, 90, 80, 70, 60),
            pressureAxisTicks(72, 131).toList()
        )
    }

    @Test
    fun pressureScaleCoversEntireValidRange() {
        val ticks = pressureAxisTicks(1, 999)
        assertEquals(0, ticks.last)
        assertEquals(1000, ticks.first)
        assertEquals(-10, ticks.step)
    }

    @Test
    fun plotsUnequalElapsedTimesAcrossMonths() {
        val origin = Instant.parse("2026-10-31T00:00:00Z").toEpochMilli()
        val next = Instant.parse("2026-11-01T12:00:00Z").toEpochMilli()
        val later = Instant.parse("2026-11-06T00:00:00Z").toEpochMilli()
        val window = TrendWindow(0.0, 10.0)
        assertEquals(0.15f, window.position(elapsedDays(next, origin)), 0.000001f)
        assertEquals(0.6f, window.position(elapsedDays(later, origin)), 0.000001f)
    }

    @Test
    fun zoomsAroundCenterAndPansWithinHistory() {
        val zoomed = TrendWindow(20.0, 30.0).move(100.0, zoom = 2.0)
        assertEquals(TrendWindow(27.5, 15.0), zoomed)
        assertEquals(TrendWindow(0.0, 15.0), zoomed.move(100.0, panFraction = 4.0))
        assertEquals(TrendWindow(85.0, 15.0), zoomed.move(100.0, panFraction = -5.0))
        assertEquals(TrendWindow(0.0, 100.0), zoomed.move(100.0, zoom = 0.01))
        assertEquals(1.0 / 24, zoomed.move(100.0, zoom = 10000.0).days, 0.000001)
    }

    @Test
    fun duplicateKeyIgnoresOnlySubsecondPrecision() {
        val base = Instant.parse("2026-11-24T10:00:00Z").toEpochMilli()
        assertEquals(measurementSecond(base), measurementSecond(base + 999))
        assertEquals(measurementSecond(base) + 1, measurementSecond(base + 1000))
        assertEquals(-1L, measurementSecond(-1))
    }
}
