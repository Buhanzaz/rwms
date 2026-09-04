package dev.buhanzaz.rwms.rentalmanager.ui.screens

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import com.google.common.truth.Truth.assertThat
import dev.buhanzaz.rwms.rentalmanager.data.RentalManagerSession
import dev.buhanzaz.rwms.rentalmanager.network.CurrentUserDto
import dev.buhanzaz.rwms.rentalmanager.network.DesiredDeliveryWindowDto
import dev.buhanzaz.rwms.rentalmanager.network.OrderDto
import dev.buhanzaz.rwms.rentalmanager.network.OrderDesiredEquipmentDto
import dev.buhanzaz.rwms.rentalmanager.network.OrderRentalItemDto
import dev.buhanzaz.rwms.rentalmanager.network.OrderRentalTermDto
import dev.buhanzaz.rwms.rentalmanager.network.OrderUnitDto
import dev.buhanzaz.rwms.rentalmanager.network.OrderPermissionsDto
import dev.buhanzaz.rwms.rentalmanager.network.RentalClientDto
import dev.buhanzaz.rwms.rentalmanager.network.WarehouseAccessDto
import dev.buhanzaz.rwms.rentalmanager.network.WarehouseDto
import dev.buhanzaz.rwms.rentalmanager.ui.RentalManagerPhase
import dev.buhanzaz.rwms.rentalmanager.ui.RentalManagerUiState
import dev.buhanzaz.rwms.rentalmanager.ui.theme.RentalManagerTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RentalManagerScreensTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `client list shows normalized legal type and mobile action without foreign systems`() {
        val state = readyState()

        compose.setContent {
            RentalManagerTheme {
                ClientsScreen(
                    state = state,
                    onSearch = {},
                    onLoadMore = {},
                    onClient = {},
                    onCreate = {},
                    onDismissNotice = {},
                )
            }
        }

        compose.onNodeWithText("ООО Север").assertIsDisplayed()
        compose.onNodeWithText("Юрлицо · +79991234567").assertIsDisplayed()
        compose.onNodeWithContentDescription("Добавить клиента").assertIsDisplayed()
        assertThat(compose.onAllNodesWithText("LEGAL_ENTITY").fetchSemanticsNodes()).isEmpty()
        assertThat(compose.onAllNodesWithText("Логистика").fetchSemanticsNodes()).isEmpty()
        assertThat(compose.onAllNodesWithText("Админка").fetchSemanticsNodes()).isEmpty()
    }

    @Test
    fun `server permission controls order editing and incomplete draft has no false save transition`() {
        val readOnly = readyState().copy(
            selectedOrder = order(canEdit = false),
            selectedOrderId = ORDER_ID,
        )
        compose.setContent {
            RentalManagerTheme {
                OrderDetailScreen(
                    state = readOnly,
                    onRetry = {},
                    onUpdate = { _, _ -> error("read-only order must not update") },
                    onSave = { error("read-only order must not save") },
                    onCancel = { error("read-only order must not cancel") },
                    assistantBusy = false,
                    onOpenAssistant = { error("read-only order must not open assistant") },
                    onDismissNotice = {},
                )
            }
        }

        compose.onNodeWithText("Заказ доступен только для просмотра.")
            .performScrollTo()
            .assertIsDisplayed()
        assertThat(compose.onAllNodesWithText("Сохранить изменения").fetchSemanticsNodes()).isEmpty()
        assertThat(compose.onAllNodesWithText("Сохранить заказ").fetchSemanticsNodes()).isEmpty()
        assertThat(compose.onAllNodesWithText("Подобрать бытовки в чате").fetchSemanticsNodes()).isEmpty()
        assertThat(compose.onAllNodesWithText("Удалить черновик").fetchSemanticsNodes()).isEmpty()
    }

    @Test
    fun `incomplete editable draft explains readiness and keeps save disabled`() {
        val editable = readyState().copy(
            selectedOrder = order(canEdit = true),
            selectedOrderId = ORDER_ID,
        )
        compose.setContent {
            RentalManagerTheme {
                OrderDetailScreen(
                    state = editable,
                    onRetry = {},
                    onUpdate = { _, _ -> },
                    onSave = { error("incomplete draft must not save") },
                    onCancel = {},
                    assistantBusy = false,
                    onOpenAssistant = {},
                    onDismissNotice = {},
                )
            }
        }

        compose.onNodeWithText("Сохранить изменения").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Готовность к сохранению").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Сохранить заказ").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Ожидается подтверждение клиента.")
            .performScrollTo()
            .assertIsDisplayed()
        assertThat(compose.onAllNodesWithText("Черновик").fetchSemanticsNodes()).hasSize(1)
    }

    @Test
    fun `editable draft opens its order linked assistant flow`() {
        var openCount = 0
        val editable = readyState().copy(
            selectedOrder = order(canEdit = true),
            selectedOrderId = ORDER_ID,
        )
        compose.setContent {
            RentalManagerTheme {
                OrderDetailScreen(
                    state = editable,
                    onRetry = {},
                    onUpdate = { _, _ -> },
                    onSave = {},
                    onCancel = {},
                    assistantBusy = false,
                    onOpenAssistant = { openCount += 1 },
                    onDismissNotice = {},
                )
            }
        }

        compose.onNodeWithText("Подобрать бытовки в чате")
            .performScrollTo()
            .performClick()

        compose.runOnIdle { assertThat(openCount).isEqualTo(1) }
        compose.onNodeWithText(
            "Чат откроет единственный диалог этого заказа: подбор и ссылка для клиента " +
                "останутся связаны с текущим заказом.",
        ).performScrollTo().assertIsDisplayed()
        assertThat(compose.onAllNodesWithText(ORDER_ID).fetchSemanticsNodes()).isEmpty()
    }

    @Test
    fun `order detail renders selected cabin and rental term without transport enums`() {
        val detail = readyState().copy(
            selectedOrder = order(canEdit = true).copy(
                unitCount = 1,
                units = listOf(orderUnit(OrderRentalTermDto(3, "2026-09-05", "2026-12-05"))),
            ),
            selectedOrderId = ORDER_ID,
        )
        compose.setContent {
            RentalManagerTheme {
                OrderDetailScreen(
                    state = detail,
                    onRetry = {},
                    onUpdate = { _, _ -> },
                    onSave = {},
                    onCancel = {},
                    assistantBusy = false,
                    onOpenAssistant = {},
                    onDismissNotice = {},
                )
            }
        }

        compose.onNodeWithText("Бытовка БК-101").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Аренда").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("ЛДСП · 6 × 2,4 м · Чистовая · Стандарт")
            .performScrollTo()
            .assertIsDisplayed()
        compose.onNodeWithText("Стол × 1").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Срок аренды: 3 месяца").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Дата отгрузки: 5 сентября 2026").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Расчётная дата возврата: 5 декабря 2026")
            .performScrollTo()
            .assertIsDisplayed()
        assertThat(compose.onAllNodesWithText("RENTED").fetchSemanticsNodes()).isEmpty()
        assertThat(compose.onAllNodesWithText("ACTIVE").fetchSemanticsNodes()).isEmpty()
        assertThat(compose.onAllNodesWithText(RENTAL_ITEM_ID).fetchSemanticsNodes()).isEmpty()
        assertThat(compose.onAllNodesWithText(RESERVATION_ID).fetchSemanticsNodes()).isEmpty()
    }

    @Test
    fun `order detail explains when client has not selected a rental term`() {
        val detail = readyState().copy(
            selectedOrder = order(canEdit = true).copy(
                unitCount = 1,
                units = listOf(orderUnit(rentalTerm = null)),
            ),
            selectedOrderId = ORDER_ID,
        )
        compose.setContent {
            RentalManagerTheme {
                OrderDetailScreen(
                    state = detail,
                    onRetry = {},
                    onUpdate = { _, _ -> },
                    onSave = {},
                    onCancel = {},
                    assistantBusy = false,
                    onOpenAssistant = {},
                    onDismissNotice = {},
                )
            }
        }

        compose.onNodeWithText("Срок аренды пока не выбран клиентом.")
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun `first invalid client submit stays in the form`() {
        var submitCount = 0
        compose.setContent {
            RentalManagerTheme {
                CreateClientScreen(
                    commandRunning = false,
                    notice = null,
                    onDismissNotice = {},
                    onSubmit = { submitCount += 1 },
                )
            }
        }

        compose.onNodeWithText("Создать клиента").performScrollTo().performClick()

        assertThat(
            compose.onAllNodesWithText("Укажите имя или название клиента.").fetchSemanticsNodes(),
        ).hasSize(1)
        compose.runOnIdle { assertThat(submitCount).isEqualTo(0) }
    }

    @Test
    fun `first invalid draft submit does not call backend`() {
        var submitCount = 0
        compose.setContent {
            RentalManagerTheme {
                CreateOrderScreen(
                    client = client(),
                    loading = false,
                    commandRunning = false,
                    notice = null,
                    onDismissNotice = {},
                    onRetry = {},
                    onSubmit = { _, _ -> submitCount += 1 },
                )
            }
        }

        compose.onNodeWithText("Основной телефон").performTextReplacement("12")
        compose.onNodeWithText("Создать черновик").performScrollTo().performClick()

        compose.onNodeWithText("Укажите корректный телефон.").assertIsDisplayed()
        compose.runOnIdle { assertThat(submitCount).isEqualTo(0) }
    }

    @Test
    fun `first invalid order update does not call backend`() {
        var updateCount = 0
        val editable = readyState().copy(
            selectedOrder = order(canEdit = true),
            selectedOrderId = ORDER_ID,
        )
        compose.setContent {
            RentalManagerTheme {
                OrderDetailScreen(
                    state = editable,
                    onRetry = {},
                    onUpdate = { _, _ -> updateCount += 1 },
                    onSave = {},
                    onCancel = {},
                    assistantBusy = false,
                    onOpenAssistant = {},
                    onDismissNotice = {},
                )
            }
        }

        compose.onNodeWithText("Основной телефон").performTextReplacement("12")
        compose.onNodeWithText("Сохранить изменения").performScrollTo().performClick()

        compose.onNodeWithText("Укажите корректный телефон.").assertIsDisplayed()
        compose.runOnIdle { assertThat(updateCount).isEqualTo(0) }
    }

    @Test
    fun `editable draft cancellation requires explicit confirmation`() {
        var cancelCount = 0
        val editable = readyState().copy(
            selectedOrder = order(canEdit = true),
            selectedOrderId = ORDER_ID,
        )
        compose.setContent {
            RentalManagerTheme {
                OrderDetailScreen(
                    state = editable,
                    onRetry = {},
                    onUpdate = { _, _ -> },
                    onSave = {},
                    onCancel = { cancelCount += 1 },
                    assistantBusy = false,
                    onOpenAssistant = {},
                    onDismissNotice = {},
                )
            }
        }

        compose.onNodeWithText("Удалить черновик").performScrollTo().performClick()

        compose.onNodeWithText("Удалить черновик бронирования?").assertIsDisplayed()
        compose.onNodeWithText(
            "Бронирование будет логически отменено, а все активные резервирования " +
                "бытовок освобождены. Действие сохранится в истории.",
        ).assertIsDisplayed()
        compose.runOnIdle { assertThat(cancelCount).isEqualTo(0) }

        compose.onNodeWithText("Да, удалить").performClick()

        compose.runOnIdle { assertThat(cancelCount).isEqualTo(1) }
        assertThat(
            compose.onAllNodesWithText("Удалить черновик бронирования?").fetchSemanticsNodes(),
        ).isEmpty()
    }

    @Test
    fun `complete editable draft invokes version fenced save action`() {
        var saveCount = 0
        val completeDraft = readyState().copy(
            selectedOrder = order(canEdit = true).copy(
                deliveryAddress = "Санкт-Петербург, Невский проспект, 1",
                desiredDeliveryWindows = listOf(
                    DesiredDeliveryWindowDto("2026-09-05", "2026-09-05"),
                ),
                unitCount = 1,
                units = listOf(orderUnit(OrderRentalTermDto(3, "2026-09-05", "2026-12-05"))),
            ),
            selectedOrderId = ORDER_ID,
        )
        compose.setContent {
            RentalManagerTheme {
                OrderDetailScreen(
                    state = completeDraft,
                    onRetry = {},
                    onUpdate = { _, _ -> },
                    onSave = { saveCount += 1 },
                    onCancel = {},
                    assistantBusy = false,
                    onOpenAssistant = {},
                    onDismissNotice = {},
                )
            }
        }

        compose.onNodeWithText("Сохранить заказ")
            .performScrollTo()
            .assertIsEnabled()
            .performClick()

        compose.runOnIdle { assertThat(saveCount).isEqualTo(1) }
    }
}

