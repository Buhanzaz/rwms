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
