package dev.buhanzaz.rwms.client.ui

import com.google.common.truth.Truth.assertThat
import java.util.Locale
import org.junit.Test

/** Verifies locale-aware display of server-supplied whole-ruble amounts. */
class CustomerMoneyFormatterTest {
    @Test
    fun `russian whole rubles use grouped integer display`() {
        assertThat(
            CustomerMoneyFormatter.wholeRubles(28_500, Locale.forLanguageTag("ru-RU")),
        ).isEqualTo("28 500 ₽")
    }

    @Test
    fun `formatting follows locale without calculating a local price`() {
        assertThat(
            CustomerMoneyFormatter.wholeRubles(28_500, Locale.US),
        ).isEqualTo("28,500 ₽")
    }

    @Test
    fun `missing server price keeps the established label`() {
        assertThat(CustomerMoneyFormatter.wholeRubles(null, Locale.US)).isEqualTo("Не рассчитана")
    }
}
