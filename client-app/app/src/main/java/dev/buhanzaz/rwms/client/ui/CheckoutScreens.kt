package dev.buhanzaz.rwms.client.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.buhanzaz.rwms.client.data.CustomerBooking
import dev.buhanzaz.rwms.client.data.CustomerCabin

/** Authoritative cart summary grouped by cabin, with no client-side price or availability calculation. */
@Composable
fun CartScreen(
    state: CustomerWorkflowState,
    onMenu: () -> Unit,
    onProfile: () -> Unit,
    onContinue: () -> Unit,
    onToggleCabin: (String) -> Unit,
    onToggleRentalCabin: (String) -> Unit,
    onBulkRentalMonths: (Long) -> Unit,
    onCabinRentalMonths: (String, Long) -> Unit,
) {
    val cartCabins = state.cart?.cabins.orEmpty().ifEmpty {
        state.cabins.filter { it.unitId in state.selectedCabinIds }
    }
    Scaffold(
        topBar = { CustomerTopBar("Корзина", onMenu, onProfile) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp).testTag("cart-screen"),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Column(
                    Modifier.fillMaxWidth().widthIn(max = 760.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("Бытовок: ${state.selectedCabinIds.size}", style = MaterialTheme.typography.titleLarge)
                    Text("Отметьте бытовки, чтобы задать им общий срок аренды.")
                    RentalMonthEditor(
                        months = state.selectedRentalTermCabinIds.firstOrNull()
                            ?.let { state.rentalTerms[it] }
                            ?: state.rentalTerms.values.firstOrNull()
                            ?: 1L,
                        enabled = state.selectedCabinIds.isNotEmpty() && !state.busy,
                        onMonths = onBulkRentalMonths,
                        label = if (state.selectedRentalTermCabinIds.isEmpty()) {
                            "Срок для всех бытовок"
                        } else {
                            "Срок для отмеченных: ${state.selectedRentalTermCabinIds.size}"
                        },
                    )
                }
            }
            items(cartCabins, key = CustomerCabin::unitId) { cabin ->
                val lines = state.cart?.equipment.orEmpty()
                    .filter { it.cabinUnitId == cabin.unitId }
                    .map { selection ->
                        val name = state.equipment.firstOrNull {
                            it.inventoryItemId == selection.inventoryItemId
                        }?.name ?: selection.inventoryItemId
                        "$name — ${selection.quantity} шт."
                    }
                CartCabinLine(
                    cabin = cabin,
                    equipment = lines,
                    checked = cabin.unitId in state.selectedRentalTermCabinIds,
                    months = state.rentalTerms[cabin.unitId] ?: 1L,
                    busy = state.busy,
                    onChecked = { onToggleRentalCabin(cabin.unitId) },
                    onMonths = { onCabinRentalMonths(cabin.unitId, it) },
                    onRemove = { onToggleCabin(cabin.unitId) },
                )
            }
            if (state.selectedCabinIds.isEmpty()) {
                item { Text("Добавьте хотя бы одну свободную бытовку") }
            }
            item {
                Button(
                    onClick = onContinue,
                    modifier = Modifier.fillMaxWidth().widthIn(max = 760.dp),
                    enabled = state.selectedCabinIds.isNotEmpty() && !state.busy,
                ) {
                    Icon(Icons.Default.LocationOn, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Продолжить к доставке")
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun CartCabinLine(
    cabin: CustomerCabin,
    equipment: List<String>,
    checked: Boolean,
    months: Long,
    busy: Boolean,
    onChecked: () -> Unit,
    onMonths: (Long) -> Unit,
    onRemove: () -> Unit,
) {
    OutlinedCard(Modifier.fillMaxWidth().widthIn(max = 760.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            androidx.compose.foundation.layout.Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = checked, onCheckedChange = { onChecked() }, enabled = !busy)
                Text("${cabin.type ?: "Тип не указан"} · № ${cabin.accountingNo}", fontWeight = FontWeight.SemiBold)
            }
            cabin.finish?.let { Text("Отделка: $it") }
            if (equipment.isEmpty()) Text("Без добавленной мебели", style = MaterialTheme.typography.bodySmall)
            equipment.forEach { Text("• $it") }
            RentalMonthEditor(
                months = months,
                enabled = !busy,
                onMonths = onMonths,
                label = "Срок этой бытовки",
            )
            OutlinedButton(onClick = onRemove, modifier = Modifier.fillMaxWidth()) { Text("Убрать из заказа") }
        }
    }
}

@Composable
private fun RentalMonthEditor(
    months: Long,
    enabled: Boolean,
    onMonths: (Long) -> Unit,
    label: String,
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium)
        androidx.compose.foundation.layout.Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { onMonths((months - 1).coerceAtLeast(1)) }, enabled = enabled && months > 1) {
                Icon(Icons.Default.Remove, contentDescription = "Уменьшить срок")
            }
            Text("$months мес.", modifier = Modifier.width(88.dp), fontWeight = FontWeight.SemiBold)
            IconButton(onClick = { onMonths((months + 1).coerceAtMost(120)) }, enabled = enabled && months < 120) {
                Icon(Icons.Default.Add, contentDescription = "Увеличить срок")
            }
        }
    }
}

