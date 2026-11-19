package com.bloodpressure.diary

import android.content.res.Configuration
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToLong

internal const val MillisecondsPerDay = 86_400_000.0
private const val MinimumVisibleDays = 1.0 / 24
private const val MaximumConnectedGapMillis = 7 * 86_400_000L

internal data class TrendWindow(val start: Double, val days: Double) {
    val end get() = start + days

    fun bounded(total: Double): TrendWindow {
        val span = days.coerceIn(MinimumVisibleDays, total)
        return TrendWindow(start.coerceIn(0.0, total - span), span)
    }

    fun move(total: Double, zoom: Double = 1.0, panFraction: Double = 0.0): TrendWindow {
        val span = (days / zoom).coerceIn(MinimumVisibleDays, total)
        return TrendWindow(start + (days - span) * 0.5 - panFraction * span, span).bounded(total)
    }

    fun position(day: Double): Float = ((day - start) / days).toFloat()
}

internal fun elapsedDays(epochMillis: Long, origin: Long): Double =
    (epochMillis - origin) / MillisecondsPerDay

internal fun isMeasurementGap(earlier: Long, later: Long): Boolean =
    later - earlier > MaximumConnectedGapMillis

private data class TrendTimeline(
    val entries: List<Measurement>,
    val zone: ZoneId,
    val origin: Long,
    val offsets: List<Double>,
    val totalDays: Double
) {
    // 血壓與脈搏共用斷線區間，門檻以原始時間戳計算。
    val gapsAfter = (0 until entries.lastIndex).filterTo(mutableSetOf()) { index ->
        isMeasurementGap(entries[index].measuredAt, entries[index + 1].measuredAt)
    }

    fun at(day: Double) = Instant.ofEpochMilli(origin + (day * MillisecondsPerDay).roundToLong()).atZone(zone)

    fun gapLabel(index: Int): String {
        val seconds = (entries[index + 1].measuredAt - entries[index].measuredAt) / 1000
        val days = seconds / 86_400
        return if (days == 7L) "超過 7 天無量測" else "$days 天無量測"
    }
}

private fun createTimeline(history: List<Measurement>): TrendTimeline {
    val zone = ZoneId.systemDefault()
    val entries = history.asReversed()
    val origin = Instant.ofEpochMilli(entries.first().measuredAt)
        .atZone(zone).toLocalDate().atStartOfDay(zone).toInstant().toEpochMilli()
    val offsets = entries.map { elapsedDays(it.measuredAt, origin) }
    return TrendTimeline(entries, zone, origin, offsets, ceil(offsets.last() + MinimumVisibleDays).coerceAtLeast(1.0))
}

