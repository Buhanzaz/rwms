package dev.buhanzaz.rwms.client.ui

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.LocalShipping
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import dev.buhanzaz.rwms.client.data.DeliverySlot
import dev.buhanzaz.rwms.client.data.DeliverySlotKind
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.delay

/** Full-screen first delivery step: Yandex map, address search, voice input, and exact point binding. */
@Composable
fun DeliveryMapScreen(
    state: CustomerWorkflowState,
    onBack: () -> Unit,
    onAddress: (String) -> Unit,
    onPoint: (Double, Double) -> Unit,
    onConfirmedLocation: (String, Double, Double) -> Unit,
    onSiteCabinCapacity: (Int) -> Unit,
    onPrivateSiteAccess: (Boolean) -> Unit,
    onFailedTripAcknowledgement: (Boolean) -> Unit,
    onSearchSlots: () -> Unit,
    onSlotsReady: () -> Unit,
    onProfile: (() -> Unit)? = null,
) {
    val selectedWarehouse = requireNotNull(state.selectedWarehouse) {
        "Delivery requires a selected warehouse"
    }
    var geocoding by remember(selectedWarehouse.id) { mutableStateOf(false) }
    var locationMessage by remember(selectedWarehouse.id) { mutableStateOf<String?>(null) }
    var suggestions by remember(selectedWarehouse.id) {
        mutableStateOf<List<DeliveryAddressSuggestion>>(emptyList())
    }
    var suggestionsEnabled by remember(selectedWarehouse.id) { mutableStateOf(false) }
    var showResponsibilityDialog by rememberSaveable(selectedWarehouse.id) { mutableStateOf(false) }
    var showVoiceInstallationDialog by rememberSaveable(selectedWarehouse.id) { mutableStateOf(false) }
    var locationDialogMessage by rememberSaveable(selectedWarehouse.id) { mutableStateOf<String?>(null) }
    var awaitingSlotGeneration by rememberSaveable(selectedWarehouse.id) { mutableStateOf<Long?>(null) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val density = LocalDensity.current
    var addressPanelHeight by remember { mutableIntStateOf(0) }
    val addressClearance = with(density) { addressPanelHeight.toDp() } + 8.dp
    val geocoder = remember(selectedWarehouse.id) { YandexDeliveryGeocoder() }
    val suggestSession = remember(selectedWarehouse.id) { YandexDeliverySuggestSession() }
    val currentLocationProvider = remember(selectedWarehouse.id, context) {
        AndroidCurrentLocationProvider(context)
    }
    val keyboardController = LocalSoftwareKeyboardController.current

    LaunchedEffect(state.slotSearchGeneration, state.busy, awaitingSlotGeneration) {
        val baseline = awaitingSlotGeneration ?: return@LaunchedEffect
        if (SlotSearchNavigationPolicy.canNavigate(baseline, state.slotSearchGeneration, state.busy)) {
            awaitingSlotGeneration = null
            onSlotsReady()
        }
    }

    DisposableEffect(geocoder, suggestSession, currentLocationProvider) {
        onDispose {
            geocoder.cancel()
            suggestSession.cancel()
            currentLocationProvider.cancel()
        }
    }

    LaunchedEffect(
        state.address,
        state.deliveryLocationConfirmed,
        suggestionsEnabled,
        selectedWarehouse.id,
    ) {
        suggestSession.cancel()
        val query = state.address.trim()
        if (!suggestionsEnabled || state.deliveryLocationConfirmed || query.length < 2) {
            suggestions = emptyList()
            return@LaunchedEffect
        }
        delay(ADDRESS_SUGGEST_DEBOUNCE_MILLIS)
        suggestSession.suggest(
            query = query,
            depotLatitude = selectedWarehouse.depotLatitude,
            depotLongitude = selectedWarehouse.depotLongitude,
            onSuccess = { suggestions = it },
            onFailure = {
                suggestions = emptyList()
                locationMessage = it
            },
        )
    }

    fun resolveAddress(query: String) {
        val normalized = query.trim()
        if (normalized.isEmpty()) {
            locationMessage = "Введите адрес доставки"
            return
        }
        keyboardController?.hide()
        geocoder.cancel()
        suggestSession.cancel()
        suggestionsEnabled = false
        suggestions = emptyList()
        onAddress(normalized)
        geocoding = true
        locationMessage = "Ищем адрес в Яндекс Картах…"
        geocoder.searchAddress(
            query = normalized,
            depotLatitude = selectedWarehouse.depotLatitude,
            depotLongitude = selectedWarehouse.depotLongitude,
            onSuccess = { location ->
                geocoding = false
                locationMessage = null
                onConfirmedLocation(location.address, location.latitude, location.longitude)
            },
            onFailure = { message ->
                geocoding = false
                locationMessage = message
            },
        )
    }

    fun resolvePoint(latitude: Double, longitude: Double, zoom: Float, progressMessage: String) {
        geocoder.cancel()
        suggestSession.cancel()
        suggestionsEnabled = false
        suggestions = emptyList()
        onPoint(latitude, longitude)
        geocoding = true
        locationMessage = progressMessage
        geocoder.reversePoint(
            latitude = latitude,
            longitude = longitude,
            zoom = zoom,
            onSuccess = { location ->
                geocoding = false
                locationMessage = null
                onConfirmedLocation(location.address, location.latitude, location.longitude)
            },
            onFailure = { message ->
                geocoding = false
                locationMessage = message
            },
        )
    }

    fun requestCurrentLocation() {
        geocoder.cancel()
        suggestSession.cancel()
        currentLocationProvider.cancel()
        suggestionsEnabled = false
        suggestions = emptyList()
        geocoding = true
        locationMessage = "Определяем ваше местоположение…"
        currentLocationProvider.request(
            onSuccess = { latitude, longitude ->
                resolvePoint(
                    latitude = latitude,
                    longitude = longitude,
                    zoom = CURRENT_LOCATION_GEOCODE_ZOOM,
                    progressMessage = "Определяем адрес вашего местоположения…",
                )
            },
            onFailure = { message ->
                geocoding = false
                locationMessage = null
                locationDialogMessage = message
            },
        )
    }

    val locationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        if (result.values.any { it }) {
            requestCurrentLocation()
        } else {
            locationDialogMessage =
                "Без разрешения на геолокацию приложение не сможет определить текущую точку"
        }
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
        val yandexRecognizer = findYandexSpeechRecognizer(context, intent)
        if (yandexRecognizer == null) {
            showVoiceInstallationDialog = true
            return
        }
        intent.component = yandexRecognizer
        runCatching { voiceLauncher.launch(intent) }
            .onFailure { showVoiceInstallationDialog = true }
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
                resolvePoint(
                    latitude = latitude,
                    longitude = longitude,
                    zoom = zoom,
                    progressMessage = "Определяем адрес выбранной точки…",
                )
            },
            onCurrentLocation = {
                if (shouldRequestDeliveryLocationPermission(hasDeliveryLocationPermission(context))) {
                    locationPermissionLauncher.launch(DELIVERY_LOCATION_PERMISSIONS)
                } else {
                    requestCurrentLocation()
                }
            },
            bottomControlsClearance = addressClearance,
            modifier = Modifier.fillMaxSize(),
        )

        CustomerTopBar(
            title = "Адрес доставки",
            onBack = onBack,
            onProfile = onProfile,
            avatarUrl = state.profile?.avatar?.thumbnailUrl,
        )

        DeliveryAddressPanel(
            address = state.address,
            geocoding = geocoding,
            status = locationMessage,
            suggestions = suggestions,
            continueEnabled = canContinue,
            onAddress = { address ->
                geocoder.cancel()
                suggestSession.cancel()
                suggestionsEnabled = true
                suggestions = emptyList()
                geocoding = false
                locationMessage = null
                onAddress(address)
            },
            onSearch = ::resolveAddress,
            onSuggestion = { suggestion ->
                keyboardController?.hide()
                suggestionsEnabled = false
                suggestions = emptyList()
                val latitude = suggestion.latitude
                val longitude = suggestion.longitude
                if (latitude != null && longitude != null) {
                    onAddress(suggestion.displayText)
                    resolvePoint(
                        latitude = latitude,
                        longitude = longitude,
                        zoom = CURRENT_LOCATION_GEOCODE_ZOOM,
                        progressMessage = "Проверяем выбранный адрес…",
                    )
                } else {
                    resolveAddress(suggestion.searchText)
                }
            },
            onVoice = ::startVoiceInput,
            onContinue = {
                keyboardController?.hide()
                showResponsibilityDialog = true
            },
            modifier = Modifier.align(Alignment.BottomCenter).imePadding()
                .onSizeChanged { addressPanelHeight = it.height },
        )
        if (state.busy && awaitingSlotGeneration != null) {
            DeliverySlotCalculationOverlay()
        }
        if (showResponsibilityDialog) {
            DeliveryResponsibilityDialog(
                selectedCabinCount = state.selectedCabinIds.size,
                siteCabinCapacity = state.siteCabinCapacity,
                privateSiteAccessConfirmed = state.privateSiteAccessConfirmed,
                failedTripChargeAcknowledged = state.failedTripChargeAcknowledged,
                busy = state.busy,
                onSiteCabinCapacity = onSiteCabinCapacity,
                onPrivateSiteAccess = onPrivateSiteAccess,
                onFailedTripAcknowledgement = onFailedTripAcknowledgement,
                onDismiss = { showResponsibilityDialog = false },
                onConfirm = {
                    showResponsibilityDialog = false
                    awaitingSlotGeneration = state.slotSearchGeneration
                    onSearchSlots()
                },
            )
        }
        if (showVoiceInstallationDialog) {
            VoiceInputInstallationDialog(onDismiss = { showVoiceInstallationDialog = false })
        }
        locationDialogMessage?.let { message ->
            DeliveryLocationUnavailableDialog(
                message = message,
                onDismiss = { locationDialogMessage = null },
            )
        }
    }

}

