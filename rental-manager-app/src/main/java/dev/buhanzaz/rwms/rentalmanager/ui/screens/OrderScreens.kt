package dev.buhanzaz.rwms.rentalmanager.ui.screens

import android.os.SystemClock
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import dev.buhanzaz.rwms.rentalmanager.network.OrderDto
import dev.buhanzaz.rwms.rentalmanager.network.OrderPaymentReceiptLineDto
import dev.buhanzaz.rwms.rentalmanager.network.OrderUnitDto
import dev.buhanzaz.rwms.rentalmanager.network.RentalClientDto
import dev.buhanzaz.rwms.rentalmanager.ui.ObservedOrderPayment
import dev.buhanzaz.rwms.rentalmanager.ui.RentalManagerNotice
import dev.buhanzaz.rwms.rentalmanager.ui.RentalManagerUiState
import dev.buhanzaz.rwms.rentalmanager.ui.paymentRemainingMillis
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.delay

@Composable
fun OrdersScreen(
    state: RentalManagerUiState,
    onSearch: (String) -> Unit,
    onLoadMore: () -> Unit,
    onOrder: (String) -> Unit,
    onDismissNotice: () -> Unit,
) {
    var query by rememberSaveable { mutableStateOf(state.orderSearch) }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            ManagerSearchField(
                value = query,
                label = "Номер заказа, клиент или адрес",
                onValueChange = { query = it },
                onSearch = { onSearch(query) },
            )
        }
        item { NoticeCard(state.notice, onDismissNotice) }
        if (state.orders.isEmpty() && !state.ordersLoading) {
            item {
                EmptyState(
                    title = "Заказы не найдены",
                    description = "Измените запрос или создайте заказ из карточки клиента.",
                )
            }
        } else {
            items(state.orders, key = OrderDto::id) { order ->
                OrderSummaryCard(order = order, onClick = { onOrder(order.id) })
            }
        }
        if (state.ordersLoading) item { InlineProgress() }
        if (state.ordersHasMore && !state.ordersLoading) {
            item {
                Button(onClick = onLoadMore, modifier = Modifier.fillMaxWidth()) {
                    Text("Показать ещё")
                }
            }
        }
    }
}

@Composable
internal fun OrderSummaryCard(order: OrderDto, onClick: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Заказ ${order.number}", fontWeight = FontWeight.SemiBold)
                    AssistChip(
                        onClick = onClick,
                        label = { Text(orderStatusLabel(order.status)) },
                    )
                }
                Text(order.client.displayName, style = MaterialTheme.typography.titleMedium)
                Text(
                    buildString {
                        append(clientTypeLabel(order.client.type))
                        order.contactPhone?.let { append(" · ").append(it) }
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                order.deliveryAddress?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                Text(
                    "Бытовок: ${order.unitCount} · ${formatUpdatedAt(order.updatedAt)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(Icons.Default.ChevronRight, contentDescription = null)
        }
    }
}

@Composable
fun CreateOrderScreen(
    client: RentalClientDto?,
    loading: Boolean,
    commandRunning: Boolean,
    notice: RentalManagerNotice?,
    onDismissNotice: () -> Unit,
    onRetry: () -> Unit,
    onSubmit: (String?, String?) -> Unit,
) {
    if (client == null) {
        if (loading) InlineProgress() else Column(modifier = Modifier.padding(16.dp)) {
            NoticeCard(notice, onDismissNotice)
            Button(onClick = onRetry, modifier = Modifier.fillMaxWidth()) { Text("Повторить") }
        }
        return
    }
    var phone by rememberSaveable(client.id) { mutableStateOf(client.phone.orEmpty()) }
    var comment by rememberSaveable(client.id) { mutableStateOf("") }
    var validationMessage by rememberSaveable(client.id) { mutableStateOf<String?>(null) }
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().then(FormMaxWidth),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            NoticeCard(notice, onDismissNotice)
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(client.displayName, style = MaterialTheme.typography.titleMedium)
                    Text(clientTypeLabel(client.type), color = MaterialTheme.colorScheme.primary)
                }
            }
            validationMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            OutlinedTextField(
                value = phone,
                onValueChange = { phone = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Основной телефон") },
                singleLine = true,
                keyboardOptions = PhoneKeyboardOptions,
            )
            OutlinedTextField(
                value = comment,
                onValueChange = { comment = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Комментарий, необязательно") },
                minLines = 3,
            )
            Text(
                "Заказ будет создан как черновик. Адрес, даты и состав заполняются только " +
                    "авторитетными данными заказа — приложение не подставляет их автоматически.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(
                onClick = {
                    val validationError = if (phone.trim().length < 7) {
                        "Укажите корректный телефон."
                    } else {
                        null
                    }
                    validationMessage = validationError
                    if (validationError == null) onSubmit(phone, comment)
                },
                enabled = !commandRunning,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (commandRunning) "Создаём…" else "Создать черновик")
            }
        }
    }
}