private fun readyState(): RentalManagerUiState {
    val user = CurrentUserDto(
        id = USER_ID,
        username = "manager",
        displayName = "Менеджер",
        principalType = "USER",
        globalRole = "RENTAL_MANAGER",
        rentalAccess = true,
        warehouseAccessAll = false,
        warehouseAccesses = listOf(WarehouseAccessDto(WAREHOUSE_ID, "EDIT")),
    )
    val warehouse = WarehouseDto(
        id = WAREHOUSE_ID,
        version = 1,
        name = "Производство СПБ",
        city = "Санкт-Петербург",
        timeZone = "Europe/Moscow",
        active = true,
        lifecycleState = "ACTIVE",
        representative = false,
        production = true,
        mainWarehouse = false,
    )
    return RentalManagerUiState(
        phase = RentalManagerPhase.READY,
        session = RentalManagerSession(user, listOf(warehouse)),
        clients = listOf(client()),
    )
}

private fun client(): RentalClientDto = RentalClientDto(
    id = CLIENT_ID,
    version = 2,
    type = "LEGAL_ENTITY",
    displayName = "ООО Север",
    phone = "+79991234567",
    contactPerson = "Иван Петров",
    responsibleManagerId = USER_ID,
    responsibleManagerDisplayName = "Менеджер",
    updatedAt = "2026-09-02T10:00:00Z",
)

