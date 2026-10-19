package dev.buhanzaz.rwms.rentalmanager.ui

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.google.common.truth.Truth.assertThat
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import dev.buhanzaz.rwms.rentalmanager.auth.RentalManagerAuthState
import dev.buhanzaz.rwms.rentalmanager.data.AssistantRepository
import dev.buhanzaz.rwms.rentalmanager.data.RentalPresentationDataSource
import dev.buhanzaz.rwms.rentalmanager.data.RentalPricingDataSource
import dev.buhanzaz.rwms.rentalmanager.network.AssistantApi
import dev.buhanzaz.rwms.rentalmanager.network.AssistantAvailableCabin
import dev.buhanzaz.rwms.rentalmanager.network.AssistantCabinFilterSuggestions
import dev.buhanzaz.rwms.rentalmanager.network.AssistantCabinSearchGroup
import dev.buhanzaz.rwms.rentalmanager.network.AssistantCabinSearchGroupResult
import dev.buhanzaz.rwms.rentalmanager.network.AssistantCabinSearchProjection
import dev.buhanzaz.rwms.rentalmanager.network.AssistantCabinSearchResult
import dev.buhanzaz.rwms.rentalmanager.network.AssistantCabinSearchResultMode
import dev.buhanzaz.rwms.rentalmanager.network.AssistantCabinStatus
import dev.buhanzaz.rwms.rentalmanager.network.AssistantCabinSelection
import dev.buhanzaz.rwms.rentalmanager.network.AssistantCabinSelectionRequest
import dev.buhanzaz.rwms.rentalmanager.network.AssistantClarificationKind
import dev.buhanzaz.rwms.rentalmanager.network.AssistantClarificationOption
import dev.buhanzaz.rwms.rentalmanager.network.AssistantClarificationQuestion
import dev.buhanzaz.rwms.rentalmanager.network.AssistantClarificationStatus
import dev.buhanzaz.rwms.rentalmanager.network.AssistantClientSummary
import dev.buhanzaz.rwms.rentalmanager.network.AssistantConversation
import dev.buhanzaz.rwms.rentalmanager.network.AssistantConversationDetail
import dev.buhanzaz.rwms.rentalmanager.network.AssistantMessage
import dev.buhanzaz.rwms.rentalmanager.network.AssistantMessageRole
import dev.buhanzaz.rwms.rentalmanager.network.AssistantRentalInquirySummary
import dev.buhanzaz.rwms.rentalmanager.network.AssistantTurnRequest
import dev.buhanzaz.rwms.rentalmanager.network.CreateAssistantConversationRequest
import dev.buhanzaz.rwms.rentalmanager.network.CreateAssistantConversationResponse
import dev.buhanzaz.rwms.rentalmanager.network.RentalPresentationDto
import dev.buhanzaz.rwms.rentalmanager.network.RentalPresentationGroupRequest
import dev.buhanzaz.rwms.rentalmanager.network.RentalPresentationMode
import dev.buhanzaz.rwms.rentalmanager.network.RentalPresentationState
import java.io.IOException
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.Source
import okio.Timeout
import okio.buffer
import org.junit.After
import org.junit.AfterClass
import org.junit.Before
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response

@OptIn(ExperimentalCoroutinesApi::class)
class RentalManagerChatViewModelTest {
    private val mainDispatcher: TestDispatcher = StandardTestDispatcher()
    private val createdViewModels = mutableListOf<RentalManagerChatViewModel>()

    @Before
    fun setUpMainDispatcher() {
        Dispatchers.setMain(mainDispatcher)
    }

    @After
    fun cancelViewModels() {
        createdViewModels.forEach { it.viewModelScope.cancel() }
        mainDispatcher.scheduler.advanceUntilIdle()
    }

    @Test
    fun `activate loads the authoritative conversation list`() = runTest {
        val fixture = fixture()

        fixture.viewModel.activate(ACTOR_A)
        advanceUntilIdle()

        assertThat(fixture.api.conversationListCalls).isEqualTo(1)
        assertThat(fixture.viewModel.state.value.actorId).isEqualTo(ACTOR_A)
        assertThat(fixture.viewModel.state.value.conversations).containsExactly(BASE_CONVERSATION)
        assertThat(fixture.viewModel.state.value.conversationsLoading).isFalse()
    }

    @Test
    fun `failed create keeps its identity for retry and scopes it to the actor`() = runTest {
        val fixture = fixture().also {
            it.api.conversationsResponse = emptyList()
            it.api.createFailuresRemaining = 3
        }
        fixture.viewModel.activate(ACTOR_A)
        advanceUntilIdle()

        fixture.viewModel.createConversation(CLIENT_ID)
        advanceUntilIdle()
        fixture.viewModel.createConversation(CLIENT_ID)
        advanceUntilIdle()
        fixture.viewModel.activate(ACTOR_B)
        advanceUntilIdle()
        fixture.viewModel.createConversation(CLIENT_ID)
        advanceUntilIdle()

        assertThat(fixture.api.createRequests).hasSize(3)
        val firstId = fixture.api.createRequests[0].conversationId
        assertThat(firstId).isNotNull()
        assertThat(fixture.api.createRequests[1].conversationId).isEqualTo(firstId)
        assertThat(fixture.api.createRequests[2].conversationId).isNotEqualTo(firstId)
    }

