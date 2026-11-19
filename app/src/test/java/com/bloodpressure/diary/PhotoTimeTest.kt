package com.bloodpressure.diary

import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PhotoTimeTest {
    private val taipei = ZoneId.of("Asia/Taipei")

    @Test
    fun usesFileModificationTimeBeforeExif() {
        val modified = Instant.parse("2026-11-19T02:12:34Z").toEpochMilli()
        assertEquals(
            PhotoTime(OffsetDateTime.parse("2026-11-19T10:12:34+08:00"), true),
            resolvePhotoTime(modified, "2026:11:01 11:00:00", "+09:00", taipei)
        )
    }

    @Test
    fun usesExifOffsetWhenFileTimeIsMissing() {
        assertEquals(
            PhotoTime(OffsetDateTime.parse("2026-11-19T10:12:34+09:00"), false),
            resolvePhotoTime(null, "2026:11:19 10:12:34", "+09:00", taipei)
        )
    }

    @Test
    fun usesDeviceZoneWhenExifHasNoOffset() {
        assertEquals(
            PhotoTime(OffsetDateTime.parse("2026-11-19T10:12:34+08:00"), false),
            resolvePhotoTime(0, "2026:11:19 10:12:34", null, taipei)
        )
    }

    @Test
    fun doesNotInventMissingOrInvalidTime() {
        assertNull(resolvePhotoTime(null, null, null, taipei))
        assertNull(resolvePhotoTime(0, "2026:02:30 09:00:00", null, taipei))
    }
}
