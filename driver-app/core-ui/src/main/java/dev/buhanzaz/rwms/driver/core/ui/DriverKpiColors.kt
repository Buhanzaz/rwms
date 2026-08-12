package dev.buhanzaz.rwms.driver.core.ui

import androidx.compose.ui.graphics.Color
import kotlin.math.roundToInt

/**
 * Defines driver feature UI state; server data and authorization remain authoritative.
 */
data class DriverKpiColorRange(
    val fromPercent: Int,
    val toPercent: Int,
    val color: String,
)

/**
 * Selects only a color explicitly issued by task-board settings.
 * Invalid/missing policies return null so the screen keeps its normal theme
 * color instead of inventing local KPI thresholds.
 */
fun driverKpiTimeColor(
    remainingPercent: Double?,
    ranges: List<DriverKpiColorRange>,
    overdueColor: String?,
): Color? {
    val percent = remainingPercent?.takeIf(Double::isFinite) ?: return null
    val encodedColor = when {
        percent < 0 -> overdueColor
        percent > 100 -> null
        else -> {
            val rounded = percent.roundToInt()
            ranges.firstOrNull { range ->
                rounded in minOf(range.fromPercent, range.toPercent)..
                    maxOf(range.fromPercent, range.toPercent)
            }?.color
        }
    }
    return encodedColor?.let(::parseServerColor)
}

private fun parseServerColor(value: String): Color? {
    val hex = value.trim().removePrefix("#")
    val argb = when (hex.length) {
        6 -> hex.toLongOrNull(16)?.let { 0xFF000000L or it }
        8 -> hex.toLongOrNull(16)
        else -> null
    } ?: return null
    return Color(argb)
}