@Composable
internal fun TrendScreen(
    history: List<Measurement>,
    desiredLandscape: Boolean?,
    onClose: () -> Unit,
    onOrientationChange: (landscape: Boolean) -> Unit
) {
    val systemLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val landscape = desiredLandscape ?: systemLandscape
    val rotation = when {
        landscape == systemLandscape -> 0f
        landscape -> 90f
        else -> -90f
    }
    var windowStart by rememberSaveable { mutableDoubleStateOf(Double.NaN) }
    var windowDays by rememberSaveable { mutableDoubleStateOf(30.0) }
    var selectedId by rememberSaveable { mutableStateOf<Long?>(null) }
    var gapStartId by rememberSaveable { mutableStateOf<Long?>(null) }
    var showingDetails by rememberSaveable { mutableStateOf(false) }
    val timeline = remember(history) { history.takeIf { it.isNotEmpty() }?.let(::createTimeline) }

    // 若系統忽略方向請求，以交換寬高的版面旋轉圖表，觸控也跟著圖面轉動。
    Column(
        Modifier.fillMaxSize().turnTrendLayout(rotation).verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("量測趨勢", Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall)
            TextButton(onClick = { onOrientationChange(!landscape) }) {
                Text(if (landscape) "切換直式" else "切換橫式")
            }
            TextButton(onClick = onClose) { Text("返回紀錄") }
        }
        if (timeline == null) {
            Text("新增一筆紀錄後即可查看趨勢圖。")
            return@Column
        }

        val total = timeline.totalDays
        fun currentWindow() = TrendWindow(
            if (windowStart.isNaN()) total - windowDays else windowStart, windowDays
        ).bounded(total)
        val window = currentWindow()
        fun updateWindow(value: TrendWindow) {
            windowStart = value.start
            windowDays = value.days
        }
        val indices = remember(timeline, window) {
            timeline.offsets.indices.filter { timeline.offsets[it] in window.start..window.end }
        }
        val visibleGaps = remember(timeline, window) {
            timeline.gapsAfter.filter {
                timeline.offsets[it] < window.end && timeline.offsets[it + 1] > window.start
            }
        }
        val selectedIndex = indices.firstOrNull { timeline.entries[it].id == selectedId } ?: -1
        val rangeFormat = remember { DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm") }
        val rangeLabel = "${rangeFormat.format(timeline.at(window.start))} 至 ${rangeFormat.format(timeline.at(window.end))}"
        Text(rangeLabel, style = MaterialTheme.typography.bodySmall)
        val scrollableDays = total - window.days
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(
                onClick = { updateWindow(window.move(total, zoom = 0.5)) },
                enabled = window.days < total
            ) { Text("縮小") }
            TextButton(
                onClick = { updateWindow(window.move(total, zoom = 2.0)) },
                enabled = window.days > MinimumVisibleDays
            ) { Text("放大") }
            TextButton(onClick = { updateWindow(TrendWindow(0.0, total)) }) { Text("全部") }
            if (landscape && scrollableDays > 0) {
                TimeScrollbar(window, scrollableDays, Modifier.weight(1f).padding(horizontal = 8.dp), ::updateWindow)
            } else {
                Spacer(Modifier.weight(1f))
            }
            TextButton(onClick = { showingDetails = true }) { Text("量測明細") }
        }
        if (!landscape && scrollableDays > 0) {
            TimeScrollbar(window, scrollableDays, Modifier.fillMaxWidth(), ::updateWindow)
        }
        if (!landscape) Text("橫向拖曳時間軸，雙指縮放，點圖查看數值。", style = MaterialTheme.typography.bodySmall)

        val dark = isSystemInDarkTheme()
        val pressureHigh = if (dark) Color(0xFFFFAB91) else Color(0xFF9A331D)
        val pressureLow = if (dark) Color(0xFF90CAF9) else Color(0xFF155694)
        val selectPoint: (Int) -> Unit = { selectedId = timeline.entries[it].id }
        val selectGap: (Int) -> Unit = { gapStartId = timeline.entries[it].id }
        val transform: (Double, Double) -> Unit = { zoom, pan ->
            updateWindow(currentWindow().move(total, zoom, pan))
        }
        TrendChart(
            "血壓（mmHg）", landscape,
            timeline, window, indices, visibleGaps, selectedIndex,
            listOf(
                TrendSeries(Measurement::systolic, pressureHigh, label = "收縮壓"),
                TrendSeries(Measurement::diastolic, pressureLow, label = "舒張壓", labelBelow = true)
            ),
            transform, selectPoint, selectGap, pressureScale = true
        )
        TrendChart(
            "脈搏（次／分）", landscape,
            timeline, window, indices, visibleGaps, selectedIndex,
            listOf(TrendSeries(Measurement::pulse, MaterialTheme.colorScheme.primary)),
            transform, selectPoint, selectGap
        )

        if (showingDetails) {
            AlertDialog(
                onDismissRequest = { showingDetails = false },
                title = { Text("目前範圍的量測明細") },
                text = {
                    if (indices.isEmpty()) {
                        Text("這段時間沒有量測紀錄。")
                    } else {
                        LazyColumn(Modifier.heightIn(max = 380.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            items(indices, key = { timeline.entries[it].id }) { ReadingDetail(timeline.entries[it]) }
                        }
                    }
                },
                confirmButton = { TextButton(onClick = { showingDetails = false }) { Text("關閉") } }
            )
        }
        val selectedGap = timeline.gapsAfter.firstOrNull { timeline.entries[it].id == gapStartId }
        if (selectedGap != null) {
            val before = timeline.entries[selectedGap]
            val after = timeline.entries[selectedGap + 1]
            AlertDialog(
                onDismissRequest = { gapStartId = null },
                title = { Text(timeline.gapLabel(selectedGap)) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("前一筆　${timestamp(before.measuredAt, before.offsetSeconds)}")
                        Text("下一筆　${timestamp(after.measuredAt, after.offsetSeconds)}")
                    }
                },
                confirmButton = { TextButton(onClick = { gapStartId = null }) { Text("關閉") } }
            )
        }
    }
}

