package dev.buhanzaz.rwms.rentalmanager.ui

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.buhanzaz.rwms.rentalmanager.auth.RentalManagerAuthState
import dev.buhanzaz.rwms.rentalmanager.data.AssistantRepository
import dev.buhanzaz.rwms.rentalmanager.data.RentalPresentationDataSource
import dev.buhanzaz.rwms.rentalmanager.data.RentalPresentationRepository
import dev.buhanzaz.rwms.rentalmanager.network.AssistantCabinSearchGroup
import dev.buhanzaz.rwms.rentalmanager.network.AssistantClarificationAnswered
import dev.buhanzaz.rwms.rentalmanager.network.AssistantClarificationQuestion
import dev.buhanzaz.rwms.rentalmanager.network.AssistantClarificationRequested
import dev.buhanzaz.rwms.rentalmanager.network.AssistantClarificationStatus
import dev.buhanzaz.rwms.rentalmanager.network.AssistantConversation
import dev.buhanzaz.rwms.rentalmanager.network.AssistantConversationDetail
import dev.buhanzaz.rwms.rentalmanager.network.AssistantTextDelta
import dev.buhanzaz.rwms.rentalmanager.network.AssistantToolActivity
import dev.buhanzaz.rwms.rentalmanager.network.AssistantTurnEvent
import dev.buhanzaz.rwms.rentalmanager.network.AssistantTurnFailed
import dev.buhanzaz.rwms.rentalmanager.network.AssistantTurnFailureCode
import dev.buhanzaz.rwms.rentalmanager.network.AssistantTurnResult
import dev.buhanzaz.rwms.rentalmanager.network.AssistantTurnTerminal
import dev.buhanzaz.rwms.rentalmanager.network.RentalManagerBackend
import dev.buhanzaz.rwms.rentalmanager.network.RentalPresentationDto
import dev.buhanzaz.rwms.rentalmanager.network.RentalPresentationGroupRequest
import dev.buhanzaz.rwms.rentalmanager.network.RentalPresentationMode
import dev.buhanzaz.rwms.rentalmanager.network.RentalPresentationState
import java.time.OffsetDateTime
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import retrofit2.HttpException

data class RentalManagerChatUiState(
    val actorId: String? = null,
    val conversations: List<AssistantConversation> = emptyList(),
    val conversationsLoading: Boolean = false,
    val selectedConversationId: String? = null,
    val detail: AssistantConversationDetail? = null,
    val detailLoading: Boolean = false,
    val creatingConversation: Boolean = false,
    val archivingConversation: Boolean = false,
    val sending: Boolean = false,
    val selectionUpdating: Boolean = false,
    val presentation: RentalPresentationDto? = null,
    val presentationLoading: Boolean = false,
    val presentationPublishing: Boolean = false,
    val liveUserContent: String? = null,
    val liveAssistantContent: String = "",
    val toolRunning: Boolean = false,
    val notice: RentalManagerNotice? = null,
)

sealed interface RentalManagerChatNavigationEvent {
    data class OpenConversation(val conversationId: String) : RentalManagerChatNavigationEvent
}

/**
 * Owns only Android chat presentation state. Conversation, inquiry, clarification and turn
 * transitions remain authoritative in assistant-service; a turn POST is never replayed.
 */
