package dev.buhanzaz.rwms.rentalmanager.data

import com.squareup.moshi.Moshi
import dev.buhanzaz.rwms.rentalmanager.network.AssistantApi
import dev.buhanzaz.rwms.rentalmanager.network.AssistantAvailableCabin
import dev.buhanzaz.rwms.rentalmanager.network.AssistantCabinSearchGroup
import dev.buhanzaz.rwms.rentalmanager.network.AssistantCabinSearchNotice
import dev.buhanzaz.rwms.rentalmanager.network.AssistantCabinSearchResult
import dev.buhanzaz.rwms.rentalmanager.network.AssistantCabinSelection
import dev.buhanzaz.rwms.rentalmanager.network.AssistantCabinSelectionRequest
import dev.buhanzaz.rwms.rentalmanager.network.AssistantClarificationAnswer
import dev.buhanzaz.rwms.rentalmanager.network.AssistantClarificationQuestion
import dev.buhanzaz.rwms.rentalmanager.network.AssistantConversation
import dev.buhanzaz.rwms.rentalmanager.network.AssistantConversationDetail
import dev.buhanzaz.rwms.rentalmanager.network.AssistantSseParser
import dev.buhanzaz.rwms.rentalmanager.network.AssistantTurnRequest
import dev.buhanzaz.rwms.rentalmanager.network.AssistantTurnResult
import dev.buhanzaz.rwms.rentalmanager.network.AssistantTurnEvent
import dev.buhanzaz.rwms.rentalmanager.network.CreateAssistantConversationRequest
import dev.buhanzaz.rwms.rentalmanager.network.CreateAssistantConversationResponse
import java.time.OffsetDateTime
import java.util.UUID
import java.util.concurrent.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import retrofit2.HttpException

