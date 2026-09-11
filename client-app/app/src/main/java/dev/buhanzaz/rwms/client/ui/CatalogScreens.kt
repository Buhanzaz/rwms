package dev.buhanzaz.rwms.client.ui

import android.graphics.Color as AndroidColor
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowInsetsControllerCompat
import coil3.compose.AsyncImage
import dev.buhanzaz.rwms.client.BuildConfig
import dev.buhanzaz.rwms.client.data.AvailableEquipment
import dev.buhanzaz.rwms.client.data.CabinFilters
import dev.buhanzaz.rwms.client.data.CabinFacets
import dev.buhanzaz.rwms.client.data.CustomerCabin
import dev.buhanzaz.rwms.client.data.CustomerWarehouse
import java.util.Locale

/**
 * Shared glass header with an optional city selector and a circular profile affordance.
 *
 * Generic screens keep a static [title]. The catalog supplies [selectedWarehouse], [warehouses]
 * and [onWarehouseSelected]. Expanding alternatives grows the header below a fixed row whose
 * center title is independent of the left menu and right profile bounds.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CustomerTopBar(
    title: String,
    onMenu: (() -> Unit)? = null,
    onProfile: (() -> Unit)? = null,
    selectedWarehouse: CustomerWarehouse? = null,
    warehouses: List<CustomerWarehouse> = emptyList(),
    onWarehouseSelected: ((CustomerWarehouse) -> Unit)? = null,
    warehouseSelectionEnabled: Boolean = true,
    avatarUrl: String? = null,
    avatarInitials: String? = null,
    onBack: (() -> Unit)? = null,
    bottomContent: (@Composable ColumnScope.() -> Unit)? = null,
) {
    var warehouseMenuExpanded by remember(selectedWarehouse?.id, warehouses) { mutableStateOf(false) }
    val alternativeWarehouses = remember(selectedWarehouse?.id, warehouses) {
        warehouses.filterNot { it.id == selectedWarehouse?.id }
    }
    val shape = RoundedCornerShape(16.dp)
    Box(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp)) {
        CustomerShellSurface(
            modifier = Modifier.fillMaxWidth().testTag("customer-header"),
            shape = shape,
        ) {
            Column(Modifier.fillMaxWidth()) {
                Box(Modifier.fillMaxWidth().height(60.dp)) {
                    if (onBack != null || onMenu != null) IconButton(
                        onClick = onBack ?: checkNotNull(onMenu),
                        modifier = Modifier.align(Alignment.CenterStart).padding(start = 4.dp)
                            .testTag(if (onBack == null) "menu-button" else "header-back"),
                    ) {
                        Icon(
                            if (onBack == null) Icons.Default.Menu else Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = if (onBack == null) "Открыть меню" else "Назад",
                        )
                    }
                    Box(
                        modifier = Modifier.align(Alignment.Center).fillMaxWidth().padding(horizontal = 54.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (selectedWarehouse == null || onWarehouseSelected == null) {
                            Text(
                                title,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                textAlign = TextAlign.Center,
                                style = MaterialTheme.typography.titleMedium,
                            )
                        } else {
                            TextButton(
                                onClick = { warehouseMenuExpanded = !warehouseMenuExpanded },
                                enabled = warehouseSelectionEnabled && alternativeWarehouses.isNotEmpty(),
                                contentPadding = PaddingValues(horizontal = 0.dp, vertical = 4.dp),
                                modifier = Modifier.testTag("warehouse-selector"),
                            ) {
                                Text(
                                    selectedWarehouse.customerCityLabel(),
                                    modifier = Modifier.weight(1f, fill = false),
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    textAlign = TextAlign.Center,
                                )
                                if (alternativeWarehouses.isNotEmpty()) {
                                    Icon(
                                        Icons.Default.ArrowDropDown,
                                        contentDescription = "Выбрать другой город",
                                        tint = MaterialTheme.colorScheme.onSurface,
                                        modifier = Modifier.size(20.dp),
                                    )
                                }
                            }
                        }
                    }
                    if (onProfile != null) IconButton(
                        onClick = onProfile,
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .padding(end = 4.dp)
                            .testTag("profile-avatar"),
                    ) {
                        Surface(
                            modifier = Modifier.size(38.dp),
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.primaryContainer,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                val initials = avatarInitials?.trim()?.takeIf(String::isNotEmpty)
                                if (initials == null) {
                                    Icon(Icons.Default.Person, contentDescription = "Профиль")
                                } else {
                                    Text(
                                        initials.take(2).uppercase(),
                                        textAlign = TextAlign.Center,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    )
                                }
                                avatarUrl?.takeIf(String::isNotBlank)?.let { url ->
                                    AsyncImage(
                                        model = customerProfileMediaUrl(url),
                                        contentDescription = "Фото профиля",
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.fillMaxSize(),
                                    )
                                }
                            }
                        }
                    }
                }
                AnimatedVisibility(
                    visible = warehouseMenuExpanded && alternativeWarehouses.isNotEmpty(),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 280.dp)
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                            .testTag("warehouse-options"),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        HorizontalDivider()
                        alternativeWarehouses.forEach { warehouse ->
                            TextButton(
                                onClick = {
                                    warehouseMenuExpanded = false
                                    onWarehouseSelected?.invoke(warehouse)
                                },
                                enabled = warehouseSelectionEnabled,
                                modifier = Modifier.fillMaxWidth().testTag("warehouse-option-${warehouse.id}"),
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(warehouse.customerCityLabel())
                                    if (warehouses.count { it.customerCityLabel() == warehouse.customerCityLabel() } > 1) {
                                        Text(warehouse.address ?: warehouse.name, style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                            }
                        }
                    }
                }
                bottomContent?.invoke(this)
            }
        }
    }
}

/** Uses the server's city field without guessing a city from an internal warehouse name. */
internal fun CustomerWarehouse.customerCityLabel(): String =
    city?.trim()?.takeIf(String::isNotEmpty) ?: "Город не указан"

