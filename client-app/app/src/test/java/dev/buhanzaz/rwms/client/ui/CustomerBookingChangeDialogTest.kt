package dev.buhanzaz.rwms.client.ui

import android.app.Application
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.google.common.truth.Truth.assertThat
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Verifies the controlled fee dialog without replacing service decisions with UI state. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class CustomerBookingChangeDialogTest {
    /** Existing local Compose semantics test environment; no device or payment provider required. */
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `pay delegates once and remains unpaid until the server projection changes`() {
        val payCalls = AtomicInteger()
        val confirmCalls = AtomicInteger()
        val current = mutableStateOf(quote())
        composeRule.setContent {
            CustomerTheme {
                CustomerBookingChangeDialog(
                    state = current.value,
                    onPay = { payCalls.incrementAndGet() },
                    onConfirm = { confirmCalls.incrementAndGet() },
                    onCallSupport = {},
                    onDismiss = {},
                )
            }
        }

        composeRule.onNodeWithText("12 500 ₽").assertExists()
        composeRule.onNodeWithText("Неустойка за перенос").assertExists()
        composeRule.onNodeWithText("Тестовая оплата: реальные деньги не списываются.").assertExists()
        composeRule.onNodeWithTag("booking-change-confirm").assertIsEnabled().performClick()
        composeRule.onNodeWithText("Оплачено — тестовый режим").assertDoesNotExist()
        composeRule.onNodeWithText("Подтвердить перенос").assertDoesNotExist()
        composeRule.runOnIdle {
            assertThat(payCalls.get()).isEqualTo(1)
            assertThat(confirmCalls.get()).isEqualTo(0)
            assertThat(current.value.settlement).isEqualTo(CustomerBookingChangeSettlement.PAYMENT_REQUIRED)
            current.value = current.value.copy(settlement = CustomerBookingChangeSettlement.TEST_PAID)
        }

        composeRule.onNodeWithText("Оплачено — тестовый режим").assertExists()
        composeRule.onNodeWithText("Оплатить (тестовый режим)").assertDoesNotExist()
        composeRule.onNodeWithText("Подтвердить перенос").performClick()
        composeRule.runOnIdle {
            assertThat(payCalls.get()).isEqualTo(1)
            assertThat(confirmCalls.get()).isEqualTo(1)
        }
    }

    @Test
    fun `pending state blocks repeat payment confirmation contact and dismissal`() {
        val calls = AtomicInteger()
        render(
            quote().copy(pending = true),
            onPay = { calls.incrementAndGet() },
            onConfirm = { calls.incrementAndGet() },
            onCallSupport = { calls.incrementAndGet() },
            onDismiss = { calls.incrementAndGet() },
        )

        composeRule.onNodeWithTag("booking-change-pending").assertExists()
        listOf("booking-change-confirm", "booking-change-call-support", "booking-change-dismiss").forEach { tag ->
            composeRule.onNodeWithTag(tag).assertIsNotEnabled().performClick()
        }
        composeRule.runOnIdle { assertThat(calls.get()).isEqualTo(0) }
        composeRule.onNodeWithText("Оплачено — тестовый режим").assertDoesNotExist()
    }

    @Test
    fun `cancellation displays the server fee without narrowing whole rubles to int`() {
        render(quote().copy(operation = CustomerBookingChangeOperation.CANCEL, amountRubles = 3_000_000_000))

        composeRule.onNodeWithText("Отменить заказ?").assertExists()
        composeRule.onNodeWithText("Неустойка за отмену").assertExists()
        composeRule.onNodeWithText("3 000 000 000 ₽").assertExists()
    }

    @Test
    fun `waived fee confirms cancellation without offering test payment`() {
        val confirms = AtomicInteger()
        render(
            quote().copy(
                operation = CustomerBookingChangeOperation.CANCEL,
                settlement = CustomerBookingChangeSettlement.WAIVED,
            ),
            onConfirm = { confirms.incrementAndGet() },
        )

        composeRule.onNodeWithText("Менеджер отменил неустойку.").assertExists()
        composeRule.onNodeWithText("Оплатить (тестовый режим)").assertDoesNotExist()
        composeRule.onNodeWithText("Подтвердить отмену").assertIsEnabled().performClick()
        composeRule.runOnIdle { assertThat(confirms.get()).isEqualTo(1) }
    }

    @Test
    fun `server free change never offers a payment`() {
        render(quote().copy(amountRubles = 0, settlement = CustomerBookingChangeSettlement.NOT_REQUIRED))

        composeRule.onNodeWithText("Изменение без неустойки.").assertExists()
        composeRule.onNodeWithText("0 ₽").assertExists()
        composeRule.onNodeWithText("Оплатить (тестовый режим)").assertDoesNotExist()
        composeRule.onNodeWithTag("booking-change-confirm").assertIsEnabled()
    }

    @Test
    fun `missing amount is not converted into free cancellation`() {
        render(quote().copy(amountRubles = null))

        composeRule.onNodeWithText("Сумма пока недоступна").assertExists()
        composeRule.onNodeWithText("0 ₽").assertDoesNotExist()
        composeRule.onNodeWithTag("booking-change-confirm").assertIsNotEnabled()
    }

    @Test
    fun `invalid amount cannot enable payment`() {
        render(quote().copy(amountRubles = -1))

        composeRule.onNodeWithText("Сумма пока недоступна").assertExists()
        composeRule.onNodeWithTag("booking-change-confirm").assertIsNotEnabled()
    }

    @Test
    fun `test payment capability must be supplied before payment is offered`() {
        render(quote().copy(testPaymentAvailable = false))

        composeRule.onNodeWithText("Тестовая оплата сейчас недоступна. Свяжитесь с менеджером.").assertExists()
        composeRule.onNodeWithTag("booking-change-confirm").assertIsNotEnabled()
        composeRule.onNodeWithTag("booking-change-call-support").assertIsEnabled()
    }

    @Test
    fun `contact action delegates the actual supplied number without a fallback`() {
        val dialled = AtomicReference<String>()
        render(quote(), onCallSupport = dialled::set)

        composeRule.onNodeWithText("Связаться с менеджером").performClick()
        composeRule.runOnIdle { assertThat(dialled.get()).isEqualTo("+74951234567") }
    }

    @Test
    fun `missing contact is explicitly unavailable`() {
        render(quote().copy(supportPhone = null))

        composeRule.onNodeWithTag("booking-change-phone-unavailable").assertExists()
        composeRule.onNodeWithTag("booking-change-call-support").assertIsNotEnabled()
    }

    @Test
    fun `server error remains visible and back delegates dismissal`() {
        val dismissCalls = AtomicInteger()
        render(
            quote().copy(errorMessage = "Выбранное время уже занято. Выберите новый вариант."),
            onDismiss = { dismissCalls.incrementAndGet() },
        )

        composeRule.onNodeWithText("Выбранное время уже занято. Выберите новый вариант.").assertExists()
        composeRule.onNodeWithText("Оплачено — тестовый режим").assertDoesNotExist()
        composeRule.onNodeWithText("Назад").performClick()
        composeRule.runOnIdle { assertThat(dismissCalls.get()).isEqualTo(1) }
    }

    private fun render(
        state: CustomerBookingChangeDialogState,
        onPay: () -> Unit = {},
        onConfirm: () -> Unit = {},
        onCallSupport: (String) -> Unit = {},
        onDismiss: () -> Unit = {},
    ) {
        composeRule.setContent {
            CustomerTheme {
                CustomerBookingChangeDialog(state, onPay, onConfirm, onCallSupport, onDismiss)
            }
        }
    }

    private fun quote() = CustomerBookingChangeDialogState(
        operation = CustomerBookingChangeOperation.RESCHEDULE,
        amountRubles = 12_500,
        testPaymentAvailable = true,
        supportPhone = "+7 (495) 123-45-67",
    )
}