    @Test
    fun `successful create updates the list and emits navigation`() = runTest {
        val fixture = fixture().also { it.api.conversationsResponse = emptyList() }
        fixture.viewModel.activate(ACTOR_A)
        advanceUntilIdle()
        val navigation = backgroundScope.async(start = CoroutineStart.UNDISPATCHED) {
            fixture.viewModel.events.first()
        }

        fixture.viewModel.createConversation(CLIENT_ID)
        advanceUntilIdle()

        val createdId = fixture.api.createRequests.single().conversationId
        assertThat(createdId).isNotNull()
        assertThat(fixture.viewModel.state.value.conversations.single().id).isEqualTo(createdId)
        assertThat(navigation.await()).isEqualTo(
            RentalManagerChatNavigationEvent.OpenConversation(requireNotNull(createdId)),
        )
    }

    @Test
    fun `order entry opens the single conversation linked to the exact order`() = runTest {
        val fixture = fixture().also { it.api.conversationsResponse = emptyList() }
        fixture.viewModel.activate(ACTOR_A)
        advanceUntilIdle()
        val navigation = backgroundScope.async(start = CoroutineStart.UNDISPATCHED) {
            fixture.viewModel.events.first()
        }

        fixture.viewModel.createConversation(CLIENT_ID, ORDER_ID)
        advanceUntilIdle()

        val request = fixture.api.createRequests.single()
        assertThat(request.clientId).isEqualTo(CLIENT_ID)
        assertThat(request.rentalOrderId).isEqualTo(ORDER_ID)
        assertThat(fixture.viewModel.state.value.conversations.single().rentalOrderId)
            .isEqualTo(ORDER_ID)
        assertThat(navigation.await()).isEqualTo(
            RentalManagerChatNavigationEvent.OpenConversation(requireNotNull(request.conversationId)),
        )
    }

    @Test
    fun `send posts once exposes live stream state and then reloads authoritative state`() = runTest {
        val fixture = fixture()
        fixture.viewModel.activate(ACTOR_A)
        fixture.viewModel.openConversation(CONVERSATION_ID)
        advanceUntilIdle()
        val listCallsBefore = fixture.api.conversationListCalls
        val detailCallsBefore = fixture.api.conversationDetailCalls
        val authoritativeConversation = BASE_CONVERSATION.copy(version = 2)
        fixture.api.conversationsResponse = listOf(authoritativeConversation)
        fixture.api.detailResponse = detail(
            conversation = authoritativeConversation,
            messages = listOf(
                AssistantMessage(
                    id = ASSISTANT_MESSAGE_ID,
                    role = AssistantMessageRole.ASSISTANT,
                    content = "Готово",
                    createdAt = UPDATED_AT,
                ),
            ),
        )
        val body = GatedAssistantStream()
        fixture.api.turnResponse = { Response.success(body) }

        fixture.viewModel.sendMessage("  Нужна бытовка  ")
        runCurrent()

        assertThat(body.awaitPause()).isTrue()
        assertThat(fixture.viewModel.state.value.sending).isTrue()
        assertThat(fixture.viewModel.state.value.liveUserContent).isEqualTo("Нужна бытовка")
        assertThat(fixture.viewModel.state.value.liveAssistantContent).isEqualTo("Готово")
        assertThat(fixture.viewModel.state.value.toolRunning).isTrue()

        body.finish()
        awaitMainCondition { !fixture.viewModel.state.value.sending }

        assertThat(fixture.api.turnCalls).isEqualTo(1)
        assertThat(fixture.api.turnRequests.single().message).isEqualTo("Нужна бытовка")
        assertThat(fixture.api.conversationDetailCalls).isEqualTo(detailCallsBefore + 1)
        assertThat(fixture.api.conversationListCalls).isEqualTo(listCallsBefore + 1)
        assertThat(fixture.viewModel.state.value.detail?.conversation?.version).isEqualTo(2)
        assertThat(fixture.viewModel.state.value.liveAssistantContent).isEmpty()
        assertThat(fixture.viewModel.state.value.toolRunning).isFalse()
    }

    @Test
    fun `rapid repeated send starts only one turn`() = runTest {
        val fixture = fixture()
        fixture.viewModel.activate(ACTOR_A)
        fixture.viewModel.openConversation(CONVERSATION_ID)
        advanceUntilIdle()

        fixture.viewModel.sendMessage("Первое сообщение")
        assertThat(fixture.viewModel.state.value.sending).isTrue()
        fixture.viewModel.sendMessage("Повторное сообщение")
        advanceUntilIdle()

        assertThat(fixture.api.turnCalls).isEqualTo(1)
        assertThat(fixture.api.turnRequests.single().message).isEqualTo("Первое сообщение")
    }

    @Test
    fun `opening another conversation does not cancel an active turn`() = runTest {
        val fixture = fixture()
        fixture.viewModel.activate(ACTOR_A)
        fixture.viewModel.openConversation(CONVERSATION_ID)
        advanceUntilIdle()
        val body = GatedAssistantStream()
        fixture.api.turnResponse = { Response.success(body) }

        fixture.viewModel.sendMessage("Сообщение в текущем диалоге")
        runCurrent()
        assertThat(body.awaitPause()).isTrue()

        fixture.viewModel.openConversation(OTHER_CONVERSATION_ID)

        assertThat(fixture.viewModel.state.value.selectedConversationId)
            .isEqualTo(CONVERSATION_ID)
        assertThat(fixture.viewModel.state.value.sending).isTrue()
        assertThat(fixture.viewModel.state.value.notice?.message)
            .isEqualTo("Дождитесь завершения текущей операции в диалоге.")

        body.finish()
        awaitMainCondition { !fixture.viewModel.state.value.sending }

        assertThat(fixture.api.turnCalls).isEqualTo(1)
        assertThat(fixture.viewModel.state.value.selectedConversationId)
            .isEqualTo(CONVERSATION_ID)
    }