class RentalManagerChatViewModel internal constructor(
    private val repository: AssistantRepository,
    private val presentations: RentalPresentationDataSource,
    private val authState: StateFlow<RentalManagerAuthState>,
    private val invalidateSession: suspend (String) -> Unit,
    private val savedStateHandle: SavedStateHandle,
) : ViewModel() {
    private val mutableState = MutableStateFlow(RentalManagerChatUiState())
    private val mutableEvents =
        MutableSharedFlow<RentalManagerChatNavigationEvent>(extraBufferCapacity = 1)
    private var listJob: Job? = null
    private var detailJob: Job? = null
    private var presentationJob: Job? = null
    private var commandJob: Job? = null

    val state = mutableState.asStateFlow()
    val events: SharedFlow<RentalManagerChatNavigationEvent> = mutableEvents.asSharedFlow()

    init {
        viewModelScope.launch {
            authState.collectLatest { current ->
                if (current is RentalManagerAuthState.SignedOut ||
                    current is RentalManagerAuthState.Failure
                ) {
                    reset()
                }
            }
        }
    }

    fun activate(actorId: String) {
        val canonicalActorId = canonicalUuid(actorId) ?: run {
            reset()
            return
        }
        val current = mutableState.value
        if (current.actorId == canonicalActorId) {
            if (current.conversations.isEmpty() && !current.conversationsLoading) {
                refreshConversations()
            }
            return
        }
        cancelWork()
        mutableState.value = RentalManagerChatUiState(actorId = canonicalActorId)
        refreshConversations()
    }

    fun deactivate() {
        reset()
    }

    fun dismissNotice() {
        mutableState.update { it.copy(notice = null) }
    }

    fun refreshConversations() {
        val actorId = mutableState.value.actorId ?: return
        listJob?.cancel()
        mutableState.update { it.copy(conversationsLoading = true, notice = null) }
        listJob = viewModelScope.launch {
            runCatching { repository.conversations() }
                .onSuccess { conversations ->
                    if (mutableState.value.actorId == actorId) {
                        mutableState.update {
                            it.copy(
                                conversations = conversations,
                                conversationsLoading = false,
                            )
                        }
                    }
                }
                .onFailure { failure ->
                    if (failure is CancellationException) throw failure
                    if (mutableState.value.actorId == actorId) {
                        mutableState.update {
                            it.copy(
                                conversationsLoading = false,
                                notice = RentalManagerNotice(handleFailure(failure), true),
                            )
                        }
                    }
                }
        }
    }

    fun openConversation(conversationId: String) {
        val canonicalConversationId = canonicalUuid(conversationId) ?: run {
            mutableState.update {
                it.copy(notice = RentalManagerNotice("Диалог не найден. Обновите список.", true))
            }
            return
        }
        val current = mutableState.value
        if (current.creatingConversation || current.archivingConversation || current.sending ||
            current.selectionUpdating || current.presentationPublishing
        ) {
            mutableState.update {
                it.copy(
                    notice = RentalManagerNotice(
                        "Дождитесь завершения текущей операции в диалоге.",
                        false,
                    ),
                )
            }
            return
        }
        detailJob?.cancel()
        mutableState.update {
            it.copy(
                selectedConversationId = canonicalConversationId,
                detail = null,
                detailLoading = true,
                creatingConversation = false,
                archivingConversation = false,
                sending = false,
                selectionUpdating = false,
                presentation = null,
                presentationLoading = false,
                presentationPublishing = false,
                liveUserContent = null,
                liveAssistantContent = "",
                toolRunning = false,
                notice = null,
            )
        }
        detailJob = viewModelScope.launch {
            loadConversation(canonicalConversationId)
        }
    }

    fun createConversation(clientId: String, rentalOrderId: String? = null) {
        val actorId = mutableState.value.actorId ?: return
        if (mutableState.value.creatingConversation || commandJob?.isActive == true) return
        val canonicalClientId = canonicalUuid(clientId) ?: run {
            mutableState.update {
                it.copy(notice = RentalManagerNotice("Клиент не найден. Обновите список.", true))
            }
            return
        }
        val canonicalOrderId = rentalOrderId?.let(::canonicalUuid)
        if (rentalOrderId != null && canonicalOrderId == null) {
            mutableState.update {
                it.copy(notice = RentalManagerNotice("Заказ не найден. Обновите данные.", true))
            }
            return
        }
        mutableState.update { it.copy(creatingConversation = true, notice = null) }
        commandJob = viewModelScope.launch {
            val fingerprint = actorScopedCommandFingerprint(
                actorId,
                listOf(canonicalClientId, canonicalOrderId).joinToString("\u001f"),
            )
            val conversationId = commandId(CREATE_CONVERSATION_COMMAND, fingerprint)
            runCatching {
                repository.createConversation(
                    clientId = canonicalClientId,
                    conversationId = conversationId.toString(),
                    rentalOrderId = canonicalOrderId,
                )
            }.onSuccess { response ->
                if (mutableState.value.actorId == actorId) {
                    clearCommandId(CREATE_CONVERSATION_COMMAND)
                    mutableState.update { state ->
                        state.copy(
                            conversations = (
                                listOf(response.conversation) + state.conversations
                                ).distinctBy(AssistantConversation::id),
                            creatingConversation = false,
                        )
                    }
                    mutableEvents.emit(
                        RentalManagerChatNavigationEvent.OpenConversation(
                            response.conversation.id,
                        ),
                    )
                }
            }.onFailure { failure ->
                if (failure is CancellationException) throw failure
                if (mutableState.value.actorId == actorId) {
                    mutableState.update {
                        it.copy(
                            creatingConversation = false,
                            notice = RentalManagerNotice(handleFailure(failure), true),
                        )
                    }
                }
            }
        }
    }

    fun archiveSelectedConversation() {
        val conversationId = mutableState.value.selectedConversationId ?: return
        val current = mutableState.value.detail?.conversation ?: return
        if (current.archived ||
            mutableState.value.archivingConversation ||
            commandJob?.isActive == true
        ) return
        mutableState.update { it.copy(archivingConversation = true, notice = null) }
        commandJob = viewModelScope.launch {
            runCatching {
                repository.archiveConversation(conversationId)
                authoritativeSnapshot(conversationId)
            }.onSuccess { (detail, conversations) ->
                if (mutableState.value.selectedConversationId == conversationId) {
                    mutableState.update {
                        it.copy(
                            detail = detail,
                            conversations = conversations,
                            archivingConversation = false,
                            notice = RentalManagerNotice("Диалог перенесён в историю.", false),
                        )
                    }
                }
            }.onFailure { failure ->
                if (failure is CancellationException) throw failure
                if (mutableState.value.selectedConversationId == conversationId) {
                    mutableState.update {
                        it.copy(
                            archivingConversation = false,
                            notice = RentalManagerNotice(handleFailure(failure), true),
                        )
                    }
                }
            }
        }
    }

    fun sendMessage(message: String) {
        val detail = mutableState.value.detail ?: return
        if (detail.conversation.archived || mutableState.value.sending) return
        if (pendingClarification(detail) != null) {
            mutableState.update {
                it.copy(
                    notice = RentalManagerNotice(
                        "Сначала выберите ответ на вопрос помощника.",
                        true,
                    ),
                )
            }
            return
        }
        val normalized = message.trim()
        if (normalized.isEmpty()) return
        executeTurn(detail.conversation.id, normalized) { onEvent ->
            repository.sendMessage(detail.conversation.id, normalized, onEvent)
        }
    }

    fun answerClarification(questionId: String, optionId: String) {
        val detail = mutableState.value.detail ?: return
        if (detail.conversation.archived || mutableState.value.sending) return
        val pending = pendingClarification(detail)
        val option = pending?.options?.firstOrNull { it.id == optionId }
        if (pending == null || pending.id != questionId || option == null) {
            mutableState.update {
                it.copy(
                    notice = RentalManagerNotice(
                        "Вопрос уже изменился. Обновляю актуальный диалог.",
                        true,
                    ),
                )
            }
            reloadSelectedConversation()
            return
        }
        executeTurn(
            conversationId = detail.conversation.id,
            userContent = "Ответ на «${pending.prompt}»: ${option.label}",
        ) { onEvent ->
            repository.answerClarification(
                conversationId = detail.conversation.id,
                questionId = pending.id,
                optionId = option.id,
                onEvent = onEvent,
            )
        }
    }

    /**
     * Replaces the complete held cabin set. The Android client may only release cabins from the
     * current authoritative selection; finding or reacquiring cabins remains a backend operation.
     */
    fun replaceSelection(rentalItemIds: Set<String>) {
        val current = mutableState.value
        val actorId = current.actorId ?: return
        val detail = current.detail ?: return
        val selection = detail.currentSelection ?: run {
            mutableState.update {
                it.copy(
                    notice = RentalManagerNotice(
                        "Активная подборка изменилась. Обновляю диалог.",
                        true,
                    ),
                )
            }
            reloadSelectedConversation()
            return
        }
        if (detail.conversation.archived || current.selectionUpdating ||
            current.presentationPublishing || current.sending || current.archivingConversation ||
            commandJob?.isActive == true
        ) return
        val currentIds = selection.rentalItemIds.toSet()
        if (!currentIds.containsAll(rentalItemIds)) {
            mutableState.update {
                it.copy(
                    notice = RentalManagerNotice(
                        "Состав подборки изменился. Показана актуальная версия.",
                        true,
                    ),
                )
            }
            reloadSelectedConversation()
            return
        }
        val nextIds = rentalItemIds.sorted()
        if (nextIds == selection.rentalItemIds.sorted()) return
        val warehouseId = selection.warehouseId ?: run {
            reloadSelectedConversation()
            return
        }
        val conversationId = detail.conversation.id
        val inquiryId = detail.conversation.rentalInquiryId
        val fingerprint = actorScopedCommandFingerprint(
            actorId,
            listOf(conversationId, inquiryId, warehouseId, nextIds.joinToString(","))
                .joinToString("\u001f"),
        )
        mutableState.update { it.copy(selectionUpdating = true, notice = null) }
        commandJob = viewModelScope.launch {
            val idempotencyKey = commandId(SELECTION_COMMAND, fingerprint)
            runCatching {
                repository.replaceSelection(
                    conversationId = conversationId,
                    expectedInquiryId = inquiryId,
                    idempotencyKey = idempotencyKey,
                    warehouseId = warehouseId,
                    rentalItemIds = nextIds,
                )
                repository.conversation(conversationId)
            }.onSuccess { authoritativeDetail ->
                clearCommandId(SELECTION_COMMAND)
                if (mutableState.value.selectedConversationId == conversationId) {
                    mutableState.update {
                        it.copy(
                            detail = authoritativeDetail,
                            selectionUpdating = false,
                            notice = RentalManagerNotice(
                                if (nextIds.isEmpty()) {
                                    "Подборка освобождена."
                                } else {
                                    "Подборка обновлена."
                                },
                                false,
                            ),
                        )
                    }
                }
            }.onFailure { failure ->
                if (failure is CancellationException) throw failure
                if (failure is HttpException && failure.code() == 409) {
                    clearCommandId(SELECTION_COMMAND)
                }
                val failureMessage = if (failure is HttpException && failure.code() == 409) {
                    "Подборка уже изменилась. Показана актуальная версия."
                } else {
                    handleFailure(failure)
                }
                val recovery = runCatching { repository.conversation(conversationId) }
                recovery.exceptionOrNull()?.let { recoveryFailure ->
                    if (recoveryFailure is CancellationException) throw recoveryFailure
                }
                val recoveredDetail = recovery.getOrNull()
                val recoveredSelection = recoveredDetail?.currentSelection
                val mutationApplied = if (nextIds.isEmpty()) {
                    recoveredDetail != null && recoveredSelection == null
                } else {
                    recoveredSelection?.warehouseId == warehouseId &&
                        recoveredSelection.rentalItemIds.sorted() == nextIds
                }
                if (mutationApplied) clearCommandId(SELECTION_COMMAND)
                if (mutableState.value.selectedConversationId == conversationId) {
                    mutableState.update {
                        it.copy(
                            detail = recoveredDetail ?: it.detail,
                            selectionUpdating = false,
                            notice = RentalManagerNotice(
                                if (mutationApplied) {
                                    "Подборка обновлена по данным сервера."
                                } else {
                                    failureMessage
                                },
                                !mutationApplied,
                            ),
                        )
                    }
                    loadPresentation(inquiryId, conversationId)
                }
            }
        }
    }

    fun publishPresentation() {
        val current = mutableState.value
        val actorId = current.actorId ?: return
        val detail = current.detail ?: return
        val selection = detail.currentSelection
        val search = detail.lastSearchResult
        if (detail.conversation.archived || current.presentationPublishing ||
            current.selectionUpdating || current.sending || current.archivingConversation ||
            commandJob?.isActive == true
        ) return
        if (current.presentationLoading) {
            mutableState.update {
                it.copy(
                    notice = RentalManagerNotice(
                        "Дождитесь проверки текущей ссылки для клиента.",
                        false,
                    ),
                )
            }
            return
        }
        val existingPresentation = current.presentation
        if (existingPresentation != null &&
            (existingPresentation.mode == RentalPresentationMode.REPLACEMENT ||
                existingPresentation.state == RentalPresentationState.BOOKING_PENDING ||
                existingPresentation.state == RentalPresentationState.BOOKED)
        ) {
            val message = if (existingPresentation.mode == RentalPresentationMode.REPLACEMENT) {
                "Представление замены доступно менеджеру аренды только для просмотра."
            } else {
                "Клиент уже использует текущую ссылку. Заменить её нельзя."
            }
            mutableState.update {
                it.copy(
                    notice = RentalManagerNotice(message, true),
                )
            }
            return
        }
        if (selection == null || search == null || selection.rentalItemIds.isEmpty()) {
            mutableState.update {
                it.copy(
                    notice = RentalManagerNotice(
                        "Сначала подберите и выберите бытовки для клиента.",
                        true,
                    ),
                )
            }
            return
        }
        val warehouseId = selection.warehouseId
        if (warehouseId == null || warehouseId != search.data.warehouseId) {
            mutableState.update {
                it.copy(
                    notice = RentalManagerNotice(
                        "Подборка уже изменилась. Обновляю актуальные данные.",
                        true,
                    ),
                )
            }
            reloadSelectedConversation()
            return
        }
        if (selection.expiresAt?.let(::isExpired) != false) {
            mutableState.update {
                it.copy(
                    notice = RentalManagerNotice(
                        "Резерв бытовок истёк. Выполните новый поиск.",
                        true,
                    ),
                )
            }
            reloadSelectedConversation()
            return
        }
        val groups = runCatching {
            presentationGroups(
                searchGroups = search.data.groups.map { result ->
                    result.group to result.cabins.map { cabin -> cabin.id }
                },
                selectedIds = selection.rentalItemIds.toSet(),
            )
        }.getOrElse {
            mutableState.update { state ->
                state.copy(
                    notice = RentalManagerNotice(
                        "Подборка не совпадает с актуальными результатами поиска. Обновите диалог.",
                        true,
                    ),
                )
            }
            return
        }
        val conversationId = detail.conversation.id
        val inquiryId = detail.conversation.rentalInquiryId
        val fingerprint = actorScopedCommandFingerprint(
            actorId,
            presentationFingerprint(conversationId, inquiryId, warehouseId, groups),
        )
        presentationJob?.cancel()
        mutableState.update {
            it.copy(presentationPublishing = true, presentationLoading = false, notice = null)
        }
        commandJob = viewModelScope.launch {
            val idempotencyKey = commandId(PRESENTATION_COMMAND, fingerprint)
            runCatching {
                presentations.publish(
                    inquiryId = inquiryId,
                    idempotencyKey = idempotencyKey,
                    warehouseId = warehouseId,
                    selectedRentalItemIds = selection.rentalItemIds,
                    groups = groups,
                )
            }.onSuccess { presentation ->
                clearCommandId(PRESENTATION_COMMAND)
                if (mutableState.value.selectedConversationId == conversationId) {
                    mutableState.update {
                        it.copy(
                            presentation = presentation,
                            presentationPublishing = false,
                            notice = RentalManagerNotice(
                                "Ссылка для клиента создана. Скопируйте или отправьте её.",
                                false,
                            ),
                        )
                    }
                }
            }.onFailure { failure ->
                if (failure is CancellationException) throw failure
                if (failure is HttpException && failure.code() == 409) {
                    clearCommandId(PRESENTATION_COMMAND)
                }
                val failureMessage = handlePresentationFailure(failure)
                val recoveredDetail = runCatching { repository.conversation(conversationId) }
                recoveredDetail.exceptionOrNull()?.let { recoveryFailure ->
                    if (recoveryFailure is CancellationException) throw recoveryFailure
                }
                val recoveredPresentation = runCatching { presentations.get(inquiryId) }
                recoveredPresentation.exceptionOrNull()?.let { recoveryFailure ->
                    if (recoveryFailure is CancellationException) throw recoveryFailure
                }
                val recoveredPresentationValue = recoveredPresentation.getOrNull()
                val publicationApplied = recoveredPresentationValue?.let { presentation ->
                    matchesPresentation(presentation, inquiryId, warehouseId, groups)
                } == true
                if (publicationApplied) clearCommandId(PRESENTATION_COMMAND)
                if (mutableState.value.selectedConversationId == conversationId) {
                    mutableState.update {
                        it.copy(
                            detail = recoveredDetail.getOrNull() ?: it.detail,
                            presentation = if (recoveredPresentation.isSuccess) {
                                recoveredPresentationValue
                            } else {
                                it.presentation
                            },
                            presentationPublishing = false,
                            presentationLoading = false,
                            notice = RentalManagerNotice(
                                if (publicationApplied) {
                                    "Ссылка для клиента подтверждена по данным сервера."
                                } else if (failure is HttpException && failure.code() == 409) {
                                    "Представление уже изменилось. Показана актуальная версия."
                                } else {
                                    failureMessage
                                },
                                !publicationApplied,
                            ),
                        )
                    }
                }
            }
        }
    }

    private fun executeTurn(
        conversationId: String,
        userContent: String,
        operation: suspend (suspend (AssistantTurnEvent) -> Unit) -> AssistantTurnResult,
    ) {
        if (mutableState.value.sending || commandJob?.isActive == true) return
        mutableState.update {
            it.copy(
                sending = true,
                liveUserContent = userContent,
                liveAssistantContent = "",
                toolRunning = false,
                notice = null,
            )
        }
        commandJob = viewModelScope.launch {
            runCatching {
                operation { event -> consumeTurnEvent(conversationId, event) }
            }.onSuccess { result ->
                val terminalNotice = terminalNotice(result.terminal)
                val refreshFailure = runCatching {
                    val snapshot = authoritativeSnapshot(conversationId)
                    if (mutableState.value.selectedConversationId == conversationId) {
                        mutableState.update {
                            it.copy(
                                detail = snapshot.first,
                                conversations = snapshot.second,
                                sending = false,
                                liveUserContent = null,
                                liveAssistantContent = "",
                                toolRunning = false,
                                notice = terminalNotice,
                            )
                        }
                    }
                }.exceptionOrNull()
                if (refreshFailure is CancellationException) throw refreshFailure
                if (refreshFailure != null && refreshFailure !is CancellationException &&
                    mutableState.value.selectedConversationId == conversationId
                ) {
                    mutableState.update {
                        it.copy(
                            sending = false,
                            toolRunning = false,
                            notice = RentalManagerNotice(handleFailure(refreshFailure), true),
                        )
                    }
                }
            }.onFailure { failure ->
                if (failure is CancellationException) throw failure
                val message = if (failure is HttpException && failure.code() == 409) {
                    "Диалог уже изменился. Показана актуальная версия."
                } else {
                    handleFailure(failure)
                }
                val recovery = runCatching { authoritativeSnapshot(conversationId) }
                recovery.exceptionOrNull()?.let { recoveryFailure ->
                    if (recoveryFailure is CancellationException) throw recoveryFailure
                }
                recovery
                    .onSuccess { (detail, conversations) ->
                        if (mutableState.value.selectedConversationId == conversationId) {
                            mutableState.update {
                                it.copy(detail = detail, conversations = conversations)
                            }
                        }
                    }
                if (mutableState.value.selectedConversationId == conversationId) {
                    mutableState.update {
                        it.copy(
                            sending = false,
                            liveUserContent = null,
                            liveAssistantContent = "",
                            toolRunning = false,
                            notice = RentalManagerNotice(message, true),
                        )
                    }
                }
            }
        }
    }

    private suspend fun consumeTurnEvent(conversationId: String, event: AssistantTurnEvent) {
        if (mutableState.value.selectedConversationId != conversationId) return
        when (event) {
            is AssistantTextDelta -> mutableState.update {
                it.copy(liveAssistantContent = it.liveAssistantContent + event.delta)
            }
            is AssistantToolActivity -> mutableState.update {
                it.copy(toolRunning = event.event == "tool.started" ||
                    (it.toolRunning && event.event != "tool.completed"))
            }
            is AssistantClarificationRequested -> mergeClarification(event.clarification)
            is AssistantClarificationAnswered -> mergeClarification(event.clarification)
            is AssistantTurnTerminal -> mutableState.update { it.copy(toolRunning = false) }
            else -> Unit
        }
    }

    private fun mergeClarification(question: AssistantClarificationQuestion) {
        mutableState.update { state ->
            val detail = state.detail ?: return@update state
            state.copy(
                detail = detail.copy(
                    clarifications = (detail.clarifications.filterNot { it.id == question.id } +
                        question).sortedBy(AssistantClarificationQuestion::sequenceNumber),
                ),
            )
        }
    }

    private fun reloadSelectedConversation() {
        val conversationId = mutableState.value.selectedConversationId ?: return
        detailJob?.cancel()
        detailJob = viewModelScope.launch { loadConversation(conversationId) }
    }

    private suspend fun loadConversation(conversationId: String) {
        runCatching { repository.conversation(conversationId) }
            .onSuccess { detail ->
                if (mutableState.value.selectedConversationId == conversationId) {
                    mutableState.update {
                        it.copy(detail = detail, detailLoading = false, notice = null)
                    }
                    loadPresentation(detail.conversation.rentalInquiryId, conversationId)
                }
            }
            .onFailure { failure ->
                if (failure is CancellationException) throw failure
                if (mutableState.value.selectedConversationId == conversationId) {
                    mutableState.update {
                        it.copy(
                            detailLoading = false,
                            notice = RentalManagerNotice(handleFailure(failure), true),
                        )
                    }
                }
            }
    }

    private fun loadPresentation(inquiryId: String, conversationId: String) {
        presentationJob?.cancel()
        mutableState.update { it.copy(presentationLoading = true) }
        presentationJob = viewModelScope.launch {
            runCatching { presentations.get(inquiryId) }
                .onSuccess { presentation ->
                    if (mutableState.value.selectedConversationId == conversationId) {
                        mutableState.update {
                            it.copy(presentation = presentation, presentationLoading = false)
                        }
                    }
                }
                .onFailure { failure ->
                    if (failure is CancellationException) throw failure
                    val message = handlePresentationFailure(failure)
                    if (mutableState.value.selectedConversationId == conversationId) {
                        mutableState.update {
                            it.copy(
                                presentationLoading = false,
                                notice = RentalManagerNotice(message, true),
                            )
                        }
                    }
                }
        }
    }

    private suspend fun authoritativeSnapshot(
        conversationId: String,
    ): Pair<AssistantConversationDetail, List<AssistantConversation>> = coroutineScope {
        val detail = async { repository.conversation(conversationId) }
        val conversations = async { repository.conversations() }
        detail.await() to conversations.await()
    }

    private fun commandId(commandName: String, fingerprint: String): UUID {
        val digest = commandFingerprintDigest(fingerprint)
        val fingerprintKey = "$commandName.fingerprint"
        val idKey = "$commandName.id"
        val storedFingerprint = savedStateHandle.get<String>(fingerprintKey)
        val storedId = savedStateHandle.get<String>(idKey)
        if (storedFingerprint == digest && storedId != null) {
            runCatching { UUID.fromString(storedId) }.getOrNull()?.let { return it }
        }
        return UUID.randomUUID().also { id ->
            savedStateHandle[fingerprintKey] = digest
            savedStateHandle[idKey] = id.toString()
        }
    }

    private fun clearCommandId(commandName: String) {
        savedStateHandle.remove<String>("$commandName.fingerprint")
        savedStateHandle.remove<String>("$commandName.id")
    }

    private suspend fun handleFailure(failure: Throwable): String = handleRentalManagerFailure(
        failure = failure,
        messageFor = repository::userMessage,
        invalidate = invalidateSession,
    )

    private suspend fun handlePresentationFailure(failure: Throwable): String =
        handleRentalManagerFailure(
            failure = failure,
            messageFor = presentations::userMessage,
            invalidate = invalidateSession,
        )

    private fun reset() {
        cancelWork()
        mutableState.value = RentalManagerChatUiState()
    }

    private fun cancelWork() {
        listJob?.cancel()
        detailJob?.cancel()
        presentationJob?.cancel()
        commandJob?.cancel()
        listJob = null
        detailJob = null
        presentationJob = null
        commandJob = null
    }

    companion object {
        private const val CREATE_CONVERSATION_COMMAND = "chat.create"
        private const val SELECTION_COMMAND = "chat.selection"
        private const val PRESENTATION_COMMAND = "chat.presentation"

        fun factory(backend: RentalManagerBackend) = viewModelFactory {
            initializer {
                RentalManagerChatViewModel(
                    repository = AssistantRepository(
                        api = backend.assistantApi,
                        moshi = backend.assistantMoshi,
                        problemMessage = backend::problemMessage,
                    ),
                    presentations = RentalPresentationRepository(backend),
                    authState = backend.auth.state,
                    invalidateSession = backend.auth::invalidate,
                    savedStateHandle = createSavedStateHandle(),
                )
            }
        }
    }
}

