package dev.buhanzaz.rwms.client.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import dev.buhanzaz.rwms.client.BuildConfig
import dev.buhanzaz.rwms.client.data.AvailableEquipment
import dev.buhanzaz.rwms.client.data.CustomerBooking
import dev.buhanzaz.rwms.client.data.CustomerCabin
import dev.buhanzaz.rwms.client.data.DeliverySlot

/**
 * Displays the authoritative rental cart as individual cabin cards.
 *
 * Duration and additional equipment actions intentionally delegate to the caller: the cart does
 * not keep a local checkout draft or calculate availability, price, or delivery itself.
 */
@Composable
fun CartScreen(
    state: CustomerWorkflowState,
    onBack: () -> Unit,
    onProfile: () -> Unit,
    onContinue: () -> Unit,
    onToggleCabin: (String) -> Unit,
    onCabinRentalMonths: (String, Long) -> Unit,
    onEquipment: (String, AvailableEquipment, Long) -> Unit,
) {
    var additionalCabinId by remember { mutableStateOf<String?>(null) }
    val cartCabins = state.cart?.cabins.orEmpty().ifEmpty {
        state.cabins.filter { it.unitId in state.selectedCabinIds }
    }
    Scaffold(
        topBar = {
            CustomerTopBar("Корзина", onBack = onBack, onProfile = onProfile, avatarUrl = state.profile?.avatar?.thumbnailUrl)
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp).testTag("cart-screen"),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(top = 20.dp, bottom = 32.dp),
        ) {
            items(cartCabins, key = CustomerCabin::unitId) { cabin ->
                val serverEquipment = state.cart?.equipment.orEmpty()
                    .filter { it.cabinUnitId == cabin.unitId }
                    .associate { selection ->
                        EquipmentKey(cabin.unitId, selection.inventoryItemId) to selection.quantity
                    }
                val selectedEquipment = state.equipmentDraft
                    .filterKeys { key -> key.cabinUnitId == cabin.unitId }
                    .takeIf { it.isNotEmpty() }
                    ?: serverEquipment
                CartCabinCard(
                    cabin = cabin,
                    selectedEquipment = selectedEquipment,
                    equipment = state.equipment,
                    months = state.rentalTerms[cabin.unitId] ?: 1L,
                    busy = state.busy,
                    onMonths = { onCabinRentalMonths(cabin.unitId, it) },
                    onAdditional = { additionalCabinId = cabin.unitId },
                    onRemove = {
                        additionalCabinId = null
                        onToggleCabin(cabin.unitId)
                    },
                )
            }
            if (cartCabins.isEmpty()) {
                item {
                    Text(
                        "Добавьте хотя бы одну бытовку в аренду.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            item {
                Button(
                    onClick = onContinue,
                    modifier = Modifier.fillMaxWidth().widthIn(max = 1_120.dp).testTag("cart-delivery-button"),
                    enabled = cartCabins.isNotEmpty() && !state.busy,
                ) {
                    Icon(Icons.Default.LocationOn, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Продолжить к доставке")
                }
            }
        }
    }
    additionalCabinId?.let { cabinId ->
        cartCabins.firstOrNull { it.unitId == cabinId }?.let { cabin ->
            val serverEquipment = state.cart?.equipment.orEmpty()
                .filter { it.cabinUnitId == cabinId }
                .associate { selection ->
                    EquipmentKey(cabinId, selection.inventoryItemId) to selection.quantity
                }
            val selectedEquipment = state.equipmentDraft
                .filterKeys { key -> key.cabinUnitId == cabinId }
                .takeIf { it.isNotEmpty() }
                ?: serverEquipment
            CartAdditionalSheet(
                cabin = cabin,
                items = state.equipment,
                selectedEquipment = selectedEquipment,
                busy = state.busy,
                onQuantity = { item, quantity -> onEquipment(cabinId, item, quantity) },
                onDismiss = { additionalCabinId = null },
            )
        }
    }
}

/** Presents one selected cabin and exposes only server-backed cart mutations for that cabin. */
@Composable
private fun CartCabinCard(
    cabin: CustomerCabin,
    selectedEquipment: Map<EquipmentKey, Long>,
    equipment: List<AvailableEquipment>,
    months: Long,
    busy: Boolean,
    onMonths: (Long) -> Unit,
    onAdditional: () -> Unit,
    onRemove: () -> Unit,
) {
    val additionalLines = selectedEquipment.entries
        .sortedBy { (key, _) -> equipment.firstOrNull { it.inventoryItemId == key.inventoryItemId }?.name ?: key.inventoryItemId }
        .map { (key, quantity) ->
            val name = equipment.firstOrNull { it.inventoryItemId == key.inventoryItemId }?.name
                ?: key.inventoryItemId
            "$name · $quantity шт."
        }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .widthIn(max = 1_120.dp)
            .testTag("cart-cabin-${cabin.unitId}"),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(Modifier.fillMaxWidth()) {
            CartCabinPhoto(
                cabin = cabin,
                modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f),
            )
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        cabin.type ?: "Тип не указан",
                        modifier = Modifier.weight(1f).testTag("cart-cabin-type-${cabin.unitId}"),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        "№ ${cabin.accountingNo}",
                        modifier = Modifier.testTag("cart-cabin-number-${cabin.unitId}"),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                cabin.category?.let { category ->
                    Text(
                        category,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                CartCabinFacts(cabin)
                Text(
                    CustomerMoneyFormatter.monthlyRentalPrice(cabin.monthlyPriceRubles),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.testTag("cart-rental-price-${cabin.unitId}"),
                )
                if (additionalLines.isNotEmpty()) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Дополнительно", style = MaterialTheme.typography.labelLarge)
                        additionalLines.forEach { line ->
                            Text(
                                line,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Срок аренды", style = MaterialTheme.typography.labelLarge)
                    CartRentalTermControl(
                        cabinUnitId = cabin.unitId,
                        months = months,
                        enabled = !busy,
                        onMonths = onMonths,
                    )
                }
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = onAdditional,
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth().testTag("cart-additional-${cabin.unitId}"),
                    ) {
                        Text("+ Дополнительно")
                    }
                    OutlinedButton(
                        onClick = onRemove,
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth().testTag("cart-remove-${cabin.unitId}"),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error),
                    ) {
                        Text("Удалить", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }
}

/** Lays out the cabin facts without turning the accounting number or finish into decorative badges. */
@Composable
private fun CartCabinFacts(cabin: CustomerCabin) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            CartCabinFact(
                label = "Отделка",
                value = cabin.finish ?: "Не указана",
                modifier = Modifier.weight(1f),
            )
            CartCabinFact(
                label = "Габариты",
                value = cabin.dimensions ?: "Не указаны",
                modifier = Modifier.weight(1f),
            )
        }
        cabin.linoleum?.let { linoleum ->
            Text(
                if (linoleum) "Линолеум" else "Без линолеума",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        cabin.characteristics.forEach { characteristic ->
            Text(
                characteristic,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Prints one compact factual label and value in a cabin cart card. */
@Composable
private fun CartCabinFact(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

/** Keeps the rental term visibly scoped to exactly one selected cabin. */
@Composable
private fun CartRentalTermControl(
    cabinUnitId: String,
    months: Long,
    enabled: Boolean,
    onMonths: (Long) -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth().testTag("cart-rental-term-$cabinUnitId"),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                onClick = { onMonths((months - 1).coerceAtLeast(1)) },
                enabled = enabled && months > 1,
                modifier = Modifier.testTag("cart-rental-decrement-$cabinUnitId"),
            ) {
                Icon(Icons.Default.Remove, contentDescription = "Уменьшить срок аренды")
            }
            Text(
                "$months мес.",
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.Center,
                fontWeight = FontWeight.SemiBold,
            )
            IconButton(
                onClick = { onMonths((months + 1).coerceAtMost(120)) },
                enabled = enabled && months < 120,
                modifier = Modifier.testTag("cart-rental-increment-$cabinUnitId"),
            ) {
                Icon(Icons.Default.Add, contentDescription = "Увеличить срок аренды")
            }
        }
    }
}

/** Shows a real server-provided photo when a selected cabin has one. */
@Composable
private fun CartCabinPhoto(cabin: CustomerCabin, modifier: Modifier = Modifier) {
    val photo = cabin.photos.firstOrNull()
    if (photo == null) {
        Surface(modifier = modifier.testTag("cart-cabin-photo-placeholder"), color = MaterialTheme.colorScheme.surfaceContainer) {
            Box(contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Default.PhotoLibrary,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "Фотография пока не добавлена",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    } else {
        AsyncImage(
            model = cartCabinMediaUrl(photo.thumbnailUrl),
            contentDescription = "Бытовка ${cabin.accountingNo}",
            contentScale = ContentScale.Crop,
            modifier = modifier,
        )
    }
}

/** Opens quantities for additional furniture and equipment without keeping an independent cart state. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CartAdditionalSheet(
    cabin: CustomerCabin,
    items: List<AvailableEquipment>,
    selectedEquipment: Map<EquipmentKey, Long>,
    busy: Boolean,
    onQuantity: (AvailableEquipment, Long) -> Unit,
    onDismiss: () -> Unit,
) {
    val availableItems = items.filter { it.availableQuantity > 0 }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(20.dp)
                .testTag("cart-additional-sheet-${cabin.unitId}"),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Дополнительно · № ${cabin.accountingNo}", style = MaterialTheme.typography.headlineSmall)
            Text(
                "Добавьте мебель или оборудование к этой бытовке.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (availableItems.isEmpty()) {
                Text("Свободных дополнительных позиций на выбранном складе нет")
            }
            availableItems.forEach { item ->
                val key = EquipmentKey(cabin.unitId, item.inventoryItemId)
                val quantity = selectedEquipment[key] ?: 0L
                val maximum = minOf(item.availableQuantity, item.maximumPerCabin?.toLong() ?: Long.MAX_VALUE)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(item.name, fontWeight = FontWeight.Medium)
                        item.category?.let { category ->
                            Text(
                                category,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    IconButton(
                        onClick = { onQuantity(item, (quantity - 1).coerceAtLeast(0)) },
                        enabled = !busy && quantity > 0,
                        modifier = Modifier.testTag("cart-equipment-decrement-${cabin.unitId}-${item.inventoryItemId}"),
                    ) {
                        Icon(Icons.Default.Remove, contentDescription = "Убрать ${item.name}")
                    }
                    Text(quantity.toString(), modifier = Modifier.width(28.dp), textAlign = TextAlign.Center)
                    IconButton(
                        onClick = { onQuantity(item, quantity + 1) },
                        enabled = !busy && quantity < maximum,
                        modifier = Modifier.testTag("cart-equipment-increment-${cabin.unitId}-${item.inventoryItemId}"),
                    ) {
                        Icon(Icons.Default.Add, contentDescription = "Добавить ${item.name}")
                    }
                }
            }
            Button(onClick = onDismiss, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Готово") }
        }
    }
}

private fun cartCabinMediaUrl(path: String): String = when {
    path.startsWith("/api/logistics/customer/v1/") -> "${BuildConfig.PUBLIC_BASE_URL}$path"
    path.startsWith("${BuildConfig.PUBLIC_BASE_URL}/api/logistics/customer/v1/") -> path
    else -> ""
}

/** Lists durable RWMS bookings rather than scenario-local logistics requests. */
@Composable
fun BookingsScreen(
    bookings: List<CustomerBooking>,
    latest: CustomerBooking?,
    busy: Boolean,
    onMenu: () -> Unit,
    onProfile: () -> Unit,
    onAccept: (String, String, List<dev.buhanzaz.rwms.client.data.CustomerSignatureStroke>) -> Unit,
    onReport: (String, String, String, String, List<dev.buhanzaz.rwms.client.data.CustomerEvidenceFile>) -> Unit,
    rescheduleBookingId: String? = null,
    rescheduleSlots: List<DeliverySlot> = emptyList(),
    selectedRescheduleSlotId: String? = null,
    onCancel: (String) -> Unit = {},
    onOpenReschedule: (String) -> Unit = {},
    onSelectRescheduleSlot: (String) -> Unit = {},
    onConfirmReschedule: () -> Unit = {},
    onDismissReschedule: () -> Unit = {},
    changeQuote: dev.buhanzaz.rwms.client.data.CustomerBookingChangeQuote? = null,
    changeDialogVisible: Boolean = false,
    changeNeedsRefresh: Boolean = false,
    changeError: String? = null,
    changeUnavailableReason: String? = null,
    onApplyChange: (Boolean) -> Unit = {},
    onDismissChange: () -> Unit = {},
    onCallChangeSupport: (String) -> Unit = {},
    pendingChangeBookingIds: Set<String> = emptySet(),
    onResumeChange: (String) -> Unit = {},
    payments: Map<String, CustomerObservedPayment> = emptyMap(),
    paymentErrors: Map<String, String> = emptyMap(),
    notifications: List<dev.buhanzaz.rwms.client.data.CustomerNotification> = emptyList(),
    updatesError: String? = null,
    onConfirmInitialPayment: (String) -> Unit = {},
    onReadNotification: (String) -> Unit = {},
    avatarUrl: String? = null,
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
        topBar = { CustomerTopBar("Мои заказы", onMenu, onProfile, avatarUrl = avatarUrl) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp).testTag("bookings-screen"),
            contentPadding = PaddingValues(top = 8.dp, bottom = 100.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            updatesError?.let { error ->
                item { Text(error, color = MaterialTheme.colorScheme.error) }
            }
            items(notifications, key = { "notification-${it.id}" }) { notification ->
                OutlinedCard(
                    modifier = Modifier.fillMaxWidth().testTag("customer-notification-${notification.id}"),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surface),
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Уведомление", style = MaterialTheme.typography.titleSmall)
                        Text(notification.message)
                        OutlinedButton(onClick = { onReadNotification(notification.id) }, enabled = !busy) {
                            Text("Прочитано")
                        }
                    }
                }
            }
            if (visible.isEmpty()) item { Text("Оформленных заказов пока нет") }
            items(visible, key = { it.bookingId ?: it.inquiryId }) { booking ->
                val payment = payments[booking.bookingId]
                OutlinedCard(
                    modifier = Modifier.fillMaxWidth().widthIn(max = 760.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                payment?.payment?.receipt?.orderNumber?.let { "Заказ № $it" }
                                    ?: if (booking.orderId == null) "Оформляем заказ" else "Аренда бытовок",
                                style = MaterialTheme.typography.titleLarge,
                            )
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = MaterialTheme.colorScheme.primaryContainer,
                            ) {
                                Text(
                                    CustomerBookingLifecyclePolicy.statusLabel(booking.status),
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                )
                            }
                        }
                        booking.deliveryAddress?.let { address ->
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Icon(Icons.Default.LocationOn, contentDescription = null, modifier = Modifier.size(20.dp))
                                Text(address, style = MaterialTheme.typography.bodyLarge)
                            }
                        }
                        if (booking.deliveryDate != null) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Icon(Icons.Default.CalendarMonth, contentDescription = null, modifier = Modifier.size(20.dp))
                                Text(
                                    formatDeliveryDate(booking.deliveryDate) +
                                        if (booking.windowStart != null && booking.windowEnd != null) {
                                            " · ${booking.windowStart.take(5)}–${booking.windowEnd.take(5)}"
                                        } else "",
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                        }
                        CustomerBookingLifecyclePolicy.cancellationFeeLabel(booking.cancellationFeeRubles)?.let { fee ->
                            Text("Стоимость отмены: $fee")
                        }
                        CustomerBookingLifecyclePolicy.recoveryMessage(booking.errorCode)?.let { message ->
                            Text(
                                message,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        booking.cabins.forEach { cabin ->
                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant,
                            ) {
                                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text("Бытовка № ${cabin.accountingNo} · ${cabin.rentalMonths} мес.", style = MaterialTheme.typography.titleSmall)
                                    Text(
                                        when (booking.status) {
                                            "CANCELLED" -> "Доставка отменена"
                                            "CANCELLATION_PENDING" -> "Отмена доставки выполняется"
                                            "REJECTED" -> "Доставка не оформлена"
                                            else -> if (cabin.arrivalEligible) "Прибыла" else "Ожидает доставки"
                                        },
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                    cabin.acceptance?.let { Text("Принята ${formatDeliveryDate(it.acceptedAt.take(10))}") }
                                    cabin.problems.forEach { problem ->
                                        Text("Проблема: ${problem.description}", style = MaterialTheme.typography.bodySmall)
                                    }
                                    if (booking.bookingId != null && booking.status == "COMPLETED" && cabin.arrivalEligible) {
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
                        if (payment != null) {
                            CustomerInitialPaymentReceipt(payment, busy || paymentErrors[booking.bookingId] != null) {
                                booking.bookingId?.let(onConfirmInitialPayment)
                            }
                        } else if (booking.orderId != null) {
                            Text("Счёт загружается. Оплата пока недоступна.", style = MaterialTheme.typography.bodySmall)
                        }
                        paymentErrors[booking.bookingId]?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                        if (CustomerBookingLifecyclePolicy.canChange(booking)) {
                            val bookingId = requireNotNull(booking.bookingId)
                            Button(
                                onClick = { onOpenReschedule(bookingId) },
                                enabled = !busy,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 8.dp)
                                    .testTag("booking-reschedule-$bookingId"),
                            ) {
                                Text("Перенести доставку")
                            }
                            OutlinedButton(
                                onClick = { onCancel(bookingId) },
                                enabled = !busy,
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.error),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("booking-cancel-$bookingId"),
                            ) {
                                Text("Отменить заказ", color = MaterialTheme.colorScheme.error)
                            }
                        }
                        if (booking.bookingId in pendingChangeBookingIds && !changeDialogVisible) {
                            OutlinedButton(onClick = { booking.bookingId?.let(onResumeChange) }, enabled = !busy) {
                                Text("Подробности изменения")
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
    if (changeDialogVisible && changeQuote != null) {
        CustomerBookingChangeDialog(
            state = CustomerBookingChangeDialogState(
                operation = CustomerBookingChangeOperation.valueOf(changeQuote.operation.name),
                amountRubles = changeQuote.amountAsLongOrNull(),
                settlement = CustomerBookingChangeSettlement.valueOf(changeQuote.settlement.name),
                applicationState = CustomerBookingChangeApplicationState.valueOf(changeQuote.applicationState.name),
                testPaymentAvailable = changeQuote.testPaymentAvailable,
                supportPhone = changeQuote.supportPhone,
                pending = busy,
                needsRefresh = changeNeedsRefresh,
                errorMessage = changeError,
                unavailableReason = changeUnavailableReason,
                targetDeliveryLabel = changeQuote.targetDeliveryDate?.let { date ->
                    formatDeliveryDate(date) + " · ${changeQuote.targetWindowStart?.take(5)}–${changeQuote.targetWindowEnd?.take(5)}"
                },
            ),
            onPay = { onApplyChange(true) },
            onConfirm = { onApplyChange(false) },
            onCallSupport = onCallChangeSupport,
            onDismiss = onDismissChange,
        )
    }
    if (rescheduleBookingId != null && !changeDialogVisible) {
        BookingRescheduleDialog(
            slots = rescheduleSlots,
            selectedSlotId = selectedRescheduleSlotId,
            busy = busy,
            onSelect = onSelectRescheduleSlot,
            onConfirm = onConfirmReschedule,
            onDismiss = onDismissReschedule,
        )
    }
}

/** Presents only booking-scoped replacement offers returned by the logistics service. */
@Composable
private fun BookingRescheduleDialog(
    slots: List<DeliverySlot>,
    selectedSlotId: String?,
    busy: Boolean,
    onSelect: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val deliveryPrices = slots.map(DeliverySlot::deliveryPriceRubles).distinct()
    val pricesDiffer = deliveryPrices.size > 1
    val sharedDeliveryPrice = deliveryPrices.singleOrNull()
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        containerColor = MaterialTheme.colorScheme.surface,
        title = { Text("Перенести доставку") },
        text = {
            if (slots.isEmpty()) {
                Text("Сейчас нет доступных вариантов. Попробуйте позже.")
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!pricesDiffer) {
                        Text("Стоимость доставки: ${CustomerMoneyFormatter.wholeRubles(sharedDeliveryPrice)}")
                    }
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(DeliverySlotPolicy.byDate(slots).flatMap { it.slots }, key = DeliverySlot::slotId) { slot ->
                            OutlinedCard(
                                onClick = { onSelect(slot.slotId) },
                                enabled = !busy,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("booking-reschedule-slot-${slot.slotId}"),
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    RadioButton(
                                        selected = slot.slotId == selectedSlotId,
                                        onClick = null,
                                        enabled = !busy,
                                    )
                                    Column(
                                        modifier = Modifier.padding(start = 8.dp),
                                        verticalArrangement = Arrangement.spacedBy(2.dp),
                                    ) {
                                        Text(formatDeliveryDate(slot.date), fontWeight = FontWeight.SemiBold)
                                        Text(deliverySlotTimeLabel(slot))
                                        if (pricesDiffer) {
                                            Text(
                                                "Стоимость доставки: ${CustomerMoneyFormatter.wholeRubles(slot.deliveryPriceRubles)}",
                                                style = MaterialTheme.typography.bodySmall,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                enabled = !busy && selectedSlotId != null,
                modifier = Modifier.testTag("booking-confirm-reschedule"),
            ) {
                Text("Подтвердить перенос")
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss, enabled = !busy) {
                Text("Не переносить")
            }
        },
        modifier = Modifier
            .fillMaxWidth()
            .widthIn(max = 760.dp)
            .testTag("booking-reschedule-dialog"),
    )
}