    @Test
    fun `stale clarification posts once and reloads without replay`() = runTest {
        val fixture = fixture().also {
            it.api.detailResponse = detail(clarifications = listOf(PENDING_CLARIFICATION))
        }
        fixture.viewModel.activate(ACTOR_A)
        fixture.viewModel.openConversation(CONVERSATION_ID)
        advanceUntilIdle()
        val listCallsBefore = fixture.api.conversationListCalls
        val detailCallsBefore = fixture.api.conversationDetailCalls
        fixture.api.detailResponse = detail(
            conversation = BASE_CONVERSATION.copy(version = 2),
            clarifications = listOf(
                PENDING_CLARIFICATION.copy(
                    status = AssistantClarificationStatus.ANSWERED,
                    answeredOptionId = OPTION_ID,
                    answeredAt = UPDATED_AT,
                ),
            ),
        )
        fixture.api.conversationsResponse = listOf(BASE_CONVERSATION.copy(version = 2))
        fixture.api.turnResponse = {
            Response.error(
                409,
                "{}".toResponseBody("application/problem+json".toMediaType()),
            )
        }

        fixture.viewModel.answerClarification(QUESTION_ID, OPTION_ID)
        advanceUntilIdle()

        assertThat(fixture.api.turnCalls).isEqualTo(1)
        assertThat(fixture.api.turnRequests.single().message).isNull()
        assertThat(fixture.api.turnRequests.single().clarificationAnswer?.questionId)
            .isEqualTo(QUESTION_ID)
        assertThat(fixture.api.turnRequests.single().clarificationAnswer?.optionId)
            .isEqualTo(OPTION_ID)
        assertThat(fixture.api.conversationDetailCalls).isEqualTo(detailCallsBefore + 1)
        assertThat(fixture.api.conversationListCalls).isEqualTo(listCallsBefore + 1)
        assertThat(fixture.viewModel.state.value.detail?.conversation?.version).isEqualTo(2)
        assertThat(fixture.viewModel.state.value.notice?.message)
            .isEqualTo("Диалог уже изменился. Показана актуальная версия.")
    }

    @Test
    fun `rapid repeated clarification answer starts only one turn`() = runTest {
        val fixture = fixture().also {
            it.api.detailResponse = detail(clarifications = listOf(PENDING_CLARIFICATION))
        }
        fixture.viewModel.activate(ACTOR_A)
        fixture.viewModel.openConversation(CONVERSATION_ID)
        advanceUntilIdle()

        fixture.viewModel.answerClarification(QUESTION_ID, OPTION_ID)
        assertThat(fixture.viewModel.state.value.sending).isTrue()
        fixture.viewModel.answerClarification(QUESTION_ID, OTHER_OPTION_ID)
        advanceUntilIdle()

        assertThat(fixture.api.turnCalls).isEqualTo(1)
        assertThat(fixture.api.turnRequests.single().clarificationAnswer?.optionId)
            .isEqualTo(OPTION_ID)
    }

    @Test
    fun `selection replaces the complete sorted set once then releases it`() = runTest {
        val fixture = fixture().also {
            it.api.detailResponse = detailWithSelection(
                rentalItemIds = listOf(RENTAL_ITEM_C, RENTAL_ITEM_A, RENTAL_ITEM_B),
            )
        }
        fixture.viewModel.activate(ACTOR_A)
        fixture.viewModel.openConversation(CONVERSATION_ID)
        advanceUntilIdle()
        val detailCallsBefore = fixture.api.conversationDetailCalls

        val remaining = linkedSetOf(RENTAL_ITEM_B, RENTAL_ITEM_A)
        fixture.viewModel.replaceSelection(remaining)
        fixture.viewModel.replaceSelection(remaining)
        advanceUntilIdle()

        assertThat(fixture.api.selectionCalls).hasSize(1)
        assertThat(fixture.api.selectionCalls.single().request.rentalItemIds)
            .containsExactly(RENTAL_ITEM_A, RENTAL_ITEM_B).inOrder()
        assertThat(fixture.api.conversationDetailCalls).isEqualTo(detailCallsBefore + 1)
        assertThat(fixture.viewModel.state.value.detail?.currentSelection?.rentalItemIds)
            .containsExactly(RENTAL_ITEM_A, RENTAL_ITEM_B).inOrder()

        fixture.viewModel.replaceSelection(emptySet())
        advanceUntilIdle()

        assertThat(fixture.api.selectionCalls).hasSize(2)
        assertThat(fixture.api.selectionCalls.last().request.rentalItemIds).isEmpty()
        assertThat(fixture.api.conversationDetailCalls).isEqualTo(detailCallsBefore + 2)
        assertThat(fixture.viewModel.state.value.detail?.currentSelection).isNull()
        assertThat(fixture.viewModel.state.value.notice?.message).isEqualTo("Подборка освобождена.")
    }

    @Test
    fun `uncertain selection retry keeps the same idempotency key`() = runTest {
        val fixture = fixture().also {
            it.api.detailResponse = detailWithSelection(
                rentalItemIds = listOf(RENTAL_ITEM_A, RENTAL_ITEM_B),
            )
            it.api.selectionFailures += IOException("response lost")
        }
        fixture.viewModel.activate(ACTOR_A)
        fixture.viewModel.openConversation(CONVERSATION_ID)
        advanceUntilIdle()
        val detailCallsBefore = fixture.api.conversationDetailCalls
        val presentationCallsBefore = fixture.presentations.getCalls

        fixture.viewModel.replaceSelection(setOf(RENTAL_ITEM_A))
        advanceUntilIdle()

        assertThat(fixture.api.selectionCalls).hasSize(1)
        assertThat(fixture.api.conversationDetailCalls).isEqualTo(detailCallsBefore + 1)
        assertThat(fixture.presentations.getCalls).isEqualTo(presentationCallsBefore + 1)

        fixture.viewModel.replaceSelection(setOf(RENTAL_ITEM_A))
        advanceUntilIdle()

        assertThat(fixture.api.selectionCalls).hasSize(2)
        assertThat(fixture.api.selectionCalls[1].idempotencyKey)
            .isEqualTo(fixture.api.selectionCalls[0].idempotencyKey)
        assertThat(fixture.viewModel.state.value.detail?.currentSelection?.rentalItemIds)
            .containsExactly(RENTAL_ITEM_A)
    }

