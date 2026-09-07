package dev.buhanzaz.rwms.client.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import dev.buhanzaz.rwms.client.data.CabinFacetWarehouse
import dev.buhanzaz.rwms.client.data.CabinFacets
import dev.buhanzaz.rwms.client.data.CustomerCabin
import dev.buhanzaz.rwms.client.data.CustomerWarehouse

private val previewWarehouse = CustomerWarehouse(
    id = "preview-warehouse",
    name = "СПБ",
    city = "Санкт-Петербург",
    timezone = "Europe/Moscow",
    depotLatitude = 59.763806,
    depotLongitude = 30.471798,
)

@Preview(name = "City · clean location marker", widthDp = 404, heightDp = 874, showBackground = true)
@Composable
internal fun CustomerWarehouseReviewPreview() {
    CustomerTheme {
        CustomerStoreLaunchGate {
            WarehouseScreen(
                warehouses = listOf(previewWarehouse),
                busy = false,
                onSelect = { _, _ -> },
                onMenu = {},
                onProfile = {},
            )
        }
    }
}

@Preview(name = "Catalog · attribute pills", widthDp = 404, heightDp = 874, showBackground = true)
@Composable
internal fun CustomerCatalogReviewPreview() {
    CustomerTheme {
        CustomerStoreLaunchGate {
            CabinCatalogScreen(
                state = CustomerWorkflowState(
                    bootstrapping = false,
                    warehouses = listOf(previewWarehouse),
                    selectedWarehouse = previewWarehouse,
                    facets = CabinFacets(
                        listOf(
                            CabinFacetWarehouse(
                                warehouseId = previewWarehouse.id,
                                name = previewWarehouse.name,
                                city = previewWarehouse.city,
                                cabinTypes = listOf("БК-1", "БК-3"),
                                finishes = listOf("ДВП"),
                                dimensions = listOf("2.4x6"),
                                categories = listOf("Обычная", "ИТР", "Новая"),
                                characteristics = listOf("Вешалка", "Панорамные окна"),
                            ),
                        ),
                    ),
                    cabins = listOf(
                        CustomerCabin(
                            unitId = "preview-cabin",
                            version = 2,
                            accountingNo = "1150518",
                            pricingVersion = 3,
                            monthlyPriceRubles = 8_000,
                            type = "БК-1",
                            finish = "ДВП",
                            dimensions = "2.4x6",
                            category = "Обычная",
                            linoleum = false,
                            characteristics = listOf("Вешалка", "Панорамные окна"),
                        ),
                    ),
                    cabinTotalPages = 1,
                ),
                onMenu = {},
                onProfile = {},
                onFilters = {},
                onLoadMore = {},
                onToggleCabin = {},
                onEquipment = { _, _, _ -> },
                onPhoto = { _, _ -> },
                onWarehouse = {},
            )
        }
    }
}