/** Displays each server-returned free cabin as one unique, photo-led rental card. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CabinCatalogScreen(
    state: CustomerWorkflowState,
    onMenu: () -> Unit,
    onProfile: () -> Unit,
    onFilters: (CabinFilters) -> Unit,
    onLoadMore: () -> Unit,
    onToggleCabin: (String) -> Unit,
    onEquipment: (String, AvailableEquipment, Long) -> Unit,
    onPhoto: (String, Int) -> Unit,
    onWarehouse: (CustomerWarehouse) -> Unit,
    avatarUrl: String? = null,
    avatarInitials: String? = null,
    bottomOverlayHeight: Dp = 0.dp,
) {
    var furnitureCabin by remember { mutableStateOf<String?>(null) }
    CustomerCatalogContent(
        state = CustomerCatalogContentState(
            state.warehouses, state.selectedWarehouse, state.facets, state.filters, state.cabins,
            state.cabinPage, state.cabinTotalPages, state.busy, state.selectedCabinIds,
        ),
        onMenu = onMenu, onProfile = onProfile, onFilters = onFilters, onLoadMore = onLoadMore,
        onToggleCabin = onToggleCabin, onFurniture = { furnitureCabin = it }, onPhoto = onPhoto,
        onWarehouse = onWarehouse, avatarUrl = avatarUrl, avatarInitials = avatarInitials ?: state.profileInitials(),
        bottomOverlayHeight = bottomOverlayHeight,
    )
    furnitureCabin?.let { cabinId ->
        FurnitureSheet(
            cabin = state.cabins.firstOrNull { it.unitId == cabinId },
            items = state.equipment,
            quantities = state.equipmentDraft.filterKeys { it.cabinUnitId == cabinId },
            onQuantity = { item, quantity -> onEquipment(cabinId, item, quantity) },
            onDismiss = { furnitureCabin = null },
            busy = state.busy,
        )
    }
}

/** Reuses the catalog's filters, cards and gallery affordances without a customer workflow. */
@Composable
internal fun GuestCabinCatalogScreen(
    state: CustomerGuestCatalogState,
    onBack: () -> Unit,
    onLogin: () -> Unit,
    onFilters: (CabinFilters) -> Unit,
    onLoadMore: () -> Unit,
    onPhoto: (String, Int) -> Unit,
    onWarehouse: (CustomerWarehouse) -> Unit,
) {
    CustomerCatalogContent(
        state = CustomerCatalogContentState(
            state.warehouses, state.selectedWarehouse, state.facets, state.filters, state.cabins,
            state.cabinPage, state.cabinTotalPages, state.busy, error = state.error,
        ),
        onProfile = onLogin, onBack = onBack, onFilters = onFilters, onLoadMore = onLoadMore,
        onToggleCabin = { onLogin() }, onFurniture = null, onPhoto = onPhoto, onWarehouse = onWarehouse,
        requiresLogin = true,
    )
}