    @Test
    fun `selection conflict refetches detail and presentation with Russian notice`() = runTest {
        val fixture = fixture().also {
            it.api.detailResponse = detailWithSelection(
                rentalItemIds = listOf(RENTAL_ITEM_A, RENTAL_ITEM_B),
            )
            it.api.selectionFailures += conflict()
            it.presentations.getResponse = ACTIVE_PRESENTATION
        }
        fixture.viewModel.activate(ACTOR_A)
        fixture.viewModel.openConversation(CONVERSATION_ID)
        advanceUntilIdle()
        val detailCallsBefore = fixture.api.conversationDetailCalls
        val presentationCallsBefore = fixture.presentations.getCalls

        fixture.viewModel.replaceSelection(setOf(RENTAL_ITEM_A))
        advanceUntilIdle()

        assertThat(fixture.api.selectionCalls).hasSize(1)
        assertThat(fixture.api.conversationDetailCalls).isEqualTo(detailCallsBefore + 1)
        assertThat(fixture.presentations.getCalls).isEqualTo(presentationCallsBefore + 1)
        assertThat(fixture.viewModel.state.value.presentation).isEqualTo(ACTIVE_PRESENTATION)
        assertThat(fixture.viewModel.state.value.notice?.message)
            .isEqualTo("Подборка уже изменилась. Показана актуальная версия.")
        assertThat(fixture.viewModel.state.value.notice?.isError).isTrue()
    }

    @Test
    fun `presentation publishes exact search grouping once and stores response`() = runTest {
        val fixture = fixture().also {
            it.api.detailResponse = detailWithSelection(
                rentalItemIds = listOf(RENTAL_ITEM_C, RENTAL_ITEM_A, RENTAL_ITEM_B),
                searchResult = SEARCH_RESULT,
            )
        }
        fixture.viewModel.activate(ACTOR_A)
        fixture.viewModel.openConversation(CONVERSATION_ID)
        advanceUntilIdle()

        fixture.viewModel.publishPresentation()
        fixture.viewModel.publishPresentation()
        advanceUntilIdle()

        assertThat(fixture.presentations.publishCalls).hasSize(1)
        val call = fixture.presentations.publishCalls.single()
        assertThat(call.selectedRentalItemIds)
            .containsExactly(RENTAL_ITEM_C, RENTAL_ITEM_A, RENTAL_ITEM_B).inOrder()
        assertThat(call.groups).containsExactly(
            RentalPresentationGroupRequest(
                key = "group-1",
                label = "ЛДСП · Чистовая · 6×2,4 · Эконом или Стандарт · С окнами · " +
                    "С линолеумом",
                rentalItemIds = listOf(RENTAL_ITEM_B, RENTAL_ITEM_A),
            ),
            RentalPresentationGroupRequest(
                key = "group-2",
                label = "Подборка 2",
                rentalItemIds = listOf(RENTAL_ITEM_C),
            ),
        ).inOrder()
        assertThat(fixture.viewModel.state.value.presentation).isEqualTo(ACTIVE_PRESENTATION)
        assertThat(fixture.viewModel.state.value.notice?.message)
            .isEqualTo("Ссылка для клиента создана. Скопируйте или отправьте её.")
    }

    @Test
    fun `uncertain presentation retry keeps the same idempotency key`() = runTest {
        val fixture = fixture().also {
            it.api.detailResponse = detailWithSelection(
                rentalItemIds = listOf(RENTAL_ITEM_A, RENTAL_ITEM_B),
                searchResult = SEARCH_RESULT,
            )
            it.presentations.publishFailures += IOException("response lost")
        }
        fixture.viewModel.activate(ACTOR_A)
        fixture.viewModel.openConversation(CONVERSATION_ID)
        advanceUntilIdle()
        val detailCallsBefore = fixture.api.conversationDetailCalls
        val presentationCallsBefore = fixture.presentations.getCalls

        fixture.viewModel.publishPresentation()
        advanceUntilIdle()

        assertThat(fixture.presentations.publishCalls).hasSize(1)
        assertThat(fixture.api.conversationDetailCalls).isEqualTo(detailCallsBefore + 1)
        assertThat(fixture.presentations.getCalls).isEqualTo(presentationCallsBefore + 1)

        fixture.viewModel.publishPresentation()
        advanceUntilIdle()

        assertThat(fixture.presentations.publishCalls).hasSize(2)
        assertThat(fixture.presentations.publishCalls[1].idempotencyKey)
            .isEqualTo(fixture.presentations.publishCalls[0].idempotencyKey)
        assertThat(fixture.viewModel.state.value.presentation).isEqualTo(ACTIVE_PRESENTATION)
    }

