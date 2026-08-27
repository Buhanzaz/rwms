package dev.buhanzaz.rwms.client.ui

import android.app.Activity
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.buhanzaz.rwms.client.data.DeliverySlot
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Full-screen first delivery step: Yandex map, address search, voice input, and exact point binding. */
@Composable
fun DeliveryMapScreen(
    state: CustomerWorkflowState,
    onBack: () -> Unit,
    onAddress: (String) -> Unit,
    onPoint: (Double, Double) -> Unit,
    onConfirmedLocation: (String, Double, Double) -> Unit,
    onLocationDraftChanged: () -> Unit,
    onContinue: () -> Unit,
) {
    val selectedWarehouse = requireNotNull(state.selectedWarehouse) {
        "Delivery requires a selected warehouse"
    }
    var geocoding by remember(selectedWarehouse.id) { mutableStateOf(false) }
    var locationMessage by remember(selectedWarehouse.id) { mutableStateOf<String?>(null) }
    var showManualCoordinates by rememberSaveable(selectedWarehouse.id) { mutableStateOf(false) }
    val geocoder = remember(selectedWarehouse.id) { YandexDeliveryGeocoder() }
    val keyboardController = LocalSoftwareKeyboardController.current

    DisposableEffect(geocoder) {
        onDispose { geocoder.cancel() }
    }

    fun resolveAddress(query: String) {
        val normalized = query.trim()
        if (normalized.isEmpty()) {
            locationMessage = "Введите адрес доставки"
            return
        }
        keyboardController?.hide()
        geocoder.cancel()
        onAddress(normalized)
        geocoding = true
        locationMessage = "Ищем адрес в Яндекс Картах…"
        geocoder.searchAddress(
            query = normalized,
            depotLatitude = selectedWarehouse.depotLatitude,
            depotLongitude = selectedWarehouse.depotLongitude,
            onSuccess = { location ->
                geocoding = false
                locationMessage = "Адрес и точка подтверждены"
                onConfirmedLocation(location.address, location.latitude, location.longitude)
            },
            onFailure = { message ->
                geocoding = false
                locationMessage = message
            },
        )
    }

    val voiceLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode != Activity.RESULT_OK) return@rememberLauncherForActivityResult
        val recognized = DeliveryVoicePolicy.recognizedAddress(
            result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS),
        )
        if (recognized == null) {
            locationMessage = "Не удалось распознать адрес"
        } else {
            resolveAddress(recognized)
        }
    }

    fun startVoiceInput() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU")
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Назовите адрес доставки")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        }
        runCatching { voiceLauncher.launch(intent) }
            .onFailure { locationMessage = "На устройстве нет сервиса голосового ввода" }
    }

    val canContinue = !state.busy && !geocoding && DeliveryLocationPolicy.canSearchSlots(
        address = state.address,
        latitude = state.latitude,
        longitude = state.longitude,
        confirmed = state.deliveryLocationConfirmed,
        selectedCabinCount = state.selectedCabinIds.size,
    )

    Box(Modifier.fillMaxSize().testTag("delivery-map-screen")) {
        DeliveryMapPointPicker(
            latitude = state.latitude,
            longitude = state.longitude,
            depotLatitude = selectedWarehouse.depotLatitude,
            depotLongitude = selectedWarehouse.depotLongitude,
            onPoint = { latitude, longitude, zoom ->
                geocoder.cancel()
                onPoint(latitude, longitude)
                geocoding = true
                locationMessage = "Определяем адрес выбранной точки…"
                geocoder.reversePoint(
                    latitude = latitude,
                    longitude = longitude,
                    zoom = zoom,
                    onSuccess = { location ->
                        geocoding = false
                        locationMessage = "Адрес и точка подтверждены"
                        onConfirmedLocation(location.address, location.latitude, location.longitude)
                    },
                    onFailure = { message ->
                        geocoding = false
                        locationMessage = "$message. Адрес можно подтвердить вручную."
                    },
                )
            },
            bottomControlsClearance = MAP_SEARCH_CLEARANCE,
            modifier = Modifier.fillMaxSize(),
        )

        Box(Modifier.fillMaxSize().safeDrawingPadding().padding(12.dp)) {
            Surface(
                modifier = Modifier.align(Alignment.TopStart),
                shape = CircleShape,
                color = Color.White.copy(alpha = 0.94f),
                shadowElevation = 5.dp,
            ) {
                IconButton(onClick = onBack, modifier = Modifier.size(54.dp)) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Назад в корзину",
                        tint = Color(0xFF303236),
                    )
                }
            }
        }

        DeliveryAddressPanel(
            address = state.address,
            confirmed = state.deliveryLocationConfirmed,
            geocoding = geocoding,
            status = locationMessage,
            continueEnabled = canContinue,
            onAddress = { address ->
                geocoder.cancel()
                geocoding = false
                locationMessage = null
                onAddress(address)
            },
            onSearch = ::resolveAddress,
            onVoice = ::startVoiceInput,
            onManualCoordinates = { showManualCoordinates = true },
            onContinue = {
                keyboardController?.hide()
                onContinue()
            },
            modifier = Modifier.align(Alignment.BottomCenter).imePadding(),
        )
    }

    if (showManualCoordinates) {
        ManualDeliveryLocationDialog(
            initialAddress = state.address,
            initialLatitude = state.latitude,
            initialLongitude = state.longitude,
            onDraftChanged = onLocationDraftChanged,
            onDismiss = { showManualCoordinates = false },
            onConfirm = { address, latitude, longitude ->
                geocoder.cancel()
                geocoding = false
                locationMessage = "Адрес и координаты подтверждены вручную"
                onConfirmedLocation(address, latitude, longitude)
                showManualCoordinates = false
            },
        )
    }
}

