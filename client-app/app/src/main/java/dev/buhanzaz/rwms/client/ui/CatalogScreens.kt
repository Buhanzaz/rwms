package dev.buhanzaz.rwms.client.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
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
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.compose.AsyncImage
import dev.buhanzaz.rwms.client.BuildConfig
import dev.buhanzaz.rwms.client.data.AvailableEquipment
import dev.buhanzaz.rwms.client.data.CabinFilters
import dev.buhanzaz.rwms.client.data.CustomerCabin
import dev.buhanzaz.rwms.client.data.CustomerWarehouse
import java.util.Locale

/**
 * Shared customer header with an optional warehouse selector and a circular profile affordance.
 *
 * Generic screens keep a static [title]. The catalog supplies [selectedWarehouse], [warehouses]
 * and [onWarehouseSelected]. Expanding alternatives grows the header below a fixed row whose
 * center title is independent of the left menu and right profile bounds.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CustomerTopBar(
    title: String,
    onMenu: () -> Unit,
    onProfile: () -> Unit,
    selectedWarehouse: CustomerWarehouse? = null,
    warehouses: List<CustomerWarehouse> = emptyList(),
    onWarehouseSelected: ((CustomerWarehouse) -> Unit)? = null,
    avatarUrl: String? = null,
    avatarInitials: String? = null,
) {
    var warehouseMenuExpanded by remember(selectedWarehouse?.id, warehouses) { mutableStateOf(false) }
    val alternativeWarehouses = remember(selectedWarehouse?.id, warehouses) {
        warehouses.filterNot { it.id == selectedWarehouse?.id }
    }
    Surface(
        modifier = Modifier.fillMaxWidth().testTag("customer-header"),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 1.dp,
    ) {
        Column(Modifier.fillMaxWidth().statusBarsPadding()) {
            Box(Modifier.fillMaxWidth().height(64.dp)) {
                IconButton(
                    onClick = onMenu,
                    modifier = Modifier.align(Alignment.CenterStart).testTag("menu-button"),
                ) {
                    Icon(Icons.Default.Menu, contentDescription = "Открыть меню")
                }
                Box(
                    modifier = Modifier.align(Alignment.Center).widthIn(max = 420.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    if (selectedWarehouse == null || onWarehouseSelected == null) {
                        Text(
                            title,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 56.dp),
                        )
                    } else {
                        TextButton(
                            onClick = { warehouseMenuExpanded = !warehouseMenuExpanded },
                            enabled = alternativeWarehouses.isNotEmpty(),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                            modifier = Modifier.testTag("warehouse-selector"),
                        ) {
                            Text(
                                "Склад: ${selectedWarehouse.name}",
                                color = MaterialTheme.colorScheme.onSurface,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                textAlign = TextAlign.Center,
                            )
                            if (alternativeWarehouses.isNotEmpty()) {
                                Icon(
                                    Icons.Default.ArrowDropDown,
                                    contentDescription = "Выбрать другой склад",
                                    tint = MaterialTheme.colorScheme.onSurface,
                                )
                            }
                        }
                    }
                }
                IconButton(
                    onClick = onProfile,
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .padding(end = 8.dp)
                        .testTag("profile-avatar"),
                ) {
                    Surface(
                        modifier = Modifier.size(40.dp),
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
                            modifier = Modifier.fillMaxWidth().testTag("warehouse-option-${warehouse.id}"),
                        ) {
                            Column {
                                Text(warehouse.name)
                                warehouse.city?.takeIf(String::isNotBlank)?.let { city ->
                                    Text(city, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

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
) {
    var showFilters by remember { mutableStateOf(false) }
    var furnitureCabin by remember { mutableStateOf<String?>(null) }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            Column {
                CustomerTopBar(
                    title = state.selectedWarehouse?.name ?: "Склад не выбран",
                    onMenu = onMenu,
                    onProfile = onProfile,
                    selectedWarehouse = state.selectedWarehouse,
                    warehouses = state.warehouses,
                    onWarehouseSelected = onWarehouse,
                    avatarUrl = avatarUrl,
                    avatarInitials = avatarInitials ?: state.profileInitials(),
                )
                CatalogStickyFilter(
                    activeFilterCount = state.filters.activeCount,
                    onFilters = { showFilters = true },
                )
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
                .testTag("catalog-screen"),
            contentPadding = PaddingValues(top = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (state.cabins.isEmpty() && !state.busy) {
                item {
                    Text(
                        "Свободных бытовок по выбранным условиям нет",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(32.dp),
                    )
                }
            }
            items(state.cabins, key = CustomerCabin::unitId) { cabin ->
                val selected = cabin.unitId in state.selectedCabinIds
                CabinCard(
                    cabin = cabin,
                    selected = selected,
                    onToggle = { onToggleCabin(cabin.unitId) },
                    onFurniture = { furnitureCabin = cabin.unitId },
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
    }
    if (showFilters) {
        CabinFilterSheet(
            current = state.filters,
            facets = state.facets,
            warehouseId = state.selectedWarehouse?.id,
            onDismiss = { showFilters = false },
            onApply = {
                showFilters = false
                onFilters(it)
            },
        )
    }
    furnitureCabin?.let { cabinId ->
        FurnitureSheet(
            cabin = state.cabins.firstOrNull { it.unitId == cabinId },
            items = state.equipment,
            quantities = state.equipmentDraft.filterKeys { it.cabinUnitId == cabinId },
            onQuantity = { item, quantity -> onEquipment(cabinId, item, quantity) },
            onDismiss = { furnitureCabin = null },
        )
    }
}

/** Keeps the catalog's single filter entry point directly below the customer header while cards scroll. */
@Composable
private fun CatalogStickyFilter(
    activeFilterCount: Int,
    onFilters: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth().testTag("catalog-sticky-filter"),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 2.dp,
    ) {
        Box(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            OutlinedButton(
                onClick = onFilters,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier.fillMaxWidth().widthIn(max = 1120.dp).testTag("catalog-filter-button"),
            ) {
                Icon(
                    Icons.Default.FilterList,
                    contentDescription = "Фильтры, выбрано $activeFilterCount",
                )
                Spacer(Modifier.width(8.dp))
                Text("Фильтры${if (activeFilterCount > 0) " · $activeFilterCount" else ""}")
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CabinCard(
    cabin: CustomerCabin,
    selected: Boolean,
    onToggle: () -> Unit,
    onFurniture: () -> Unit,
    onPhoto: (Int) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth().widthIn(max = 1120.dp).testTag("cabin-${cabin.unitId}"),
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(
            width = if (selected) 2.dp else 1.dp,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
        ),
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
                        wide = true,
                        modifier = Modifier.weight(1f),
                    )
                }
            } else {
                Column(Modifier.fillMaxWidth().testTag("cabin-card-compact")) {
                    CabinPhotoPager(
                        cabin = cabin,
                        onPhoto = onPhoto,
                        modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f),
                    )
                    CabinCardBody(
                        cabin = cabin,
                        selected = selected,
                        onToggle = onToggle,
                        onFurniture = onFurniture,
                        wide = false,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CabinCardBody(
    cabin: CustomerCabin,
    selected: Boolean,
    onToggle: () -> Unit,
    onFurniture: () -> Unit,
    wide: Boolean,
    modifier: Modifier,
) {
    val details: @Composable ColumnScope.() -> Unit = {
        Text(
            cabin.category?.uppercase() ?: "БЫТОВКА",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "№ ${cabin.accountingNo}",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.testTag("cabin-number-${cabin.unitId}"),
            )
            Text(
                cabin.type ?: "Тип не указан",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f).testTag("cabin-type-${cabin.unitId}"),
            )
        }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            cabin.dimensions?.let { CabinFact(Icons.Default.Straighten, it) }
            cabin.finish?.let { CabinFact(text = "Отделка: $it") }
            cabin.linoleum?.let { CabinFact(Icons.Default.CheckCircle, if (it) "Линолеум" else "Без линолеума") }
            cabin.characteristics.forEach { CabinFact(Icons.Default.CheckCircle, it) }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Text(
            CustomerMoneyFormatter.monthlyRentalPrice(cabin.monthlyPriceRubles),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.testTag("cabin-rental-price-${cabin.unitId}"),
        )
        if (selected) {
            OutlinedButton(
                onClick = onFurniture,
                modifier = Modifier.fillMaxWidth(),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            ) {
                Text("+ Дополнительно")
            }
        }
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
                modifier = Modifier.width(205.dp),
            )
        }
    } else {
        Column(modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            details()
            CabinCardAction(
                selected = selected,
                onToggle = onToggle,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun CabinCardAction(
    selected: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (selected) {
            OutlinedButton(
                onClick = onToggle,
                modifier = Modifier.fillMaxWidth(),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary),
            ) {
                Text("Убрать из заказа")
            }
        } else {
            Button(onClick = onToggle, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.ShoppingCart, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("В заказ")
            }
        }
    }
}

@Composable
private fun CabinPhotoPager(cabin: CustomerCabin, onPhoto: (Int) -> Unit, modifier: Modifier) {
    if (cabin.photos.isEmpty()) {
        Box(
            modifier.then(Modifier.testTag("cabin-photo-placeholder")),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    Icons.Default.PhotoLibrary,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
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
            AsyncImage(
                model = customerMediaUrl(photo.thumbnailUrl),
                contentDescription = "Бытовка ${cabin.accountingNo}, фото ${page + 1} из ${cabin.photos.size}",
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .clickable(onClickLabel = "Открыть фотографию") { onPhoto(page) },
            )
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

@Composable
private fun CabinFact(icon: androidx.compose.ui.graphics.vector.ImageVector? = null, text: String) {
    Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
        Row(
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            icon?.let { factIcon ->
                Icon(factIcon, contentDescription = null, modifier = Modifier.size(16.dp))
            }
            Text(text, style = MaterialTheme.typography.bodySmall)
        }
    }
}

internal fun usesWideCabinCard(widthDp: Float): Boolean = widthDp >= 920f

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun CabinFilterSheet(
    current: CabinFilters,
    facets: dev.buhanzaz.rwms.client.data.CabinFacets,
    warehouseId: String?,
    onDismiss: () -> Unit,
    onApply: (CabinFilters) -> Unit,
) {
    var draft by remember(current) { mutableStateOf(current) }
    val availableFacets = facets.warehouses.firstOrNull { it.warehouseId == warehouseId } ?: facets.selected
    val compatibleDimensions = remember(availableFacets, draft.cabinType) {
        availableFacets.compatibleDimensionsFor(draft.cabinType)
    }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.92f)
                .navigationBarsPadding()
                .testTag("catalog-filter-sheet"),
        ) {
            Text(
                "Фильтры",
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            )
            LazyColumn(
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentPadding = PaddingValues(start = 20.dp, top = 10.dp, end = 20.dp, bottom = 18.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
            item { Text("Тип", style = MaterialTheme.typography.titleMedium) }
            item {
                if (availableFacets.cabinTypes.isEmpty()) {
                    EmptyFacetMessage()
                } else {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        availableFacets.cabinTypes.forEach { value ->
                            FilterChip(
                                selected = facetValuesMatch(draft.cabinType, value),
                                onClick = {
                                    val selectedType = value.takeUnless {
                                        facetValuesMatch(draft.cabinType, value)
                                    }
                                    draft = draft.withCabinType(selectedType, availableFacets)
                                },
                                label = { Text(value) },
                            )
                        }
                    }
                }
            }
            item { Text("Отделка", style = MaterialTheme.typography.titleMedium) }
            item {
                if (availableFacets.finishes.isEmpty()) {
                    EmptyFacetMessage()
                } else {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        availableFacets.finishes.forEach { value ->
                            FilterChip(
                                selected = draft.finish == value,
                                onClick = { draft = draft.copy(finish = value.takeUnless { draft.finish == value }) },
                                label = { Text(value) },
                            )
                        }
                    }
                }
            }
            item { Text("Размер", style = MaterialTheme.typography.titleMedium) }
            item {
                if (compatibleDimensions.isEmpty()) {
                    EmptyFacetMessage()
                } else {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        compatibleDimensions.forEach { value ->
                            FilterChip(
                                selected = facetValuesMatch(draft.dimensions, value),
                                onClick = {
                                    draft = draft.copy(
                                        dimensions = value.takeUnless {
                                            facetValuesMatch(draft.dimensions, value)
                                        },
                                    )
                                },
                                label = { Text(value) },
                            )
                        }
                    }
                }
            }
            item { Text("Категория", style = MaterialTheme.typography.titleMedium) }
            item {
                if (availableFacets.categories.isEmpty()) {
                    EmptyFacetMessage()
                } else {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        availableFacets.categories.forEach { value ->
                            FilterChip(
                                selected = draft.category == value,
                                onClick = { draft = draft.copy(category = value.takeUnless { draft.category == value }) },
                                label = { Text(value) },
                            )
                        }
                    }
                }
            }
            item { Text("Пол", style = MaterialTheme.typography.titleMedium) }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = draft.linoleum == true,
                        onClick = { draft = draft.copy(linoleum = if (draft.linoleum == true) null else true) },
                        label = { Text("Линолеум") },
                    )
                    FilterChip(
                        selected = draft.linoleum == false,
                        onClick = { draft = draft.copy(linoleum = if (draft.linoleum == false) null else false) },
                        label = { Text("Без линолеума") },
                    )
                }
            }
            item { Text("Характеристики", style = MaterialTheme.typography.titleMedium) }
            item {
                if (availableFacets.characteristics.isEmpty()) {
                    EmptyFacetMessage()
                } else {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        availableFacets.characteristics.forEach { value ->
                            FilterChip(
                                selected = value in draft.characteristics,
                                onClick = {
                                    draft = draft.copy(
                                        characteristics = if (value in draft.characteristics) {
                                            draft.characteristics - value
                                        } else {
                                            draft.characteristics + value
                                        },
                                    )
                                },
                                label = { Text(value) },
                            )
                        }
                    }
                }
            }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.onSurface)
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = { draft = CabinFilters() },
                    modifier = Modifier.weight(1f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface),
                ) {
                    Text("Сбросить")
                }
                Button(onClick = { onApply(draft) }, modifier = Modifier.weight(1f)) { Text("Показать") }
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

