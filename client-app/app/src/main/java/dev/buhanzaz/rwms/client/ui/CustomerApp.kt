package dev.buhanzaz.rwms.client.ui

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apartment
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.HomeWork
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.Icon
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

/** Free-cabin catalog destination. */
@Serializable
internal data object CatalogRoute : NavKey

/** Current selected cabins/equipment destination. */
@Serializable
internal data object CartRoute : NavKey

/** Full-screen delivery address and map-point destination. */
@Serializable
internal data object DeliveryMapRoute : NavKey

/** Server-returned delivery date destination. */
@Serializable
internal data object DeliveryDatesRoute : NavKey

/** Server-returned slots for one exact delivery date. */
@Serializable
internal data class DeliverySlotsRoute(val date: String) : NavKey

/** Held-slot rental duration and checkout destination. */
@Serializable
internal data object DeliveryConfirmationRoute : NavKey

/** Durable customer booking history destination. */
@Serializable
internal data object BookingsRoute : NavKey

/** Customer profile destination. */
@Serializable
internal data object ProfileRoute : NavKey

/** Full-screen cabin gallery opened at a specific photo. */
@Serializable
internal data class GalleryRoute(val unitId: String, val initialPage: Int) : NavKey

/** Top-level app that binds Hilt state to the testable conditional navigation content. */
@Composable
fun CustomerApp(viewModel: CustomerAppViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val appearanceStore = remember(context.applicationContext) { CustomerAppearanceStore(context) }
    val appearanceMode by appearanceStore.mode.collectAsStateWithLifecycle(CustomerAppearanceMode.LIGHT)
    val appearanceScope = rememberCoroutineScope()
    CustomerTheme(appearanceMode = appearanceMode) {
        CustomerStoreLaunchGate {
            CustomerAppContent(
                state = state,
                onLogin = viewModel::login,
                onRegister = viewModel::register,
                onLogout = viewModel::logout,
                onSaveProfile = viewModel::saveProfile,
                onAvatarSelected = viewModel::uploadProfileAvatar,
                onWarehouse = viewModel::selectWarehouse,
                onEnsureActiveInquiry = viewModel::ensureActiveInquiry,
                onFilters = viewModel::applyFilters,
                onLoadMoreCabins = viewModel::loadMoreCabins,
                onToggleCabin = viewModel::toggleCabin,
                onCabinRentalMonths = viewModel::setCabinRentalMonths,
                onEquipment = viewModel::setEquipment,
                onAddress = viewModel::setAddress,
                onPoint = viewModel::setMapPoint,
                onConfirmedLocation = viewModel::confirmDeliveryLocation,
                onSiteCabinCapacity = viewModel::setSiteCabinCapacity,
                onPrivateSiteAccess = viewModel::setPrivateSiteAccessConfirmed,
                onFailedTripAcknowledgement = viewModel::setFailedTripChargeAcknowledged,
                onSearchSlots = viewModel::searchSlots,
                onSelectSlot = viewModel::selectSlot,
                onHoldSlot = viewModel::holdSelectedSlot,
                onCheckout = viewModel::checkout,
                onCancelBooking = viewModel::cancelBooking,
                onOpenBookingReschedule = viewModel::openBookingReschedule,
                onSelectBookingRescheduleSlot = viewModel::selectBookingRescheduleSlot,
                onConfirmBookingReschedule = viewModel::confirmBookingReschedule,
                onDismissBookingReschedule = viewModel::dismissBookingReschedule,
                onApplyBookingChange = viewModel::applyBookingChange,
                onRefreshBookingChange = { viewModel.refreshBookingChange() },
                onResumeBookingChange = { viewModel.refreshBookingChange(it) },
                onDismissBookingChange = viewModel::dismissBookingChange,
                onCallBookingChangeSupport = { phone ->
                    when (openCustomerSupportDialer(context, phone)) {
                        CustomerSupportDialResult.OPENED -> Unit
                        CustomerSupportDialResult.PHONE_UNAVAILABLE ->
                            viewModel.reportBookingChangeContactError("Телефон менеджера недоступен.")
                        CustomerSupportDialResult.DIALER_UNAVAILABLE ->
                            viewModel.reportBookingChangeContactError("На устройстве нет доступного приложения для звонков.")
                    }
                },
                onAcceptCabin = viewModel::acceptCabin,
                onReportProblem = viewModel::reportCabinProblem,
                onDismissError = viewModel::dismissError,
                appearanceMode = appearanceMode,
                onAppearanceMode = { mode ->
                    appearanceScope.launch { appearanceStore.setMode(mode) }
                },
            )
        }
    }
}

