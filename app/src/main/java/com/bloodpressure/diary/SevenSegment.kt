package com.bloodpressure.diary

import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfInt
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

private const val DisplaySize = 256
private val edgeThresholds = listOf(24.0 to 56.0, 12.0 to 32.0, 45.0 to 90.0)
private val digitsByMask = mapOf(
    63 to 0, 6 to 1, 91 to 2, 79 to 3, 102 to 4, 109 to 5,
    125 to 6, 7 to 7, 39 to 7, 127 to 8, 111 to 9
)

// 找出近方形 LCD 的外框，校正透視，再套用樣本血壓計的數字版面。
internal fun readSevenSegmentDisplay(gray: Mat): Readings? {
    val originals = mutableSetOf<Readings>()
    scanDisplays(gray) { pixels ->
        parseDisplayPixels(pixels)?.let(originals::add)
        originals.size <= 1
    }
    originals.singleOrNull()?.let { return it }

    val candidates = mutableListOf<DisplayRows>()
    val collect: (ByteArray) -> Boolean = { pixels ->
        val rows = DisplayPixels(pixels).readRows(searchHorizontalStrokes = true)
        if (rows.supportedCount >= 2 && rows.pressuresAgree) candidates.add(rows)
        true
    }
    scanDisplays(gray, onPixels = collect)
    scanDisplays(gray, enhanceEdges = true, onPixels = collect)
    val readings = Readings(
        strongestRow(candidates.mapNotNull { it.systolic }),
        strongestRow(candidates.mapNotNull { it.diastolic }),
        strongestRow(candidates.mapNotNull { it.pulse })
    )
    if (readings.systolic != null && readings.diastolic != null && readings.systolic <= readings.diastolic) {
        return readings.pulse?.let { Readings(null, null, it) }
    }
    // 三欄完整結果必須能在同一個 LCD 裁切找到，避免拼接互不相容的候選。
    if (readings.isComplete && candidates.none { it.values == readings }) return null
    return readings.takeIf { it.systolic != null || it.diastolic != null || it.pulse != null }
}

private fun scanDisplays(gray: Mat, enhanceEdges: Boolean = false, onPixels: (ByteArray) -> Boolean) {
    val blur = Mat()
    val enhanced = Mat()
    val edges = Mat()
    val hierarchy = Mat()
    val kernel = Mat.ones(3, 3, CvType.CV_8U)
    val target = MatOfPoint2f(
        Point(0.0, 0.0), Point(255.0, 0.0), Point(255.0, 255.0), Point(0.0, 255.0)
    )
    val normalized = Mat()
    try {
        if (enhanceEdges) {
            val clahe = Imgproc.createCLAHE(2.5, Size(8.0, 8.0))
            try {
                clahe.apply(gray, enhanced)
            } finally {
                clahe.collectGarbage()
                clahe.clear()
            }
        }
        Imgproc.GaussianBlur(if (enhanceEdges) enhanced else gray, blur, Size(5.0, 5.0), 0.0)
        for ((low, high) in edgeThresholds) {
            Imgproc.Canny(blur, edges, low, high)
            Imgproc.morphologyEx(edges, edges, Imgproc.MORPH_CLOSE, kernel)
            val contours = mutableListOf<MatOfPoint>()
            try {
                Imgproc.findContours(edges, contours, hierarchy, Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_NONE)
                for (contour in contours) {
                    val corners = displayCorners(contour, gray.cols(), gray.rows()) ?: continue
                    val source = MatOfPoint2f(*corners)
                    try {
                        val transform = Imgproc.getPerspectiveTransform(source, target)
                        try {
                            Imgproc.warpPerspective(gray, normalized, transform, Size(256.0, 256.0))
                        } finally {
                            transform.release()
                        }
                    } finally {
                        source.release()
                    }
                    val pixels = ByteArray(DisplaySize * DisplaySize)
                    normalized.get(0, 0, pixels)
                    if (!onPixels(pixels)) return
                }
            } finally {
                contours.forEach { it.release() }
            }
        }
    } finally {
        listOf(blur, enhanced, edges, hierarchy, kernel, target, normalized).forEach { it.release() }
    }
}

