package dev.buhanzaz.rwms.client.ui

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apartment
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.HomeWork
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
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
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

/** Initial city selection retains access to the signed-in drawer and profile. */
@Serializable
internal data object WarehouseRoute : NavKey

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
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    val signedIn = state is CustomerAppState.Ready
    LaunchedEffect(viewModel, lifecycle, signedIn) {
        if (signedIn) lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
            while (true) {
                viewModel.refreshCustomerUpdates()
                kotlinx.coroutines.delay(5_000)
            }
        }
    }
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
                onConfirmInitialPayment = viewModel::confirmInitialPayment,
                onReadNotification = viewModel::readNotification,
                onRefreshUpdates = viewModel::refreshCustomerUpdates,
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
    onConfirmInitialPayment: (String) -> Unit = {},
    onReadNotification: (String) -> Unit = {},
    onRefreshUpdates: () -> Unit = {},
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
                else -> SignedInNavigation(
                    state = workflow,
                    onLogout = onLogout,
                    onSaveProfile = onSaveProfile,
                    onAvatarSelected = onAvatarSelected,
                    onWarehouse = onWarehouse,
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
                    onConfirmInitialPayment = onConfirmInitialPayment,
                    onReadNotification = onReadNotification,
                    onRefreshUpdates = onRefreshUpdates,
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
    onWarehouse: (dev.buhanzaz.rwms.client.data.CustomerWarehouse, Boolean) -> Unit,
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
    onConfirmInitialPayment: (String) -> Unit,
    onReadNotification: (String) -> Unit,
    onRefreshUpdates: () -> Unit,
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
    val backStack = rememberNavBackStack(if (state.selectedWarehouse == null) WarehouseRoute else CatalogRoute)
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val coroutineScope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val current = backStack.lastOrNull()

    fun topLevel(route: NavKey) {
        backStack.clear()
        backStack.add(
            if (state.selectedWarehouse == null && (route is CatalogRoute || route is CartRoute)) WarehouseRoute else route,
        )
        coroutineScope.launch { drawerState.close() }
    }

    fun openProfile() {
        if (backStack.lastOrNull() !is ProfileRoute) backStack.add(ProfileRoute)
    }

    LaunchedEffect(state.selectedWarehouse?.id) {
        if (state.selectedWarehouse != null && backStack.lastOrNull() is WarehouseRoute) topLevel(CatalogRoute)
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
            ModalDrawerSheet(
                modifier = Modifier.fillMaxHeight(),
                drawerContainerColor = MaterialTheme.colorScheme.surface,
                drawerShape = RoundedCornerShape(topEnd = 16.dp, bottomEnd = 16.dp),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState()),
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 16.dp, bottom = 20.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CustomerStoreLogo(Modifier.width(180.dp))
                        Spacer(Modifier.weight(1f))
                        IconButton(onClick = { coroutineScope.launch { drawerState.close() } }) {
                            Icon(Icons.Default.Close, contentDescription = "Закрыть меню")
                        }
                    }
                    NavigationDrawerItem(
                        shape = RoundedCornerShape(12.dp),
                        label = { Text("Аренда") },
                        selected = current is CatalogRoute,
                        onClick = { topLevel(CatalogRoute) },
                        icon = { Icon(Icons.Default.HomeWork, contentDescription = null) },
                    )
                    NavigationDrawerItem(
                        shape = RoundedCornerShape(12.dp),
                        label = { Text("Корзина (${state.selectedCabinIds.size})") },
                        selected = current is CartRoute || current.isDeliveryFlowRoute(),
                        onClick = { topLevel(CartRoute) },
                        icon = { Icon(Icons.Default.ShoppingCart, contentDescription = null) },
                    )
                    NavigationDrawerItem(
                        shape = RoundedCornerShape(12.dp),
                        label = { Text("Мои заказы") },
                        selected = current is BookingsRoute,
                        onClick = { topLevel(BookingsRoute) },
                        icon = { Icon(Icons.Default.Book, contentDescription = null) },
                    )
                    NavigationDrawerItem(
                        shape = RoundedCornerShape(12.dp),
                        label = { Text("Профиль") },
                        selected = current is ProfileRoute,
                        onClick = { topLevel(ProfileRoute) },
                        icon = { Icon(Icons.Default.Person, contentDescription = null) },
                    )
                    NavigationDrawerItem(
                        shape = RoundedCornerShape(12.dp),
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
                        shape = RoundedCornerShape(12.dp),
                        label = { Text(appearanceMode.toggleActionTitle) },
                        selected = false,
                        onClick = { onAppearanceMode(nextAppearanceMode) },
                        icon = { Icon(nextAppearanceMode.appearanceIcon(), contentDescription = null) },
                        modifier = Modifier.testTag("appearance-toggle"),
                    )
                    NavigationDrawerItem(label = { Text("Выйти") }, selected = false, onClick = onLogout, shape = RoundedCornerShape(12.dp))
                }
            }
        },
    ) {
        Box(Modifier.fillMaxSize()) {
            NavDisplay(
                backStack = backStack,
                onBack = { if (backStack.size > 1) backStack.removeLastOrNull() },
                transitionSpec = {
                    (slideInHorizontally(tween(260)) { it / 6 } + fadeIn(tween(220))) togetherWith
                        (slideOutHorizontally(tween(260)) { -it / 10 } + fadeOut(tween(160)))
                },
                popTransitionSpec = {
                    (slideInHorizontally(tween(260)) { -it / 10 } + fadeIn(tween(220))) togetherWith
                        (slideOutHorizontally(tween(260)) { it / 6 } + fadeOut(tween(160)))
                },
                predictivePopTransitionSpec = {
                    slideInHorizontally { -it / 6 } togetherWith slideOutHorizontally { it }
                },
                entryDecorators = listOf(
                    rememberSaveableStateHolderNavEntryDecorator(),
                    rememberViewModelStoreNavEntryDecorator(),
                ),
                entryProvider = entryProvider {
                    entry<WarehouseRoute> {
                        WarehouseScreen(
                            warehouses = state.warehouses,
                            busy = state.busy,
                            onSelect = onWarehouse,
                            onMenu = { coroutineScope.launch { drawerState.open() } },
                            onProfile = ::openProfile,
                            avatarUrl = state.profile?.avatar?.thumbnailUrl,
                        )
                    }
                    entry<CatalogRoute> {
                        CabinCatalogScreen(
                            state = state,
                            onMenu = { coroutineScope.launch { drawerState.open() } },
                            onProfile = ::openProfile,
                            onWarehouse = { onWarehouse(it, state.rememberWarehouseChoice) },
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
                            onProfile = ::openProfile,
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
                            onProfile = ::openProfile,
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
                            onProfile = ::openProfile,
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
                            onProfile = ::openProfile,
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
                            onProfile = ::openProfile,
                            onBack = { backStack.removeLastOrNull() },
                            onCheckout = onCheckout,
                        )
                    }
                    entry<BookingsRoute> {
                        BookingsScreen(
                            bookings = state.bookings,
                            payments = state.payments,
                            avatarUrl = state.profile?.avatar?.thumbnailUrl,
                            paymentErrors = state.paymentErrors,
                            notifications = state.notifications,
                            updatesError = state.updatesError,
                            onConfirmInitialPayment = onConfirmInitialPayment,
                            onReadNotification = onReadNotification,
                            onRefreshUpdates = onRefreshUpdates,
                            latest = state.booking,
                            busy = state.busy,
                            onMenu = { coroutineScope.launch { drawerState.open() } },
                            onProfile = ::openProfile,
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
                            onBack = {
                                if (backStack.size > 1) backStack.removeLastOrNull() else topLevel(CatalogRoute)
                            },
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
            if (state.selectedCabinIds.isNotEmpty() &&
                (current is CatalogRoute || current is BookingsRoute || current is ProfileRoute)
            ) {
                CustomerCartButton(
                    count = state.selectedCabinIds.size,
                    onClick = { topLevel(CartRoute) },
                    modifier = Modifier.align(Alignment.BottomEnd).navigationBarsPadding().imePadding().padding(20.dp),
                )
            }
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding())
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

/** Floating cart is the only persistent shortcut; the count tracks selected server-backed cabins. */
@Composable
private fun CustomerCartButton(count: Int, onClick: () -> Unit, modifier: Modifier = Modifier) {
    BadgedBox(
        modifier = modifier,
        badge = {
            Badge(containerColor = MaterialTheme.colorScheme.surface, contentColor = MaterialTheme.colorScheme.primary) {
                Text(count.toString(), modifier = Modifier.testTag("cart-count"))
            }
        },
    ) {
        Button(
            onClick = onClick,
            modifier = Modifier.size(60.dp).testTag("cart-fab"),
            contentPadding = PaddingValues(0.dp),
        ) {
            Icon(Icons.Default.ShoppingCart, contentDescription = "Корзина, бытовок: $count")
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
