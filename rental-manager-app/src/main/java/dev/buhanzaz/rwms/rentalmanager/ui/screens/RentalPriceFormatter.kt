package dev.buhanzaz.rwms.rentalmanager.ui.screens

import java.text.NumberFormat
import java.util.Locale

/** Displays exact server whole rubles per month without using floating-point arithmetic. */
internal fun formatMonthlyRentalPrice(amount: Long): String {
    require(amount >= 0) { "Rental price must be nonnegative" }
    val value = NumberFormat.getIntegerInstance(Locale.forLanguageTag("ru-RU"))
        .format(amount).replace('\u00a0', ' ').replace('\u202f', ' ')
    return "$value ₽/мес."
}

/** Formats canonical whole-RUB receipt strings exactly, including values beyond signed 64-bit. */
internal fun formatReceiptRubles(amount: String): String? {
    if (!WHOLE_RUBLES.matches(amount)) return null
    val exact = amount.toBigIntegerOrNull() ?: return null
    val value = NumberFormat.getIntegerInstance(Locale.forLanguageTag("ru-RU"))
        .format(exact).replace('\u00a0', ' ').replace('\u202f', ' ')
    return "$value ₽"
}

private val WHOLE_RUBLES = Regex("^(0|[1-9][0-9]{0,79})$")