/** Lists durable RWMS bookings rather than scenario-local logistics requests. */
@Composable
fun BookingsScreen(
    bookings: List<CustomerBooking>,
    latest: CustomerBooking?,
    busy: Boolean,
    onMenu: () -> Unit,
    onProfile: () -> Unit,
    onRefresh: () -> Unit,
    onAccept: (String, String, List<dev.buhanzaz.rwms.client.data.CustomerSignatureStroke>) -> Unit,
    onReport: (String, String, String, String, List<dev.buhanzaz.rwms.client.data.CustomerEvidenceFile>) -> Unit,
) {
    val visible = CustomerBookingPolicy.visible(latest, bookings)
    var acceptanceTarget by androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf<Pair<String, dev.buhanzaz.rwms.client.data.CustomerBookingCabin>?>(null)
    }
    var problemTarget by androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf<Pair<String, dev.buhanzaz.rwms.client.data.CustomerBookingCabin>?>(null)
    }
    androidx.compose.runtime.LaunchedEffect(visible, busy, acceptanceTarget) {
        val (bookingId, target) = acceptanceTarget ?: return@LaunchedEffect
        val accepted = visible.firstOrNull { it.bookingId == bookingId }
            ?.cabins?.firstOrNull { it.cabinUnitId == target.cabinUnitId }
            ?.acceptance != null
        if (!busy && accepted) acceptanceTarget = null
    }
    androidx.compose.runtime.LaunchedEffect(visible, busy, problemTarget) {
        val (bookingId, target) = problemTarget ?: return@LaunchedEffect
        val problemCount = visible.firstOrNull { it.bookingId == bookingId }
            ?.cabins?.firstOrNull { it.cabinUnitId == target.cabinUnitId }
            ?.problems?.size ?: target.problems.size
        if (!busy && problemCount > target.problems.size) problemTarget = null
    }
    Scaffold(
        topBar = { CustomerTopBar("Мои заказы", onMenu, onProfile) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp).testTag("bookings-screen"),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                OutlinedButton(
                    onClick = onRefresh,
                    modifier = Modifier.fillMaxWidth().widthIn(max = 760.dp),
                    enabled = !busy,
                ) { Text("Обновить статусы") }
            }
            if (visible.isEmpty()) item { Text("Оформленных заказов пока нет") }
            items(visible, key = { it.bookingId ?: it.inquiryId }) { booking ->
                OutlinedCard(Modifier.fillMaxWidth().widthIn(max = 760.dp)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Заказ ${booking.orderId ?: "создаётся"}", fontWeight = FontWeight.SemiBold)
                        Text("Статус: ${bookingStatusLabel(booking.status)}")
                        booking.deliveryAddress?.let { Text("Доставка: $it") }
                        if (booking.deliveryDate != null) {
                            Text("${booking.deliveryDate} · ${booking.windowStart?.take(5)}–${booking.windowEnd?.take(5)}")
                        }
                        booking.errorCode?.let { Text("Код: $it", style = MaterialTheme.typography.bodySmall) }
                        booking.cabins.forEach { cabin ->
                            OutlinedCard(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text("Бытовка № ${cabin.accountingNo} · ${cabin.rentalMonths} мес.")
                                    Text(
                                        if (cabin.arrivalEligible) "Прибыла" else "Ожидает доставки",
                                        color = if (cabin.arrivalEligible) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    cabin.acceptance?.let { Text("Принята ${it.acceptedAt}") }
                                    cabin.problems.forEach { problem ->
                                        Text("Проблема: ${problem.description}", style = MaterialTheme.typography.bodySmall)
                                    }
                                    if (booking.bookingId != null && cabin.arrivalEligible) {
                                        if (cabin.acceptance == null) {
                                            Button(
                                                onClick = { acceptanceTarget = booking.bookingId to cabin },
                                                enabled = !busy,
                                                modifier = Modifier.fillMaxWidth(),
                                            ) { Text("Принять бытовку") }
                                        }
                                        OutlinedButton(
                                            onClick = { problemTarget = booking.bookingId to cabin },
                                            enabled = !busy,
                                            modifier = Modifier.fillMaxWidth(),
                                        ) {
                                            Text(if (cabin.acceptance == null) "Сообщить до приёмки" else "Сообщить о проблеме")
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
    acceptanceTarget?.let { (bookingId, cabin) ->
        CustomerSignatureDialog(
            accountingNo = cabin.accountingNo,
            busy = busy,
            onDismiss = { acceptanceTarget = null },
            onSubmit = { strokes ->
                onAccept(bookingId, cabin.cabinUnitId, strokes)
            },
        )
    }
    problemTarget?.let { (bookingId, cabin) ->
        CustomerProblemDialog(
            accountingNo = cabin.accountingNo,
            phase = if (cabin.acceptance == null) "До приёмки" else "После приёмки",
            busy = busy,
            onDismiss = { problemTarget = null },
            onSubmit = { category, description, evidence ->
                onReport(bookingId, cabin.cabinUnitId, category, description, evidence)
            },
        )
    }
}

private fun bookingStatusLabel(status: String): String = when (status) {
    "COMPLETED" -> "оформлен"
    "REJECTED" -> "отклонён"
    "PENDING" -> "обрабатывается"
    else -> status
}