/** Pure app shell used by tests to prove signed-out and signed-in graphs are mutually exclusive. */
@Composable
@Suppress("LongParameterList")
fun CustomerAppContent(
    state: CustomerAppState,
    onLogin: (String, String, Boolean) -> Unit = { _, _, _ -> },
    onRegister: (String, String, String, String, String) -> Unit = { _, _, _, _, _ -> },
    onLogout: () -> Unit = {},
    onSaveProfile: (dev.buhanzaz.rwms.client.data.CustomerProfile) -> Unit = {},
    onAvatarSelected: (android.net.Uri) -> Unit = {},
    onWarehouse: (dev.buhanzaz.rwms.client.data.CustomerWarehouse, Boolean) -> Unit = { _, _ -> },
    onEnsureActiveInquiry: () -> Unit = {},
    onFilters: (dev.buhanzaz.rwms.client.data.CabinFilters) -> Unit = {},
    onLoadMoreCabins: () -> Unit = {},
    onToggleCabin: (String) -> Unit = {},
    onCabinRentalMonths: (String, Long) -> Unit = { _, _ -> },
    onEquipment: (String, dev.buhanzaz.rwms.client.data.AvailableEquipment, Long) -> Unit = { _, _, _ -> },
    onAddress: (String) -> Unit = {},
    onPoint: (Double, Double) -> Unit = { _, _ -> },
    onConfirmedLocation: (String, Double, Double) -> Unit = { _, _, _ -> },
    onSiteCabinCapacity: (Int) -> Unit = {},
    onPrivateSiteAccess: (Boolean) -> Unit = {},
    onFailedTripAcknowledgement: (Boolean) -> Unit = {},
    onSearchSlots: () -> Unit = {},
    onSelectSlot: (String) -> Unit = {},
    onHoldSlot: () -> Unit = {},
    onCheckout: () -> Unit = {},
    onCancelBooking: (String) -> Unit = {},
    onOpenBookingReschedule: (String) -> Unit = {},
    onSelectBookingRescheduleSlot: (String) -> Unit = {},
    onConfirmBookingReschedule: () -> Unit = {},
    onDismissBookingReschedule: () -> Unit = {},
    onApplyBookingChange: (Boolean) -> Unit = {},
    onRefreshBookingChange: () -> Unit = {},
    onResumeBookingChange: (String) -> Unit = {},
    onDismissBookingChange: () -> Unit = {},
    onCallBookingChangeSupport: (String) -> Unit = {},
    onAcceptCabin: (String, String, List<dev.buhanzaz.rwms.client.data.CustomerSignatureStroke>) -> Unit = { _, _, _ -> },
    onReportProblem: (String, String, String, String, List<dev.buhanzaz.rwms.client.data.CustomerEvidenceFile>) -> Unit = { _, _, _, _, _ -> },
    onDismissError: () -> Unit = {},
    appearanceMode: CustomerAppearanceMode = CustomerAppearanceMode.LIGHT,
    onAppearanceMode: (CustomerAppearanceMode) -> Unit = {},
) {
    when (state) {
        CustomerAppState.Loading -> LoadingCustomerScreen("Проверяем безопасную сессию…")
        is CustomerAppState.SignedOut -> CustomerAuthenticationScreen(
            message = state.message,
            submitting = state.submitting,
            onLogin = onLogin,
            onRegister = onRegister,
        )
        is CustomerAppState.Ready -> {
            val workflow = state.workflow
            when {
                workflow.bootstrapping -> LoadingCustomerScreen("Загружаем данные клиента…")
                workflow.profile == null -> ProfileFormScreen(
                    existing = null,
                    busy = workflow.busy,
                    onSave = onSaveProfile,
                    errorMessage = workflow.error,
                    registrationDraft = workflow.registrationProfileDraft,
                )
                workflow.selectedWarehouse == null -> WarehouseScreen(
                    warehouses = workflow.warehouses,
                    busy = workflow.busy,
                    onSelect = onWarehouse,
                    onLogout = onLogout,
                )
                else -> SignedInNavigation(
                    state = workflow,
                    onLogout = onLogout,
                    onSaveProfile = onSaveProfile,
                    onAvatarSelected = onAvatarSelected,
                    onWarehouse = { warehouse ->
                        onWarehouse(warehouse, workflow.rememberWarehouseChoice)
                    },
                    onEnsureActiveInquiry = onEnsureActiveInquiry,
                    onFilters = onFilters,
                    onLoadMoreCabins = onLoadMoreCabins,
                    onToggleCabin = onToggleCabin,
                    onCabinRentalMonths = onCabinRentalMonths,
                    onEquipment = onEquipment,
                    onAddress = onAddress,
                    onPoint = onPoint,
                    onConfirmedLocation = onConfirmedLocation,
                    onSiteCabinCapacity = onSiteCabinCapacity,
                    onPrivateSiteAccess = onPrivateSiteAccess,
                    onFailedTripAcknowledgement = onFailedTripAcknowledgement,
                    onSearchSlots = onSearchSlots,
                    onSelectSlot = onSelectSlot,
                    onHoldSlot = onHoldSlot,
                    onCheckout = onCheckout,
                    onCancelBooking = onCancelBooking,
                    onOpenBookingReschedule = onOpenBookingReschedule,
                    onSelectBookingRescheduleSlot = onSelectBookingRescheduleSlot,
                    onConfirmBookingReschedule = onConfirmBookingReschedule,
                    onDismissBookingReschedule = onDismissBookingReschedule,
                    onApplyBookingChange = onApplyBookingChange,
                    onRefreshBookingChange = onRefreshBookingChange,
                    onResumeBookingChange = onResumeBookingChange,
                    onDismissBookingChange = onDismissBookingChange,
                    onCallBookingChangeSupport = onCallBookingChangeSupport,
                    onAcceptCabin = onAcceptCabin,
                    onReportProblem = onReportProblem,
                    onDismissError = onDismissError,
                    appearanceMode = appearanceMode,
                    onAppearanceMode = onAppearanceMode,
                )
            }
        }
    }
}