    @Test
    fun `presentation conflict refetches detail and presentation with Russian notice`() = runTest {
        val fixture = fixture().also {
            it.api.detailResponse = detailWithSelection(
                rentalItemIds = listOf(RENTAL_ITEM_A, RENTAL_ITEM_B),
                searchResult = SEARCH_RESULT,
            )
            it.presentations.publishFailures += conflict()
        }
        fixture.viewModel.activate(ACTOR_A)
        fixture.viewModel.openConversation(CONVERSATION_ID)
        advanceUntilIdle()
        val detailCallsBefore = fixture.api.conversationDetailCalls
        val presentationCallsBefore = fixture.presentations.getCalls
        fixture.presentations.getResponse = ACTIVE_PRESENTATION.copy(version = 2, revision = 2)

        fixture.viewModel.publishPresentation()
        advanceUntilIdle()

        assertThat(fixture.presentations.publishCalls).hasSize(1)
        assertThat(fixture.api.conversationDetailCalls).isEqualTo(detailCallsBefore + 1)
        assertThat(fixture.presentations.getCalls).isEqualTo(presentationCallsBefore + 1)
        assertThat(fixture.viewModel.state.value.presentation?.version).isEqualTo(2)
        assertThat(fixture.viewModel.state.value.notice?.message)
            .isEqualTo("Представление уже изменилось. Показана актуальная версия.")
        assertThat(fixture.viewModel.state.value.notice?.isError).isTrue()

        fixture.viewModel.deactivate()
        advanceUntilIdle()
    }

    @Test
    fun `signed out auth clears chat state`() = runTest {
        val fixture = fixture()
        fixture.viewModel.activate(ACTOR_A)
        fixture.viewModel.openConversation(CONVERSATION_ID)
        advanceUntilIdle()
        assertThat(fixture.viewModel.state.value.actorId).isEqualTo(ACTOR_A)
        assertThat(fixture.viewModel.state.value.detail).isNotNull()

        fixture.authState.value = RentalManagerAuthState.SignedOut
        advanceUntilIdle()

        assertThat(fixture.viewModel.state.value).isEqualTo(RentalManagerChatUiState())
    }

    @Test
    fun `selection prices load once and explicit refresh observes changed tariff`() = runTest {
        val fixture = fixture()
        fixture.api.detailResponse = detailWithSelection(listOf(RENTAL_ITEM_A), SEARCH_RESULT)
        fixture.viewModel.activate(ACTOR_A)
        fixture.viewModel.openConversation(CONVERSATION_ID)
        advanceUntilIdle()
        assertThat(fixture.pricing.calls).hasSize(1)
        assertThat(fixture.viewModel.state.value.rentalPrices).containsEntry(RENTAL_ITEM_A, 0L)

        fixture.pricing.amount = Long.MAX_VALUE
        fixture.viewModel.refreshRentalPrices()
        advanceUntilIdle()
        assertThat(fixture.pricing.calls).hasSize(2)
        assertThat(fixture.viewModel.state.value.rentalPrices)
            .containsEntry(RENTAL_ITEM_A, Long.MAX_VALUE)
        assertThat(fixture.viewModel.state.value.rentalPricesLoading).isFalse()
        fixture.pricing.amount = 10_000
        fixture.viewModel.openConversation(CONVERSATION_ID)
        advanceUntilIdle()
        assertThat(fixture.pricing.calls).hasSize(3)
        assertThat(fixture.viewModel.state.value.rentalPrices).containsEntry(RENTAL_ITEM_A, 10_000L)
    }

    @Test
    fun `failed prices preserve selection without displaying a fabricated zero`() = runTest {
        val fixture = fixture()
        fixture.api.detailResponse = detailWithSelection(listOf(RENTAL_ITEM_A), SEARCH_RESULT)
        fixture.pricing.failure = IOException("offline")
        fixture.viewModel.activate(ACTOR_A)
        fixture.viewModel.openConversation(CONVERSATION_ID)
        advanceUntilIdle()
        assertThat(fixture.viewModel.state.value.rentalPrices).isEmpty()
        assertThat(fixture.viewModel.state.value.rentalPricesError).contains("недоступны")
        assertThat(fixture.viewModel.state.value.detail?.currentSelection?.rentalItemIds)
            .containsExactly(RENTAL_ITEM_A)

        fixture.pricing.failure = null
        fixture.pricing.amount = 8_000
        fixture.viewModel.refreshRentalPrices()
        advanceUntilIdle()
        assertThat(fixture.viewModel.state.value.rentalPrices).containsEntry(RENTAL_ITEM_A, 8_000L)
        assertThat(fixture.viewModel.state.value.rentalPricesError).isNull()
    }

    @Test
    fun `late noncancellable price result cannot enter another actor state`() = runTest {
        val fixture = fixture()
        val pending = CompletableDeferred<Map<String, Long>>()
        fixture.pricing.pending = pending
        fixture.api.detailResponse = detailWithSelection(listOf(RENTAL_ITEM_A), SEARCH_RESULT)
        fixture.viewModel.activate(ACTOR_A)
        fixture.viewModel.openConversation(CONVERSATION_ID)
        runCurrent()
        assertThat(fixture.viewModel.state.value.rentalPricesLoading).isTrue()

        fixture.viewModel.activate(ACTOR_B)
        runCurrent()
        pending.complete(mapOf(RENTAL_ITEM_A to 8_000L))
        advanceUntilIdle()
        assertThat(fixture.viewModel.state.value.actorId).isEqualTo(ACTOR_B)
        assertThat(fixture.viewModel.state.value.rentalPrices).isEmpty()
        assertThat(fixture.viewModel.state.value.rentalPricesLoading).isFalse()
    }

