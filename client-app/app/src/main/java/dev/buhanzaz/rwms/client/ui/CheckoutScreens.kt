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
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
                Text(
                    "Бытовок: ${state.selectedCabinIds.size}",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.fillMaxWidth().widthIn(max = 760.dp),
                )
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
                CartCabinLine(cabin, lines, onRemove = { onToggleCabin(cabin.unitId) })
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
private fun CartCabinLine(cabin: CustomerCabin, equipment: List<String>, onRemove: () -> Unit) {
    OutlinedCard(Modifier.fillMaxWidth().widthIn(max = 760.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("${cabin.type ?: "Тип не указан"} · № ${cabin.accountingNo}", fontWeight = FontWeight.SemiBold)
            cabin.finish?.let { Text("Отделка: $it") }
            if (equipment.isEmpty()) Text("Без добавленной мебели", style = MaterialTheme.typography.bodySmall)
            equipment.forEach { Text("• $it") }
            OutlinedButton(onClick = onRemove, modifier = Modifier.fillMaxWidth()) { Text("Убрать из заказа") }
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
) {
    val visible = CustomerBookingPolicy.visible(latest, bookings)
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
                        booking.errorCode?.let { Text("Код: $it", style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

private fun bookingStatusLabel(status: String): String = when (status) {
    "COMPLETED" -> "оформлен"
    "REJECTED" -> "отклонён"
    "PENDING" -> "обрабатывается"
    else -> status
}