@Composable
@Suppress("LongParameterList")
private fun SignedInNavigation(
    state: CustomerWorkflowState,
    onLogout: () -> Unit,
    onSaveProfile: (dev.buhanzaz.rwms.client.data.CustomerProfile) -> Unit,
    onAvatarSelected: (android.net.Uri) -> Unit,
    onWarehouse: (dev.buhanzaz.rwms.client.data.CustomerWarehouse) -> Unit,
    onEnsureActiveInquiry: () -> Unit,
    onFilters: (dev.buhanzaz.rwms.client.data.CabinFilters) -> Unit,
    onLoadMoreCabins: () -> Unit,
    onToggleCabin: (String) -> Unit,
    onCabinRentalMonths: (String, Long) -> Unit,
    onEquipment: (String, dev.buhanzaz.rwms.client.data.AvailableEquipment, Long) -> Unit,
    onAddress: (String) -> Unit,
    onPoint: (Double, Double) -> Unit,
    onConfirmedLocation: (String, Double, Double) -> Unit,
    onSiteCabinCapacity: (Int) -> Unit,
    onPrivateSiteAccess: (Boolean) -> Unit,
    onFailedTripAcknowledgement: (Boolean) -> Unit,
    onSearchSlots: () -> Unit,
    onSelectSlot: (String) -> Unit,
    onHoldSlot: () -> Unit,
    onCheckout: () -> Unit,
    onCancelBooking: (String) -> Unit,
    onOpenBookingReschedule: (String) -> Unit,
    onSelectBookingRescheduleSlot: (String) -> Unit,
    onConfirmBookingReschedule: () -> Unit,
    onDismissBookingReschedule: () -> Unit,
    onApplyBookingChange: (Boolean) -> Unit,
    onRefreshBookingChange: () -> Unit,
    onResumeBookingChange: (String) -> Unit,
    onDismissBookingChange: () -> Unit,
    onCallBookingChangeSupport: (String) -> Unit,
    onAcceptCabin: (String, String, List<dev.buhanzaz.rwms.client.data.CustomerSignatureStroke>) -> Unit,
    onReportProblem: (String, String, String, String, List<dev.buhanzaz.rwms.client.data.CustomerEvidenceFile>) -> Unit,
    onDismissError: () -> Unit,
    appearanceMode: CustomerAppearanceMode,
    onAppearanceMode: (CustomerAppearanceMode) -> Unit,
) {
    val backStack = rememberNavBackStack(CatalogRoute)
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val coroutineScope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val current = backStack.lastOrNull()

    fun topLevel(route: NavKey) {
        backStack.clear()
        backStack.add(route)
        coroutineScope.launch { drawerState.close() }
    }

    LaunchedEffect(state.error, state.bookingChangeDialogVisible) {
        if (state.bookingChangeDialogVisible) return@LaunchedEffect
        state.error?.let { message ->
            snackbar.showSnackbar(message)
            onDismissError()
        }
    }
    LaunchedEffect(state.booking?.status) {
        if (CustomerBookingPolicy.locksCart(state.booking)) {
            topLevel(BookingsRoute)
        }
    }
    LaunchedEffect(state.bookingChangeDialogVisible) {
        if (state.bookingChangeDialogVisible) topLevel(BookingsRoute)
    }
    LaunchedEffect(current, state.booking?.status, state.inquiryId) {
        if (current is CatalogRoute || current is CartRoute) onEnsureActiveInquiry()
    }
    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = !current.isDeliveryFlowRoute(),
        drawerContent = {
            ModalDrawerSheet(modifier = Modifier.fillMaxHeight()) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState()),
                ) {
                    Text("RWMS Клиент", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(24.dp))
                    NavigationDrawerItem(
                        label = { Text("Аренда") },
                        selected = current is CatalogRoute,
                        onClick = { topLevel(CatalogRoute) },
                        icon = { Icon(Icons.Default.HomeWork, contentDescription = null) },
                    )
                    NavigationDrawerItem(
                        label = { Text("Корзина (${state.selectedCabinIds.size})") },
                        selected = current is CartRoute || current.isDeliveryFlowRoute(),
                        onClick = { topLevel(CartRoute) },
                        icon = { Icon(Icons.Default.ShoppingCart, contentDescription = null) },
                    )
                    NavigationDrawerItem(
                        label = { Text("Мои заказы") },
                        selected = current is BookingsRoute,
                        onClick = { topLevel(BookingsRoute) },
                        icon = { Icon(Icons.Default.Book, contentDescription = null) },
                    )
                    NavigationDrawerItem(
                        label = { Text("Профиль") },
                        selected = current is ProfileRoute,
                        onClick = { topLevel(ProfileRoute) },
                        icon = { Icon(Icons.Default.Person, contentDescription = null) },
                    )
                    NavigationDrawerItem(
                        label = { Text("Доступ для юрлиц") },
                        selected = false,
                        onClick = {
                            coroutineScope.launch {
                                drawerState.close()
                                snackbar.showSnackbar("Доступ для юридических лиц появится позже")
                            }
                        },
                        icon = { Icon(Icons.Default.Apartment, contentDescription = null) },
                        modifier = Modifier.testTag("legal-entity-access-placeholder"),
                    )
                    HorizontalDivider(Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                    val nextAppearanceMode = appearanceMode.toggle()
                    NavigationDrawerItem(
                        label = { Text(appearanceMode.toggleActionTitle) },
                        selected = false,
                        onClick = { onAppearanceMode(nextAppearanceMode) },
                        icon = { Icon(nextAppearanceMode.appearanceIcon(), contentDescription = null) },
                        modifier = Modifier.testTag("appearance-toggle"),
                    )
                    NavigationDrawerItem(label = { Text("Выйти") }, selected = false, onClick = onLogout)
                }
            }
        },
    ) {
        CustomerAdaptiveNavigation(
            current = current,
            cartCount = state.selectedCabinIds.size,
            hideTopLevelNavigation = current.isDeliveryFlowRoute(),
            onCatalog = { topLevel(CatalogRoute) },
            onCart = { topLevel(CartRoute) },
            onBookings = { topLevel(BookingsRoute) },
        ) {
            Box(Modifier.fillMaxSize()) {
                NavDisplay(
                    backStack = backStack,
                    onBack = { backStack.removeLastOrNull() },
                    entryDecorators = listOf(
                        rememberSaveableStateHolderNavEntryDecorator(),
                        rememberViewModelStoreNavEntryDecorator(),
                    ),
                    entryProvider = entryProvider {
                        entry<CatalogRoute> {
                            CabinCatalogScreen(
                                state = state,
                                onMenu = { coroutineScope.launch { drawerState.open() } },
                                onProfile = { topLevel(ProfileRoute) },
                                onWarehouse = onWarehouse,
                                onFilters = onFilters,
                                onLoadMore = onLoadMoreCabins,
                                onToggleCabin = onToggleCabin,
                                onEquipment = onEquipment,
                                onPhoto = { unitId, page -> backStack.add(GalleryRoute(unitId, page)) },
                                avatarUrl = state.profile?.avatar?.thumbnailUrl,
                            )
                        }
                        entry<CartRoute> {
                            CartScreen(
                                state = state,
                                onMenu = { coroutineScope.launch { drawerState.open() } },
                                onProfile = { topLevel(ProfileRoute) },
                                onContinue = {
                                    if (backStack.lastOrNull() !is DeliveryMapRoute) {
                                        backStack.add(DeliveryMapRoute)
                                    }
                                },
                                onToggleCabin = onToggleCabin,
                                onCabinRentalMonths = onCabinRentalMonths,
                                onEquipment = onEquipment,
                            )
                        }
                        entry<DeliveryMapRoute> {
                            DeliveryMapScreen(
                                state = state,
                                onBack = { backStack.removeLastOrNull() },
                                onAddress = onAddress,
                                onPoint = onPoint,
                                onConfirmedLocation = onConfirmedLocation,
                                onSiteCabinCapacity = onSiteCabinCapacity,
                                onPrivateSiteAccess = onPrivateSiteAccess,
                                onFailedTripAcknowledgement = onFailedTripAcknowledgement,
                                onSearchSlots = onSearchSlots,
                                onSlotsReady = {
                                    if (backStack.lastOrNull() !is DeliveryDatesRoute) {
                                        backStack.add(DeliveryDatesRoute)
                                    }
                                },
                            )
                        }
                        entry<DeliveryDatesRoute> {
                            DeliveryDatesScreen(
                                state = state,
                                onBack = { backStack.removeLastOrNull() },
                                onDate = { date ->
                                    val route = DeliverySlotsRoute(date)
                                    if (backStack.lastOrNull() != route) backStack.add(route)
                                },
                            )
                        }
                        entry<DeliverySlotsRoute> { route ->
                            DeliverySlotsScreen(
                                state = state,
                                date = route.date,
                                onBack = { backStack.removeLastOrNull() },
                                onSelectSlot = onSelectSlot,
                                onHoldSlot = onHoldSlot,
                                onHeld = {
                                    if (backStack.lastOrNull() !is DeliveryConfirmationRoute) {
                                        backStack.add(DeliveryConfirmationRoute)
                                    }
                                },
                            )
                        }
                        entry<DeliveryConfirmationRoute> {
                            DeliveryConfirmationScreen(
                                state = state,
                                onBack = { backStack.removeLastOrNull() },
                                onCheckout = onCheckout,
                            )
                        }
                        entry<BookingsRoute> {
                            BookingsScreen(
                                bookings = state.bookings,
                                latest = state.booking,
                                busy = state.busy,
                                onMenu = { coroutineScope.launch { drawerState.open() } },
                                onProfile = { topLevel(ProfileRoute) },
                                rescheduleBookingId = state.bookingRescheduleId,
                                rescheduleSlots = state.bookingRescheduleSlots,
                                selectedRescheduleSlotId = state.selectedBookingRescheduleSlotId,
                                onCancel = onCancelBooking,
                                onOpenReschedule = onOpenBookingReschedule,
                                onSelectRescheduleSlot = onSelectBookingRescheduleSlot,
                                onConfirmReschedule = onConfirmBookingReschedule,
                                onDismissReschedule = onDismissBookingReschedule,
                                changeQuote = state.bookingChangeQuote,
                                changeDialogVisible = state.bookingChangeDialogVisible,
                                changeNeedsRefresh = state.bookingChangeNeedsRefresh,
                                changeError = state.error,
                                changeUnavailableReason = state.bookingChangeUnavailableReason,
                                onApplyChange = onApplyBookingChange,
                                onRefreshChange = onRefreshBookingChange,
                                pendingChangeBookingIds = state.bookingChangeReferences.keys,
                                onResumeChange = onResumeBookingChange,
                                onDismissChange = onDismissBookingChange,
                                onCallChangeSupport = onCallBookingChangeSupport,
                                onAccept = onAcceptCabin,
                                onReport = onReportProblem,
                            )
                        }
                        entry<ProfileRoute> {
                            ProfileFormScreen(
                                existing = state.profile,
                                busy = state.busy,
                                onSave = onSaveProfile,
                                onAvatarSelected = onAvatarSelected,
                                onBack = { topLevel(CatalogRoute) },
                            )
                        }
                        entry<GalleryRoute> { route ->
                            FullscreenCabinGallery(
                                cabin = state.cabins.firstOrNull { it.unitId == route.unitId }
                                    ?: state.cart?.cabins?.firstOrNull { it.unitId == route.unitId },
                                initialPage = route.initialPage,
                                onClose = { backStack.removeLastOrNull() },
                            )
                        }
                    },
                )
                SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
                if (shouldShowGlobalBusyOverlay(state.busy, current)) {
                    Box(
                        Modifier.fillMaxSize().testTag("global-busy-overlay"),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator()
                    }
                }
            }
        }
    }
}

