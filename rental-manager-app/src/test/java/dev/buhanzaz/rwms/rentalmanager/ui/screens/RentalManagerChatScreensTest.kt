package dev.buhanzaz.rwms.rentalmanager.ui.screens

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToKey
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.runtime.mutableStateOf
import com.google.common.truth.Truth.assertThat
import dev.buhanzaz.rwms.rentalmanager.network.AssistantClarificationKind
import dev.buhanzaz.rwms.rentalmanager.network.AssistantAvailableCabin
import dev.buhanzaz.rwms.rentalmanager.network.AssistantCabinFilterSuggestions
import dev.buhanzaz.rwms.rentalmanager.network.AssistantCabinSearchGroup
import dev.buhanzaz.rwms.rentalmanager.network.AssistantCabinSearchGroupResult
import dev.buhanzaz.rwms.rentalmanager.network.AssistantCabinSearchNotice
import dev.buhanzaz.rwms.rentalmanager.network.AssistantCabinSearchNoticeCode
import dev.buhanzaz.rwms.rentalmanager.network.AssistantCabinSearchProjection
import dev.buhanzaz.rwms.rentalmanager.network.AssistantCabinSearchResult
import dev.buhanzaz.rwms.rentalmanager.network.AssistantCabinSearchResultMode
import dev.buhanzaz.rwms.rentalmanager.network.AssistantCabinSelection
import dev.buhanzaz.rwms.rentalmanager.network.AssistantCabinStatus
import dev.buhanzaz.rwms.rentalmanager.network.AssistantClarificationOption
import dev.buhanzaz.rwms.rentalmanager.network.AssistantClarificationQuestion
import dev.buhanzaz.rwms.rentalmanager.network.AssistantClarificationStatus
import dev.buhanzaz.rwms.rentalmanager.network.AssistantConversation
import dev.buhanzaz.rwms.rentalmanager.network.AssistantConversationDetail
import dev.buhanzaz.rwms.rentalmanager.network.AssistantMessage
import dev.buhanzaz.rwms.rentalmanager.network.AssistantMessageRole
import dev.buhanzaz.rwms.rentalmanager.network.RentalClientDto
import dev.buhanzaz.rwms.rentalmanager.network.RentalPresentationDto
import dev.buhanzaz.rwms.rentalmanager.network.RentalPresentationMode
import dev.buhanzaz.rwms.rentalmanager.network.RentalPresentationState
import dev.buhanzaz.rwms.rentalmanager.ui.RentalManagerChatUiState
import dev.buhanzaz.rwms.rentalmanager.ui.RentalManagerNotice
import dev.buhanzaz.rwms.rentalmanager.ui.RentalManagerUiState
import dev.buhanzaz.rwms.rentalmanager.ui.theme.RentalManagerTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RentalManagerChatScreensTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `conversation filter separates active dialogs from history`() {
        compose.setContent {
            RentalManagerTheme {
                ChatConversationsScreen(
                    state = RentalManagerChatUiState(
                        conversations = listOf(
                            conversation(ACTIVE_CONVERSATION_ID, "ООО Актив", archived = false),
                            conversation(ARCHIVED_CONVERSATION_ID, "Иван Архивный", archived = true),
                        ),
                    ),
                    onRefresh = {},
                    onConversation = {},
                    onCreate = {},
                    onDismissNotice = {},
                )
            }
        }

        compose.onNodeWithText("ООО Актив").assertIsDisplayed()
        compose.onNodeWithText("Иван Архивный").assertDoesNotExist()

        compose.onNodeWithText("История").performClick()

        compose.onNodeWithText("Иван Архивный").assertIsDisplayed()
        compose.onNodeWithText("ООО Актив").assertDoesNotExist()
    }

    @Test
    fun `conversation exposes only the first pending clarification`() {
        var answer: Pair<String, String>? = null
        val first = clarification(
            id = FIRST_QUESTION_ID,
            sequenceNumber = 1,
            prompt = "Какой тип бытовки нужен?",
            optionId = FIRST_OPTION_ID,
            optionLabel = "ЛДСП",
        )
        val second = clarification(
            id = SECOND_QUESTION_ID,
            sequenceNumber = 2,
            prompt = "Нужна ли отделка?",
            optionId = SECOND_OPTION_ID,
            optionLabel = "Да",
        )

        compose.setContent {
            RentalManagerTheme {
                ChatConversationScreen(
                    state = chatDetailState(
                        clarifications = listOf(second, first),
                    ),
                    onRetry = {},
                    onArchive = {},
                    onSend = {},
                    onAnswer = { questionId, optionId -> answer = questionId to optionId },
                    onDismissNotice = {},
                )
            }
        }

        compose.onNodeWithText("Какой тип бытовки нужен?").assertIsDisplayed()
        compose.onNodeWithText("Нужна ли отделка?").assertDoesNotExist()
        compose.onNodeWithText("ЛДСП").performClick()
        compose.runOnIdle {
            assertThat(answer).isEqualTo(FIRST_QUESTION_ID to FIRST_OPTION_ID)
        }
    }

    @Test
    fun `archived conversation is strictly read only`() {
        compose.setContent {
            RentalManagerTheme {
                ChatConversationScreen(
                    state = chatDetailState(archived = true),
                    onRetry = {},
                    onArchive = { error("archive action must not be available") },
                    onSend = { error("composer must not be available") },
                    onAnswer = { _, _ -> error("clarification actions must not be available") },
                    onDismissNotice = {},
                )
            }
        }

        compose.onNodeWithText(
            "Диалог находится в истории и доступен только для чтения.",
        ).assertIsDisplayed()
        assertThat(compose.onAllNodesWithText("Сообщение").fetchSemanticsNodes()).isEmpty()
        assertThat(compose.onAllNodesWithText("В историю").fetchSemanticsNodes()).isEmpty()
        compose.onNodeWithContentDescription("Отправить сообщение").assertDoesNotExist()
    }

    @Test
    fun `conversation renders persisted and live messages with tool progress`() {
        val persisted = AssistantMessage(
            id = PERSISTED_MESSAGE_ID,
            role = AssistantMessageRole.ASSISTANT,
            content = "Сохранённый ответ",
            createdAt = UPDATED_AT,
        )
        compose.setContent {
            RentalManagerTheme {
                ChatConversationScreen(
                    state = chatDetailState(messages = listOf(persisted)).copy(
                        sending = true,
                        liveUserContent = "Проверьте доступность",
                        liveAssistantContent = "Проверяю варианты",
                        toolRunning = true,
                    ),
                    onRetry = {},
                    onArchive = {},
                    onSend = {},
                    onAnswer = { _, _ -> },
                    onDismissNotice = {},
                )
            }
        }

        compose.onNodeWithText("Сохранённый ответ").assertIsDisplayed()
        compose.onNodeWithText("Проверьте доступность").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Проверяю варианты").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Проверяем доступные варианты…")
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun `new conversation selects a real client and prioritizes chat notice`() {
        var selectedClientId: String? = null
        val client = RentalClientDto(
            id = CLIENT_ID,
            version = 2,
            type = "LEGAL_ENTITY",
            displayName = "ООО Север",
            phone = "+79991234567",
            contactPerson = "Иван Петров",
            responsibleManagerId = MANAGER_ID,
            updatedAt = UPDATED_AT,
        )
        val managerState = RentalManagerUiState(
            clients = listOf(client),
            notice = RentalManagerNotice("Ошибка поиска клиентов", isError = true),
        )
        val chatState = RentalManagerChatUiState(
            notice = RentalManagerNotice("Диалог уже создаётся", isError = true),
        )

        compose.setContent {
            RentalManagerTheme {
                NewConversationScreen(
                    managerState = managerState,
                    chatState = chatState,
                    onSearchClients = {},
                    onLoadMoreClients = {},
                    onCreateConversation = { selectedClientId = it },
                    onDismissNotice = {},
                )
            }
        }

        compose.onNodeWithText("Диалог уже создаётся").assertIsDisplayed()
        compose.onNodeWithText("Ошибка поиска клиентов").assertDoesNotExist()
        compose.onNodeWithText("ООО Север").performClick()
        compose.runOnIdle { assertThat(selectedClientId).isEqualTo(CLIENT_ID) }
    }

    @Test
    fun `conversation renders Russian shortage and lets manager release selected cabins`() {
        val selectionChanges = mutableListOf<Set<String>>()
        val shortage = AssistantCabinSearchNotice(
            code = AssistantCabinSearchNoticeCode.CABINS_PARTIALLY_FOUND,
            groups = listOf(SEARCH_GROUP),
            requestedQuantity = 2,
            foundQuantity = 1,
        )
        val message = AssistantMessage(
            id = PERSISTED_MESSAGE_ID,
            role = AssistantMessageRole.ASSISTANT,
            content = "Нашёл доступные варианты",
            createdAt = UPDATED_AT,
            searchNotices = listOf(shortage),
        )

        val uiState = mutableStateOf(chatDetailState(messages = listOf(message)))
        compose.setContent {
            RentalManagerTheme {
                ChatConversationScreen(
                    state = uiState.value,
                    onRetry = {},
                    onArchive = {},
                    onSend = {},
                    onAnswer = { _, _ -> },
                    onSelectionChange = { selectedIds ->
                        selectionChanges += selectedIds
                        val detail = requireNotNull(uiState.value.detail)
                        uiState.value = uiState.value.copy(
                            detail = detail.copy(
                                currentSelection = detail.currentSelection
                                    ?.let { selection ->
                                        selection.copy(
                                            rentalItemIds = selection.rentalItemIds.filter(
                                                selectedIds::contains,
                                            ),
                                            items = selection.items.filter { cabin ->
                                                selectedIds.contains(cabin.id)
                                            },
                                        )
                                    }
                                    ?.takeIf { it.rentalItemIds.isNotEmpty() },
                            ),
                        )
                    },
                    onPublishPresentation = {},
                    onDismissNotice = {},
                )
            }
        }

        compose.onNodeWithText(
            "Найдено меньше, чем запрошено: «ЛДСП · Чистовая · 6×2,4 · Стандарт · " +
                "С окнами · С линолеумом». Запрошено: 2; найдено: 1.",
        ).assertExists()

        compose.runOnIdle {
            uiState.value = selectionChatState(messages = listOf(message))
        }
        compose.onNodeWithTag(CHAT_CONVERSATION_CONTENT_TAG)
            .performScrollToKey("current-selection")
        compose.onNodeWithText("БК-101").assertExists()
        compose.onAllNodesWithText(
            "Аренда · 6×2,4 · Чистовая · Стандарт · С окнами · С линолеумом",
        ).assertCountEquals(2)
        compose.onNodeWithText("Выбрано: 2").assertExists()

        compose.onAllNodes(isToggleable())[0].performClick()
        compose.onNodeWithTag(CHAT_CONVERSATION_CONTENT_TAG)
            .performScrollToKey("current-selection")
        compose.onNodeWithText("Освободить всю подборку")
            .performScrollTo()
            .performSemanticsAction(SemanticsActions.OnClick)

        compose.runOnIdle {
            assertThat(selectionChanges).containsExactly(
                setOf(RENTAL_ITEM_B),
                emptySet<String>(),
            ).inOrder()
        }
    }

    @Test
    fun `conversation renders public offer actions without claiming automatic delivery`() {
        compose.setContent {
            RentalManagerTheme {
                ChatConversationScreen(
                    state = selectionChatState().copy(presentation = ACTIVE_PRESENTATION),
                    onRetry = {},
                    onArchive = {},
                    onSend = {},
                    onAnswer = { _, _ -> },
                    onSelectionChange = {},
                    onPublishPresentation = {},
                    onDismissNotice = {},
                )
            }
        }

        compose.onNodeWithText(
            "https://77-90-158-90.sslip.io/offer/offer-token",
        ).assertExists()
        compose.onNodeWithText("Копировать ссылку").assertExists()
        compose.onNodeWithText("Поделиться").assertExists()
        assertThat(compose.onAllNodesWithText("Ссылка отправлена клиенту").fetchSemanticsNodes())
            .isEmpty()
    }

    @Test
    fun `booking presentation blocks creating a replacement link`() {
        compose.setContent {
            RentalManagerTheme {
                ChatConversationScreen(
                    state = selectionChatState().copy(
                        presentation = ACTIVE_PRESENTATION.copy(
                            state = RentalPresentationState.BOOKING_PENDING,
                            canConfirm = false,
                        ),
                    ),
                    onRetry = {},
                    onArchive = {},
                    onSend = {},
                    onAnswer = { _, _ -> },
                    onSelectionChange = {},
                    onPublishPresentation = {},
                    onDismissNotice = {},
                )
            }
        }

        compose.onNodeWithText("Клиент оформляет заказ").assertExists()
        compose.onNodeWithTag(CHAT_CONVERSATION_CONTENT_TAG)
            .performScrollToKey("current-selection")
        compose.onNodeWithText("Создать ссылку для клиента").assertIsNotEnabled()
    }
}