/** Blocks repeated input while logistics recalculates authoritative delivery availability. */
@Composable
internal fun DeliverySlotCalculationOverlay() {
    Dialog(
        onDismissRequest = {},
        properties = DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        Surface(
            modifier = Modifier.fillMaxSize().testTag("delivery-slot-calculation-overlay"),
            color = CustomerStoreNavy.copy(alpha = 0.38f),
        ) {
            Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                Surface(
                    modifier = Modifier.fillMaxWidth().widthIn(max = 420.dp),
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                ) {
                    Column(
                        modifier = Modifier.padding(28.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(20.dp),
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(40.dp).testTag("delivery-slot-calculation-progress"),
                            color = MaterialTheme.colorScheme.primary,
                            strokeWidth = 3.dp,
                        )
                        Text("Идёт расчёт свободных слотов", style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
        }
    }
}

/** Second delivery step that exposes server-returned dates and expandable slot previews. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeliveryDatesScreen(
    state: CustomerWorkflowState,
    onBack: () -> Unit,
    onDate: (String) -> Unit,
    onProfile: (() -> Unit)? = null,
) {
    val dates = remember(state.slots) { DeliverySlotPolicy.byDate(state.slots) }
    var expandedDate by rememberSaveable { mutableStateOf<String?>(null) }
    Scaffold(
        topBar = {
            CustomerTopBar(
                "Дата доставки",
                onBack = onBack,
                onProfile = onProfile,
                avatarUrl = state.profile?.avatar?.thumbnailUrl,
            )
        },
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
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("Выберите удобный день", style = MaterialTheme.typography.headlineSmall)
                    Text(
                        "Учли маршрут до вашего адреса и занятость машин.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.testTag("delivery-date-explanation"),
                    )
                    val deliveryPrice = DeliverySlotPolicy.deliveryPriceRubles(state.slots)
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surface,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.LocalShipping, contentDescription = null, modifier = Modifier.size(22.dp))
                            Spacer(Modifier.width(10.dp))
                            Text(
                                text = deliveryPrice?.let { "Стоимость доставки: ${CustomerMoneyFormatter.wholeRubles(it)}" }
                                    ?: CustomerMoneyFormatter.wholeRubles(null),
                                style = MaterialTheme.typography.titleMedium,
                                color = if (deliveryPrice == null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.testTag("delivery-price"),
                            )
                        }
                    }
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
    onProfile: (() -> Unit)? = null,
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
        topBar = {
            CustomerTopBar(
                "Время доставки",
                onBack = onBack,
                onProfile = onProfile,
                avatarUrl = state.profile?.avatar?.thumbnailUrl,
            )
        },
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
                    Text(formatDeliveryDate(date), style = MaterialTheme.typography.titleMedium)
                    Text(
                        state.address,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(slots, key = DeliverySlot::slotId) { slot ->
                OutlinedCard(
                    shape = RoundedCornerShape(16.dp),
                    onClick = { onSelectSlot(slot.slotId) },
                    enabled = !state.busy,
                    border = BorderStroke(
                        if (selected?.slotId == slot.slotId) 2.dp else 1.dp,
                        if (selected?.slotId == slot.slotId) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.outlineVariant,
                    ),
                    colors = CardDefaults.outlinedCardColors(
                        containerColor = if (selected?.slotId == slot.slotId) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.surface
                        },
                    ),
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
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                deliverySlotTimeLabel(slot),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                CustomerMoneyFormatter.wholeRubles(slot.deliveryPriceRubles),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
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
                    enabled = selected != null &&
                        state.privateSiteAccessConfirmed &&
                        state.failedTripChargeAcknowledged &&
                        !state.busy,
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
    onCheckout: () -> Unit,
    onProfile: (() -> Unit)? = null,
) {
    val held = state.heldSlot?.slot
    Scaffold(
        topBar = {
            CustomerTopBar(
                "Подтверждение",
                onBack = onBack,
                onProfile = onProfile,
                avatarUrl = state.profile?.avatar?.thumbnailUrl,
            )
        },
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
                        modifier = Modifier.fillMaxWidth().widthIn(max = 760.dp).padding(16.dp),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
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
                    Column(
                        Modifier.fillMaxWidth().widthIn(max = 760.dp).padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text("Проверьте заказ", style = MaterialTheme.typography.headlineSmall)
                        Text("Адрес, время доставки и срок аренды.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth().widthIn(max = 760.dp).padding(horizontal = 16.dp),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
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
                                deliverySlotTimeLabel(held),
                            )
                            ConfirmationFact("Бытовки", state.selectedCabinIds.size.toString())
                            ConfirmationFact(
                                "Проезд к объекту",
                                if (held.siteCabinCapacity == 2) {
                                    "До 2 бытовок за один приезд"
                                } else {
                                    "По 1 бытовке за один приезд"
                                },
                            )
                            ConfirmationFact(
                                "Стоимость доставки",
                                CustomerMoneyFormatter.wholeRubles(held.deliveryPriceRubles),
                            )
                        }
                    }
                }
                item {
                    OutlinedCard(
                        modifier = Modifier.fillMaxWidth().widthIn(max = 760.dp).padding(horizontal = 16.dp),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surface),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    ) {
                        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("Срок аренды по бытовкам", style = MaterialTheme.typography.titleMedium)
                            val cartCabins = state.cart?.cabins.orEmpty().ifEmpty {
                                state.cabins.filter { it.unitId in state.selectedCabinIds }
                            }
                            if (cartCabins.isNotEmpty()) {
                                cartCabins.forEach { cabin ->
                                    Text("№ ${cabin.accountingNo} — ${state.rentalTerms[cabin.unitId] ?: 1L} мес.")
                                }
                            } else {
                                state.selectedCabinIds.groupBy { state.rentalTerms[it] ?: 1L }
                                    .toSortedMap().forEach { (months, cabinIds) ->
                                        Text("${cabinIds.size} шт. · $months мес.")
                                    }
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
                        enabled = !state.busy && state.selectedCabinIds.all { cabinId ->
                            (state.rentalTerms[cabinId] ?: 1L) in 1L..120L
                        },
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
    geocoding: Boolean,
    status: String?,
    suggestions: List<DeliveryAddressSuggestion>,
    continueEnabled: Boolean,
    onAddress: (String) -> Unit,
    onSearch: (String) -> Unit,
    onSuggestion: (DeliveryAddressSuggestion) -> Unit,
    onVoice: () -> Unit,
    onContinue: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth().widthIn(max = 760.dp).navigationBarsPadding().padding(16.dp)
            .figmaButtonShadow(RoundedCornerShape(16.dp)),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            Modifier.padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (suggestions.isNotEmpty()) {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 240.dp)
                        .testTag("delivery-address-suggestions"),
                ) {
                    items(
                        items = suggestions,
                        key = { suggestion ->
                            listOf(
                                suggestion.searchText,
                                suggestion.latitude?.toString().orEmpty(),
                                suggestion.longitude?.toString().orEmpty(),
                            ).joinToString("|")
                        },
                    ) { suggestion ->
                        Surface(
                            onClick = { onSuggestion(suggestion) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("delivery-address-suggestion"),
                            color = MaterialTheme.colorScheme.surface,
                        ) {
                            Text(
                                suggestion.displayText,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 11.dp),
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextField(
                    value = address,
                    onValueChange = onAddress,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("delivery-address-field"),
                    placeholder = { Text("Поиск адреса") },
                    leadingIcon = {
                        IconButton(onClick = onVoice, enabled = !geocoding) {
                            Icon(Icons.Default.Mic, contentDescription = "Голосовой ввод адреса")
                        }
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { onSearch(address) }),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                        disabledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        disabledIndicatorColor = Color.Transparent,
                    ),
                )
                Button(
                    onClick = onContinue,
                    enabled = continueEnabled,
                    modifier = Modifier.size(56.dp).testTag("delivery-map-continue"),
                    contentPadding = PaddingValues(0.dp),
                ) {
                    Icon(Icons.Default.ChevronRight, contentDescription = "Продолжить к выбору даты")
                }
            }
            if (status != null) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    if (geocoding) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(6.dp))
                    }
                    Text(
                        status,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/** Collects receiving capacity and delivery responsibility before slot calculation starts. */
@Composable
internal fun DeliveryResponsibilityDialog(
    selectedCabinCount: Int,
    siteCabinCapacity: Int,
    privateSiteAccessConfirmed: Boolean,
    failedTripChargeAcknowledged: Boolean,
    busy: Boolean,
    onSiteCabinCapacity: (Int) -> Unit,
    onPrivateSiteAccess: (Boolean) -> Unit,
    onFailedTripAcknowledgement: (Boolean) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    require(selectedCabinCount > 0)
    require(siteCabinCapacity in 1..2)
    val vehicleCopy = if (siteCabinCapacity == 2) {
        "Машина с прицепом проедет к адресу и сможет работать на объекте"
    } else {
        "Машина без прицепа проедет к адресу и сможет работать на объекте"
    }
    Dialog(
        onDismissRequest = { if (!busy) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth().widthIn(max = 560.dp).padding(20.dp).heightIn(max = 700.dp),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Условия доставки", modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                    IconButton(onClick = onDismiss, enabled = !busy) {
                        Icon(Icons.Default.Close, contentDescription = "Закрыть условия доставки")
                    }
                }
                Column(
                    modifier = Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Text("Сколько бытовок объект может принять за один заезд?", style = MaterialTheme.typography.bodyLarge)
                    if (selectedCabinCount == 1) {
                        Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                            Text(
                                "1 бытовка — машина без прицепа",
                                modifier = Modifier.fillMaxWidth().padding(14.dp).testTag("site-cabin-capacity-fixed"),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    } else {
                        Row(
                            modifier = Modifier.fillMaxWidth().testTag("site-cabin-capacity-selector"),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            (1..2).forEach { capacity ->
                                val selected = capacity == siteCabinCapacity
                                Surface(
                                    onClick = { onSiteCabinCapacity(capacity) },
                                    enabled = !busy,
                                    modifier = Modifier.weight(1f).testTag("site-cabin-capacity-$capacity"),
                                    shape = RoundedCornerShape(12.dp),
                                    color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                                    border = BorderStroke(
                                        if (selected) 2.dp else 1.dp,
                                        if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                                    ),
                                ) {
                                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Text(if (capacity == 1) "1 бытовка" else "2 бытовки", style = MaterialTheme.typography.titleMedium)
                                        Text(if (capacity == 1) "Без прицепа" else "С прицепом", style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                            }
                        }
                    }
                    DeliveryConsentRow(
                        checked = privateSiteAccessConfirmed,
                        enabled = !busy,
                        text = vehicleCopy,
                        tag = "private-site-access-confirmation",
                        onChecked = onPrivateSiteAccess,
                    )
                    DeliveryConsentRow(
                        checked = failedTripChargeAcknowledged,
                        enabled = !busy,
                        text = "Подтверждаю ответственность за ложные сведения о проезде; тариф определяется договором",
                        tag = "failed-trip-charge-acknowledgement",
                        onChecked = onFailedTripAcknowledgement,
                    )
                }
                Button(
                    onClick = onConfirm,
                    enabled = privateSiteAccessConfirmed && failedTripChargeAcknowledged && !busy,
                    modifier = Modifier.fillMaxWidth().testTag("confirm-delivery-responsibility"),
                ) {
                    Text("Выбрать дату доставки")
                }
            }
        }
    }
}

@Composable
private fun DeliveryConsentRow(
    checked: Boolean,
    enabled: Boolean,
    text: String,
    tag: String,
    onChecked: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .toggleable(value = checked, enabled = enabled, role = Role.Checkbox, onValueChange = onChecked)
            .testTag(tag),
        verticalAlignment = Alignment.Top,
    ) {
        Checkbox(checked = checked, onCheckedChange = null, enabled = enabled)
        Text(text, modifier = Modifier.padding(start = 10.dp, top = 2.dp), style = MaterialTheme.typography.bodyMedium)
    }
}

/** Explains that the required Yandex-owned recognizer is absent instead of using another provider. */
@Composable
internal fun VoiceInputInstallationDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Установить голосовой ввод.") },
        text = { Text("Для голосового адреса нужен установленный голосовой ввод Яндекса.") },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Понятно") } },
    )
}