    @Test
    fun `warehouse change reloads prices and clearing selection clears price state`() = runTest {
        val fixture = fixture()
        fixture.api.detailResponse = detailWithSelection(listOf(RENTAL_ITEM_A), SEARCH_RESULT)
        fixture.viewModel.activate(ACTOR_A)
        fixture.viewModel.openConversation(CONVERSATION_ID)
        advanceUntilIdle()
        val otherWarehouse = "30000000-0000-4000-8000-000000000099"
        fixture.api.detailResponse = fixture.api.detailResponse.copy(
            currentSelection = fixture.api.detailResponse.currentSelection?.let { selection ->
                selection.copy(
                    warehouseId = otherWarehouse,
                    items = selection.items.map { it.copy(warehouseId = otherWarehouse) },
                )
            },
        )
        fixture.viewModel.openConversation(CONVERSATION_ID)
        advanceUntilIdle()
        assertThat(fixture.pricing.calls.last().first).isEqualTo(otherWarehouse)

        fixture.api.detailResponse = detail()
        fixture.viewModel.openConversation(CONVERSATION_ID)
        advanceUntilIdle()
        assertThat(fixture.viewModel.state.value.rentalPrices).isEmpty()
        assertThat(fixture.pricing.calls).hasSize(2)
    }

    @Test
    fun `superseded price refresh cannot replace a newer price for the same selection`() = runTest {
        val fixture = fixture()
        val pending = CompletableDeferred<Map<String, Long>>()
        fixture.pricing.pending = pending
        fixture.api.detailResponse = detailWithSelection(listOf(RENTAL_ITEM_A), SEARCH_RESULT)
        fixture.viewModel.activate(ACTOR_A)
        fixture.viewModel.openConversation(CONVERSATION_ID)
        runCurrent()
        fixture.pricing.pending = null
        fixture.pricing.amount = 12_000
        fixture.viewModel.refreshRentalPrices()
        runCurrent()
        pending.complete(mapOf(RENTAL_ITEM_A to 8_000L))
        advanceUntilIdle()
        assertThat(fixture.viewModel.state.value.rentalPrices).containsEntry(RENTAL_ITEM_A, 12_000L)
    }

    private fun fixture(): ChatFixture {
        val api = FakeAssistantApi()
        val presentations = FakeRentalPresentationDataSource()
        val pricing = FakeRentalPricingDataSource()
        val authState = MutableStateFlow<RentalManagerAuthState>(RentalManagerAuthState.SignedIn)
        val invalidations = mutableListOf<String>()
        val repository = AssistantRepository(
            api = api,
            moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build(),
            problemMessage = { "Ошибка помощника" },
        )
        return ChatFixture(
            api = api,
            presentations = presentations,
            pricing = pricing,
            authState = authState,
            invalidations = invalidations,
            viewModel = RentalManagerChatViewModel(
                repository = repository,
                presentations = presentations,
                pricing = pricing,
                authState = authState,
                invalidateSession = { invalidations += it },
                savedStateHandle = SavedStateHandle(),
            ),
        ).also { createdViewModels += it.viewModel }
    }

    private fun TestScope.awaitMainCondition(condition: () -> Boolean) {
        repeat(200) {
            runCurrent()
            if (condition()) return
            Thread.sleep(5)
        }
        error("Timed out waiting for chat state")
    }

    companion object {
        @AfterClass
        @JvmStatic
        fun resetMainDispatcherAfterClass() {
            Dispatchers.resetMain()
        }
    }

}

private data class ChatFixture(
    val api: FakeAssistantApi,
    val presentations: FakeRentalPresentationDataSource,
    val pricing: FakeRentalPricingDataSource,
    val authState: MutableStateFlow<RentalManagerAuthState>,
    val invalidations: MutableList<String>,
    val viewModel: RentalManagerChatViewModel,
)

private class FakeRentalPricingDataSource : RentalPricingDataSource {
    var amount = 0L
    var failure: Throwable? = null
    var pending: CompletableDeferred<Map<String, Long>>? = null
    val calls = mutableListOf<Pair<String, List<String>>>()

    override suspend fun prices(warehouseId: String, cabinIds: List<String>): Map<String, Long> {
        calls += warehouseId to cabinIds
        failure?.let { throw it }
        pending?.let { return withContext(NonCancellable) { it.await() } }
        return cabinIds.associateWith { amount }
    }
}

private class FakeAssistantApi : AssistantApi {
    var conversationsResponse: List<AssistantConversation> = listOf(BASE_CONVERSATION)
    var detailResponse: AssistantConversationDetail = detail()
    var createFailuresRemaining: Int = 0
    var turnResponse: () -> Response<ResponseBody> = { completedTurnResponse() }
    val createRequests = mutableListOf<CreateAssistantConversationRequest>()
    val turnRequests = mutableListOf<AssistantTurnRequest>()
    val selectionCalls = mutableListOf<SelectionCall>()
    val selectionFailures = ArrayDeque<Throwable>()
    var conversationListCalls: Int = 0
    var conversationDetailCalls: Int = 0
    var turnCalls: Int = 0

    override suspend fun conversations(rentalOrderId: String?): List<AssistantConversation> {
        conversationListCalls += 1
        return conversationsResponse
    }

    override suspend fun createConversation(
        request: CreateAssistantConversationRequest,
    ): CreateAssistantConversationResponse {
        createRequests += request
        if (createFailuresRemaining > 0) {
            createFailuresRemaining -= 1
            throw IOException("offline")
        }
        val conversationId = requireNotNull(request.conversationId)
        return CreateAssistantConversationResponse(
            conversation = BASE_CONVERSATION.copy(
                id = conversationId,
                clientId = request.clientId,
                rentalOrderId = request.rentalOrderId,
            ),
            inquiry = AssistantRentalInquirySummary(INQUIRY_ID, "DRAFT"),
            client = AssistantClientSummary(request.clientId, "LEGAL_ENTITY", "ООО Монтаж"),
        )
    }

    override suspend fun conversation(conversationId: String): AssistantConversationDetail {
        conversationDetailCalls += 1
        return detailResponse
    }

    override suspend fun archiveConversation(conversationId: String): Response<Unit> =
        Response.success(Unit)