private fun displayCorners(contour: MatOfPoint, width: Int, height: Int): Array<Point>? {
    val bounds = Imgproc.boundingRect(contour)
    if (bounds.width.toDouble() / width !in 0.23..0.93 ||
        bounds.height.toDouble() / height !in 0.13..0.69 ||
        bounds.width.toDouble() / bounds.height !in 0.74..1.36 ||
        bounds.y + bounds.height * 0.5 >= height * 0.68 ||
        Imgproc.contourArea(contour) < bounds.area() * 0.72
    ) return null

    val coordinates = contour.toArray()
    val hull = MatOfInt()
    val boundary = MatOfPoint2f()
    val polygon = MatOfPoint2f()
    try {
        Imgproc.convexHull(contour, hull)
        boundary.fromArray(*hull.toArray().map { coordinates[it] }.toTypedArray())
        Imgproc.approxPolyDP(boundary, polygon, Imgproc.arcLength(boundary, true) * 0.04, true)
        val points = polygon.toList()
        if (points.size != 4) return null
        val corners = listOf(
            points.minBy { it.x + it.y }, points.maxBy { it.x - it.y },
            points.maxBy { it.x + it.y }, points.minBy { it.x - it.y }
        )
        if (corners.distinct().size != 4) return null
        return straightenCorners(coordinates, corners) ?: corners.toTypedArray()
    } finally {
        hull.release()
        boundary.release()
        polygon.release()
    }
}

private data class Edge(val x: Double, val y: Double, val dx: Double, val dy: Double)

private fun straightenCorners(contour: Array<Point>, corners: List<Point>): Array<Point>? {
    // 圓角處略過，只以四邊的直線部分反推交點。
    val sides = MatOfPoint2f()
    val fitted = Mat()
    try {
        val lines = corners.indices.map { index ->
            val a = corners[index]
            val b = corners[(index + 1) % 4]
            val dx = b.x - a.x
            val dy = b.y - a.y
            val length = hypot(dx, dy)
            val points = contour.filter { point ->
                val x = point.x - a.x
                val y = point.y - a.y
                val position = (x * dx + y * dy) / (length * length)
                position in 0.17..0.83 && abs(dx * y - dy * x) / length < maxOf(4.0, length * 0.10)
            }
            if (points.size < 10) return null
            sides.fromList(points)
            Imgproc.fitLine(sides, fitted, Imgproc.DIST_HUBER, 0.0, 0.01, 0.01)
            val values = FloatArray(4)
            fitted.get(0, 0, values)
            Edge(values[2].toDouble(), values[3].toDouble(), values[0].toDouble(), values[1].toDouble())
        }
        return Array(4) { index ->
            val a = lines[(index + 3) % 4]
            val b = lines[index]
            val det = a.dx * b.dy - a.dy * b.dx
            if (abs(det) < 0.3) return null
            val t = ((b.x - a.x) * b.dy - (b.y - a.y) * b.dx) / det
            Point(a.x + a.dx * t, a.y + a.dy * t)
        }
    } finally {
        sides.release()
        fitted.release()
    }
}

internal fun parseDisplayPixels(pixels: ByteArray): Readings? {
    val rows = DisplayPixels(pixels).readRows()
    return rows.values.takeIf { it.isComplete && rows.pressuresAgree }
}

private data class RowReading(val value: Int, val evidence: Double)

private data class DisplayRows(val systolic: RowReading?, val diastolic: RowReading?, val pulse: RowReading?) {
    val supportedCount get() = listOf(systolic, diastolic, pulse).count { it != null }
    val pressuresAgree get() = systolic == null || diastolic == null || systolic.value > diastolic.value
    val values get() = Readings(systolic?.value, diastolic?.value, pulse?.value)
}

private fun strongestRow(rows: List<RowReading>): Int? {
    val best = rows.maxByOrNull { it.evidence } ?: return null
    val competing = rows.filter { it.value != best.value }.maxOfOrNull { it.evidence } ?: return best.value
    // 重複輪廓不能當成多票。只有筆畫證據明顯強於其他數字時才採用。
    return best.value.takeIf { best.evidence - competing >= maxOf(5.0, competing * 0.15) }
}

private class DisplayPixels(pixels: ByteArray) {
    private val stride = DisplaySize + 1
    private val sums = IntArray(stride * stride)

    init {
        for (y in 0 until DisplaySize) {
            var rowSum = 0
            for (x in 0 until DisplaySize) {
                rowSum += pixels[y * DisplaySize + x].toInt() and 255
                sums[(y + 1) * stride + x + 1] = sums[y * stride + x + 1] + rowSum
            }
        }
    }

