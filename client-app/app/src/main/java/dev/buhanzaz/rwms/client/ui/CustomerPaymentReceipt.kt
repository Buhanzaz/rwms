package dev.buhanzaz.rwms.client.ui

import android.os.SystemClock
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import dev.buhanzaz.rwms.client.data.CustomerOrderPayment
import dev.buhanzaz.rwms.client.data.exactReceiptInteger
import java.text.NumberFormat
import java.time.Duration
import java.time.Instant
import java.util.Locale
import kotlinx.coroutines.delay

internal const val CUSTOMER_PAYMENT_CHECKING_MESSAGE = "Проверяем результат оплаты…"

/** Receipt plus monotonic observation time: changing the device clock cannot extend payment. */
data class CustomerObservedPayment(val payment: CustomerOrderPayment, val receivedElapsedMillis: Long) {
    /** Conservative remaining time anchored to server time at the start of its HTTP read. */
    fun remainingMillis(nowElapsedMillis: Long): Long {
        val expiry = payment.expiresAt ?: return 0
        val initial = Duration.between(Instant.parse(payment.serverTime), Instant.parse(expiry)).toMillis()
        return (initial - (nowElapsedMillis - receivedElapsedMillis).coerceAtLeast(0)).coerceAtLeast(0)
    }
}

/** Exact whole-RUB display, including immutable totals beyond the int64 range. */
internal fun receiptRubles(amount: String): String =
    NumberFormat.getIntegerInstance(Locale.forLanguageTag("ru-RU")).format(exactReceiptInteger(amount))
        .replace('\u00a0', ' ').replace('\u202f', ' ') + " ₽"

/** Branded itemized non-fiscal bill with exact server amounts and explicit simulated-payment consent. */
@Composable
fun CustomerInitialPaymentReceipt(
    observed: CustomerObservedPayment,
    busy: Boolean,
    onConfirm: () -> Unit,
) {
    val payment = observed.payment
    val receipt = payment.receipt
    var detailsVisible by rememberSaveable(receipt?.orderNumber) { mutableStateOf(false) }
    var now by remember(observed) { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(observed, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                now = SystemClock.elapsedRealtime()
                delay(1_000)
            }
        }
    }
    Surface(
        modifier = Modifier.fillMaxWidth().testTag("initial-payment-receipt"),
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (receipt == null) {
                Text("Счёт ещё не выставлен. Оплата не подтверждена.")
                if (payment.state == null && payment.expiresAt == null && payment.orderStatus == "SAVED") {
                    Text(
                        "Для этого заказа срок оплаты не назначен. Автоматическая отмена по таймеру не применяется.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.testTag("payment-without-deadline"),
                    )
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("Заказ № ${receipt.orderNumber}", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "СЧЁТ НА ОПЛАТУ · НЕ ФИСКАЛЬНЫЙ ЧЕК",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Итого", style = MaterialTheme.typography.titleMedium)
                    Text(
                        receiptRubles(receipt.totalRubles),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.End,
                        modifier = Modifier.weight(1f).padding(start = 12.dp),
                    )
                }
                if (!receipt.deliveryIncluded) {
                    Text(
                        "Доставка в счёт не включена. Её стоимость ещё не согласована.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                val remaining = observed.remainingMillis(now)
                Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (payment.state == "PENDING") {
                            Icon(Icons.Outlined.Schedule, contentDescription = null, modifier = Modifier.size(20.dp))
                        }
                        Text(
                            when (payment.state) {
                                "PENDING" -> if (remaining > 0) {
                                    val seconds = (remaining + 999) / 1_000
                                    "Резерв до оплаты: ${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"
                                } else "Срок оплаты истёк. Проверяем снятие резерва…"
                                "CONFIRMED" -> if (payment.source == "MANAGER_CONFIRMATION") "Оплата подтверждена менеджером"
                                else "Тестовая оплата подтверждена"
                                "EXPIRING" -> "Срок оплаты истёк. Резерв снимается…"
                                "EXPIRED" -> "Не оплачено вовремя. Резерв снят"
                                "CANCELLED" -> "Заказ отменён"
                                else -> "Оплата не подтверждена"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }
                if (payment.state == "PENDING") {
                    Text("Тестовая оплата: реальные деньги не списываются.", style = MaterialTheme.typography.bodySmall)
                    Button(
                        onClick = onConfirm,
                        enabled = !busy && payment.canConfirm && remaining > 0,
                        modifier = Modifier.fillMaxWidth().testTag("confirm-initial-payment"),
                    ) {
                        Text("Оплатить тестово")
                    }
                }
                TextButton(
                    onClick = { detailsVisible = !detailsVisible },
                    modifier = Modifier.fillMaxWidth().testTag("initial-payment-receipt-details-toggle"),
                ) {
                    Text(
                        if (detailsVisible) "Скрыть детали счёта" else "Показать детали счёта (${receipt.lines.size})",
                        modifier = Modifier.weight(1f),
                        textAlign = TextAlign.Start,
                    )
                    Icon(
                        if (detailsVisible) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = if (detailsVisible) "Скрыть детали счёта" else "Показать детали счёта",
                    )
                }
                if (detailsVisible) {
                    HorizontalDivider()
                    receipt.lines.forEach { line ->
                        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text(line.label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    receiptRubles(line.amountRubles),
                                    modifier = Modifier.weight(0.8f),
                                    style = MaterialTheme.typography.titleSmall,
                                    textAlign = TextAlign.End,
                                )
                            }
                            Text(
                                if (line.kind == "DELIVERY") "Доставка · 1 услуга"
                                else "${line.quantity} шт. × ${receiptRubles(line.unitPriceRubles)}/мес. × ${line.rentalMonths} мес.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}
