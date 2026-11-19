package com.bloodpressure.diary

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DisplayPixelsTest {
    @Test
    fun readsAllThreeRowsWithTheMeterStyleSeven() {
        val image = display(includePulse = true)
        assertEquals(Readings(105, 81, 78), parseDisplayPixels(image))
    }

    @Test
    fun rejectsDisplayWithoutPulseOrWithInsufficientContrast() {
        assertNull(parseDisplayPixels(display(includePulse = false)))
        assertNull(parseDisplayPixels(ByteArray(256 * 256) { 100 }))
    }

    private fun display(includePulse: Boolean): ByteArray {
        val pixels = ByteArray(256 * 256) { 125 }
        digit(pixels, 48, 44, 36, 63, "bc")
        digit(pixels, 96, 44, 36, 63, "abcdef")
        digit(pixels, 144, 44, 36, 63, "acdfg")
        digit(pixels, 96, 121, 36, 63, "abcdefg")
        digit(pixels, 144, 121, 36, 63, "bc")
        if (includePulse) {
            digit(pixels, 119, 198, 27, 47, "abcf")
            digit(pixels, 153, 198, 27, 47, "abcdefg")
        }
        return pixels
    }

    private fun digit(pixels: ByteArray, x: Int, y: Int, w: Int, h: Int, segments: String) {
        for (dy in 0 until h) {
            for (dx in 0 until w) {
                val u = dx.toDouble() / w
                val v = dy.toDouble() / h
                val active = when {
                    u in 0.22..0.78 && v in 0.02..0.10 -> 'a'
                    u in 0.81..0.99 && v in 0.13..0.42 -> 'b'
                    u in 0.81..0.99 && v in 0.56..0.87 -> 'c'
                    u in 0.22..0.78 && v in 0.89..0.98 -> 'd'
                    u in 0.02..0.19 && v in 0.56..0.87 -> 'e'
                    u in 0.02..0.19 && v in 0.13..0.42 -> 'f'
                    u in 0.22..0.78 && v in 0.45..0.53 -> 'g'
                    else -> null
                }
                if (active != null && active in segments) pixels[(y + dy) * 256 + x + dx] = 35
            }
        }
    }
}