private fun pendingClarification(
    detail: AssistantConversationDetail,
): AssistantClarificationQuestion? = detail.clarifications
    .asSequence()
    .filter { it.status == AssistantClarificationStatus.PENDING }
    .minByOrNull(AssistantClarificationQuestion::sequenceNumber)

private fun canonicalUuid(value: String): String? = runCatching {
    UUID.fromString(value).toString()
}.getOrNull()?.takeIf { it.equals(value, ignoreCase = true) }

private fun presentationGroups(
    searchGroups: List<Pair<AssistantCabinSearchGroup, List<String>>>,
    selectedIds: Set<String>,
): List<RentalPresentationGroupRequest> {
    require(selectedIds.isNotEmpty())
    val included = mutableSetOf<String>()
    val result = searchGroups.mapIndexedNotNull { index, (group, cabinIds) ->
        val groupIds = cabinIds.mapNotNull { id ->
            if (!selectedIds.contains(id)) return@mapNotNull null
            require(included.add(id)) { "A selected cabin occurs in more than one search group" }
            id
        }
        if (groupIds.isEmpty()) {
            null
        } else {
            RentalPresentationGroupRequest(
                key = "group-${index + 1}",
                label = presentationGroupLabel(group, index).take(255),
                rentalItemIds = groupIds,
            )
        }
    }
    require(result.size in 1..5) { "A presentation requires from one to five groups" }
    require(included == selectedIds) { "Search results do not contain the complete selection" }
    return result
}