@Composable
fun OrderDetailScreen(
    state: RentalManagerUiState,
    onRetry: () -> Unit,
    onUpdate: (String?, String?) -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
    onConfirmPayment: () -> Unit = {},
    assistantBusy: Boolean,
    onOpenAssistant: () -> Unit,
    onDismissNotice: () -> Unit,
) {
    val order = state.selectedOrder
    if (order == null) {
        if (state.selectedOrderLoading) InlineProgress() else Column(modifier = Modifier.padding(16.dp)) {
            NoticeCard(state.notice, onDismissNotice)
            Button(onClick = onRetry, modifier = Modifier.fillMaxWidth()) { Text("Повторить") }
        }
        return
    }
    var phone by rememberSaveable(order.id, order.version) { mutableStateOf(order.contactPhone.orEmpty()) }
    var comment by rememberSaveable(order.id, order.version) { mutableStateOf(order.comment.orEmpty()) }
    var validationMessage by rememberSaveable(order.id, order.version) { mutableStateOf<String?>(null) }
    var cancelDialogOpen by rememberSaveable(order.id, order.version) { mutableStateOf(false) }
    val editable = order.permissions?.canEdit == true

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().then(FormMaxWidth),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            NoticeCard(state.notice, onDismissNotice)
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Заказ ${order.number}", style = MaterialTheme.typography.headlineSmall)
                        AssistChip(onClick = {}, label = { Text(orderStatusLabel(order.status)) })
                    }
                    Text(order.client.displayName, style = MaterialTheme.typography.titleMedium)
                    Text(clientTypeLabel(order.client.type), color = MaterialTheme.colorScheme.primary)
                    Text("Менеджер: ${order.managerDisplayName}")
                    Text("Бытовок: ${order.unitCount}")
                    order.deliveryAddress?.let { Text("Адрес: $it") }
                    if (order.desiredDeliveryWindows.isNotEmpty()) {
                        Text(
                            "Даты клиента: " + order.desiredDeliveryWindows.joinToString { window ->
                                if (window.startDate == window.endDate) formatDate(window.startDate)
                                else "${formatDate(window.startDate)} — ${formatDate(window.endDate)}"
                            },
                        )
                    }
                }
            }
            if (editable && (order.status == "DRAFT" || order.status == "SAVED")) {
                Button(
                    onClick = onOpenAssistant,
                    enabled = !state.commandRunning && !assistantBusy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        when {
                            assistantBusy -> "Открываем чат…"
                            order.unitCount == 0L -> "Подобрать бытовки в чате"
                            else -> "Открыть чат по заказу"
                        },
                    )
                }
                Text(
                    "Чат откроет единственный диалог этого заказа: подбор и ссылка для клиента " +
                        "останутся связаны с текущим заказом.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            OrderUnitsSection(order)
            state.selectedOrderPayment?.let { observed ->
                OrderPaymentCard(
                    observed = observed,
                    commandRunning = state.commandRunning,
                    onConfirm = onConfirmPayment,
                    onRefresh = onRetry,
                )
            }
            validationMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            OutlinedTextField(
                value = phone,
                onValueChange = { phone = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Основной телефон") },
                singleLine = true,
                enabled = editable && !state.commandRunning,
                keyboardOptions = PhoneKeyboardOptions,
            )
            OutlinedTextField(
                value = comment,
                onValueChange = { comment = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Комментарий") },
                minLines = 3,
                enabled = editable && !state.commandRunning,
            )
            if (editable) {
                Button(
                    onClick = {
                        val validationError = if (
                            phone.isNotBlank() && phone.trim().length < 7
                        ) {
                            "Укажите корректный телефон."
                        } else {
                            null
                        }
                        validationMessage = validationError
                        if (validationError == null) onUpdate(phone, comment)
                    },
                    enabled = !state.commandRunning,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (state.commandRunning) "Сохраняем…" else "Сохранить изменения")
                }
            } else {
                Text(
                    "Заказ доступен только для просмотра.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (editable && order.status == "DRAFT") {
                DraftSaveReadiness(
                    order = order,
                    commandRunning = state.commandRunning,
                    onSave = onSave,
                )
            }
            if (editable && order.status == "DRAFT") {
                OutlinedButton(
                    onClick = { cancelDialogOpen = true },
                    enabled = !state.commandRunning,
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Удалить черновик")
                }
            }
        }
    }
    if (cancelDialogOpen) {
        AlertDialog(
            onDismissRequest = {
                if (!state.commandRunning) cancelDialogOpen = false
            },
            title = { Text("Удалить черновик бронирования?") },
            text = {
                Text(
                    "Бронирование будет логически отменено, а все активные резервирования " +
                        "бытовок освобождены. Действие сохранится в истории.",
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        cancelDialogOpen = false
                        onCancel()
                    },
                    enabled = !state.commandRunning,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                    ),
                ) {
                    Text("Да, удалить")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { cancelDialogOpen = false },
                    enabled = !state.commandRunning,
                ) {
                    Text("Не удалять")
                }
            },
        )
    }
}