private fun chatDetailState(
    archived: Boolean = false,
    messages: List<AssistantMessage> = emptyList(),
    clarifications: List<AssistantClarificationQuestion> = emptyList(),
): RentalManagerChatUiState {
    val conversation = conversation(ACTIVE_CONVERSATION_ID, "ООО Север", archived)
    return RentalManagerChatUiState(
        selectedConversationId = conversation.id,
        detail = AssistantConversationDetail(
            conversation = conversation,
            messages = messages,
            clarifications = clarifications,
        ),
    )
}

private fun selectionChatState(
    messages: List<AssistantMessage> = emptyList(),
): RentalManagerChatUiState {
    val conversation = conversation(ACTIVE_CONVERSATION_ID, "ООО Север", archived = false)
    return RentalManagerChatUiState(
        selectedConversationId = conversation.id,
        detail = AssistantConversationDetail(
            conversation = conversation,
            messages = messages,
            lastSearchResult = SEARCH_RESULT,
            clarifications = emptyList(),
            currentSelection = AssistantCabinSelection(
                inquiryId = INQUIRY_ID,
                warehouseId = WAREHOUSE_ID,
                expiresAt = FUTURE_EXPIRY,
                rentalItemIds = listOf(RENTAL_ITEM_A, RENTAL_ITEM_B),
                items = listOf(AVAILABLE_CABIN_A, AVAILABLE_CABIN_B),
            ),
        ),
    )
}