private fun presentationGroupLabel(group: AssistantCabinSearchGroup, index: Int): String {
    val categories = group.categories?.takeIf { it.isNotEmpty() }?.joinToString(" или ")
        ?: group.category
    return listOfNotNull(
        group.cabinType?.displayCabinFacet(),
        group.finish?.takeIf(String::isNotBlank),
        group.dimensions?.takeIf(String::isNotBlank),
        categories?.takeIf(String::isNotBlank),
        group.characteristics?.takeIf(String::isNotBlank),
        group.linoleum?.let { if (it) "С линолеумом" else "Без линолеума" },
    ).joinToString(" · ").ifEmpty { "Подборка ${index + 1}" }
}

private fun String.displayCabinFacet(): String? = takeIf(String::isNotBlank)?.let { value ->
    when (value.uppercase()) {
        "LDSP" -> "ЛДСП"
        else -> value
    }
}

private fun presentationFingerprint(
    conversationId: String,
    inquiryId: String,
    warehouseId: String,
    groups: List<RentalPresentationGroupRequest>,
): String = buildString {
    appendFingerprintPart(conversationId)
    appendFingerprintPart(inquiryId)
    appendFingerprintPart(warehouseId)
    groups.forEach { group ->
        appendFingerprintPart(group.key)
        appendFingerprintPart(group.label)
        group.rentalItemIds.forEach { rentalItemId -> appendFingerprintPart(rentalItemId) }
    }
}