/** Shows an explicit permission or provider failure for the current-location action. */
@Composable
internal fun DeliveryLocationUnavailableDialog(message: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Не удалось определить местоположение") },
        text = { Text(message) },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Понятно") } },
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
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier
            .fillMaxWidth()
            .widthIn(max = 760.dp)
            .padding(horizontal = 16.dp),
    ) {
        Column {
            Row(
                Modifier.fillMaxWidth().padding(start = 16.dp, top = 14.dp, bottom = 14.dp),
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
                            Text(deliverySlotTimeLabel(slot))
                        }
                    }
                }
            }
            Button(
                onClick = onSelect,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, bottom = 12.dp)
                    .testTag("delivery-date-${availability.date}"),
            ) { Text("Выбрать дату") }
        }
    }
}

@Composable
private fun ConfirmationFact(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyLarge)
    }
}

internal fun formatDeliveryDate(isoDate: String): String = runCatching {
    LocalDate.parse(isoDate).format(DateTimeFormatter.ofPattern("d MMMM, EEEE", Locale.forLanguageTag("ru")))
}.getOrDefault(isoDate)

private fun formatSlotTime(value: String): String = value.take(5)

/** Renders the customer-facing promise without disguising a flexible arrival as a fixed window. */
internal fun deliverySlotTimeLabel(slot: DeliverySlot): String =
    if (slot.kind == DeliverySlotKind.DURING_DAY) {
        "В течение дня. Точное время подтвердит логист"
    } else {
        "${formatSlotTime(slot.start)}–${formatSlotTime(slot.end)}"
    }