private fun conversation(
    id: String,
    displayName: String,
    archived: Boolean,
): AssistantConversation = AssistantConversation(
    id = id,
    version = 3,
    clientId = CLIENT_ID,
    rentalInquiryId = INQUIRY_ID,
    rentalOrderId = null,
    clientType = "LEGAL_ENTITY",
    clientDisplayName = displayName,
    archived = archived,
    archivedAt = UPDATED_AT.takeIf { archived },
    createdAt = UPDATED_AT,
    updatedAt = UPDATED_AT,
)

private fun clarification(
    id: String,
    sequenceNumber: Int,
    prompt: String,
    optionId: String,
    optionLabel: String,
): AssistantClarificationQuestion = AssistantClarificationQuestion(
    id = id,
    branchKey = "branch-$sequenceNumber",
    sequenceNumber = sequenceNumber,
    kind = AssistantClarificationKind.CABIN_TYPE,
    prompt = prompt,
    status = AssistantClarificationStatus.PENDING,
    options = listOf(
        AssistantClarificationOption(
            id = optionId,
            label = optionLabel,
            value = optionLabel,
        ),
        AssistantClarificationOption(
            id = "00000000-0000-0000-0000-${id.takeLast(12).replaceFirstChar { '9' }}",
            label = "Другой вариант",
            value = "OTHER",
        ),
    ),
    answeredOptionId = null,
    createdAt = UPDATED_AT,
    answeredAt = null,
)

