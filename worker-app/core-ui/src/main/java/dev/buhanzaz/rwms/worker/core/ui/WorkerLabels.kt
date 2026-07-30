package dev.buhanzaz.rwms.worker.core.ui

/**
 * Selects a human-facing cabin number without leaking internal UUID correlation
 * keys into either the task list or detail screen.
 */
fun cabinNumberForDisplay(vararg candidates: String?): String? =
    candidates.asSequence()
        .mapNotNull { it?.trim()?.takeIf(String::isNotEmpty) }
        .firstOrNull { !UUID_PATTERN.containsMatchIn(it) }

private val UUID_PATTERN = Regex(
    pattern = """(?i)(?<![0-9a-f])[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}(?![0-9a-f])""",
)