/** Validated assistant conversation boundary; logistics and asset remain selection/hold owners. */
class AssistantRepository(
    private val api: AssistantApi,
    moshi: Moshi,
    private val problemMessage: (HttpException) -> String,
) {
    private val sse = AssistantSseParser(moshi)

    suspend fun conversations(rentalOrderId: String? = null): List<AssistantConversation> {
        val canonicalOrderId = rentalOrderId?.let { requireUuid(it, "rental order id").toString() }
        return api.conversations(canonicalOrderId).onEach(::validateConversation)
    }

    suspend fun createConversation(
        clientId: String,
        conversationId: String? = null,
        rentalOrderId: String? = null,
    ): CreateAssistantConversationResponse {
        val canonicalClientId = requireUuid(clientId, "client id").toString()
        val canonicalConversationId = conversationId?.let {
            requireUuid(it, "conversation id").toString()
        }
        val canonicalOrderId = rentalOrderId?.let {
            requireUuid(it, "rental order id").toString()
        }
        val response = api.createConversation(
            CreateAssistantConversationRequest(
                conversationId = canonicalConversationId,
                clientId = canonicalClientId,
                rentalOrderId = canonicalOrderId,
            ),
        )
        validateConversation(response.conversation)
        requireUuid(response.inquiry.id, "rental inquiry id")
        requireUuid(response.client.id, "client id")
        if (response.conversation.clientId != canonicalClientId ||
            response.client.id != canonicalClientId ||
            response.conversation.rentalInquiryId != response.inquiry.id ||
            (canonicalConversationId != null && response.conversation.id != canonicalConversationId) ||
            response.conversation.rentalOrderId != canonicalOrderId
        ) {
            throw IllegalStateException("Assistant conversation identities do not match")
        }
        return response
    }

    suspend fun conversation(conversationId: String): AssistantConversationDetail {
        val requestedId = requireUuid(conversationId, "conversation id").toString()
        val detail = api.conversation(requestedId)
        validateConversation(detail.conversation)
        if (detail.conversation.id != requestedId) {
            throw IllegalStateException("Assistant returned a different conversation")
        }
        detail.messages.forEach { message -> requireUuid(message.id, "message id") }
        detail.messages.flatMap { it.searchNotices }.forEach(::validateSearchNotice)
        detail.clarifications.forEach(::validateClarification)
        detail.lastSearchResult?.let(::validateSearchResult)
        detail.currentSelection?.let { selection ->
            validateSelection(selection, detail.conversation.rentalInquiryId)
            require(selection.rentalItemIds.isNotEmpty()) {
                "Empty assistant selection must be represented as null"
            }
        }
        return detail
    }

    suspend fun archiveConversation(conversationId: String) {
        val response = api.archiveConversation(requireUuid(conversationId, "conversation id").toString())
        if (!response.isSuccessful) throw HttpException(response)
    }

    suspend fun replaceSelection(
        conversationId: String,
        expectedInquiryId: String,
        idempotencyKey: UUID,
        warehouseId: String,
        rentalItemIds: Collection<String>,
    ): AssistantCabinSelection {
        val canonicalConversationId = requireUuid(conversationId, "conversation id").toString()
        val canonicalInquiryId = requireUuid(expectedInquiryId, "rental inquiry id").toString()
        val canonicalWarehouseId = requireUuid(warehouseId, "warehouse id").toString()
        val canonicalIds = rentalItemIds
            .map { requireUuid(it, "rental item id").toString() }
            .distinct()
            .sorted()
        require(canonicalIds.size == rentalItemIds.size) { "Rental item ids must be unique" }
        require(canonicalIds.size <= MAX_SELECTION_ITEMS) { "Assistant selection is too large" }
        val selection = api.replaceSelection(
            conversationId = canonicalConversationId,
            idempotencyKey = idempotencyKey.toString(),
            request = AssistantCabinSelectionRequest(
                warehouseId = canonicalWarehouseId,
                rentalItemIds = canonicalIds,
            ),
        )
        validateSelection(selection, canonicalInquiryId)
        if (canonicalIds.isEmpty()) {
            require(selection.warehouseId == null && selection.expiresAt == null) {
                "Released assistant selection retained warehouse or expiry"
            }
        } else {
            require(selection.warehouseId == canonicalWarehouseId) {
                "Assistant selection warehouse does not match"
            }
            require(selection.rentalItemIds == canonicalIds) {
                "Assistant selection items do not match"
            }
        }
        return selection
    }

    suspend fun sendMessage(
        conversationId: String,
        message: String,
        onEvent: suspend (AssistantTurnEvent) -> Unit = {},
    ): AssistantTurnResult {
        val normalizedMessage = message.trim()
        require(normalizedMessage.isNotEmpty()) { "Assistant message must not be blank" }
        require(normalizedMessage.length <= MAX_MESSAGE_CHARS) { "Assistant message is too long" }
        return turn(
            conversationId = conversationId,
            request = AssistantTurnRequest(message = normalizedMessage),
            onEvent = onEvent,
        )
    }

    suspend fun answerClarification(
        conversationId: String,
        questionId: String,
        optionId: String,
        onEvent: suspend (AssistantTurnEvent) -> Unit = {},
    ): AssistantTurnResult = turn(
        conversationId = conversationId,
        request = AssistantTurnRequest(
            clarificationAnswer = AssistantClarificationAnswer(
                questionId = requireUuid(questionId, "clarification question id").toString(),
                optionId = requireUuid(optionId, "clarification option id").toString(),
            ),
        ),
        onEvent = onEvent,
    )

    fun userMessage(failure: Throwable): String = when (failure) {
        is CancellationException -> throw failure
        is HttpException -> problemMessage(failure)
        else -> "Не удалось получить ответ помощника. Проверьте подключение и повторите попытку."
    }

    private suspend fun turn(
        conversationId: String,
        request: AssistantTurnRequest,
        onEvent: suspend (AssistantTurnEvent) -> Unit,
    ): AssistantTurnResult {
        val expectedConversationId = requireUuid(conversationId, "conversation id")
        val response = api.turn(expectedConversationId.toString(), request)
        if (!response.isSuccessful) throw HttpException(response)
        val body = response.body() ?: throw IllegalStateException("Assistant response has no SSE body")
        val mediaType = body.contentType()?.toString().orEmpty()
        if (!mediaType.substringBefore(';').trim().equals("text/event-stream", ignoreCase = true)) {
            body.close()
            throw IllegalStateException("Assistant response is not an SSE stream")
        }
        return withContext(Dispatchers.IO) {
            body.use { sse.parse(it, expectedConversationId, onEvent) }
        }
    }

    private fun validateConversation(conversation: AssistantConversation) {
        requireUuid(conversation.id, "conversation id")
        require(conversation.version >= 0) { "Invalid conversation version" }
        requireUuid(conversation.clientId, "client id")
        requireUuid(conversation.rentalInquiryId, "rental inquiry id")
        conversation.rentalOrderId?.let { requireUuid(it, "rental order id") }
    }

    private fun validateClarification(clarification: AssistantClarificationQuestion) {
        requireUuid(clarification.id, "clarification id")
        require(clarification.sequenceNumber >= 1) { "Invalid clarification sequence" }
        require(clarification.options.size in 2..30) { "Invalid clarification options" }
        clarification.options.forEach { requireUuid(it.id, "clarification option id") }
        clarification.answeredOptionId?.let { requireUuid(it, "answered option id") }
    }

    private fun validateSearchResult(result: AssistantCabinSearchResult) {
        require(result.tool == "search_available_cabins") { "Invalid assistant search tool" }
        val warehouseId = requireUuid(result.data.warehouseId, "search warehouse id").toString()
        requireOffsetDateTime(result.data.expiresAt, "search expiry")
        result.data.groups.forEach { entry ->
            validateSearchGroup(entry.group)
            entry.cabins.forEach { cabin -> validateCabin(cabin, warehouseId) }
        }
        result.notices.forEach(::validateSearchNotice)
    }

    private fun validateSearchNotice(notice: AssistantCabinSearchNotice) {
        require(notice.groups.isNotEmpty()) { "Assistant search notice has no groups" }
        require(notice.requestedQuantity in 1..30) { "Invalid requested cabin quantity" }
        require(notice.foundQuantity in 0..30) { "Invalid found cabin quantity" }
        require(notice.foundQuantity <= notice.requestedQuantity) {
            "Found cabin quantity exceeds requested quantity"
        }
        notice.groups.forEach(::validateSearchGroup)
    }

    private fun validateSearchGroup(group: AssistantCabinSearchGroup) {
        require(group.quantity in 1..30) { "Invalid assistant search group quantity" }
        val categories = group.categories
        require(categories == null || categories.size in 1..3) {
            "Invalid assistant search categories"
        }
        require(categories == null || categories.distinct().size == categories.size) {
            "Assistant search categories must be unique"
        }
        require(group.category == null || categories == null) {
            "Assistant search group mixes category filters"
        }
    }

    private fun validateSelection(selection: AssistantCabinSelection, expectedInquiryId: String) {
        require(requireUuid(selection.inquiryId, "selection inquiry id").toString() == expectedInquiryId) {
            "Assistant selection inquiry does not match"
        }
        require(selection.rentalItemIds.size <= MAX_SELECTION_ITEMS) {
            "Assistant selection is too large"
        }
        val ids = selection.rentalItemIds.map {
            requireUuid(it, "selected rental item id").toString()
        }
        require(ids.distinct().size == ids.size) { "Assistant selection contains duplicate ids" }
        val itemIds = selection.items.map {
            requireUuid(it.id, "available cabin id").toString()
        }
        require(itemIds == ids) { "Assistant selection item order does not match" }
        if (ids.isEmpty()) {
            require(selection.items.isEmpty()) { "Released assistant selection retained items" }
            require(selection.warehouseId == null && selection.expiresAt == null) {
                "Released assistant selection retained warehouse or expiry"
            }
            return
        }
        val warehouseId = selection.warehouseId?.let {
            requireUuid(it, "selection warehouse id").toString()
        } ?: throw IllegalArgumentException("Assistant selection warehouse is missing")
        selection.expiresAt?.let { requireOffsetDateTime(it, "selection expiry") }
            ?: throw IllegalArgumentException("Assistant selection expiry is missing")
        selection.items.forEach { cabin -> validateCabin(cabin, warehouseId) }
    }

    private fun validateCabin(cabin: AssistantAvailableCabin, expectedWarehouseId: String) {
        requireUuid(cabin.id, "available cabin id")
        require(cabin.version >= 0) { "Invalid available cabin version" }
        require(requireUuid(cabin.warehouseId, "available cabin warehouse id").toString() ==
            expectedWarehouseId
        ) { "Available cabin warehouse does not match" }
        requireOffsetDateTime(cabin.updatedAt, "available cabin update time")
    }

    private fun requireOffsetDateTime(value: String, field: String) {
        runCatching { OffsetDateTime.parse(value) }
            .getOrElse { throw IllegalArgumentException("Invalid $field", it) }
    }

    private fun requireUuid(value: String, field: String): UUID {
        val parsed = runCatching { UUID.fromString(value) }
            .getOrElse { throw IllegalArgumentException("Invalid $field", it) }
        require(parsed.toString().equals(value, ignoreCase = true)) { "Invalid $field" }
        return parsed
    }

    private companion object {
        const val MAX_MESSAGE_CHARS = 8_000
        const val MAX_SELECTION_ITEMS = 100
    }
}
