package dev.buhanzaz.rwms.client.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.outlined.HomeWork
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.automirrored.outlined.ReceiptLong
import androidx.compose.material.icons.outlined.ShoppingCart
import androidx.compose.material3.Badge
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
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
            viewModel.resumeAutomaticUpdates()
            launch {
                var connected: Boolean? = null
                customerValidatedConnectivity(context.applicationContext).collect { available ->
                    if (connected == false && available) {
                        viewModel.resumeAutomaticUpdates()
                        viewModel.refreshCustomerUpdates()
                    }
                    connected = available
                }
            }
            while (true) {
                viewModel.refreshCustomerUpdates()
                kotlinx.coroutines.delay(5_000)
            }
        }
    }
    val browsingGuest = state is CustomerAppState.GuestCatalog
    LaunchedEffect(viewModel, lifecycle, browsingGuest) {
        if (browsingGuest) lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
            viewModel.refreshGuestCatalog()
            var connected: Boolean? = null
            customerValidatedConnectivity(context.applicationContext).collect { available ->
                if (connected == false && available) viewModel.refreshGuestCatalog()
                connected = available
            }
        }
    }
    CustomerTheme(appearanceMode = appearanceMode) {
        CustomerStoreLaunchGate(videoBackgroundEnabled = state !is CustomerAppState.SignedOut) {
            CustomerAppContent(
                state = state,
                onLogin = viewModel::login,
                onRegister = viewModel::register,
                onRetryRegistration = viewModel::retryRegistrationProfile,
                onContinueAsGuest = viewModel::continueAsGuest,
                onGuestWarehouse = viewModel::selectGuestWarehouse,
                onGuestFilters = viewModel::applyGuestFilters,
                onGuestLoadMore = viewModel::loadMoreGuestCabins,
                onGuestBack = viewModel::leaveGuestCatalog,
                onGuestLogin = viewModel::requestGuestLogin,
                onLogout = viewModel::logout,
                onSaveProfile = viewModel::saveProfile,
                onAvatarCropped = viewModel::uploadProfileAvatar,
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
                onCancelBooking = viewModel::cancelBooking,
                onOpenBookingReschedule = viewModel::openBookingReschedule,
                onSelectBookingRescheduleSlot = viewModel::selectBookingRescheduleSlot,
                onConfirmBookingReschedule = viewModel::confirmBookingReschedule,
                onDismissBookingReschedule = viewModel::dismissBookingReschedule,
                onApplyBookingChange = viewModel::applyBookingChange,
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
    onRegister: (String, String, String, String, String, String, String) -> Unit = { _, _, _, _, _, _, _ -> },
    onRetryRegistration: () -> Unit = {},
    onContinueAsGuest: () -> Unit = {},
    onGuestWarehouse: (dev.buhanzaz.rwms.client.data.CustomerWarehouse) -> Unit = {},
    onGuestFilters: (dev.buhanzaz.rwms.client.data.CabinFilters) -> Unit = {},
    onGuestLoadMore: () -> Unit = {},
    onGuestBack: () -> Unit = {},
    onGuestLogin: () -> Unit = {},
    onLogout: () -> Unit = {},
    onSaveProfile: (dev.buhanzaz.rwms.client.data.CustomerProfile) -> Unit = {},
    onAvatarCropped: (ByteArray) -> Unit = {},
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
    onCancelBooking: (String) -> Unit = {},
    onOpenBookingReschedule: (String) -> Unit = {},
    onSelectBookingRescheduleSlot: (String) -> Unit = {},
    onConfirmBookingReschedule: () -> Unit = {},
    onDismissBookingReschedule: () -> Unit = {},
    onApplyBookingChange: (Boolean) -> Unit = {},
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
            onContinueAsGuest = onContinueAsGuest,
            initialPage = if (state.showLogin) CustomerAuthenticationPage.LOGIN else CustomerAuthenticationPage.START,
        )
        is CustomerAppState.GuestCatalog -> GuestCustomerCatalog(
            state.catalog, onGuestWarehouse, onGuestFilters, onGuestLoadMore, onGuestBack, onGuestLogin,
        )
        is CustomerAppState.Ready -> {
            val workflow = state.workflow
            when {
                workflow.bootstrapping -> LoadingCustomerScreen("Загружаем данные клиента…")
                workflow.profile == null -> RegistrationStatusScreen(
                    busy = workflow.busy,
                    pending = workflow.registrationPending,
                    error = workflow.registrationError ?: workflow.error,
                    onRetry = onRetryRegistration,
                    onLogout = onLogout,
                )
                else -> SignedInNavigation(
                    state = workflow,
                    onLogout = onLogout,
                    onSaveProfile = onSaveProfile,
                    onAvatarCropped = onAvatarCropped,
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
                    onCancelBooking = onCancelBooking,
                    onOpenBookingReschedule = onOpenBookingReschedule,
                    onSelectBookingRescheduleSlot = onSelectBookingRescheduleSlot,
                    onConfirmBookingReschedule = onConfirmBookingReschedule,
                    onDismissBookingReschedule = onDismissBookingReschedule,
                    onApplyBookingChange = onApplyBookingChange,
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

/** Guest navigation exposes only city selection, catalog, photos, and the real login entry. */
@Composable
private fun GuestCustomerCatalog(
    state: CustomerGuestCatalogState,
    onWarehouse: (dev.buhanzaz.rwms.client.data.CustomerWarehouse) -> Unit,
    onFilters: (dev.buhanzaz.rwms.client.data.CabinFilters) -> Unit,
    onLoadMore: () -> Unit,
    onBack: () -> Unit,
    onLogin: () -> Unit,
) {
    var galleryCabin by rememberSaveable(state.selectedWarehouse?.id) { mutableStateOf<String?>(null) }
    var galleryPage by rememberSaveable { mutableStateOf(0) }
    BackHandler(onBack = onBack)
    if (state.selectedWarehouse == null) {
        WarehouseScreen(
            warehouses = state.warehouses, busy = state.busy,
            onSelect = { warehouse, _ -> onWarehouse(warehouse) }, onMenu = null, onProfile = onLogin,
            onBack = onBack, allowRemember = false, errorMessage = state.error,
        )
    } else {
        GuestCabinCatalogScreen(
            state = state, onBack = onBack, onLogin = onLogin, onFilters = onFilters,
            onLoadMore = onLoadMore, onWarehouse = onWarehouse,
            onPhoto = { unitId, page -> galleryCabin = unitId; galleryPage = page },
        )
    }
    galleryCabin?.let { cabinId ->
        FullscreenCabinGallery(state.cabins.firstOrNull { it.unitId == cabinId }, galleryPage) { galleryCabin = null }
    }
}

@Composable
@Suppress("LongParameterList")
private fun SignedInNavigation(
    state: CustomerWorkflowState,
    onLogout: () -> Unit,
    onSaveProfile: (dev.buhanzaz.rwms.client.data.CustomerProfile) -> Unit,
    onAvatarCropped: (ByteArray) -> Unit,
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
    onCancelBooking: (String) -> Unit,
    onOpenBookingReschedule: (String) -> Unit,
    onSelectBookingRescheduleSlot: (String) -> Unit,
    onConfirmBookingReschedule: () -> Unit,
    onDismissBookingReschedule: () -> Unit,
    onApplyBookingChange: (Boolean) -> Unit,
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
    var editingAvatar by remember { mutableStateOf(false) }

    fun topLevel(route: NavKey) {
        val root = if (state.selectedWarehouse == null) WarehouseRoute else CatalogRoute
        val destination = if (root is WarehouseRoute && route is CartRoute) root else route
        backStack.clear()
        backStack.add(root)
        if (destination != root && !(root is WarehouseRoute && destination is CatalogRoute)) {
            backStack.add(destination)
        }
        coroutineScope.launch { drawerState.close() }
    }

    fun selectDrawerDestination(route: NavKey) {
        coroutineScope.launch {
            drawerState.close()
            topLevel(route)
        }
    }

    fun backFrom(route: NavKey) {
        if (backStack.lastOrNull() == route && backStack.size > 1) backStack.removeLastOrNull()
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
        gesturesEnabled = !current.isDeliveryFlowRoute() && !editingAvatar,
        drawerContent = {
            ModalDrawerSheet(
                modifier = Modifier.width(264.dp).fillMaxHeight().testTag("customer-drawer"),
                drawerContainerColor = MaterialTheme.colorScheme.surface,
                drawerShape = RoundedCornerShape(topEnd = 24.dp, bottomEnd = 24.dp),
                windowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Vertical),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 12.dp, vertical = 16.dp),
                ) {
                    Column(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        CustomerStoreLogo(
                            Modifier.width(148.dp).height(61.dp).testTag("drawer-logo"),
                        )
                        Text(
                            "Аренда и доставка",
                            modifier = Modifier.fillMaxWidth(),
                            textAlign = TextAlign.Center,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    HorizontalDivider(Modifier.padding(horizontal = 16.dp, vertical = 12.dp))
                    NavigationDrawerItem(
                        shape = RoundedCornerShape(12.dp),
                        label = { Text("Аренда") },
                        selected = current is CatalogRoute,
                        onClick = { selectDrawerDestination(CatalogRoute) },
                        icon = { Icon(Icons.Outlined.HomeWork, contentDescription = null) },
                    )
                    NavigationDrawerItem(
                        shape = RoundedCornerShape(12.dp),
                        label = { Text("Корзина (${state.selectedCabinIds.size})") },
                        selected = current is CartRoute || current.isDeliveryFlowRoute(),
                        onClick = { selectDrawerDestination(CartRoute) },
                        icon = { Icon(Icons.Outlined.ShoppingCart, contentDescription = null) },
                    )
                    NavigationDrawerItem(
                        shape = RoundedCornerShape(12.dp),
                        label = { Text("Мои заказы") },
                        selected = current is BookingsRoute,
                        onClick = { selectDrawerDestination(BookingsRoute) },
                        icon = { Icon(Icons.AutoMirrored.Outlined.ReceiptLong, contentDescription = null) },
                    )
                    NavigationDrawerItem(
                        shape = RoundedCornerShape(12.dp),
                        label = { Text("Профиль") },
                        selected = current is ProfileRoute,
                        onClick = { selectDrawerDestination(ProfileRoute) },
                        icon = { Icon(Icons.Outlined.Person, contentDescription = null) },
                    )
                    HorizontalDivider(Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                    val nextAppearanceMode = appearanceMode.toggle()
                    NavigationDrawerItem(
                        shape = RoundedCornerShape(12.dp),
                        label = { Text(appearanceMode.title) },
                        selected = false,
                        onClick = { onAppearanceMode(nextAppearanceMode) },
                        icon = { Icon(appearanceMode.appearanceIcon(), contentDescription = null) },
                        modifier = Modifier.testTag("appearance-toggle"),
                    )
                    NavigationDrawerItem(
                        label = { Text("Выйти") },
                        selected = false,
                        onClick = onLogout,
                        shape = RoundedCornerShape(12.dp),
                        icon = { Icon(Icons.AutoMirrored.Outlined.Logout, contentDescription = null) },
                    )
                }
            }
        },
    ) {
        Box(Modifier.fillMaxSize()) {
            NavDisplay(
                backStack = backStack,
                modifier = Modifier.fillMaxSize().clipToBounds(),
                onBack = { if (backStack.size > 1) backStack.removeLastOrNull() },
                transitionSpec = {
                    slideInHorizontally(tween(260)) { it } togetherWith
                        slideOutHorizontally(tween(260)) { -it }
                },
                popTransitionSpec = {
                    slideInHorizontally(tween(260)) { -it } togetherWith
                        slideOutHorizontally(tween(260)) { it }
                },
                predictivePopTransitionSpec = {
                    slideInHorizontally(tween(260)) { -it } togetherWith
                        slideOutHorizontally(tween(260)) { it }
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
                            errorMessage = state.registrationError,
                        )
                    }
                    entry<CatalogRoute> {
                        val density = LocalDensity.current
                        var cartButtonHeight by remember { mutableStateOf(0) }
                        val cartVisible = state.selectedCabinIds.isNotEmpty()
                        Box(Modifier.fillMaxSize()) {
                            Box(Modifier.fillMaxSize()) {
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
                                    bottomOverlayHeight = if (cartVisible) with(density) { cartButtonHeight.toDp() } else 0.dp,
                                )
                            }
                            if (cartVisible) {
                                CustomerCartButton(
                                    count = state.selectedCabinIds.size,
                                    onClick = { if (backStack.lastOrNull() is CatalogRoute) backStack.add(CartRoute) },
                                    modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                                        .onSizeChanged { cartButtonHeight = it.height }
                                        .navigationBarsPadding().imePadding()
                                        .padding(horizontal = 16.dp, vertical = 12.dp),
                                )
                            }
                        }
                    }
                    entry<CartRoute> {
                        CartScreen(
                            state = state,
                            onBack = { backFrom(CartRoute) },
                            onChooseCabin = { topLevel(CatalogRoute) },
                            onProfile = ::openProfile,
                            onContinue = {
                                if (backStack.lastOrNull() is CartRoute) {
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
                            onBack = { backFrom(DeliveryMapRoute) },
                            onAddress = onAddress,
                            onPoint = onPoint,
                            onConfirmedLocation = onConfirmedLocation,
                            onSiteCabinCapacity = onSiteCabinCapacity,
                            onPrivateSiteAccess = onPrivateSiteAccess,
                            onFailedTripAcknowledgement = onFailedTripAcknowledgement,
                            onSearchSlots = onSearchSlots,
                            onSlotsReady = {
                                if (backStack.lastOrNull() is DeliveryMapRoute) {
                                    backStack.add(DeliveryDatesRoute)
                                }
                            },
                        )
                    }
                    entry<DeliveryDatesRoute> {
                        DeliveryDatesScreen(
                            state = state,
                            onProfile = ::openProfile,
                            onBack = { backFrom(DeliveryDatesRoute) },
                            onDate = { date ->
                                val route = DeliverySlotsRoute(date)
                                if (backStack.lastOrNull() is DeliveryDatesRoute) backStack.add(route)
                            },
                        )
                    }
                    entry<DeliverySlotsRoute> { route ->
                        DeliverySlotsScreen(
                            state = state,
                            onProfile = ::openProfile,
                            date = route.date,
                            onBack = { backFrom(route) },
                            onSelectSlot = onSelectSlot,
                            onHoldSlot = onHoldSlot,
                            onHeld = {
                                if (backStack.lastOrNull() == route) {
                                    backStack.add(DeliveryConfirmationRoute)
                                }
                            },
                        )
                    }
                    entry<DeliveryConfirmationRoute> {
                        DeliveryConfirmationScreen(
                            state = state,
                            onProfile = ::openProfile,
                            onBack = { backFrom(DeliveryConfirmationRoute) },
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
                            updatesError = listOfNotNull(
                                state.updatesError,
                                if (state.automaticUpdatesPaused) "Автоматическая проверка продолжится после восстановления связи или возвращения в приложение." else null,
                            ).joinToString("\n").takeIf(String::isNotBlank),
                            onConfirmInitialPayment = onConfirmInitialPayment,
                            onReadNotification = onReadNotification,
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
                            changeError = listOfNotNull(
                                state.error ?: state.bookingChangeReadError
                                    ?: state.updatesError.takeIf { state.bookingChangeNeedsRefresh },
                                if (state.automaticUpdatesPaused) "Проверка статуса продолжится после восстановления связи или возвращения в приложение." else null,
                            ).joinToString("\n").takeIf(String::isNotBlank),
                            changeUnavailableReason = state.bookingChangeUnavailableReason,
                            onApplyChange = onApplyBookingChange,
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
                            existing = requireNotNull(state.profile),
                            busy = state.busy,
                            errorMessage = state.error,
                            onSave = onSaveProfile,
                            onAvatarCropped = onAvatarCropped,
                            onAvatarEditingChanged = { editingAvatar = it },
                            onBack = { backFrom(ProfileRoute) },
                        )
                    }
                    entry<GalleryRoute> { route ->
                        FullscreenCabinGallery(
                            cabin = state.cabins.firstOrNull { it.unitId == route.unitId }
                                ?: state.cart?.cabins?.firstOrNull { it.unitId == route.unitId },
                            initialPage = route.initialPage,
                            onClose = { backFrom(route) },
                        )
                    }
                },
            )
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding())

        }
    }
}

/** Catalog-only cart shortcut; the count tracks selected server-backed cabins. */
@Composable
private fun CustomerCartButton(count: Int, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Button(
        onClick = onClick,
        modifier = modifier.testTag("cart-fab"),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 14.dp),
    ) {
        Icon(Icons.Default.ShoppingCart, contentDescription = null)
        Spacer(Modifier.width(10.dp))
        Text("Перейти в корзину", modifier = Modifier.weight(1f))
        Badge(containerColor = MaterialTheme.colorScheme.surface, contentColor = MaterialTheme.colorScheme.primary) {
            Text(count.toString(), modifier = Modifier.testTag("cart-count"))
        }
    }
}

private fun NavKey?.isDeliveryFlowRoute(): Boolean =
    this is DeliveryMapRoute ||
        this is DeliveryDatesRoute ||
        this is DeliverySlotsRoute ||
        this is DeliveryConfirmationRoute

private fun CustomerAppearanceMode.appearanceIcon(): ImageVector = when (this) {
    CustomerAppearanceMode.LIGHT -> Icons.Default.LightMode
    CustomerAppearanceMode.DARK -> Icons.Default.DarkMode
}

@Composable
private fun LoadingCustomerScreen(message: String) {
    Column(
        Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CustomerLoadingLine(tag = "customer-session-loading")
        Text(
            message,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Keeps interrupted registration recoverable without asking for the same profile data twice. */
@Composable
private fun RegistrationStatusScreen(
    busy: Boolean,
    pending: Boolean,
    error: String?,
    onRetry: () -> Unit,
    onLogout: () -> Unit,
) {
    androidx.compose.material3.Scaffold(
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
        topBar = { CustomerTopBar(title = "Регистрация") },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(16.dp).testTag("registration-status-screen"),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (busy) {
                CustomerLoadingLine(tag = "registration-loading")
                Text("Сохраняем регистрацию…", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Text(
                    error ?: "Данные регистрации недоступны. Обратитесь в поддержку.",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyLarge,
                )
                if (pending) {
                    Button(onClick = onRetry, modifier = Modifier.fillMaxWidth().testTag("registration-retry")) {
                        Text("Повторить сохранение")
                    }
                }
                TextButton(onClick = onLogout, modifier = Modifier.fillMaxWidth()) { Text("Выйти") }
            }
        }
    }
}
