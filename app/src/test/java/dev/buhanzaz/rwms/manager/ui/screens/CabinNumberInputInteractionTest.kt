package dev.buhanzaz.rwms.manager.ui.screens

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import dev.buhanzaz.rwms.manager.network.InventorySessionDto
import dev.buhanzaz.rwms.manager.ui.MaintenanceEditorMode
import dev.buhanzaz.rwms.manager.ui.MaintenanceEditorState
import dev.buhanzaz.rwms.manager.ui.ManagerUiState
import dev.buhanzaz.rwms.manager.ui.theme.ManagerTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Verifies that public cabin-number fields retain focus and accept mixed identifiers. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h1280dp-xxhdpi")
class CabinNumberInputInteractionTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `inventory check cabin field focuses and accepts an alphanumeric number`() {
        compose.setContent {
            ManagerTheme(darkTheme = false) {
                InventoryDashboardScreen(
                    uiState = ManagerUiState(inventorySession = inventorySession()),
                    onBack = {},
                    onLoadInventory = {},
                    onPrepareNewNumber = { _, _, _ -> },
                    onOpenEditor = {},
                    onResolveConflict = { _, _, _, _ -> },
                    onOpenInventoryFinding = { _, _, _ -> },
                )
            }
        }

        inputCabinNumber()
    }

    @Test
    fun `maintenance cabin field focuses and accepts an alphanumeric number`() {
        compose.setContent {
            ManagerTheme(darkTheme = false) {
                var assetSearch by remember { mutableStateOf("") }
                MaintenanceEditorScreen(
                    editor = maintenanceEditor(),
                    uiState = ManagerUiState(assetSearch = assetSearch),
                    onBack = {},
                    onUpdateAssetSearch = { assetSearch = it },
                    onSearchAssets = {},
                    onSelectAsset = {},
                    onAddCatalogNodes = { _, _, _, _, _, _ -> true },
                    onToggleReworkCandidate = {},
                    onRefreshCatalog = {},
                    onEdit = {},
                    onOpenPhotos = {},
                    onAddPhoto = {},
                    onOpenFurniture = {},
                    onSelectCoverPhoto = {},
                    onSaveDraft = { complete -> complete() },
                    onSubmit = { complete -> complete() },
                )
            }
        }

        inputCabinNumber()
    }

    private fun inputCabinNumber() {
        cabinNumberField()
            .performClick()
            .assertIsFocused()
            .performTextInput("БК-12")
        compose.onNode(hasSetTextAction() and hasText("БК-12"))
            .assertIsFocused()
    }

    private fun cabinNumberField() = compose.onNode(
        hasSetTextAction() and hasText("Номер бытовки", substring = true),
    )

    private fun inventorySession() = InventorySessionDto(
        id = "inventory-1",
        sessionRevision = 1,
        warehouseId = "warehouse-1",
        warehouseVersion = 1,
        warehouseTimeZone = "Europe/Moscow",
        businessDate = "2026-09-09",
        lifecycle = "OPEN",
        expectedCount = 0,
        findingCount = 0,
        inspectedCount = 0,
        startedAt = "2026-09-09T09:00:00Z",
        publicationState = "DRAFT",
    )

    private fun maintenanceEditor() = MaintenanceEditorState(
        mode = MaintenanceEditorMode.ESTIMATE,
        entityId = null,
        expectedVersion = null,
        readOnly = false,
        selectedAsset = null,
        dispatchDate = "2026-09-09",
        sourceParty = "",
        lines = emptyList(),
        photoUris = emptyList(),
        readyMedia = emptyList(),
        priority = 3,
        step = 1,
    )
}