    override suspend fun replaceSelection(
        conversationId: String,
        idempotencyKey: String,
        request: AssistantCabinSelectionRequest,
    ): AssistantCabinSelection {
        selectionCalls += SelectionCall(conversationId, idempotencyKey, request)
        selectionFailures.removeFirstOrNull()?.let { throw it }
        val response = if (request.rentalItemIds.isEmpty()) {
            AssistantCabinSelection(
                inquiryId = INQUIRY_ID,
                warehouseId = null,
                expiresAt = null,
                rentalItemIds = emptyList(),
                items = emptyList(),
            )
        } else {
            AssistantCabinSelection(
                inquiryId = INQUIRY_ID,
                warehouseId = request.warehouseId,
                expiresAt = FUTURE_EXPIRY,
                rentalItemIds = request.rentalItemIds,
                items = request.rentalItemIds.map(::availableCabin),
            )
        }
        detailResponse = detailResponse.copy(currentSelection = response.takeIf {
            it.rentalItemIds.isNotEmpty()
        })
        return response
    }

    override suspend fun turn(
        conversationId: String,
        request: AssistantTurnRequest,
    ): Response<ResponseBody> {
        turnCalls += 1
        turnRequests += request
        return turnResponse()
    }
}

private data class SelectionCall(
    val conversationId: String,
    val idempotencyKey: String,
    val request: AssistantCabinSelectionRequest,
)

private data class PresentationPublishCall(
    val inquiryId: String,
    val idempotencyKey: UUID,
    val warehouseId: String,
    val selectedRentalItemIds: List<String>,
    val groups: List<RentalPresentationGroupRequest>,
)

private class FakeRentalPresentationDataSource : RentalPresentationDataSource {
    var getResponse: RentalPresentationDto? = null
    var publishResponse: RentalPresentationDto = ACTIVE_PRESENTATION
    val publishFailures = ArrayDeque<Throwable>()
    val publishCalls = mutableListOf<PresentationPublishCall>()
    var getCalls: Int = 0

    override suspend fun get(inquiryId: String): RentalPresentationDto? {
        getCalls += 1
        return getResponse
    }

    override suspend fun publish(
        inquiryId: String,
        idempotencyKey: UUID,
        warehouseId: String,
        selectedRentalItemIds: Collection<String>,
        groups: List<RentalPresentationGroupRequest>,
    ): RentalPresentationDto {
        publishCalls += PresentationPublishCall(
            inquiryId = inquiryId,
            idempotencyKey = idempotencyKey,
            warehouseId = warehouseId,
            selectedRentalItemIds = selectedRentalItemIds.toList(),
            groups = groups,
        )
        publishFailures.removeFirstOrNull()?.let { throw it }
        return publishResponse
    }

    override fun userMessage(failure: Throwable): String = "Не удалось создать ссылку."
}

private class GatedAssistantStream : ResponseBody() {
    private val paused = CountDownLatch(1)
    private val resume = CountDownLatch(1)
    private val first = Buffer().writeUtf8(
        sseEvent(
            "assistant.delta",
            "{\"event\":\"assistant.delta\",\"conversationId\":\"$CONVERSATION_ID\"," +
                "\"delta\":\"Готово\"}",
        ) + sseEvent(
            "tool.started",
            "{\"event\":\"tool.started\",\"conversationId\":\"$CONVERSATION_ID\"," +
                "\"toolCallId\":\"$TOOL_CALL_ID\"}",
        ),
    )
    private val second = Buffer().writeUtf8(
        sseEvent(
            "tool.completed",
            "{\"event\":\"tool.completed\",\"conversationId\":\"$CONVERSATION_ID\"," +
                "\"toolCallId\":\"$TOOL_CALL_ID\",\"result\":{}}",
        ) + sseEvent(
            "turn.completed",
            "{\"event\":\"turn.completed\",\"conversationId\":\"$CONVERSATION_ID\"," +
                "\"messageId\":\"$ASSISTANT_MESSAGE_ID\"}",
        ),
    )
    private var resumed = false
    private val streamSource: BufferedSource = object : Source {
        override fun read(sink: Buffer, byteCount: Long): Long {
            if (!first.exhausted()) return first.read(sink, byteCount)
            if (!resumed) {
                paused.countDown()
                if (!resume.await(5, TimeUnit.SECONDS)) throw IOException("stream gate timed out")
                resumed = true
            }
            if (!second.exhausted()) return second.read(sink, byteCount)
            return -1
        }

        override fun timeout(): Timeout = Timeout.NONE

        override fun close() {
            resume.countDown()
        }
    }.buffer()

    override fun contentType(): MediaType = "text/event-stream".toMediaType()

    override fun contentLength(): Long = -1

    override fun source(): BufferedSource = streamSource

    fun awaitPause(): Boolean = paused.await(5, TimeUnit.SECONDS)

    fun finish() {
        resume.countDown()
    }
}

private fun completedTurnResponse(): Response<ResponseBody> = Response.success(
    sseEvent(
        "turn.completed",
        "{\"event\":\"turn.completed\",\"conversationId\":\"$CONVERSATION_ID\"," +
            "\"messageId\":\"$ASSISTANT_MESSAGE_ID\"}",
    ).toResponseBody("text/event-stream".toMediaType()),
)

private fun sseEvent(name: String, data: String): String = "event: $name\ndata: $data\n\n"

private fun detail(
    conversation: AssistantConversation = BASE_CONVERSATION,
    messages: List<AssistantMessage> = emptyList(),
    lastSearchResult: AssistantCabinSearchResult? = null,
    clarifications: List<AssistantClarificationQuestion> = emptyList(),
    currentSelection: AssistantCabinSelection? = null,
): AssistantConversationDetail = AssistantConversationDetail(
    conversation = conversation,
    messages = messages,
    lastSearchResult = lastSearchResult,
    clarifications = clarifications,
    currentSelection = currentSelection,
)