@Composable
private fun OrderPaymentCard(
    observed: ObservedOrderPayment,
    commandRunning: Boolean,
    onConfirm: () -> Unit,
    onRefresh: () -> Unit,
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    var elapsedRealtimeMillis by rememberSaveable(observed.payment.orderId, observed.payment.orderVersion) {
        mutableStateOf(SystemClock.elapsedRealtime())
    }
    val remainingMillis = paymentRemainingMillis(
        observed.payment,
        observed.observedElapsedRealtimeMillis,
        elapsedRealtimeMillis,
    )
    if (observed.payment.state == "PENDING" || observed.payment.state == "EXPIRING") {
        LaunchedEffect(observed.payment, observed.observedElapsedRealtimeMillis) {
            lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                var refreshAt = SystemClock.elapsedRealtime() + PAYMENT_REFRESH_INTERVAL_MILLIS
                while (true) {
                    val now = SystemClock.elapsedRealtime()
                    val remaining = paymentRemainingMillis(
                        observed.payment,
                        observed.observedElapsedRealtimeMillis,
                        now,
                    )
                    elapsedRealtimeMillis = now
                    if (now >= refreshAt) {
                        onRefresh()
                        refreshAt = now + PAYMENT_REFRESH_INTERVAL_MILLIS
                    }
                    delay(
                        if (remaining != null && remaining > 0L) minOf(1_000L, remaining) else 1_000L,
                    )
                }
            }
        }
    }
    SectionTitle("Оплата")
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val receipt = observed.payment.receipt
            if (receipt == null || observed.payment.state == null) {
                Text("Счёт ещё не выставлен", fontWeight = FontWeight.Medium)
                Text(
                    "Отсутствие счёта не означает, что заказ оплачен.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                return@Column
            }
            Text("Счёт №${receipt.orderNumber}", fontWeight = FontWeight.SemiBold)
            receipt.lines.forEach { line ->
                Text(formatReceiptLine(line))
            }
            Text("Итого: ${formatReceiptRubles(receipt.totalRubles) ?: "сумма уточняется"}")
            if (!receipt.deliveryIncluded) {
                Text(
                    "Доставка не включена в счёт: это не бесплатная доставка.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            when (observed.payment.state) {
                "CONFIRMED" -> Text(
                    confirmedPaymentLabel(observed.payment.source),
                    color = MaterialTheme.colorScheme.primary,
                )
                "PENDING" -> Text("Ожидается подтверждение оплаты.")
                "EXPIRING", "EXPIRED" -> Text("Срок оплаты истёк. Обновите заказ.", color = MaterialTheme.colorScheme.error)
                "CANCELLED" -> Text("Оплата отменена.", color = MaterialTheme.colorScheme.error)
                else -> Text("Статус оплаты уточняется.")
            }
            if (remainingMillis != null && observed.payment.state == "PENDING") {
                Text("Осталось: ${formatPaymentCountdown(remainingMillis)}")
            }
            if (observed.payment.state == "PENDING" && observed.payment.canConfirm && remainingMillis != null && remainingMillis > 0L) {
                Button(
                    onClick = onConfirm,
                    enabled = !commandRunning,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (commandRunning) "Подтверждаем…" else "Подтвердить оплату")
                }
            } else if (observed.payment.state == "PENDING") {
                OutlinedButton(
                    onClick = onRefresh,
                    enabled = !commandRunning,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Обновить статус оплаты")
                }
            }
        }
    }
}