@Composable
private fun CustomerAdaptiveNavigation(
    current: NavKey?,
    cartCount: Int,
    hideTopLevelNavigation: Boolean,
    onCatalog: () -> Unit,
    onCart: () -> Unit,
    onBookings: () -> Unit,
    content: @Composable () -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        if (hideTopLevelNavigation) {
            content()
            return@BoxWithConstraints
        }
        val expanded = maxWidth >= 840.dp
        if (expanded) {
            Row(Modifier.fillMaxSize()) {
                NavigationRail(modifier = Modifier.fillMaxHeight()) {
                    NavigationRailItem(
                        selected = current is CatalogRoute,
                        onClick = onCatalog,
                        icon = { Icon(Icons.Default.HomeWork, contentDescription = "Аренда") },
                        label = { Text("Аренда") },
                    )
                    NavigationRailItem(
                        selected = current is CartRoute || current.isDeliveryFlowRoute(),
                        onClick = onCart,
                        icon = { Icon(Icons.Default.ShoppingCart, contentDescription = "Корзина, $cartCount") },
                        label = { Text("Корзина") },
                    )
                    NavigationRailItem(
                        selected = current is BookingsRoute,
                        onClick = onBookings,
                        icon = { Icon(Icons.Default.Book, contentDescription = "Заказы") },
                        label = { Text("Заказы") },
                    )
                }
                Box(Modifier.weight(1f)) { content() }
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f)) { content() }
                NavigationBar {
                    NavigationBarItem(
                        selected = current is CatalogRoute,
                        onClick = onCatalog,
                        icon = { Icon(Icons.Default.HomeWork, contentDescription = null) },
                        label = { Text("Аренда") },
                    )
                    NavigationBarItem(
                        selected = current is CartRoute || current.isDeliveryFlowRoute(),
                        onClick = onCart,
                        icon = { Icon(Icons.Default.ShoppingCart, contentDescription = null) },
                        label = { Text("Корзина $cartCount") },
                    )
                    NavigationBarItem(
                        selected = current is BookingsRoute,
                        onClick = onBookings,
                        icon = { Icon(Icons.Default.Book, contentDescription = null) },
                        label = { Text("Заказы") },
                    )
                }
            }
        }
    }
}

private fun NavKey?.isDeliveryFlowRoute(): Boolean =
    this is DeliveryMapRoute ||
        this is DeliveryDatesRoute ||
        this is DeliverySlotsRoute ||
        this is DeliveryConfirmationRoute

internal fun shouldShowGlobalBusyOverlay(isBusy: Boolean, current: NavKey?): Boolean =
    isBusy && current !is DeliveryMapRoute

private fun CustomerAppearanceMode.appearanceIcon(): ImageVector = when (this) {
    CustomerAppearanceMode.LIGHT -> Icons.Default.LightMode
    CustomerAppearanceMode.DARK -> Icons.Default.DarkMode
}

@Composable
private fun LoadingCustomerScreen(message: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Text(message, modifier = Modifier.padding(16.dp))
        }
    }
}