private fun detailWithSelection(
    rentalItemIds: List<String>,
    searchResult: AssistantCabinSearchResult? = SEARCH_RESULT,
): AssistantConversationDetail = detail(
    lastSearchResult = searchResult,
    currentSelection = AssistantCabinSelection(
        inquiryId = INQUIRY_ID,
        warehouseId = WAREHOUSE_ID,
        expiresAt = FUTURE_EXPIRY,
        rentalItemIds = rentalItemIds,
        items = rentalItemIds.map(::availableCabin),
    ),
)

private fun availableCabin(id: String): AssistantAvailableCabin = AssistantAvailableCabin(
    id = id,
    version = 4,
    warehouseId = WAREHOUSE_ID,
    number = when (id) {
        RENTAL_ITEM_A -> "БК-101"
        RENTAL_ITEM_B -> "БК-102"
        else -> "БК-103"
    },
    status = AssistantCabinStatus.FREE,
    rentalType = "Аренда",
    dimensions = "6×2,4",
    finishing = "Чистовая",
    category = "Стандарт",
    characteristics = "С окнами",
    linoleum = true,
    updatedAt = UPDATED_AT,
)

private fun conflict(): HttpException = HttpException(
    Response.error<Unit>(
        409,
        "{}".toResponseBody("application/problem+json".toMediaType()),
    ),
)

private const val ACTOR_A = "01000000-0000-4000-8000-000000000001"
private const val ACTOR_B = "01000000-0000-4000-8000-000000000002"
private const val CONVERSATION_ID = "10000000-0000-4000-8000-000000000001"
private const val OTHER_CONVERSATION_ID = "10000000-0000-4000-8000-000000000002"
private const val CLIENT_ID = "20000000-0000-4000-8000-000000000002"
private const val ORDER_ID = "20000000-0000-4000-8000-000000000003"
private const val INQUIRY_ID = "30000000-0000-4000-8000-000000000003"
private const val ASSISTANT_MESSAGE_ID = "40000000-0000-4000-8000-000000000004"
private const val QUESTION_ID = "50000000-0000-4000-8000-000000000005"
private const val OPTION_ID = "60000000-0000-4000-8000-000000000006"
private const val OTHER_OPTION_ID = "60000000-0000-4000-8000-000000000007"
private const val TOOL_CALL_ID = "70000000-0000-4000-8000-000000000008"
private const val WAREHOUSE_ID = "80000000-0000-4000-8000-000000000001"
private const val RENTAL_ITEM_A = "90000000-0000-4000-8000-000000000001"
private const val RENTAL_ITEM_B = "90000000-0000-4000-8000-000000000002"
private const val RENTAL_ITEM_C = "90000000-0000-4000-8000-000000000003"
private const val PRESENTATION_ID = "a0000000-0000-4000-8000-000000000001"
private const val CREATED_AT = "2026-09-02T09:00:00Z"
private const val UPDATED_AT = "2026-09-02T10:00:00Z"
private const val FUTURE_EXPIRY = "2099-09-02T12:00:00+03:00"

private val BASE_CONVERSATION = AssistantConversation(
    id = CONVERSATION_ID,
    version = 1,
    clientId = CLIENT_ID,
    rentalInquiryId = INQUIRY_ID,
    rentalOrderId = null,
    clientType = "LEGAL_ENTITY",
    clientDisplayName = "ООО Монтаж",
    archived = false,
    archivedAt = null,
    createdAt = CREATED_AT,
    updatedAt = CREATED_AT,
)

private val PENDING_CLARIFICATION = AssistantClarificationQuestion(
    id = QUESTION_ID,
    branchKey = "cabin_type",
    sequenceNumber = 1,
    kind = AssistantClarificationKind.CABIN_TYPE,
    prompt = "Какой тип бытовки нужен?",
    status = AssistantClarificationStatus.PENDING,
    options = listOf(
        AssistantClarificationOption(OPTION_ID, "ЛДСП", "LDSP"),
        AssistantClarificationOption(OTHER_OPTION_ID, "ПВХ", "PVC"),
    ),
    answeredOptionId = null,
    createdAt = CREATED_AT,
    answeredAt = null,
)

private val SEARCH_RESULT = AssistantCabinSearchResult(
    tool = "search_available_cabins",
    resultMode = AssistantCabinSearchResultMode.REPLACE,
    filterSuggestions = AssistantCabinFilterSuggestions(
        cabinTypes = listOf("LDSP"),
        finishes = listOf("Чистовая"),
        dimensions = listOf("6×2,4"),
        categories = listOf("Эконом", "Стандарт"),
        characteristics = listOf("С окнами"),
    ),
    notices = emptyList(),
    data = AssistantCabinSearchProjection(
        warehouseId = WAREHOUSE_ID,
        expiresAt = FUTURE_EXPIRY,
        groups = listOf(
            AssistantCabinSearchGroupResult(
                group = AssistantCabinSearchGroup(
                    cabinType = "LDSP",
                    finish = "Чистовая",
                    dimensions = "6×2,4",
                    categories = listOf("Эконом", "Стандарт"),
                    characteristics = "С окнами",
                    linoleum = true,
                    quantity = 2,
                ),
                cabins = listOf(availableCabin(RENTAL_ITEM_B), availableCabin(RENTAL_ITEM_A)),
            ),
            AssistantCabinSearchGroupResult(
                group = AssistantCabinSearchGroup(quantity = 1),
                cabins = listOf(availableCabin(RENTAL_ITEM_C)),
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