@Composable
private fun TimeScrollbar(
    window: TrendWindow,
    scrollableDays: Double,
    modifier: Modifier,
    updateWindow: (TrendWindow) -> Unit
) {
    Slider(
        value = (window.start / scrollableDays).toFloat().coerceIn(0f, 1f),
        onValueChange = { updateWindow(TrendWindow(it * scrollableDays, window.days)) },
        modifier = modifier.semantics { contentDescription = "時間軸捲動" }
    )
}

private fun Modifier.turnTrendLayout(degrees: Float): Modifier {
    if (degrees == 0f) return this
    return layout { measurable, constraints ->
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        val content = measurable.measure(Constraints.fixed(height, width))
        layout(width, height) {
            content.placeWithLayer((width - content.width) / 2, (height - content.height) / 2) {
                rotationZ = degrees
            }
        }
    }
}

private fun readingValues(entry: Measurement): String =
    "${entry.systolic}／${entry.diastolic} mmHg　脈搏 ${entry.pulse} 次／分"

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ReadingDetail(entry: Measurement) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            timestamp(entry.measuredAt, entry.offsetSeconds),
            modifier = Modifier.alignByBaseline(),
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
            style = MaterialTheme.typography.bodySmall
        )
        Text(readingValues(entry), modifier = Modifier.alignByBaseline())
    }
}

private data class TrendSeries(
    val value: (Measurement) -> Int,
    val color: Color,
    val label: String? = null,
    val labelBelow: Boolean = false
)

internal fun pressureAxisTicks(minimum: Int, maximum: Int): IntProgression {
    val low = (minimum / 10 - 1).coerceAtLeast(0) * 10
    val high = (maximum / 10 + 1) * 10
    return high downTo low step 10
}

private val tickIntervals = listOf(
    1.0 / 144, 1.0 / 48, 1.0 / 24, 1.0 / 8, 0.25, 0.5, 1.0, 2.0, 4.0, 7.0, 14.0, 28.0, 56.0, 112.0
)