private fun formatReceiptLine(
    line: OrderPaymentReceiptLineDto,
): String {
    val unitPrice = formatReceiptRubles(line.unitPriceRubles) ?: "сумма уточняется"
    val amount = formatReceiptRubles(line.amountRubles) ?: "сумма уточняется"
    return if (line.rentalMonths != null) {
        "${line.label}: ${line.quantity} × $unitPrice/мес. × ${line.rentalMonths} мес. = $amount"
    } else {
        "${line.label}: ${line.quantity} × $unitPrice = $amount"
    }
}

private fun confirmedPaymentLabel(source: String?): String = when (source) {
    "MANAGER_CONFIRMATION" -> "Оплата подтверждена менеджером."
    "CUSTOMER_TEST" -> "Тестовая оплата подтверждена клиентом."
    "PRESENTATION_TEST" -> "Тестовая оплата подтверждена по предложению."
    else -> "Подтверждение оплаты сохранено."
}

private fun formatPaymentCountdown(remainingMillis: Long): String {
    val seconds = remainingMillis.coerceAtLeast(0L) / 1_000L
    return "%d:%02d".format(seconds / 60L, seconds % 60L)
}

private const val PAYMENT_REFRESH_INTERVAL_MILLIS = 15_000L

private data class DraftSaveState(
    val contactReady: Boolean,
    val addressReady: Boolean,
    val warehouseReady: Boolean,
    val cabinsReady: Boolean,
    val rentalTermsReady: Boolean,
    val deliveryDatesReady: Boolean,
) {
    val complete: Boolean
        get() = contactReady &&
            addressReady &&
            warehouseReady &&
            cabinsReady &&
            rentalTermsReady &&
            deliveryDatesReady
}

private fun draftSaveState(order: OrderDto): DraftSaveState {
    val selectedUnits = order.units.filter(OrderUnitDto::added)
    val cabinsReady = order.unitCount > 0 && selectedUnits.size.toLong() == order.unitCount
    return DraftSaveState(
        contactReady = !order.contactPhone.isNullOrBlank(),
        addressReady = !order.deliveryAddress.isNullOrBlank(),
        warehouseReady = !order.warehouseId.isNullOrBlank(),
        cabinsReady = cabinsReady,
        rentalTermsReady = cabinsReady && selectedUnits.all {
            (it.rentalTerm?.rentalMonths ?: 0L) > 0L
        },
        deliveryDatesReady = order.desiredDeliveryWindows.isNotEmpty(),
    )
}

@Composable
private fun DraftSaveReadiness(
    order: OrderDto,
    commandRunning: Boolean,
    onSave: () -> Unit,
) {
    val readiness = draftSaveState(order)
    SectionTitle("Готовность к сохранению")
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "Заказ можно сохранить, когда все данные подтверждены сервером.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            DraftReadinessRow(
                title = "Контакт заказа",
                ready = readiness.contactReady,
                missingText = "Сохраните основной телефон.",
            )
            DraftReadinessRow(
                title = "Адрес доставки",
                ready = readiness.addressReady,
                missingText = "Ожидается подтверждение клиента.",
            )
            DraftReadinessRow(
                title = "Склад",
                ready = readiness.warehouseReady,
                missingText = "Определится по выбранным бытовкам.",
            )
            DraftReadinessRow(
                title = "Бытовки",
                ready = readiness.cabinsReady,
                missingText = "Добавьте хотя бы одну бытовку.",
            )
            DraftReadinessRow(
                title = "Срок аренды",
                ready = readiness.rentalTermsReady,
                missingText = "Ожидается выбор клиента для каждой бытовки.",
            )
            DraftReadinessRow(
                title = "Даты доставки",
                ready = readiness.deliveryDatesReady,
                missingText = "Ожидается выбор клиента.",
            )
            if (!readiness.complete) {
                Text(
                    "Подготовьте предложение в связанном чате и отправьте клиенту ссылку.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Button(
                onClick = onSave,
                enabled = readiness.complete && !commandRunning,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (commandRunning) "Сохраняем заказ…" else "Сохранить заказ")
            }
        }
    }
}