/** Second delivery step that exposes server-returned dates and expandable slot previews. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeliveryDatesScreen(
    state: CustomerWorkflowState,
    onBack: () -> Unit,
    onDate: (String) -> Unit,
) {
    val dates = remember(state.slots) { DeliverySlotPolicy.byDate(state.slots) }
    var expandedDate by rememberSaveable { mutableStateOf<String?>(null) }
    Scaffold(
        topBar = { DeliveryStepTopBar(step = 2, title = "Дата доставки", onBack = onBack) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().testTag("delivery-dates-screen"),
            contentPadding = padding,
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Column(
                    Modifier.fillMaxWidth().widthIn(max = 760.dp).padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text("Выберите удобный день", style = MaterialTheme.typography.headlineSmall)
                    Text("Показаны только даты, которые Logistics подтвердил для выбранного адреса.")
                }
            }
            items(dates, key = DeliveryDateAvailability::date) { availability ->
                DeliveryDateCard(
                    availability = availability,
                    expanded = expandedDate == availability.date,
                    onExpand = {
                        expandedDate = if (expandedDate == availability.date) null else availability.date
                    },
                    onSelect = { onDate(availability.date) },
                )
            }
            if (dates.isEmpty() && !state.busy) {
                item {
                    Column(
                        Modifier.fillMaxWidth().widthIn(max = 760.dp).padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(
                            if (state.slotSearchCompleted) {
                                "Для этого адреса пока нет свободных дат"
                            } else {
                                "Получаем свободные даты…"
                            },
                            style = MaterialTheme.typography.titleMedium,
                        )
                        OutlinedButton(onClick = onBack) { Text("Изменить адрес") }
                    }
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

/** Third delivery step that selects and server-holds one slot from a chosen date. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeliverySlotsScreen(
    state: CustomerWorkflowState,
    date: String,
    onBack: () -> Unit,
    onSelectSlot: (String) -> Unit,
    onHoldSlot: () -> Unit,
    onHeld: () -> Unit,
) {
    val slots = remember(state.slots, date) {
        DeliverySlotPolicy.byDate(state.slots).firstOrNull { it.date == date }?.slots.orEmpty()
    }
    val selected = slots.firstOrNull { it.slotId == state.selectedSlotId }
    var holdRequested by rememberSaveable(date) { mutableStateOf(false) }

    LaunchedEffect(holdRequested, state.heldSlot?.slot?.slotId, state.selectedSlotId) {
        if (holdRequested && state.heldSlot?.slot?.slotId == state.selectedSlotId) {
            holdRequested = false
            onHeld()
        }
    }

    Scaffold(
        topBar = { DeliveryStepTopBar(step = 3, title = formatDeliveryDate(date), onBack = onBack) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().testTag("delivery-slots-screen"),
            contentPadding = padding,
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Column(
                    Modifier.fillMaxWidth().widthIn(max = 760.dp).padding(horizontal = 16.dp, vertical = 12.dp),
                ) {
                    Text("Выберите время", style = MaterialTheme.typography.headlineSmall)
                    Text("Свободность будет повторно проверена перед закреплением.")
                }
            }
            items(slots, key = DeliverySlot::slotId) { slot ->
                OutlinedCard(
                    onClick = { onSelectSlot(slot.slotId) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .widthIn(max = 760.dp)
                        .padding(horizontal = 16.dp)
                        .testTag("delivery-slot-${slot.slotId}"),
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = selected?.slotId == slot.slotId, onClick = null)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                "${formatSlotTime(slot.start)}–${formatSlotTime(slot.end)}",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text("Зона дороги: ${slot.travelZoneHours} ч · доступно: ${slot.capacityRemaining}")
                        }
                    }
                }
            }
            if (slots.isEmpty()) {
                item {
                    Text(
                        "На эту дату свободных слотов больше нет",
                        modifier = Modifier.fillMaxWidth().widthIn(max = 760.dp).padding(24.dp),
                    )
                }
            }
            item {
                Button(
                    onClick = {
                        holdRequested = true
                        if (state.heldSlot?.slot?.slotId != selected?.slotId) onHoldSlot()
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .widthIn(max = 760.dp)
                        .padding(horizontal = 16.dp)
                        .testTag("hold-slot-button"),
                    enabled = selected != null && !state.busy,
                ) {
                    Text(if (state.busy) "Проверяем слот…" else "Продолжить")
                    Spacer(Modifier.width(6.dp))
                    Icon(Icons.Default.ChevronRight, contentDescription = null)
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

/** Fourth delivery step that confirms rental duration against the held authoritative slot. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeliveryConfirmationScreen(
    state: CustomerWorkflowState,
    onBack: () -> Unit,
    onRentalMonths: (Long) -> Unit,
    onCheckout: () -> Unit,
) {
    val held = state.heldSlot?.slot
    Scaffold(
        topBar = { DeliveryStepTopBar(step = 4, title = "Подтверждение", onBack = onBack) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().testTag("delivery-confirmation-screen"),
            contentPadding = padding,
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (held == null) {
                item {
                    Card(
                        Modifier.fillMaxWidth().widthIn(max = 760.dp).padding(16.dp),
                    ) {
                        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(
                                if (state.booking?.status == "REJECTED") {
                                    "Бронирование отклонено. Выберите новый слот."
                                } else {
                                    "Закреплённый слот недоступен."
                                },
                                style = MaterialTheme.typography.titleMedium,
                            )
                            OutlinedButton(onClick = onBack) { Text("Вернуться к слотам") }
                        }
                    }
                }
            } else {
                item {
                    Card(
                        Modifier.fillMaxWidth().widthIn(max = 760.dp).padding(horizontal = 16.dp, vertical = 12.dp),
                    ) {
                        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Default.CheckCircle,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                                Spacer(Modifier.width(8.dp))
                                Text("Слот временно закреплён", fontWeight = FontWeight.SemiBold)
                            }
                            ConfirmationFact("Адрес", state.address)
                            ConfirmationFact("Дата", formatDeliveryDate(held.date))
                            ConfirmationFact(
                                "Время",
                                "${formatSlotTime(held.start)}–${formatSlotTime(held.end)}",
                            )
                            ConfirmationFact("Бытовки", state.selectedCabinIds.size.toString())
                        }
                    }
                }
                item {
                    OutlinedCard(
                        Modifier.fillMaxWidth().widthIn(max = 760.dp).padding(horizontal = 16.dp),
                    ) {
                        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("Срок аренды", style = MaterialTheme.typography.titleMedium)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                IconButton(
                                    onClick = { onRentalMonths((state.rentalMonths - 1L).coerceAtLeast(1L)) },
                                    enabled = state.rentalMonths > 1 && !state.busy,
                                ) { Icon(Icons.Default.Remove, contentDescription = "Уменьшить срок") }
                                Text(
                                    "${state.rentalMonths} мес.",
                                    modifier = Modifier.width(112.dp),
                                    style = MaterialTheme.typography.titleLarge,
                                )
                                IconButton(
                                    onClick = { onRentalMonths((state.rentalMonths + 1L).coerceAtMost(120L)) },
                                    enabled = state.rentalMonths < 120 && !state.busy,
                                ) { Icon(Icons.Default.Add, contentDescription = "Увеличить срок") }
                            }
                        }
                    }
                }
                item {
                    Button(
                        onClick = onCheckout,
                        modifier = Modifier
                            .fillMaxWidth()
                            .widthIn(max = 760.dp)
                            .padding(horizontal = 16.dp)
                            .testTag("checkout-button"),
                        enabled = !state.busy,
                    ) {
                        Icon(Icons.Default.ShoppingCart, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Подтвердить бронирование")
                    }
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun DeliveryAddressPanel(
    address: String,
    confirmed: Boolean,
    geocoding: Boolean,
    status: String?,
    continueEnabled: Boolean,
    onAddress: (String) -> Unit,
    onSearch: (String) -> Unit,
    onVoice: () -> Unit,
    onManualCoordinates: () -> Unit,
    onContinue: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth().widthIn(max = 760.dp),
        shape = RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp),
        color = Color.White,
        shadowElevation = 10.dp,
    ) {
        Column(
            Modifier.navigationBarsPadding().padding(start = 14.dp, top = 8.dp, end = 14.dp, bottom = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(
                Modifier
                    .size(width = 42.dp, height = 4.dp)
                    .background(Color(0xFFD0D2D5), CircleShape),
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextField(
                    value = address,
                    onValueChange = onAddress,
                    modifier = Modifier.weight(1f).testTag("delivery-address-field"),
                    placeholder = { Text("Поиск адреса") },
                    leadingIcon = {
                        IconButton(onClick = onVoice, enabled = !geocoding) {
                            Icon(Icons.Default.Mic, contentDescription = "Голосовой ввод адреса")
                        }
                    },
                    trailingIcon = {
                        IconButton(onClick = onVoice, enabled = !geocoding) {
                            Icon(
                                Icons.Default.AutoAwesome,
                                contentDescription = "Открыть голосовой поиск",
                                tint = Color(0xFF7A4DFF),
                            )
                        }
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(24.dp),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { onSearch(address) }),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color(0xFFF3F3F5),
                        unfocusedContainerColor = Color(0xFFF3F3F5),
                        disabledContainerColor = Color(0xFFF3F3F5),
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        disabledIndicatorColor = Color.Transparent,
                    ),
                )
                FilledIconButton(
                    onClick = onContinue,
                    enabled = continueEnabled,
                    modifier = Modifier.size(56.dp).testTag("delivery-map-continue"),
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = DELIVERY_ACTION_COLOR,
                        contentColor = Color.White,
                        disabledContainerColor = Color(0xFFE4E4E6),
                        disabledContentColor = Color(0xFF9A9A9D),
                    ),
                ) {
                    Icon(Icons.Default.ChevronRight, contentDescription = "Продолжить к выбору даты")
                }
            }
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                    if (geocoding) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(6.dp))
                    }
                    Text(
                        status ?: if (confirmed) "Адрес подтверждён" else "Введите адрес или отметьте точку",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (confirmed) Color(0xFF1E7B47) else Color(0xFF62646A),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                TextButton(onClick = onManualCoordinates) { Text("Координаты") }
            }
        }
    }
}

/** Manual fallback that binds one visible address to validated latitude and longitude. */
@Composable
private fun ManualDeliveryLocationDialog(
    initialAddress: String,
    initialLatitude: Double?,
    initialLongitude: Double?,
    onDraftChanged: () -> Unit,
    onDismiss: () -> Unit,
    onConfirm: (String, Double, Double) -> Unit,
) {
    var address by remember { mutableStateOf(initialAddress) }
    var latitude by remember { mutableStateOf(initialLatitude?.toString().orEmpty()) }
    var longitude by remember { mutableStateOf(initialLongitude?.toString().orEmpty()) }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Адрес и координаты") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = address,
                    onValueChange = {
                        address = it
                        error = null
                        onDraftChanged()
                    },
                    label = { Text("Адрес доставки") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = latitude,
                    onValueChange = {
                        latitude = it
                        error = null
                        onDraftChanged()
                    },
                    label = { Text("Широта") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                )
                OutlinedTextField(
                    value = longitude,
                    onValueChange = {
                        longitude = it
                        error = null
                        onDraftChanged()
                    },
                    label = { Text("Долгота") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val parsedLatitude = latitude.replace(',', '.').toDoubleOrNull()
                    val parsedLongitude = longitude.replace(',', '.').toDoubleOrNull()
                    error = when {
                        address.isBlank() -> "Введите адрес доставки"
                        parsedLatitude == null || parsedLongitude == null -> "Укажите координаты числами"
                        parsedLatitude !in -90.0..90.0 -> "Широта вне допустимого диапазона"
                        parsedLongitude !in -180.0..180.0 -> "Долгота вне допустимого диапазона"
                        else -> null
                    }
                    if (error == null) onConfirm(address.trim(), parsedLatitude!!, parsedLongitude!!)
                },
            ) { Text("Подтвердить") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}

@Composable
private fun DeliveryDateCard(
    availability: DeliveryDateAvailability,
    expanded: Boolean,
    onExpand: () -> Unit,
    onSelect: () -> Unit,
) {
    OutlinedCard(
        onClick = onSelect,
        modifier = Modifier
            .fillMaxWidth()
            .widthIn(max = 760.dp)
            .padding(horizontal = 16.dp)
            .testTag("delivery-date-${availability.date}"),
    ) {
        Column {
            Row(
                Modifier.fillMaxWidth().padding(start = 16.dp, top = 10.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Default.CalendarMonth, contentDescription = null)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(formatDeliveryDate(availability.date), style = MaterialTheme.typography.titleMedium)
                    Text("Свободных окон: ${availability.slots.size}", style = MaterialTheme.typography.bodySmall)
                }
                IconButton(onClick = onExpand, modifier = Modifier.testTag("delivery-date-expand-${availability.date}")) {
                    Icon(
                        if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = if (expanded) "Скрыть свободные слоты" else "Показать свободные слоты",
                    )
                }
            }
            AnimatedVisibility(expanded) {
                Column {
                    HorizontalDivider()
                    availability.slots.forEach { slot ->
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Default.Schedule, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("${formatSlotTime(slot.start)}–${formatSlotTime(slot.end)}")
                        }
                    }
                    Text(
                        "Нажмите на дату, чтобы выбрать слот",
                        modifier = Modifier.padding(start = 18.dp, end = 18.dp, bottom = 12.dp),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DeliveryStepTopBar(step: Int, title: String, onBack: () -> Unit) {
    TopAppBar(
        title = {
            Column {
                Text("Шаг $step из 4", style = MaterialTheme.typography.labelMedium)
                Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        },
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
            }
        },
    )
}

@Composable
private fun ConfirmationFact(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyLarge)
    }
}

private fun formatDeliveryDate(isoDate: String): String = runCatching {
    LocalDate.parse(isoDate).format(DateTimeFormatter.ofPattern("d MMMM, EEEE", Locale.forLanguageTag("ru")))
}.getOrDefault(isoDate)

private fun formatSlotTime(value: String): String = value.take(5)

private val DELIVERY_ACTION_COLOR = Color(0xFFFF8A34)
private val MAP_SEARCH_CLEARANCE = 178.dp