private const val ACTIVE_CONVERSATION_ID = "00000000-0000-0000-0000-000000000401"
private const val ARCHIVED_CONVERSATION_ID = "00000000-0000-0000-0000-000000000402"
private const val CLIENT_ID = "00000000-0000-0000-0000-000000000201"
private const val MANAGER_ID = "00000000-0000-0000-0000-000000000010"
private const val INQUIRY_ID = "00000000-0000-0000-0000-000000000501"
private const val PERSISTED_MESSAGE_ID = "00000000-0000-0000-0000-000000000601"
private const val FIRST_QUESTION_ID = "00000000-0000-0000-0000-000000000701"
private const val SECOND_QUESTION_ID = "00000000-0000-0000-0000-000000000702"
private const val FIRST_OPTION_ID = "00000000-0000-0000-0000-000000000801"
private const val SECOND_OPTION_ID = "00000000-0000-0000-0000-000000000802"
private const val WAREHOUSE_ID = "00000000-0000-4000-8000-000000000901"
private const val RENTAL_ITEM_A = "00000000-0000-4000-8000-000000000902"
private const val RENTAL_ITEM_B = "00000000-0000-4000-8000-000000000903"
private const val PRESENTATION_ID = "00000000-0000-4000-8000-000000000904"
private const val UPDATED_AT = "2026-09-02T10:00:00Z"
private const val FUTURE_EXPIRY = "2099-09-02T12:00:00+03:00"