@Composable
private fun DraftReadinessRow(
    title: String,
    ready: Boolean,
    missingText: String,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, fontWeight = FontWeight.Medium)
            if (!ready) {
                Text(
                    missingText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Text(
            if (ready) "Готово" else "Не заполнено",
            color = if (ready) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.error
            },
            style = MaterialTheme.typography.labelLarge,
        )
    }
}

@Composable
private fun OrderUnitsSection(order: OrderDto) {
    val selectedUnits = order.units.filter(OrderUnitDto::added)
    SectionTitle("Бытовки в заказе")
    when {
        selectedUnits.isEmpty() && order.unitCount == 0L -> Text(
            "Бытовки пока не выбраны.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        selectedUnits.size.toLong() != order.unitCount -> Text(
            "Состав заказа временно недоступен. Обновите карточку.",
            color = MaterialTheme.colorScheme.error,
        )
        else -> selectedUnits.forEach { candidate ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "Бытовка ${candidate.unit.number}",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            rentalItemStatusLabel(candidate.unit.status),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    val attributes = listOfNotNull(
                        candidate.unit.rentalType?.displayRentalType(),
                        candidate.unit.dimensions?.takeIf(String::isNotBlank),
                        candidate.unit.finishing?.takeIf(String::isNotBlank),
                        candidate.unit.category?.takeIf(String::isNotBlank),
                    )
                    if (attributes.isNotEmpty()) {
                        Text(
                            attributes.joinToString(" · "),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (candidate.desiredContents.isNotEmpty()) {
                        Text("Заказанное наполнение", fontWeight = FontWeight.Medium)
                        candidate.desiredContents.forEach { equipment ->
                            Text("${equipment.equipmentName} × ${equipment.quantity}")
                        }
                    }
                    val term = candidate.rentalTerm
                    if (term == null) {
                        Text(
                            "Срок аренды пока не выбран клиентом.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        Text("Срок аренды: ${rentalMonthsLabel(term.rentalMonths)}")
                        Text("Дата отгрузки: ${formatOptionalDate(term.shipmentDate)}")
                        Text("Расчётная дата возврата: ${formatOptionalDate(term.returnDate)}")
                    }
                }
            }
        }
    }
}

private fun String.displayRentalType(): String? = takeIf(String::isNotBlank)?.let { value ->
    when (value.uppercase()) {
        "LDSP" -> "ЛДСП"
        else -> value
    }
}

private fun rentalItemStatusLabel(status: String): String = when (status) {
    "RENTED" -> "Аренда"
    "BOOKED", "RESERVED" -> "Бронь"
    "REPAIR", "WAITING_REPAIR_CHECK" -> "В ремонте"
    "WRITTEN_OFF" -> "Списана"
    "LOST" -> "Утеряна"
    "CAPITAL_REPAIR" -> "Капремонт"
    "AFTER_RENT" -> "Ожидает осмотра"
    "WAITING_ESTIMATE_CONFIRMATION" -> "Ожидает подтверждения сметы"
    "SALE", "USED_SALE" -> "Продажа Б/У"
    "FREE" -> "Свободна"
    "WAREHOUSE" -> "Склад"
    "OWN_NEEDS" -> "Собственные нужды"
    "IN_TRANSFER" -> "В перемещении"
    else -> "Статус уточняется"
}

private fun rentalMonthsLabel(months: Long): String {
    val suffix = when {
        months % 100 in 11L..14L -> "месяцев"
        months % 10 == 1L -> "месяц"
        months % 10 in 2L..4L -> "месяца"
        else -> "месяцев"
    }
    return "$months $suffix"
}

private fun formatOptionalDate(value: String?): String =
    value?.let(::formatDate) ?: "Не назначена"

private fun formatDate(value: String): String = runCatching {
    LocalDate.parse(value).format(
        DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.forLanguageTag("ru-RU")),
    )
}.getOrDefault("дата уточняется")