    fun readRows(searchHorizontalStrokes: Boolean = false) = DisplayRows(
        readRow(44, 36, 63, intArrayOf(48, 96, 144), true, searchHorizontalStrokes),
        readRow(121, 36, 63, intArrayOf(96, 144), false, searchHorizontalStrokes),
        readRow(198, 27, 47, intArrayOf(119, 153), false, searchHorizontalStrokes)
    )

    private fun readRow(
        y: Int, width: Int, height: Int, positions: IntArray,
        firstOptional: Boolean, searchHorizontalStrokes: Boolean
    ): RowReading? {
        val hits = mutableMapOf<Int, Double>()
        // 在有限位移內對齊整列，避免將鄰近圖示當成數字的一部分。
        for (dy in -6..6 step 2) {
            for (dx in -6..6 step 2) {
                val signatures = positions.map { signals(it + dx, y + dy, width, height, searchHorizontalStrokes) }
                val contrast = signatures.flatMap { it.asList() }.sortedDescending()[2]
                if (contrast < 15) continue
                val on = maxOf(10.0, contrast * 0.33)
                val off = maxOf(6.0, contrast * 0.21)
                var value = 0
                var margin = Double.POSITIVE_INFINITY
                var activeSum = 0.0
                var activeCount = 0
                var valid = true
                for ((index, signature) in signatures.withIndex()) {
                    var mask = 0
                    for ((bit, signal) in signature.withIndex()) {
                        if (signal > off && signal < on) valid = false
                        if (signal >= on) {
                            mask = mask or (1 shl bit)
                            activeSum += signal
                            activeCount++
                        }
                        margin = minOf(margin, maxOf(signal - on, off - signal))
                    }
                    if (index == 0 && firstOptional && mask == 0) continue
                    val digit = digitsByMask[mask]
                    if (digit == null || (index == 0 && digit == 0)) {
                        valid = false
                        break
                    }
                    value = value * 10 + digit
                }
                if (valid && value > 0) {
                    val score = contrast * 0.12 + margin + activeSum / activeCount * 0.18
                    hits[value] = maxOf(hits[value] ?: 0.0, score)
                }
            }
        }
        val ranked = hits.entries.sortedByDescending { it.value }
        val best = ranked.firstOrNull() ?: return null
        val runnerUp = ranked.getOrNull(1)
        return RowReading(best.key, best.value).takeIf { runnerUp == null || best.value - runnerUp.value >= 3.0 }
    }

    private fun signals(x: Int, y: Int, width: Int, height: Int, searchHorizontalStrokes: Boolean): DoubleArray {
        fun sample(rx: Double, ry: Double, rw: Double, rh: Double): Int = mean(
            (x + width * rx).roundToInt(), (y + height * ry).roundToInt(),
            (width * rw).roundToInt().coerceAtLeast(1), (height * rh).roundToInt().coerceAtLeast(1)
        )
        fun horizontalContrast(row: Double) =
            (sample(0.5, row - 0.10, 0.19, 0.023) + sample(0.5, row + 0.10, 0.19, 0.023)) / 2.0 -
                sample(0.5, row, 0.19, 0.023)
        fun horizontal(row: Double): Double {
            val center = horizontalContrast(row)
            // 小幅位移可補回透視校正後略偏離固定取樣點的橫筆。
            if (!searchHorizontalStrokes) return center
            val shift = 2.0 / height
            return maxOf(center, horizontalContrast(row - shift), horizontalContrast(row + shift))
        }
        fun vertical(col: Double, row: Double) =
            (sample(col - 0.20, row, 0.045, 0.10) + sample(col + 0.20, row, 0.045, 0.10)) / 2.0 -
                sample(col, row, 0.045, 0.10)
        return doubleArrayOf(
            horizontal(0.06), vertical(0.89, 0.26), vertical(0.91, 0.74), horizontal(0.92),
            vertical(0.11, 0.74), vertical(0.10, 0.26), horizontal(0.485)
        )
    }

    private fun mean(x: Int, y: Int, dx: Int, dy: Int): Int {
        val left = (x - dx).coerceIn(0, DisplaySize - 1)
        val right = (x + dx + 1).coerceIn(left + 1, DisplaySize)
        val top = (y - dy).coerceIn(0, DisplaySize - 1)
        val bottom = (y + dy + 1).coerceIn(top + 1, DisplaySize)
        val total = sums[bottom * stride + right] - sums[bottom * stride + left] -
            sums[top * stride + right] + sums[top * stride + left]
        return total / ((right - left) * (bottom - top))
    }
}
