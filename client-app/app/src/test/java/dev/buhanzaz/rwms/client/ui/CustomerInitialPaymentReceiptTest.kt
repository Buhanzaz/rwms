package dev.buhanzaz.rwms.client.ui

import android.app.Application
import android.os.SystemClock
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.google.common.truth.Truth.assertThat
import dev.buhanzaz.rwms.client.data.initialPaymentFixture
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Receipt interaction, exact money, monotonic deadline and missing historical bill regression. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class CustomerInitialPaymentReceiptTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun `receipt shows actual bill and only explicit click requests simulated payment`() {
        var confirms = 0
        val payment = initialPaymentFixture()
        compose.setContent {
            ScrollableReceipt {
                CustomerInitialPaymentReceipt(CustomerObservedPayment(payment, SystemClock.elapsedRealtime()), false) { confirms++ }
            }
        }
        compose.onNodeWithText("СЧЁТ НА ОПЛАТУ · НЕ ФИСКАЛЬНЫЙ ЧЕК").assertIsDisplayed()
        compose.onNodeWithText("3 шт. × 350 ₽/мес. × 2 мес.").assertIsDisplayed()
        compose.onNodeWithText("14 100 ₽").performScrollTo().assertIsDisplayed()
        assertThat(confirms).isEqualTo(0)
        compose.onNodeWithTag("confirm-initial-payment").performScrollTo().assertIsEnabled().performClick()
        assertThat(confirms).isEqualTo(1)
        compose.onNodeWithText("Тестовая оплата подтверждена").assertDoesNotExist()
    }

    @Test
    fun `expired device monotonic window disables payment without inventing server expiry`() {
        val payment = initialPaymentFixture()
        compose.setContent {
            ScrollableReceipt {
                CustomerInitialPaymentReceipt(CustomerObservedPayment(payment, SystemClock.elapsedRealtime() - 300_001), false) {}
            }
        }
        compose.onNodeWithTag("confirm-initial-payment").assertIsNotEnabled()
        compose.onNodeWithText("Срок оплаты истёк. Проверяем снятие резерва…").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Не оплачено вовремя. Резерв снят").assertDoesNotExist()
    }

    @Test
    fun `confirmed bill reports server provenance and cannot charge again`() {
        val payment = initialPaymentFixture().copy(state = "CONFIRMED", canConfirm = false, source = "MANAGER_CONFIRMATION",
            resolvedAt = "2026-09-05T12:01:00Z")
        compose.setContent { ScrollableReceipt {
            CustomerInitialPaymentReceipt(CustomerObservedPayment(payment, SystemClock.elapsedRealtime()), false) {}
        } }
        compose.onNodeWithText("Оплата подтверждена менеджером").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("confirm-initial-payment").assertDoesNotExist()
    }

    @Test
    fun `missing historical bill is neither free nor paid`() {
        val payment = initialPaymentFixture().copy(state = null, receipt = null, startedAt = null, expiresAt = null, canConfirm = false)
        compose.setContent { ScrollableReceipt {
            CustomerInitialPaymentReceipt(CustomerObservedPayment(payment, SystemClock.elapsedRealtime()), false) {}
        } }
        compose.onNodeWithText("Счёт ещё не выставлен. Оплата не подтверждена.").assertIsDisplayed()
        compose.onNodeWithTag("confirm-initial-payment").assertDoesNotExist()
    }

    @Test
    fun `countdown uses server observation and exact integer amounts beyond long`() {
        val observed = CustomerObservedPayment(initialPaymentFixture(), 1_000)
        assertThat(observed.remainingMillis(121_000)).isEqualTo(180_000)
        assertThat(observed.remainingMillis(301_000)).isEqualTo(0)
        assertThat(observed.remainingMillis(1)).isEqualTo(300_000)
        assertThat(receiptRubles("1106804644422573096840")).isEqualTo("1 106 804 644 422 573 096 840 ₽")
        assertThat(CustomerInquiryRecoveryPolicy.requiresFreshInquiry("CANCELLED")).isTrue()
    }

    @Composable
    private fun ScrollableReceipt(content: @Composable () -> Unit) {
        CustomerTheme { Column(Modifier.verticalScroll(rememberScrollState())) { content() } }
    }
}