/** Only fields drawn by the shared catalog; it cannot stand in for an authenticated workflow. */
private data class CustomerCatalogContentState(
    val warehouses: List<CustomerWarehouse>,
    val selectedWarehouse: CustomerWarehouse?,
    val facets: CabinFacets,
    val filters: CabinFilters,
    val cabins: List<CustomerCabin>,
    val cabinPage: Long,
    val cabinTotalPages: Long,
    val busy: Boolean,
    val selectedCabinIds: Set<String> = emptySet(),
    val error: String? = null,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CustomerCatalogContent(
    state: CustomerCatalogContentState,
    onProfile: () -> Unit,
    onFilters: (CabinFilters) -> Unit,
    onLoadMore: () -> Unit,
    onToggleCabin: (String) -> Unit,
    onFurniture: ((String) -> Unit)?,
    onPhoto: (String, Int) -> Unit,
    onWarehouse: (CustomerWarehouse) -> Unit,
    onMenu: (() -> Unit)? = null,
    onBack: (() -> Unit)? = null,
    avatarUrl: String? = null,
    avatarInitials: String? = null,
    requiresLogin: Boolean = false,
    bottomOverlayHeight: Dp = 0.dp,
) {
    var showFilters by remember { mutableStateOf(false) }
    val catalogScroll = rememberLazyListState()
    BackHandler(enabled = showFilters) { showFilters = false }
    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            CustomerTopBar(
                title = state.selectedWarehouse?.customerCityLabel() ?: "Выберите город",
                onMenu = onMenu,
                onProfile = onProfile,
                selectedWarehouse = state.selectedWarehouse,
                warehouses = state.warehouses,
                onWarehouseSelected = onWarehouse,
                warehouseSelectionEnabled = !state.busy,
                avatarUrl = avatarUrl,
                avatarInitials = avatarInitials,
                onBack = onBack,
            ) {
                CatalogStickyFilter(
                    activeFilterCount = state.filters.activeCount,
                    expanded = showFilters,
                    onFilters = { showFilters = !showFilters },
                )
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize()) {
            // Match WorkerApp: the viewport extends behind the header; only its last 24dp fade in.
            Box(
                Modifier
                    .fillMaxSize()
                    .clipToBounds(),
            ) {
                LazyColumn(
                    state = catalogScroll,
                    modifier = Modifier
                        .fillMaxSize()
                        .fadeIntoCatalogHeader((padding.calculateTopPadding() - 8.dp).coerceAtLeast(1.dp))
                        .testTag("catalog-screen"),
                    contentPadding = PaddingValues(
                        start = 16.dp,
                        end = 16.dp,
                        top = padding.calculateTopPadding() + 8.dp,
                        bottom = maxOf(padding.calculateBottomPadding(), bottomOverlayHeight) + 20.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    if (state.busy) {
                        item {
                            CustomerLoadingLine(
                                modifier = Modifier.fillMaxWidth(),
                                tag = if (requiresLogin) "guest-catalog-loading" else "catalog-loading",
                            )
                        }
                    }
                    state.error?.let { error ->
                        item { Text(error, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(12.dp).testTag("guest-catalog-error")) }
                    }
                    if (state.cabins.isEmpty() && !state.busy && state.error == null) {
                        item {
                            Column(
                                modifier = Modifier.fillMaxWidth().testTag("catalog-empty"),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(2.dp),
                            ) {
                                Text(
                                    "Свободных бытовок по выбранным условиям нет",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                    textAlign = TextAlign.Center,
                                )
                                if (state.filters.activeCount > 0) {
                                    TextButton(
                                        onClick = { onFilters(CabinFilters()) },
                                        modifier = Modifier.testTag("catalog-empty-clear-filters"),
                                    ) { Text("Сбросить фильтры") }
                                }
                            }
                        }
                    }
                    items(state.cabins, key = CustomerCabin::unitId) { cabin ->
                        val selected = cabin.unitId in state.selectedCabinIds
                        CabinCard(
                            cabin = cabin,
                            selected = selected,
                            onToggle = { onToggleCabin(cabin.unitId) },
                            onFurniture = onFurniture?.let { action -> { action(cabin.unitId) } },
                            requiresLogin = requiresLogin,
                            busy = state.busy,
                            onPhoto = { page -> onPhoto(cabin.unitId, page) },
                        )
                    }
                    if (state.cabinPage + 1 < state.cabinTotalPages) {
                        item {
                            OutlinedButton(
                                onClick = onLoadMore,
                                modifier = Modifier.fillMaxWidth().widthIn(max = 1120.dp),
                                enabled = !state.busy,
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                            ) {
                                Text("Показать ещё")
                            }
                        }
                    }
                }
                AnimatedVisibility(
                    visible = showFilters,
                    modifier = Modifier.align(Alignment.TopCenter).padding(top = padding.calculateTopPadding()).padding(horizontal = 16.dp),
                    enter = expandVertically(expandFrom = Alignment.Top),
                    exit = shrinkVertically(shrinkTowards = Alignment.Top),
                ) {
                    Column(
                        Modifier.verticalScroll(rememberScrollState()).padding(top = 8.dp, bottom = 20.dp),
                    ) {
                        CabinFilterPanel(
                            current = state.filters,
                            facets = state.facets,
                            warehouseId = state.selectedWarehouse?.id,
                            onApply = onFilters,
                        )
                    }
                }
            }
        }
    }
}

/** Matches WorkerApp's 24dp mask inside the header and fully hides every pixel above that band. */
private fun Modifier.fadeIntoCatalogHeader(headerBottom: Dp): Modifier =
    graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        .drawWithCache {
            val mask = Brush.verticalGradient(
                colors = listOf(Color.Transparent, Color.Black),
                startY = (headerBottom - 24.dp).toPx().coerceAtLeast(0f),
                endY = headerBottom.toPx().coerceAtLeast(1f),
            )
            onDrawWithContent {
                drawContent()
                drawRect(mask, blendMode = BlendMode.DstIn)
            }
        }

/** Keeps the catalog title and filter action inside the fixed customer header. */
@Composable
private fun CatalogStickyFilter(
    activeFilterCount: Int,
    expanded: Boolean,
    onFilters: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 2.dp)
            .testTag("catalog-sticky-filter"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "Каталог",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onFilters, modifier = Modifier.testTag("catalog-filter-button")) {
            Icon(if (expanded) Icons.Default.Close else Icons.Default.FilterList, contentDescription = null)
            Spacer(Modifier.width(6.dp))
            Text(if (expanded) "Закрыть" else "Фильтры${if (activeFilterCount > 0) " · $activeFilterCount" else ""}")
        }
    }
}

