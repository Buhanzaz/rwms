package dev.buhanzaz.rwms.client.ui

import java.text.NumberFormat
import java.util.Locale

/** Formats authoritative whole-ruble amounts without deriving or rounding a local price. */
internal object CustomerMoneyFormatter {
    /** Monthly rent is independent from delivery, fees and the selected initial rental term. */
    fun monthlyRentalPrice(amount: Long, locale: Locale = Locale.forLanguageTag("ru-RU")): String {
        require(amount >= 0) { "Rental price must be nonnegative" }
        val formatted = NumberFormat.getIntegerInstance(locale).format(amount)
            .replace('\u00a0', ' ')
            .replace('\u202f', ' ')
        return "$formatted ₽/мес."
    }

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
