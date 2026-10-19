package dev.buhanzaz.rwms.client.ui

import com.google.common.truth.Truth.assertThat
import java.util.Locale
import org.junit.Test

/** Verifies locale-aware display of server-supplied whole-ruble amounts. */
class CustomerMoneyFormatterTest {
    @Test
    fun `monthly rent preserves exact long amounts and explicit zero`() {
        assertThat(CustomerMoneyFormatter.monthlyRentalPrice(Long.MAX_VALUE))
            .isEqualTo("9 223 372 036 854 775 807 ₽/мес.")
        assertThat(CustomerMoneyFormatter.monthlyRentalPrice(0)).isEqualTo("0 ₽/мес.")
    }

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
