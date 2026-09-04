package dev.buhanzaz.rwms.client.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** Presentation intent only; the owning service decides whether this change incurs a fee. */
internal enum class CustomerBookingChangeOperation {
    CANCEL,
    RESCHEDULE,
}

/** Server-confirmed settlement projected into the dialog, never changed by a button locally. */
internal enum class CustomerBookingChangeSettlement {
    PAYMENT_REQUIRED,
    TEST_PAID,
    WAIVED,
    NOT_REQUIRED,
}

/**
 * Controlled presentation of one server fee quote. Missing or invalid amounts remain unavailable;
 * this model does not calculate deadlines, prices, payment results, or replacement slots.
 */
internal data class CustomerBookingChangeDialogState(
    val operation: CustomerBookingChangeOperation,
    val amountRubles: Long?,
    val settlement: CustomerBookingChangeSettlement = CustomerBookingChangeSettlement.PAYMENT_REQUIRED,
    val testPaymentAvailable: Boolean = false,
    val supportPhone: String? = null,
    val pending: Boolean = false,
    val errorMessage: String? = null,
)

/**
 * Confirms a service-owned fee or an already settled change using the existing CustomerApp style.
 * Clicking pay only invokes [onPay]; TEST_PAID must arrive in a subsequent server projection.
 */
@Composable
internal fun CustomerBookingChangeDialog(
    state: CustomerBookingChangeDialogState,
    onPay: () -> Unit,
    onConfirm: () -> Unit,
    onCallSupport: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val amount = state.amountRubles?.takeIf { it >= 0 }
    val phone = customerSupportDialNumber(state.supportPhone)
    val paymentRequired = state.settlement == CustomerBookingChangeSettlement.PAYMENT_REQUIRED
    val actionEnabled = !state.pending && amount != null &&
        (!paymentRequired || (amount > 0 && state.testPaymentAvailable))
    val operationLabel = when (state.operation) {
        CustomerBookingChangeOperation.CANCEL -> "отмену"
        CustomerBookingChangeOperation.RESCHEDULE -> "перенос"
    }
    AlertDialog(
        onDismissRequest = { if (!state.pending) onDismiss() },
        title = {
            Text(
                when (state.operation) {
                    CustomerBookingChangeOperation.CANCEL -> "Отменить заказ?"
                    CustomerBookingChangeOperation.RESCHEDULE -> "Перенести доставку"
                },
            )
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.surfaceVariant,
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text("Неустойка за $operationLabel", style = MaterialTheme.typography.labelLarge)
                        Text(
                            CustomerBookingLifecyclePolicy.cancellationFeeLabel(amount) ?: "Сумма пока недоступна",
                            style = MaterialTheme.typography.headlineSmall,
                            modifier = Modifier.testTag("booking-change-amount"),
                        )
                    }
                }
                Text(
                    when (state.settlement) {
                        CustomerBookingChangeSettlement.PAYMENT_REQUIRED ->
                            "Изменение бронирования предусматривает неустойку. Если нужна помощь, свяжитесь с менеджером."
                        CustomerBookingChangeSettlement.TEST_PAID -> "Оплачено — тестовый режим"
                        CustomerBookingChangeSettlement.WAIVED -> "Менеджер отменил неустойку."
                        CustomerBookingChangeSettlement.NOT_REQUIRED -> "Изменение без неустойки."
                    },
                    modifier = Modifier
                        .testTag("booking-change-settlement")
                        .semantics { liveRegion = LiveRegionMode.Polite },
                )
                if (paymentRequired && state.testPaymentAvailable) {
                    Text(
                        "Тестовая оплата: реальные деньги не списываются.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else if (paymentRequired) {
                    Text("Тестовая оплата сейчас недоступна. Свяжитесь с менеджером.")
                }
                if (phone == null) {
                    Text(
                        "Телефон менеджера пока не указан. Позвонить из приложения сейчас нельзя.",
                        modifier = Modifier.testTag("booking-change-phone-unavailable"),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (state.pending) {
                    Text(
                        "Ожидаем подтверждение сервиса…",
                        modifier = Modifier
                            .testTag("booking-change-pending")
                            .semantics { liveRegion = LiveRegionMode.Polite },
                    )
                }
                state.errorMessage?.takeIf(String::isNotBlank)?.let { message ->
                    Text(
                        message,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier
                            .testTag("booking-change-error")
                            .semantics { liveRegion = LiveRegionMode.Polite },
                    )
                }
            }
        },
        confirmButton = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = { if (paymentRequired) onPay() else onConfirm() },
                    enabled = actionEnabled,
                    modifier = Modifier.fillMaxWidth().testTag("booking-change-confirm"),
                ) {
                    Text(
                        if (paymentRequired) {
                            "Оплатить (тестовый режим)"
                        } else {
                            "Подтвердить $operationLabel"
                        },
                    )
                }
                OutlinedButton(
                    onClick = { phone?.let(onCallSupport) },
                    enabled = !state.pending && phone != null,
                    modifier = Modifier.fillMaxWidth().testTag("booking-change-call-support"),
                ) {
                    Text("Связаться с менеджером")
                }
                OutlinedButton(
                    onClick = onDismiss,
                    enabled = !state.pending,
                    modifier = Modifier.fillMaxWidth().testTag("booking-change-dismiss"),
                ) {
                    Text("Назад")
                }
            }
        },
        tonalElevation = 0.dp,
        modifier = Modifier.widthIn(max = 560.dp).testTag("booking-change-dialog"),
    )
}
