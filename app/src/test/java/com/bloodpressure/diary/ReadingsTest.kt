package com.bloodpressure.diary

import org.junit.Assert.assertEquals
import org.junit.Test

class ReadingsTest {
    @Test
    fun fillsMissingFieldsWithFallbackAndKeepsUncontestedValues() {
        val segments = Readings(null, 83, 68)
        assertEquals(Readings(113, 83, 68), segments.withFallback(Readings(113, null, 68)))
        assertEquals(segments, segments.withFallback(Readings(null, null, null)))
    }

    @Test
    fun leavesConflictingValuesBlank() {
        val segments = Readings(null, 83, 68)
        assertEquals(Readings(113, null, 68), segments.withFallback(Readings(113, 88, null)))
        assertEquals(Readings(null, null, 68), segments.withFallback(Readings(80, null, null)))
    }

    @Test
    fun pairsLabelsInSeparateColumns() {
        assertEquals(Readings(123, 81, 72), parseReadings(listOf(
            token("SYS", 0, 0), token("DIA", 0, 30), token("PUL", 0, 60),
            token("123", 100, 0), token("81", 100, 30), token("72", 100, 60)
        )))
    }

    @Test
    fun recognizesTraditionalChineseAndValuesOnTheLeft() {
        assertEquals(Readings(128, 85, 75), parseReadings(listOf(
            token("128", 0, 0), token("收縮壓：", 100, 0),
            token("85", 0, 30), token("舒張壓", 100, 30),
            token("75", 0, 60), token("脈搏", 100, 60)
        )))
    }

    @Test
    fun leavesUnlabeledValuesBlank() {
        assertEquals(Readings(null, null, null), parseReadings(listOf(
            token("120", 100, 0), token("80", 100, 30), token("70", 100, 60)
        )))
    }

    @Test
    fun rejectsMultipleCandidatesAndDamagedDigits() {
        assertEquals(Readings(null, null, 72), parseReadings(listOf(
            token("SYS", 0, 0), token("120", 100, 0), token("121", 150, 0),
            token("DIA", 0, 30), token("8O", 100, 30),
            token("PULSE", 0, 60), token("72", 100, 60)
        )))
    }

    @Test
    fun rejectsInvertedPressureWithoutSwapping() {
        assertEquals(Readings(null, null, 72), parseReadings(listOf(
            token("SYS", 0, 0), token("80", 100, 0), token("DIA", 0, 30), token("120", 100, 30),
            token("PUL", 0, 60), token("72", 100, 60)
        )))
    }

    @Test
    fun rejectsSharedAndDuplicateLabels() {
        assertEquals(Readings(null, null, null), parseReadings(listOf(
            token("SYS", 0, 0), token("DIA", 40, 0), token("120", 100, 0),
            token("PUL", 0, 60), token("PULSE", 40, 60), token("72", 100, 60)
        )))
    }

    @Test
    fun rejectsValueAlsoClaimedByAnAmbiguousRow() {
        assertEquals(Readings(null, null, null), parseReadings(listOf(
            token("SYS", 0, 0), token("DIA", 40, 7),
            token("120", 100, 0), token("80", 100, 14)
        )))
    }

    private fun token(text: String, left: Int, top: Int) =
        OcrToken(text, left, top, left + 30, top + 15)
}
