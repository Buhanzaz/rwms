package dev.buhanzaz.rwms.manager.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.google.common.truth.Truth.assertThat
import dev.buhanzaz.rwms.manager.ui.screens.TransferDetailScreen
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Native Compose interaction coverage for the transfer arrival priority decision. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ManagerTransferArrivalScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val state = mutableStateOf(transferArrivalTestState())
    private var confirmedPriority: Int? = null
    private var confirmations = 0

    @Test
    fun repairArrivalRequiresASelectedPriorityAndAtLeastOnePhoto() {
        showArrival(priorityRequired = true, withPhoto = false)
        compose.onNodeWithText("Подтвердить приёмку").performScrollTo().assertIsNotEnabled()
        (1..5).forEach { priority ->
            compose.onNodeWithText("$priority").performScrollTo().assertIsNotSelected()
        }
        compose.onNodeWithText("3").performScrollTo().performClick().assertIsSelected()
        compose.onNodeWithText("Подтвердить приёмку").performScrollTo().assertIsNotEnabled()

        compose.runOnIdle { state.value = state.value.copy(transferPhotoUris = listOf("content://photo")) }
        compose.onNodeWithText("Подтвердить приёмку").performScrollTo().assertIsEnabled().performClick()
        compose.runOnIdle {
            assertThat(confirmations).isEqualTo(1)
            assertThat(confirmedPriority).isEqualTo(3)
        }
    }

    @Test
    fun arrivalWithoutARepairDoesNotAskForPriority() {
        showArrival(priorityRequired = false, withPhoto = true)
        compose.onNodeWithText("Приоритет продолжения ремонта").assertDoesNotExist()
        compose.onNodeWithText("Подтвердить приёмку").performScrollTo().assertIsEnabled().performClick()
        compose.runOnIdle {
            assertThat(confirmations).isEqualTo(1)
            assertThat(confirmedPriority).isNull()
        }
    }

    @Test
    fun changedDocumentBusyStateOrLostAccessDisablesArrival() {
        showArrival(priorityRequired = false, withPhoto = true)
        val original = state.value
        listOf(
            original.copy(selectedTransfer = original.selectedTransfer?.copy(version = 8)),
            original.copy(busy = true),
            original.copy(currentUser = original.currentUser?.copy(warehouseAccesses = emptyList())),
        ).forEach { blocked ->
            compose.runOnIdle { state.value = blocked }
            compose.onNodeWithText("Подтвердить приёмку").performScrollTo().assertIsNotEnabled()
        }
        compose.runOnIdle { assertThat(confirmations).isEqualTo(0) }
    }

    private fun showArrival(priorityRequired: Boolean, withPhoto: Boolean) {
        state.value = state.value.copy(
            transferArrival = TransferArrivalState("transfer-1", 7, "line-1", 4, priorityRequired),
            transferPhotoUris = if (withPhoto) listOf("content://photo") else emptyList(),
        )
        compose.setContent {
            MaterialTheme {
                TransferDetailScreen(
                    uiState = state.value,
                    onBack = {},
                    onDepartTransfer = {},
                    onArriveTransfer = {},
                    onDepart = {},
                    onStartArrival = {},
                    onOpenArrivalPhotos = {},
                    onArrivalPriority = { priority ->
                        state.value = state.value.copy(
                            transferArrival = state.value.transferArrival?.copy(priority = priority),
                        )
                    },
                    onArrive = {
                        confirmedPriority = state.value.transferArrival?.priority
                        confirmations++
                    },
                    onCloseArrival = {},
                    onCancel = {},
                    onReconcile = {},
                )
            }
        }
    }
}