private fun matchesPresentation(
    presentation: RentalPresentationDto,
    inquiryId: String,
    warehouseId: String,
    groups: List<RentalPresentationGroupRequest>,
): Boolean = presentation.inquiryId == inquiryId &&
    presentation.warehouseId == warehouseId &&
    presentation.mode == RentalPresentationMode.NORMAL &&
    presentation.groups.size == groups.size &&
    presentation.groups.zip(groups).all { (actual, expected) ->
        actual.key == expected.key &&
            actual.label == expected.label &&
            actual.cabins.map { it.id } == expected.rentalItemIds
    }

private fun StringBuilder.appendFingerprintPart(value: String) {
    append(value.length).append(':').append(value).append('|')
}

private fun isExpired(value: String): Boolean = runCatching {
    !OffsetDateTime.parse(value).isAfter(OffsetDateTime.now())
}.getOrDefault(true)

private fun terminalNotice(terminal: AssistantTurnTerminal): RentalManagerNotice? =
    if (terminal is AssistantTurnFailed) {
        RentalManagerNotice(
            message = when (terminal.code) {
                AssistantTurnFailureCode.CONVERSATION_UNAVAILABLE ->
                    "Диалог больше недоступен. Обновите список."
                AssistantTurnFailureCode.PROVIDER_FAILED ->
                    "Помощник временно недоступен. Повторите сообщение позже."
                AssistantTurnFailureCode.TURN_FAILED ->
                    "Не удалось обработать сообщение. Повторите попытку."
            },
            isError = true,
        )
    } else {
        null
    }