/** Resolves only an installed Yandex speech activity, never the platform's arbitrary default recognizer. */
internal fun findYandexSpeechRecognizer(context: Context, intent: Intent): ComponentName? =
    context.packageManager.queryIntentActivities(intent, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)
        .asSequence()
        .mapNotNull { resolveInfo -> resolveInfo.activityInfo }
        .filter { activity -> isYandexSpeechRecognizerPackage(activity.packageName) }
        .sortedBy { activity -> activity.packageName }
        .map { activity -> ComponentName(activity.packageName, activity.name) }
        .firstOrNull()

/** Accepts Yandex-owned recognizer packages and rejects arbitrary system or Google providers. */
internal fun isYandexSpeechRecognizerPackage(packageName: String): Boolean =
    packageName.lowercase(Locale.ROOT).let { normalized ->
        normalized.startsWith("ru.yandex.") || normalized.startsWith("com.yandex.")
    }

/** Returns whether Android already granted either precise or approximate delivery location access. */
internal fun hasDeliveryLocationPermission(context: Context): Boolean = DELIVERY_LOCATION_PERMISSIONS.any { permission ->
    ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
}

/** Keeps the Android permission prompt behind the explicit current-location arrow action. */
internal fun shouldRequestDeliveryLocationPermission(permissionGranted: Boolean): Boolean = !permissionGranted

private const val ADDRESS_SUGGEST_DEBOUNCE_MILLIS = 250L
private const val CURRENT_LOCATION_GEOCODE_ZOOM = 16f
private val DELIVERY_LOCATION_PERMISSIONS = arrayOf(
    Manifest.permission.ACCESS_FINE_LOCATION,
    Manifest.permission.ACCESS_COARSE_LOCATION,
)
