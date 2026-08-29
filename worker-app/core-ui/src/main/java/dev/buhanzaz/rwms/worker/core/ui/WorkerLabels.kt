package dev.buhanzaz.rwms.worker.core.ui

/**
 * Selects a human-facing cabin number without leaking internal UUID correlation
 * keys into either the task list or detail screen.
 */
fun cabinNumberForDisplay(vararg candidates: String?): String? =
    candidates.asSequence()
        .mapNotNull { it?.trim()?.takeIf(String::isNotEmpty) }
        .firstOrNull { !UUID_PATTERN.containsMatchIn(it) }

/** Converts a synchronized queue name into the worker-facing repair-stage label. */
fun workerTaskStageLabel(value: String?): String? =
    value?.trim()?.takeIf(String::isNotEmpty)?.let { label ->
        if (TECHNICAL_MAINTENANCE_LABEL.matches(label)) "Работы" else label
    }

/** Returns only source-owned repair-complexity titles using the worker-facing three-level scale. */
fun workerRepairComplexityLabel(value: String?): String? =
    when (value?.trim()?.lowercase()) {
        "лёгкий ремонт", "легкий ремонт" -> "Легкий ремонт"
        "средний ремонт" -> "Средний ремонт"
        "сложный ремонт", "тяжёлый ремонт", "тяжелый ремонт" -> "Тяжелый ремонт"
        "капитальный ремонт" -> "Капитальный ремонт"
        else -> null
    }

/** Formats the zero-based worker-package ordinal against its authoritative package count. */
fun workerTaskStageOrdinal(routeStepIndex: Int, routeStepCount: Int, separator: String): String {
    val current = routeStepIndex.coerceAtLeast(0) + 1
    val total = routeStepCount.coerceAtLeast(current)
    return "$current$separator$total"
}

/**
 * Recognizes only logistics-owned canonical text emitted for a warehouse-to-warehouse transfer.
 * This helper deliberately does not classify every logistics document as a transfer because the
 * current worker feed does not expose a dedicated document-type field.
 */
fun isInterwarehouseTransferTask(vararg values: String?): Boolean = values.asSequence()
    .mapNotNull { value -> value?.trim()?.lowercase()?.takeIf(String::isNotEmpty) }
    .any { value ->
        value == "переместить бытовку между складами" ||
            value == "переместить мебель между складами" ||
            value.startsWith("перемещение бытовки между складами") ||
            value.startsWith("межскладской груз:")
    }

private val UUID_PATTERN = Regex(
    pattern = """(?i)(?<![0-9a-f])[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}(?![0-9a-f])""",
)

private val TECHNICAL_MAINTENANCE_LABEL = Regex(
    pattern = "^\\s*maintenance\\s+r(?:e|a)pair\\s*$",
    option = RegexOption.IGNORE_CASE,
)