@Composable
private fun EmptyFacetMessage() {
    Text(
        "Нет доступных вариантов на этом складе",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurface,
    )
}

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
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(20.dp)) {
            Text("Мебель · ${cabin?.accountingNo.orEmpty()}", style = MaterialTheme.typography.headlineSmall)
            Text("Отображаются только позиции в наличии на выбранном складе.")
            Spacer(Modifier.height(12.dp))
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
                    IconButton(onClick = { onQuantity(item, (quantity - 1).coerceAtLeast(0)) }, enabled = quantity > 0) {
                        Icon(Icons.Default.Remove, contentDescription = "Уменьшить ${item.name}")
                    }
                    Text(quantity.toString(), modifier = Modifier.width(28.dp))
                    IconButton(
                        onClick = { onQuantity(item, quantity + 1) },
                        enabled = quantity < maximum,
                    ) { Icon(Icons.Default.Add, contentDescription = "Добавить ${item.name}") }
                }
            }
            Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text("Готово") }
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
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.scrim) {
            val pager = rememberPagerState(initialPage = initialPage.coerceIn(cabin.photos.indices)) { cabin.photos.size }
            Box(Modifier.fillMaxSize()) {
                HorizontalPager(state = pager, modifier = Modifier.fillMaxSize()) { page ->
                    AsyncImage(
                        model = customerMediaUrl(cabin.photos[page].url),
                        contentDescription = "Фото ${page + 1} из ${cabin.photos.size}",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                FloatingActionButton(
                    onClick = onClose,
                    modifier = Modifier.align(Alignment.TopEnd).padding(24.dp),
                ) { Icon(Icons.Default.Close, contentDescription = "Закрыть фотографии") }
                Text(
                    "${pager.currentPage + 1} / ${cabin.photos.size}",
                    color = MaterialTheme.colorScheme.surface,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(28.dp),
                )
            }
        }
    }
}

@Composable
private fun LaunchedClose(onClose: () -> Unit) {
    androidx.compose.runtime.LaunchedEffect(Unit) { onClose() }
}

private fun customerMediaUrl(path: String): String = when {
    path.startsWith("/api/logistics/customer/v1/") -> "${BuildConfig.PUBLIC_BASE_URL}$path"
    path.startsWith("${BuildConfig.PUBLIC_BASE_URL}/api/logistics/customer/v1/") -> path
    else -> ""
}

private fun customerProfileMediaUrl(path: String): String = when {
    path.startsWith("/api/media/v1/") -> "${BuildConfig.PUBLIC_BASE_URL}$path"
    path.startsWith("${BuildConfig.PUBLIC_BASE_URL}/api/media/v1/") -> path
    else -> ""
}