private val SEARCH_GROUP = AssistantCabinSearchGroup(
    cabinType = "LDSP",
    finish = "Чистовая",
    dimensions = "6×2,4",
    category = "Стандарт",
    characteristics = "С окнами",
    linoleum = true,
    quantity = 2,
)

private val AVAILABLE_CABIN_A = AssistantAvailableCabin(
    id = RENTAL_ITEM_A,
    version = 2,
    warehouseId = WAREHOUSE_ID,
    number = "БК-101",
    status = AssistantCabinStatus.FREE,
    rentalType = "Аренда",
    dimensions = "6×2,4",
    finishing = "Чистовая",
    category = "Стандарт",
    characteristics = "С окнами",
    linoleum = true,
    updatedAt = UPDATED_AT,
)

private val AVAILABLE_CABIN_B = AVAILABLE_CABIN_A.copy(
    id = RENTAL_ITEM_B,
    number = "БК-102",
)

private val SEARCH_RESULT = AssistantCabinSearchResult(
    tool = "search_available_cabins",
    resultMode = AssistantCabinSearchResultMode.REPLACE,
    filterSuggestions = AssistantCabinFilterSuggestions(
        cabinTypes = listOf("ЛДСП"),
        finishes = listOf("Чистовая"),
        dimensions = listOf("6×2,4"),
        categories = listOf("Стандарт"),
        characteristics = listOf("С окнами"),
    ),
    notices = emptyList(),
    data = AssistantCabinSearchProjection(
        warehouseId = WAREHOUSE_ID,
        expiresAt = FUTURE_EXPIRY,
        groups = listOf(
            AssistantCabinSearchGroupResult(
                group = SEARCH_GROUP,
                cabins = listOf(AVAILABLE_CABIN_A, AVAILABLE_CABIN_B),
            ),
        ),
    ),
)

private val ACTIVE_PRESENTATION = RentalPresentationDto(
    id = PRESENTATION_ID,
    version = 1,
    revision = 1,
    inquiryId = INQUIRY_ID,
    warehouseId = WAREHOUSE_ID,
    state = RentalPresentationState.ACTIVE,
    expiresAt = FUTURE_EXPIRY,
    viewUntil = FUTURE_EXPIRY,
    canConfirm = true,
    publicPath = "/offer/offer-token",
    bookedOrderId = null,
    mode = RentalPresentationMode.NORMAL,
    replacementUnitIds = emptyList(),
    requiredSelectionCount = null,
    requiresDesiredDeliveryWindows = false,
    desiredDeliveryWindows = emptyList(),
    equipmentAvailability = emptyList(),
    groups = emptyList(),
)
