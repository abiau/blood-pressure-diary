package com.bloodpressure.diary

internal data class Readings(val systolic: Int?, val diastolic: Int?, val pulse: Int?) {
    val isComplete get() = systolic != null && diastolic != null && pulse != null

    fun withFallback(other: Readings): Readings {
        fun agree(first: Int?, second: Int?): Int? = when {
            first == null -> second
            second == null || first == second -> first
            else -> null
        }
        val sys = agree(systolic, other.systolic)
        val dia = agree(diastolic, other.diastolic)
        val heartRate = agree(pulse, other.pulse)
        return if (sys != null && dia != null && sys <= dia) Readings(null, null, heartRate)
        else Readings(sys, dia, heartRate)
    }
}

internal data class OcrToken(val text: String, val left: Int, val top: Int, val right: Int, val bottom: Int)

private enum class Field(val label: Regex) {
    SYSTOLIC(Regex("SYS|收縮壓|高壓")),
    DIASTOLIC(Regex("DIA|舒張壓|低壓")),
    PULSE(Regex("PUL|PULSE|脈搏|心率"))
}

// 只配對同列的明確標籤和獨立整數，共用候選或多個候選均留空。
internal fun parseReadings(tokens: List<OcrToken>): Readings {
    val numbers = tokens.filter { it.text.matches(Regex("[0-9]{2,3}")) }
    val candidates = Field.entries.associateWith { field ->
        val labels = tokens.filter { field.label.matches(it.text.uppercase().trimEnd(':', '：', '.')) }
        labels.map { label ->
            numbers.filter { number ->
                val overlap = minOf(number.bottom, label.bottom) - maxOf(number.top, label.top)
                overlap > 0 && overlap * 2 >= minOf(number.bottom - number.top, label.bottom - label.top) &&
                    (number.left >= label.right || number.right <= label.left)
            }
        }
    }
    fun value(field: Field): Int? {
        val candidate = candidates.getValue(field).singleOrNull()?.singleOrNull() ?: return null
        val shared = candidates.any { (other, rows) -> other != field && rows.any { candidate in it } }
        return candidate.text.toInt().takeIf { it > 0 && !shared }
    }
    val systolic = value(Field.SYSTOLIC)
    val diastolic = value(Field.DIASTOLIC)
    val pulse = value(Field.PULSE)
    return if (systolic != null && diastolic != null && systolic <= diastolic) {
        Readings(null, null, pulse)
    } else {
        Readings(systolic, diastolic, pulse)
    }
}