@Composable
private fun CabinCard(
    cabin: CustomerCabin,
    selected: Boolean,
    onToggle: () -> Unit,
    onFurniture: (() -> Unit)?,
    onPhoto: (Int) -> Unit,
    requiresLogin: Boolean = false,
    busy: Boolean,
) {
    Card(
        modifier = Modifier.fillMaxWidth().widthIn(max = 1120.dp).testTag("cabin-${cabin.unitId}"),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        BoxWithConstraints {
            val wide = usesWideCabinCard(maxWidth.value)
            if (wide) {
                Row(Modifier.fillMaxWidth().testTag("cabin-card-wide")) {
                    CabinPhotoPager(
                        cabin = cabin,
                        onPhoto = onPhoto,
                        modifier = Modifier.width(350.dp).height(286.dp),
                    )
                    CabinCardBody(
                        cabin = cabin,
                        selected = selected,
                        onToggle = onToggle,
                        onFurniture = onFurniture,
                        requiresLogin = requiresLogin,
                        busy = busy,
                        wide = true,
                        modifier = Modifier.weight(1f),
                    )
                }
            } else {
                Column(Modifier.fillMaxWidth().testTag("cabin-card-compact")) {
                    CabinPhotoPager(
                        cabin = cabin,
                        onPhoto = onPhoto,
                        modifier = Modifier.fillMaxWidth().then(
                            if (cabin.photos.isEmpty()) Modifier.height(80.dp) else Modifier.aspectRatio(16f / 9f),
                        ),
                    )
                    CabinCardBody(
                        cabin = cabin,
                        selected = selected,
                        onToggle = onToggle,
                        onFurniture = onFurniture,
                        requiresLogin = requiresLogin,
                        busy = busy,
                        wide = false,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

@Composable
private fun CabinCardBody(
    cabin: CustomerCabin,
    selected: Boolean,
    onToggle: () -> Unit,
    onFurniture: (() -> Unit)?,
    requiresLogin: Boolean,
    busy: Boolean,
    wide: Boolean,
    modifier: Modifier,
) {
    val details: @Composable ColumnScope.() -> Unit = {
        CustomerCabinDetails(
            cabin = cabin,
            identityTagPrefix = "cabin",
            priceTagPrefix = "cabin-rental-price",
        )
    }
    if (wide) {
        Row(modifier.padding(20.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            Column(
                Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                content = details,
            )
            CabinCardAction(
                selected = selected,
                onToggle = onToggle,
                onFurniture = onFurniture,
                cabinUnitId = cabin.unitId,
                requiresLogin = requiresLogin,
                busy = busy,
                modifier = Modifier.width(205.dp),
            )
        }
    } else {
        Column(modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            details()
            CabinCardAction(
                selected = selected,
                onToggle = onToggle,
                onFurniture = onFurniture,
                cabinUnitId = cabin.unitId,
                requiresLogin = requiresLogin,
                busy = busy,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CabinCardAction(
    selected: Boolean,
    onToggle: () -> Unit,
    onFurniture: (() -> Unit)?,
    cabinUnitId: String,
    requiresLogin: Boolean,
    busy: Boolean,
    modifier: Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (selected && !requiresLogin) {
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                onFurniture?.let { action ->
                    TextButton(
                        onClick = action,
                        enabled = !busy,
                        modifier = Modifier.testTag("cabin-additional-$cabinUnitId"),
                    ) {
                        Text("+ Дополнительно")
                    }
                }
                TextButton(onClick = onToggle, enabled = !busy, modifier = Modifier.testTag("cabin-remove")) {
                    Icon(Icons.Default.CheckCircle, contentDescription = "Убрать из заказа", modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("В заказе")
                }
            }
        } else {
            Button(onClick = onToggle, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                Icon(if (requiresLogin) Icons.Default.Person else Icons.Default.ShoppingCart, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(if (requiresLogin) "Войти для заказа" else "В заказ")
            }
        }
    }
}

@Composable
private fun CabinPhotoPager(cabin: CustomerCabin, onPhoto: (Int) -> Unit, modifier: Modifier) {
    if (cabin.photos.isEmpty()) {
        Surface(
            modifier.testTag("cabin-photo-placeholder"),
            color = MaterialTheme.colorScheme.surfaceContainer,
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Default.PhotoLibrary,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "Фотографий пока нет",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        return
    }
    val pager = rememberPagerState { cabin.photos.size }
    Box(modifier) {
        HorizontalPager(state = pager, modifier = Modifier.fillMaxSize()) { page ->
            val photo = cabin.photos[page]
            var failed by remember(photo.photoId, photo.generation, photo.thumbnailUrl) { mutableStateOf(false) }
            Box(Modifier.fillMaxSize().clickable(onClickLabel = "Открыть фотографию") { onPhoto(page) }) {
                AsyncImage(
                    model = customerMediaUrl(photo.thumbnailUrl),
                    contentDescription = "Бытовка ${cabin.accountingNo}, фото ${page + 1} из ${cabin.photos.size}",
                    contentScale = ContentScale.Crop,
                    onError = { failed = true },
                    onSuccess = { failed = false },
                    modifier = Modifier.fillMaxSize(),
                )
                if (failed) Text(
                    "Фотография недоступна", Modifier.align(Alignment.Center).padding(16.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Surface(
            color = MaterialTheme.colorScheme.scrim.copy(alpha = 0.76f),
            contentColor = Color.White,
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier.align(Alignment.BottomStart).padding(12.dp),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 9.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(Icons.Default.PhotoLibrary, contentDescription = null, modifier = Modifier.size(16.dp))
                Text(
                    if (cabin.photos.size == 1) "1 фото" else "${pager.currentPage + 1}/${cabin.photos.size} фото",
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
    }
}

/** Shared, factual product identity used in catalog and cart cards. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CustomerCabinDetails(
    cabin: CustomerCabin,
    identityTagPrefix: String,
    priceTagPrefix: String,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Text(
                cabin.type ?: "Тип не указан",
                style = MaterialTheme.typography.titleLarge.copy(fontSize = 18.sp, lineHeight = 24.sp),
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f).testTag("$identityTagPrefix-type-${cabin.unitId}"),
            )
            Text(
                "№ ${cabin.accountingNo}",
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp, lineHeight = 20.sp),
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.End,
                modifier = Modifier
                    .widthIn(max = 148.dp)
                    .wrapContentWidth(Alignment.End)
                    .testTag("$identityTagPrefix-number-${cabin.unitId}"),
            )
        }
        cabin.category?.let { CustomerCabinAttributeChip(it, emphasized = true) }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            CustomerCabinFact("Габариты", cabin.dimensions ?: "Не указаны", Modifier.weight(1f))
            CustomerCabinFact("Отделка", cabin.finish ?: "Не указана", Modifier.weight(1f))
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            cabin.linoleum?.let {
                CustomerCabinAttributeChip(if (it) "Линолеум" else "Без линолеума")
            }
            cabin.characteristics.forEach {
                CustomerCabinAttributeChip(it)
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Text(
            CustomerMoneyFormatter.monthlyRentalPrice(cabin.monthlyPriceRubles),
            style = MaterialTheme.typography.titleLarge.copy(fontSize = 22.sp, lineHeight = 28.sp),
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.testTag("$priceTagPrefix-${cabin.unitId}"),
        )
    }
}

@Composable
private fun CustomerCabinAttributeChip(label: String, emphasized: Boolean = false) {
    val chipColor = if (emphasized) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.secondaryContainer
    }
    Surface(
        modifier = Modifier.testTag("cabin-attribute-$label"),
        shape = RoundedCornerShape(50),
        color = chipColor,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.34f)),
    ) {
        Text(
            label,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            style = MaterialTheme.typography.labelMedium,
            color = if (emphasized) {
                MaterialTheme.colorScheme.onPrimaryContainer
            } else {
                MaterialTheme.colorScheme.onSecondaryContainer
            },
        )
    }
}

@Composable
internal fun CustomerCabinFact(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

internal fun usesWideCabinCard(widthDp: Float): Boolean = widthDp >= 920f

/** Expands server-owned facets above the catalog without replacing its current results. */
@Composable
private fun CabinFilterPanel(
    current: CabinFilters,
    facets: dev.buhanzaz.rwms.client.data.CabinFacets,
    warehouseId: String?,
    onApply: (CabinFilters) -> Unit,
) {
    var draft by remember(current, warehouseId) { mutableStateOf(current) }
    var expandedFacet by remember(warehouseId) { mutableStateOf<String?>(null) }
    val available = facets.warehouses.firstOrNull { it.warehouseId == warehouseId } ?: facets.selected
    val dimensions = remember(available, draft.cabinType) { available.compatibleDimensionsFor(draft.cabinType) }
    fun apply(next: CabinFilters) {
        draft = next
        onApply(next)
    }
    val shape = RoundedCornerShape(16.dp)
    Surface(
        modifier = Modifier
            .widthIn(max = 760.dp)
            .fillMaxWidth()
            .figmaButtonShadow(shape)
            .testTag("catalog-filter-panel"),
        shape = shape,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            CatalogFacetField(
                title = "Тип",
                options = available.cabinTypes,
                selected = listOfNotNull(draft.cabinType),
                expanded = expandedFacet == "Тип",
                onExpandedChange = { expandedFacet = if (it) "Тип" else null },
                modifier = Modifier.fillMaxWidth(),
            ) {
                apply(draft.withCabinType(it, available))
            }
            CatalogFacetField(
                title = "Отделка",
                options = available.finishes,
                selected = listOfNotNull(draft.finish),
                expanded = expandedFacet == "Отделка",
                onExpandedChange = { expandedFacet = if (it) "Отделка" else null },
                modifier = Modifier.fillMaxWidth(),
            ) {
                apply(draft.copy(finish = it))
            }
            CatalogFacetField(
                title = "Размер",
                options = dimensions,
                selected = listOfNotNull(draft.dimensions),
                expanded = expandedFacet == "Размер",
                onExpandedChange = { expandedFacet = if (it) "Размер" else null },
                modifier = Modifier.fillMaxWidth(),
            ) {
                apply(draft.copy(dimensions = it))
            }
            CatalogFacetField(
                title = "Категория",
                options = available.categories,
                selected = listOfNotNull(draft.category),
                expanded = expandedFacet == "Категория",
                onExpandedChange = { expandedFacet = if (it) "Категория" else null },
                modifier = Modifier.fillMaxWidth(),
            ) {
                apply(draft.copy(category = it))
            }
            val floor = when (draft.linoleum) {
                true -> "Линолеум"
                false -> "Без линолеума"
                null -> null
            }
            CatalogFacetField(
                title = "Пол",
                options = listOf("Линолеум", "Без линолеума"),
                selected = listOfNotNull(floor),
                expanded = expandedFacet == "Пол",
                onExpandedChange = { expandedFacet = if (it) "Пол" else null },
                modifier = Modifier.fillMaxWidth(),
            ) {
                apply(draft.copy(linoleum = it?.let { value -> value == "Линолеум" }))
            }
            if (available.characteristics.isNotEmpty()) {
                Column(
                    modifier = Modifier.fillMaxWidth().testTag("filter-characteristics"),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text("Характеристики", style = MaterialTheme.typography.labelLarge)
                    available.characteristics.forEach { value ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .testTag("filter-characteristic-$value")
                                .toggleable(
                                    value = value in draft.characteristics,
                                    role = Role.Checkbox,
                                ) { checked ->
                                    apply(
                                        draft.copy(
                                            characteristics = if (checked) draft.characteristics + value
                                            else draft.characteristics - value,
                                        ),
                                    )
                                },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(
                                checked = value in draft.characteristics,
                                onCheckedChange = null,
                            )
                            Text(value, modifier = Modifier.padding(start = 4.dp))
                        }
                    }
                }
            }
            OutlinedButton(
                onClick = { apply(CabinFilters()) },
                modifier = Modifier.fillMaxWidth().testTag("catalog-filter-reset"),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            ) { Text("Сбросить фильтры") }
        }
    }
}

@Composable
private fun CatalogFacetField(
    title: String,
    options: List<String>,
    selected: List<String>,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    modifier: Modifier,
    onSelect: (String?) -> Unit,
) {
    Surface(
        modifier = modifier.testTag("filter-field-$title"),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column {
            Row(
                Modifier.fillMaxWidth().clickable { onExpandedChange(!expanded) }.heightIn(min = 68.dp).padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(title, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        selected.joinToString().ifEmpty { "Любой" },
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Icon(Icons.Default.ArrowDropDown, contentDescription = null, modifier = Modifier.size(20.dp))
            }
            if (expanded) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 240.dp)
                        .verticalScroll(rememberScrollState())
                        .testTag("filter-options-$title"),
                ) {
                    TextButton(
                        onClick = {
                            onSelect(null)
                            onExpandedChange(false)
                        },
                        modifier = Modifier.fillMaxWidth().testTag("filter-option-$title-any"),
                    ) { Text("Любой") }
                    if (options.isEmpty()) {
                        Text(
                            "Нет доступных вариантов в этом городе",
                            modifier = Modifier.padding(12.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    options.forEach { value ->
                        TextButton(
                            onClick = {
                                onSelect(if (selected.any { facetValuesMatch(it, value) }) null else value)
                                onExpandedChange(false)
                            },
                            modifier = Modifier.fillMaxWidth().testTag("filter-option-$title-$value"),
                        ) {
                            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                                Text(
                                    value,
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 28.dp),
                                    textAlign = TextAlign.Center,
                                )
                                if (selected.any { facetValuesMatch(it, value) }) {
                                    Icon(
                                        Icons.Default.CheckCircle,
                                        contentDescription = "Выбрано",
                                        modifier = Modifier.align(Alignment.CenterEnd).size(20.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Returns only the size facet values that are valid for the selected cabin type. */
private fun dev.buhanzaz.rwms.client.data.CabinFacetWarehouse.compatibleDimensionsFor(
    cabinType: String?,
): List<String> {
    if (cabinType == null || typeDimensions.isEmpty()) return dimensions
    return typeDimensions.firstOrNull { relation ->
        facetValuesMatch(relation.cabinType, cabinType)
    }?.dimensions.orEmpty()
}

/** Replaces a type selection while retaining a dimension only when that exact facet supports it. */
private fun CabinFilters.withCabinType(
    cabinType: String?,
    facets: dev.buhanzaz.rwms.client.data.CabinFacetWarehouse,
): CabinFilters = copy(
    cabinType = cabinType,
    dimensions = dimensions.canonicalFacetValue(facets.compatibleDimensionsFor(cabinType)),
)

private fun String?.canonicalFacetValue(values: List<String>): String? = values.firstOrNull { value ->
    facetValuesMatch(this, value)
}

private fun facetValuesMatch(first: String?, second: String): Boolean =
    first?.normalizedFacetValue() == second.normalizedFacetValue()

private fun String.normalizedFacetValue(): String =
    trim().split(Regex("\\s+")).joinToString(" ").lowercase(Locale.ROOT)

private fun CustomerWorkflowState.profileInitials(): String? {
    val currentProfile = profile ?: return null
    val nameParts = listOfNotNull(currentProfile.firstName, currentProfile.lastName)
        .flatMap { value -> value.trim().split(Regex("\\s+")) }
        .filter(String::isNotBlank)
    val companyParts = currentProfile.companyName
        ?.trim()
        ?.split(Regex("\\s+"))
        .orEmpty()
        .filter(String::isNotBlank)
    val source = nameParts.ifEmpty { companyParts }
    return source.take(2).mapNotNull(String::firstOrNull).joinToString("").takeIf(String::isNotBlank)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FurnitureSheet(
    cabin: CustomerCabin?,
    items: List<AvailableEquipment>,
    quantities: Map<EquipmentKey, Long>,
    onQuantity: (AvailableEquipment, Long) -> Unit,
    onDismiss: () -> Unit,
    busy: Boolean,
) {
    ModalBottomSheet(onDismissRequest = { if (!busy) onDismiss() }) {
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(max = 560.dp)
                .navigationBarsPadding()
                .padding(20.dp),
        ) {
            Text("Мебель · ${cabin?.accountingNo.orEmpty()}", style = MaterialTheme.typography.headlineSmall)
            Text("Отображаются только позиции в наличии на выбранном складе.")
            Spacer(Modifier.height(12.dp))
            Column(
                modifier = Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
            ) {
                if (items.isEmpty()) Text("Свободной мебели на складе нет")
                items.forEach { item ->
                    val key = EquipmentKey(requireNotNull(cabin).unitId, item.inventoryItemId)
                    val quantity = quantities[key] ?: 0L
                    val maximum = minOf(item.availableQuantity, item.maximumPerCabin?.toLong() ?: Long.MAX_VALUE)
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(item.name, fontWeight = FontWeight.Medium)
                            Text("Доступно: ${item.availableQuantity} · максимум: $maximum", style = MaterialTheme.typography.bodySmall)
                        }
                        IconButton(
                            onClick = { onQuantity(item, (quantity - 1).coerceAtLeast(0)) },
                            enabled = !busy && quantity > 0,
                        ) {
                            Icon(Icons.Default.Remove, contentDescription = "Уменьшить ${item.name}")
                        }
                        Text(quantity.toString(), modifier = Modifier.width(28.dp))
                        IconButton(
                            onClick = { onQuantity(item, quantity + 1) },
                            enabled = !busy && quantity < maximum,
                        ) { Icon(Icons.Default.Add, contentDescription = "Добавить ${item.name}") }
                    }
                }
            }
            Button(onClick = onDismiss, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Готово") }
        }
    }
}

/** Full-window swipe gallery; closing it returns to the catalog, never to a cabin passport. */
@Composable
fun FullscreenCabinGallery(cabin: CustomerCabin?, initialPage: Int, onClose: () -> Unit) {
    if (cabin == null || cabin.photos.isEmpty()) {
        LaunchedClose(onClose)
        return
    }
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Surface(Modifier.fillMaxSize(), color = Color.Black) {
            GallerySystemBars()
            val pager = rememberPagerState(initialPage = initialPage.coerceIn(cabin.photos.indices)) { cabin.photos.size }
            var zoomedPhotoId by remember(cabin.unitId, cabin.photos) { mutableStateOf<String?>(null) }
            Box(Modifier.fillMaxSize()) {
                HorizontalPager(
                    state = pager,
                    userScrollEnabled = zoomedPhotoId != cabin.photos[pager.currentPage].photoId,
                    modifier = Modifier.fillMaxSize(),
                ) { page ->
                    val photo = cabin.photos[page]
                    key(photo.photoId, photo.generation, photo.url) {
                        GalleryPhoto(
                            photoId = photo.photoId,
                            photoUrl = photo.url,
                            description = "Фото ${page + 1} из ${cabin.photos.size}",
                            onZoomedChange = { zoomed ->
                                zoomedPhotoId = if (zoomed) photo.photoId else zoomedPhotoId.takeUnless { it == photo.photoId }
                            },
                        )
                    }
                }
                IconButton(
                    onClick = onClose,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .statusBarsPadding()
                        .padding(12.dp)
                        .testTag("gallery-close"),
                ) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = "Закрыть фотографии",
                        tint = Color.White,
                    )
                }
                Text(
                    "${pager.currentPage + 1} / ${cabin.photos.size}",
                    color = Color.White,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .navigationBarsPadding()
                        .padding(20.dp),
                )
            }
        }
    }
}

/** Keeps each gallery page independently zoomable while a transformed photo blocks pager swipes. */
@Composable
private fun GalleryPhoto(
    photoId: String,
    photoUrl: String,
    description: String,
    onZoomedChange: (Boolean) -> Unit,
) {
    var loading by remember(photoId, photoUrl) { mutableStateOf(true) }
    var failed by remember(photoId, photoUrl) { mutableStateOf(false) }
    var loaded by remember(photoId, photoUrl) { mutableStateOf(false) }
    var scale by remember(photoId, photoUrl) { mutableStateOf(1f) }
    var offset by remember(photoId, photoUrl) { mutableStateOf(Offset.Zero) }
    var viewportWidth by remember(photoId, photoUrl) { mutableStateOf(0) }
    var viewportHeight by remember(photoId, photoUrl) { mutableStateOf(0) }

    DisposableEffect(photoId, photoUrl) {
        onDispose { onZoomedChange(false) }
    }
    val transformState = rememberTransformableState { zoomChange, panChange, _ ->
        val nextScale = (scale * zoomChange).coerceIn(1f, 4f)
        val maximumX = viewportWidth * (nextScale - 1f) / 2f
        val maximumY = viewportHeight * (nextScale - 1f) / 2f
        scale = nextScale
        offset = if (nextScale == 1f) {
            Offset.Zero
        } else {
            Offset(
                (offset.x + panChange.x).coerceIn(-maximumX, maximumX),
                (offset.y + panChange.y).coerceIn(-maximumY, maximumY),
            )
        }
        onZoomedChange(nextScale > 1f)
    }
    Box(Modifier.fillMaxSize().clipToBounds().onSizeChanged { size ->
        viewportWidth = size.width
        viewportHeight = size.height
    }) {
        AsyncImage(
            model = customerMediaUrl(photoUrl),
            contentDescription = description,
            contentScale = ContentScale.Fit,
            onLoading = {
                loading = true
                failed = false
                loaded = false
            },
            onError = {
                loading = false
                failed = true
                loaded = false
            },
            onSuccess = {
                loading = false
                failed = false
                loaded = true
            },
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offset.x
                    translationY = offset.y
                }
                .transformable(
                    state = transformState,
                    canPan = { scale > 1f },
                    enabled = loaded,
                ),
        )
        if (loading) {
            LinearProgressIndicator(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(horizontal = 48.dp, vertical = 12.dp)
                    .fillMaxWidth()
                    .height(3.dp)
                    .testTag("gallery-photo-loading-$photoId"),
                color = Color.White,
                trackColor = Color.White.copy(alpha = 0.24f),
            )
        }
        if (failed) {
            Text(
                "Фотография недоступна",
                modifier = Modifier.align(Alignment.Center).testTag("gallery-photo-error-$photoId"),
                color = Color.White,
            )
        }
        if (scale > 1f) {
            TextButton(
                onClick = {
                    scale = 1f
                    offset = Offset.Zero
                    onZoomedChange(false)
                },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .navigationBarsPadding()
                    .padding(12.dp)
                    .testTag("gallery-reset-zoom-$photoId"),
            ) { Text("Сбросить масштаб", color = Color.White) }
        }
    }
}

/** Colors only the transient dialog window so closing the gallery restores the host system bars. */
@Composable
private fun GallerySystemBars() {
    val dialogWindow = (LocalView.current.parent as? DialogWindowProvider)?.window ?: return
    DisposableEffect(dialogWindow) {
        val statusBarColor = dialogWindow.statusBarColor
        val navigationBarColor = dialogWindow.navigationBarColor
        val controller = WindowInsetsControllerCompat(dialogWindow, dialogWindow.decorView)
        val lightStatusBars = controller.isAppearanceLightStatusBars
        val lightNavigationBars = controller.isAppearanceLightNavigationBars
        dialogWindow.statusBarColor = AndroidColor.BLACK
        dialogWindow.navigationBarColor = AndroidColor.BLACK
        controller.isAppearanceLightStatusBars = false
        controller.isAppearanceLightNavigationBars = false
        onDispose {
            dialogWindow.statusBarColor = statusBarColor
            dialogWindow.navigationBarColor = navigationBarColor
            controller.isAppearanceLightStatusBars = lightStatusBars
            controller.isAppearanceLightNavigationBars = lightNavigationBars
        }
    }
}

@Composable
private fun LaunchedClose(onClose: () -> Unit) {
    androidx.compose.runtime.LaunchedEffect(Unit) { onClose() }
}

/** Keeps both customer and public catalog photos on the configured gateway origin. */
internal fun customerMediaUrl(path: String): String = when {
    path.startsWith("/api/logistics/public/v1/catalog/") -> "${BuildConfig.PUBLIC_BASE_URL}$path"
    path.startsWith("${BuildConfig.PUBLIC_BASE_URL}/api/logistics/public/v1/catalog/") -> path
    path.startsWith("/api/logistics/customer/v1/") -> "${BuildConfig.PUBLIC_BASE_URL}$path"
    path.startsWith("${BuildConfig.PUBLIC_BASE_URL}/api/logistics/customer/v1/") -> path
    else -> ""
}

private fun customerProfileMediaUrl(path: String): String = when {
    path.startsWith("/api/media/v1/") -> "${BuildConfig.PUBLIC_BASE_URL}$path"
    path.startsWith("${BuildConfig.PUBLIC_BASE_URL}/api/media/v1/") -> path
    else -> ""
}
