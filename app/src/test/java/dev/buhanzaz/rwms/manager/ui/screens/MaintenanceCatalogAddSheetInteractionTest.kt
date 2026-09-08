package dev.buhanzaz.rwms.manager.ui.screens

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.google.common.truth.Truth.assertThat
import dev.buhanzaz.rwms.manager.network.CatalogNodeDto
import dev.buhanzaz.rwms.manager.ui.MaintenanceEditorMode
import dev.buhanzaz.rwms.manager.ui.MaintenanceEditorState
import dev.buhanzaz.rwms.manager.ui.ManagerUiState
import dev.buhanzaz.rwms.manager.ui.theme.ManagerTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Exercises the fixed catalog-add action on a compact manager screen. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h640dp-xxhdpi")
class MaintenanceCatalogAddSheetInteractionTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `work catalog add keeps next action inside a compact screen`() {
        val workName = "Длинная работа " + "с подробным описанием ".repeat(40)
        renderCatalogAdd(nodeType = "WORK", name = workName)

        openAndAssertNextVisible(workName)
    }

    @Test
    fun `material catalog add keeps next action inside a compact screen`() {
        renderCatalogAdd(nodeType = "MATERIAL", name = "Тестовый материал")

        openAndAssertNextVisible("Тестовый материал")
    }

    private fun openAndAssertNextVisible(nodeName: String) {
        compose.onNodeWithContentDescription(nodeName).performClick()
        compose.waitForIdle()

        val nextBounds = compose.onNodeWithTag("maintenance-catalog-add-next")
            .assertIsDisplayed()
            .fetchSemanticsNode()
            .boundsInRoot
        val rootBounds = compose.onAllNodes(isRoot())[0].fetchSemanticsNode().boundsInRoot
        assertThat(nextBounds.top).isAtLeast(rootBounds.top)
        assertThat(nextBounds.bottom).isAtMost(rootBounds.bottom)
    }

    private fun renderCatalogAdd(nodeType: String, name: String) {
        val editor = mutableStateOf(editor())
        val node = catalogNode(nodeType = nodeType, name = name)
        compose.setContent {
            ManagerTheme(darkTheme = false) {
                MaintenanceCatalogStep(
                    editor = editor.value,
                    uiState = ManagerUiState(maintenanceCatalogNodes = listOf(node)),
                    onAddCatalogNodes = { _, _, _, _, _, _ -> true },
                    onRefreshCatalog = {},
                    onEdit = { update -> editor.value = update(editor.value) },
                    onContinue = {},
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }

    private fun catalogNode(nodeType: String, name: String) = CatalogNodeDto(
        id = nodeType.lowercase(),
        catalogVersionId = "catalog-1",
        nodeType = nodeType,
        name = name,
        active = true,
        unit = "шт.",
        unitPrice = "10.00",
        durationMinutes = if (nodeType == "WORK") 30 else 0,
        showInMainMenu = true,
    )

    private fun editor() = MaintenanceEditorState(
        mode = MaintenanceEditorMode.ESTIMATE,
        entityId = "estimate-1",
        expectedVersion = 1,
        readOnly = false,
        selectedAsset = null,
        dispatchDate = "2026-09-09",
        sourceParty = "",
        lines = emptyList(),
        photoUris = emptyList(),
        readyMedia = emptyList(),
        priority = 3,
        step = 3,
    )
}
