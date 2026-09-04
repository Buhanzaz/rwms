package dev.buhanzaz.rwms.client.ui

import java.text.NumberFormat
import java.util.Locale

/** Formats authoritative whole-ruble amounts without deriving or rounding a local price. */
internal object CustomerMoneyFormatter {
    /** Returns a locale-grouped integer amount or the established missing-price label. */
    fun wholeRubles(
        amount: Int?,
        locale: Locale = Locale.forLanguageTag("ru-RU"),
    ): String {
        if (amount == null) return "Не рассчитана"
        val formatted = NumberFormat.getIntegerInstance(locale).format(amount)
            .replace('\u00a0', ' ')
            .replace('\u202f', ' ')
        return "$formatted ₽"
    }
}