@Composable
private fun TrendChart(
    title: String,
    compact: Boolean,
    timeline: TrendTimeline,
    window: TrendWindow,
    indices: List<Int>,
    visibleGaps: List<Int>,
    selectedIndex: Int,
    series: List<TrendSeries>,
    onTransform: (zoom: Double, panFraction: Double) -> Unit,
    onSelect: (Int) -> Unit,
    onGapSelect: (Int) -> Unit,
    pressureScale: Boolean = false
) {
    // 同一段時間內沒有量測時也保留時間軸，方便繼續捲動。
    val plotIndices = remember(timeline, indices, window) {
        val before = timeline.offsets.indexOfLast { it < window.start }
        val after = timeline.offsets.indexOfFirst { it > window.end }
        listOf(before).filter { it >= 0 } + indices + listOf(after).filter { it >= 0 }
    }
    val values = plotIndices.flatMap { index -> series.map { it.value(timeline.entries[index]) } }
    val minimum = values.minOrNull() ?: 0
    val maximum = values.maxOrNull() ?: 100
    val ticks = if (pressureScale) {
        pressureAxisTicks(minimum, maximum)
    } else {
        val low = (minimum / 10 - 1).coerceAtLeast(0) * 10
        val step = ((maximum - low + 49) / 40).coerceAtLeast(1) * 10
        (low + step * 4) downTo low step step
    }
    val low = ticks.last
    val high = ticks.first
    val colors = MaterialTheme.colorScheme
    val text = MaterialTheme.typography.labelSmall
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val fontScale = density.fontScale.coerceAtLeast(1f)
    val axisLeft = with(density) { (34.dp * fontScale).toPx() }
    val axisRight = with(density) { (12.dp * fontScale).toPx() }
    val top = with(density) { (20.dp * fontScale).toPx() }
    val bottom = with(density) { (30.dp * fontScale).toPx() }
    val defaultHeight = if (compact) 156.dp else 185.dp
    // 保留每格 10 mmHg，數值跨度大時加高圖面，使刻度標籤保持可讀。
    val chartHeight = if (pressureScale) maxOf(defaultHeight, 50.dp + 16.dp * ((high - low) / 10))
        else defaultHeight
    fun plotWidth(width: Float) = (width - axisLeft - axisRight).coerceAtLeast(1f)
    fun plotHeight(height: Float) = (height - top - bottom).coerceAtLeast(1f)
    fun point(index: Int, value: Int, width: Float, height: Float) = Offset(
        axisLeft + window.position(timeline.offsets[index]) * plotWidth(width),
        top + (high - value).toFloat() / (high - low) * plotHeight(height)
    )
    var canvasWidth by remember { mutableIntStateOf(0) }
    val transformable = rememberTransformableState { zoom, pan, _ ->
        onTransform(zoom.toDouble(), (pan.x / plotWidth(canvasWidth.toFloat())).toDouble())
    }
    val currentOnSelect by rememberUpdatedState(onSelect)
    val currentOnGapSelect by rememberUpdatedState(onGapSelect)
    val emptyLabel = "此時段無量測紀錄"

    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            if (pressureScale) {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    series.forEach { line ->
                        line.label?.let { Text("● $it", color = line.color, style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }
            Canvas(
                Modifier.fillMaxWidth().height(chartHeight * fontScale)
                    .then(if (indices.isEmpty()) Modifier.semantics { contentDescription = emptyLabel } else Modifier)
                    .onSizeChanged { canvasWidth = it.width }
                    .transformable(
                        state = transformable,
                        canPan = { delta -> abs(delta.x) > abs(delta.y) },
                        lockRotationOnZoomPan = true
                    )
                    .pointerInput(indices, visibleGaps, timeline, window, high, low, axisLeft, axisRight) {
                        detectTapGestures { tap ->
                            val width = size.width.toFloat()
                            val height = size.height.toFloat()
                            val day = window.start + (tap.x - axisLeft) / plotWidth(width) * window.days
                            val gap = visibleGaps.firstOrNull {
                                timeline.offsets[it] < day && day < timeline.offsets[it + 1]
                            }
                            if (gap != null && tap.x in axisLeft..(axisLeft + plotWidth(width)) &&
                                tap.y in top..(top + plotHeight(height))
                            ) {
                                // 靠近端點的量測點仍可優先點選。
                                val touchesEndpoint = listOf(gap, gap + 1).any { index ->
                                    index in indices && series.any { line ->
                                        val mark = point(index, line.value(timeline.entries[index]), width, height)
                                        (mark - tap).getDistance() <= 10.dp.toPx()
                                    }
                                }
                                if (!touchesEndpoint) {
                                    currentOnGapSelect(gap)
                                    return@detectTapGestures
                                }
                            }
                            if (indices.isEmpty()) return@detectTapGestures
                            val nearX = indices.minOf { abs(point(it, low, width, height).x - tap.x) }
                            val nearby = indices.filter {
                                abs(point(it, low, width, height).x - tap.x) <= nearX + 4.dp.toPx()
                            }
                            currentOnSelect(nearby.minBy { index ->
                                series.minOf { line ->
                                    val mark = point(index, line.value(timeline.entries[index]), width, height)
                                    abs(mark.y - tap.y)
                                }
                            })
                        }
                    }
            ) {
                val width = plotWidth(size.width)
                val height = plotHeight(size.height)
                val bands = visibleGaps.map { gap ->
                    val left = axisLeft + width * window.position(maxOf(window.start, timeline.offsets[gap]))
                    val right = axisLeft + width * window.position(minOf(window.end, timeline.offsets[gap + 1]))
                    Triple(gap, left, right)
                }
                for ((_, left, right) in bands) {
                    drawRect(colors.onSurface.copy(alpha = 0.07f), Offset(left, top), Size(right - left, height))
                }
                for (tick in ticks) {
                    val y = top + height * (high - tick) / (high - low)
                    drawLine(colors.outlineVariant, Offset(axisLeft, y), Offset(axisLeft + width, y))
                    val number = measurer.measure(tick.toString(), text)
                    drawText(number, colors.onSurfaceVariant,
                        Offset((axisLeft - number.size.width - 5.dp.toPx()).coerceAtLeast(0f), y - number.size.height / 2f))
                }

                val timeFormat = if (window.days <= 2.0) "HH:mm" else "MM/dd"
                val format = DateTimeFormatter.ofPattern(timeFormat)
                val labelWidth = measurer.measure("00/00", text).size.width + 22.dp.toPx()
                val minimumStep = window.days / (width / labelWidth).coerceAtLeast(2f)
                val tickStep = tickIntervals.firstOrNull { it >= minimumStep } ?: ceil(minimumStep / 28) * 28
                var day = ceil(window.start / tickStep) * tickStep
                while (day <= window.end) {
                    val x = axisLeft + width * window.position(day)
                    val label = measurer.measure(format.format(timeline.at(day)), text)
                    drawLine(colors.outlineVariant, Offset(x, top), Offset(x, top + height))
                    drawText(label, colors.onSurfaceVariant, Offset(
                        (x - label.size.width / 2f).coerceIn(0f, (size.width - label.size.width).coerceAtLeast(0f)),
                        top + height + 7.dp.toPx()
                    ))
                    day += tickStep
                }
                if (selectedIndex >= 0) {
                    val x = point(selectedIndex, low, size.width, size.height).x
                    drawLine(colors.onSurfaceVariant, Offset(x, top), Offset(x, top + height), strokeWidth = 1.5.dp.toPx())
                }

                clipRect(axisLeft, top, axisLeft + width, top + height) {
                    series.forEach { line ->
                        val stroke = 1.5.dp.toPx()
                        plotIndices.zipWithNext().forEach { (from, to) ->
                            if (from !in timeline.gapsAfter) {
                                val first = point(from, line.value(timeline.entries[from]), size.width, size.height)
                                val last = point(to, line.value(timeline.entries[to]), size.width, size.height)
                                drawLine(line.color, first, last, stroke)
                            }
                        }
                        for (index in indices) {
                            val mark = point(index, line.value(timeline.entries[index]), size.width, size.height)
                            val radius = if (index == selectedIndex) 4.5.dp.toPx() else 2.6.dp.toPx()
                            drawCircle(line.color, radius, mark)
                        }
                    }
                }

                val occupied = mutableListOf<Rect>()
                val numericText = text.copy(fontWeight = FontWeight.Bold)
                if (indices.isEmpty()) {
                    val label = measurer.measure(emptyLabel, text, constraints = Constraints(maxWidth = width.toInt()))
                    val position = Offset(
                        axisLeft + (width - label.size.width) / 2f,
                        top + (height - label.size.height) / 2f
                    )
                    drawRect(
                        colors.surface.copy(alpha = 0.95f), position,
                        Size(label.size.width.toFloat(), label.size.height.toFloat())
                    )
                    drawText(label, colors.onSurfaceVariant, position)
                } else {
                    for ((gap, left, right) in bands) {
                        val label = measurer.measure(timeline.gapLabel(gap), text)
                        if (right - left < label.size.width + 20.dp.toPx()) continue
                        val x = (left + right - label.size.width) / 2f
                        val y = top + (height - label.size.height) / 2f
                        drawText(label, colors.onSurfaceVariant, Offset(x, y))
                        occupied.add(Rect(x, y, x + label.size.width, y + label.size.height))
                    }
                }
                val ordered = if (selectedIndex >= 0) listOf(selectedIndex) + indices.filter { it != selectedIndex } else indices
                for (index in ordered) {
                    for (line in series) {
                        val value = line.value(timeline.entries[index])
                        val mark = point(index, value, size.width, size.height)
                        val label = measurer.measure(value.toString(), numericText)
                        val x = (mark.x - label.size.width / 2f)
                            .coerceIn(axisLeft, (size.width - label.size.width).coerceAtLeast(axisLeft))
                        val y = if (line.labelBelow) mark.y + 4.dp.toPx() else mark.y - label.size.height - 4.dp.toPx()
                        val rect = Rect(x, y, x + label.size.width, y + label.size.height)
                        val padded = rect.inflate(2.dp.toPx())
                        if (occupied.none { it.overlaps(padded) }) {
                            drawRect(colors.surface, rect.topLeft, rect.size)
                            drawText(label, line.color, rect.topLeft)
                            occupied.add(padded)
                        }
                    }
                }
            }
            if (selectedIndex >= 0) {
                val entry = timeline.entries[selectedIndex]
                Text(
                    timestamp(entry.measuredAt, entry.offsetSeconds) + "\n" + readingValues(entry),
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}
