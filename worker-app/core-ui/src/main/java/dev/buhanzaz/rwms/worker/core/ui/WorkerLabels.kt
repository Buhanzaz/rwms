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

/** Returns only source-owned repair-complexity titles and normalizes the former heavy label. */
fun workerRepairComplexityLabel(value: String?): String? =
    when (value?.trim()?.lowercase()) {
        "лёгкий ремонт", "легкий ремонт" -> "Лёгкий ремонт"
        "средний ремонт" -> "Средний ремонт"
        "сложный ремонт", "тяжёлый ремонт", "тяжелый ремонт" -> "Сложный ремонт"
        "капитальный ремонт" -> "Капитальный ремонт"
        else -> null
    }

private val UUID_PATTERN = Regex(
    pattern = """(?i)(?<![0-9a-f])[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}(?![0-9a-f])""",
)

private val TECHNICAL_MAINTENANCE_LABEL = Regex(
    pattern = "^\\s*maintenance\\s+r(?:e|a)pair\\s*$",
    option = RegexOption.IGNORE_CASE,
)