private fun order(canEdit: Boolean): OrderDto = OrderDto(
    id = ORDER_ID,
    version = 4,
    number = "A-100",
    status = "DRAFT",
    client = client(),
    managerId = USER_ID,
    managerDisplayName = "Менеджер",
    warehouseId = WAREHOUSE_ID,
    contactPhone = "+79991234567",
    unitCount = 0,
    updatedAt = "2026-09-02T10:00:00Z",
    permissions = OrderPermissionsDto(
        canEdit = canEdit,
        canReplaceUnits = false,
        canExtendRentalTerms = false,
        canViewOtherManagers = false,
    ),
)

private fun orderUnit(rentalTerm: OrderRentalTermDto?): OrderUnitDto = OrderUnitDto(
    reservationId = RESERVATION_ID,
    added = true,
    reservationState = "ACTIVE",
    unit = OrderRentalItemDto(
        id = RENTAL_ITEM_ID,
        version = 7,
        warehouseId = WAREHOUSE_ID,
        number = "БК-101",
        status = "RENTED",
        rentalType = "LDSP",
        dimensions = "6 × 2,4 м",
        finishing = "Чистовая",
        category = "Стандарт",
        characteristics = "С окнами",
        linoleum = true,
        tags = emptyList(),
        contents = emptyList(),
        createdAt = "2026-08-01T10:00:00Z",
        updatedAt = "2026-09-02T09:00:00Z",
    ),
    desiredContents = listOf(
        OrderDesiredEquipmentDto(
            equipmentId = EQUIPMENT_ID,
            equipmentName = "Стол",
            quantity = 1,
            reservationState = "ACTIVE",
        ),
    ),
    rentalTerm = rentalTerm,
)

private const val USER_ID = "00000000-0000-0000-0000-000000000010"
private const val WAREHOUSE_ID = "00000000-0000-0000-0000-000000000101"
private const val CLIENT_ID = "00000000-0000-0000-0000-000000000201"
private const val ORDER_ID = "00000000-0000-0000-0000-000000000301"
private const val RESERVATION_ID = "00000000-0000-0000-0000-000000000401"
private const val RENTAL_ITEM_ID = "00000000-0000-0000-0000-000000000501"
private const val EQUIPMENT_ID = "00000000-0000-0000-0000-000000000601"
