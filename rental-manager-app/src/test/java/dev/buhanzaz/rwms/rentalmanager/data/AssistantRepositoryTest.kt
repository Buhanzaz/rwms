package dev.buhanzaz.rwms.rentalmanager.data

import com.google.common.truth.Truth.assertThat
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import dev.buhanzaz.rwms.rentalmanager.network.AssistantApi
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
import dev.buhanzaz.rwms.rentalmanager.network.AssistantCabinSelectionRequest
import dev.buhanzaz.rwms.rentalmanager.network.AssistantCabinStatus
import dev.buhanzaz.rwms.rentalmanager.network.AssistantClarificationAnswer
import dev.buhanzaz.rwms.rentalmanager.network.AssistantClientSummary
import dev.buhanzaz.rwms.rentalmanager.network.AssistantConversation
import dev.buhanzaz.rwms.rentalmanager.network.AssistantConversationDetail
import dev.buhanzaz.rwms.rentalmanager.network.AssistantMessage
import dev.buhanzaz.rwms.rentalmanager.network.AssistantMessageRole
import dev.buhanzaz.rwms.rentalmanager.network.AssistantRentalInquirySummary
import dev.buhanzaz.rwms.rentalmanager.network.AssistantTextDelta
import dev.buhanzaz.rwms.rentalmanager.network.AssistantTurnRequest
import dev.buhanzaz.rwms.rentalmanager.network.CreateAssistantConversationRequest
import dev.buhanzaz.rwms.rentalmanager.network.CreateAssistantConversationResponse
import java.util.UUID
import java.util.concurrent.CancellationException
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response

class AssistantRepositoryTest {
    @Test
    fun `creates a conversation only from an existing client`() = runTest {
        val api = FakeAssistantApi()
        val repository = repository(api)

        val response = repository.createConversation(
            clientId = CLIENT_ID,
            conversationId = CONVERSATION_ID,
            rentalOrderId = ORDER_ID,
        )

        assertThat(response.conversation.id).isEqualTo(CONVERSATION_ID)
        assertThat(api.createRequest).isEqualTo(
            CreateAssistantConversationRequest(CONVERSATION_ID, CLIENT_ID, ORDER_ID),
        )
    }

    @Test
    fun `free text and exact clarification answers stay mutually exclusive`() = runTest {
        val api = FakeAssistantApi()
        val repository = repository(api)

        val textResult = repository.sendMessage(CONVERSATION_ID, "Нужна бытовка")
        val textRequest = api.turnRequest
        val answerResult = repository.answerClarification(
            CONVERSATION_ID,
            QUESTION_ID,
            OPTION_ID,
        )
        val answerRequest = api.turnRequest

        assertThat(textResult.assistantText).isEqualTo("Готово")
        assertThat(textRequest?.message).isEqualTo("Нужна бытовка")
        assertThat(textRequest?.clarificationAnswer).isNull()
        assertThat(answerResult.assistantText).isEqualTo("Готово")
        assertThat(answerRequest?.message).isNull()
        assertThat(answerRequest?.clarificationAnswer).isEqualTo(
            AssistantClarificationAnswer(QUESTION_ID, OPTION_ID),
        )
    }

    @Test
    fun `normalizes text and exposes validated stream events without replay`() = runTest {
        val api = FakeAssistantApi()
        val repository = repository(api)
        val deltas = mutableListOf<String>()

        repository.sendMessage(CONVERSATION_ID, "  Нужна бытовка  ") { event ->
            if (event is AssistantTextDelta) deltas += event.delta
        }

        assertThat(api.turnCalls).isEqualTo(1)
        assertThat(api.turnRequest?.message).isEqualTo("Нужна бытовка")
        assertThat(deltas).containsExactly("Готово")
    }

    @Test
    fun `rejects a create response for another requested identity`() = runTest {
        val api = FakeAssistantApi().apply {
            createResponse = CREATE_RESPONSE.copy(
                conversation = CONVERSATION.copy(clientId = OTHER_CLIENT_ID),
                client = AssistantClientSummary(OTHER_CLIENT_ID, "LEGAL_ENTITY", "Чужой клиент"),
            )
        }
        val repository = repository(api)

        val failure = captureFailure {
            repository.createConversation(
                clientId = CLIENT_ID,
                conversationId = CONVERSATION_ID,
                rentalOrderId = ORDER_ID,
            )
        }

        assertThat(failure).isInstanceOf(IllegalStateException::class.java)
        assertThat(failure).hasMessageThat().contains("identities")
    }

    @Test
    fun `validates typed search selection and message notices in conversation detail`() = runTest {
        val api = FakeAssistantApi().apply { conversationResponse = VALID_DETAIL }
        val repository = repository(api)

        val detail = repository.conversation(CONVERSATION_ID)

        assertThat(detail.messages.single().searchNotices).containsExactly(VALID_NOTICE)
        assertThat(detail.lastSearchResult).isEqualTo(VALID_SEARCH_RESULT)
        assertThat(detail.currentSelection).isEqualTo(VALID_SELECTION)
    }

    @Test
    fun `rejects invalid typed detail projections`() = runTest {
        val api = FakeAssistantApi()
        val repository = repository(api)
        val invalidDetails = listOf(
            VALID_DETAIL.copy(
                messages = listOf(
                    VALID_MESSAGE.copy(
                        searchNotices = listOf(
                            VALID_NOTICE.copy(foundQuantity = 3),
                        ),
                    ),
                ),
            ),
            VALID_DETAIL.copy(
                lastSearchResult = VALID_SEARCH_RESULT.copy(tool = "unknown_tool"),
            ),
            VALID_DETAIL.copy(
                currentSelection = VALID_SELECTION.copy(inquiryId = OTHER_INQUIRY_ID),
            ),
        )

        val failures = invalidDetails.map { invalidDetail ->
            api.conversationResponse = invalidDetail
            captureFailure { repository.conversation(CONVERSATION_ID) }
        }

        assertThat(failures).hasSize(3)
        failures.forEach { failure ->
            assertThat(failure).isInstanceOf(IllegalArgumentException::class.java)
        }
        assertThat(failures[0]).hasMessageThat().contains("quantity")
        assertThat(failures[1]).hasMessageThat().contains("search tool")
        assertThat(failures[2]).hasMessageThat().contains("inquiry")
    }

    @Test
    fun `non empty replacement sends a sorted complete request and validates response`() = runTest {
        val api = FakeAssistantApi().apply { selectionResponse = VALID_SELECTION }
        val repository = repository(api)
        val idempotencyKey = UUID.fromString(IDEMPOTENCY_KEY)

        val selection = repository.replaceSelection(
            conversationId = CONVERSATION_ID,
            expectedInquiryId = INQUIRY_ID,
            idempotencyKey = idempotencyKey,
            warehouseId = WAREHOUSE_ID,
            rentalItemIds = listOf(CABIN_B_ID, CABIN_A_ID),
        )

        assertThat(selection).isEqualTo(VALID_SELECTION)
        assertThat(api.selectionConversationId).isEqualTo(CONVERSATION_ID)
        assertThat(api.selectionIdempotencyKey).isEqualTo(IDEMPOTENCY_KEY)
        assertThat(api.selectionRequest).isEqualTo(
            AssistantCabinSelectionRequest(
                warehouseId = WAREHOUSE_ID,
                rentalItemIds = listOf(CABIN_A_ID, CABIN_B_ID),
            ),
        )
    }

    @Test
    fun `release accepts only empty authoritative selection shape`() = runTest {
        val api = FakeAssistantApi().apply { selectionResponse = RELEASED_SELECTION }
        val repository = repository(api)

        val released = repository.replaceSelection(
            conversationId = CONVERSATION_ID,
            expectedInquiryId = INQUIRY_ID,
            idempotencyKey = UUID.fromString(IDEMPOTENCY_KEY),
            warehouseId = WAREHOUSE_ID,
            rentalItemIds = emptyList(),
        )

        assertThat(released).isEqualTo(RELEASED_SELECTION)
        assertThat(api.selectionRequest).isEqualTo(
            AssistantCabinSelectionRequest(WAREHOUSE_ID, emptyList()),
        )

        val invalidReleases = listOf(
            RELEASED_SELECTION.copy(warehouseId = WAREHOUSE_ID),
            RELEASED_SELECTION.copy(expiresAt = EXPIRES_AT),
            RELEASED_SELECTION.copy(items = listOf(CABIN_A)),
            VALID_SELECTION,
        )
        invalidReleases.forEach { invalidRelease ->
            api.selectionResponse = invalidRelease
            assertThat(
                captureFailure {
                    repository.replaceSelection(
                        conversationId = CONVERSATION_ID,
                        expectedInquiryId = INQUIRY_ID,
                        idempotencyKey = UUID.fromString(IDEMPOTENCY_KEY),
                        warehouseId = WAREHOUSE_ID,
                        rentalItemIds = emptyList(),
                    )
                },
            ).isInstanceOf(IllegalArgumentException::class.java)
        }
    }

    @Test
    fun `replacement rejects duplicate input and mismatched authoritative identities and order`() =
        runTest {
            val api = FakeAssistantApi()
            val repository = repository(api)
            suspend fun replace(): AssistantCabinSelection = repository.replaceSelection(
                conversationId = CONVERSATION_ID,
                expectedInquiryId = INQUIRY_ID,
                idempotencyKey = UUID.fromString(IDEMPOTENCY_KEY),
                warehouseId = WAREHOUSE_ID,
                rentalItemIds = listOf(CABIN_A_ID, CABIN_B_ID),
            )

            val duplicateInput = captureFailure {
                repository.replaceSelection(
                    conversationId = CONVERSATION_ID,
                    expectedInquiryId = INQUIRY_ID,
                    idempotencyKey = UUID.fromString(IDEMPOTENCY_KEY),
                    warehouseId = WAREHOUSE_ID,
                    rentalItemIds = listOf(CABIN_A_ID, CABIN_A_ID),
                )
            }
            assertThat(duplicateInput).hasMessageThat().contains("unique")
            assertThat(api.selectionCalls).isEqualTo(0)

            api.selectionResponse = VALID_SELECTION.copy(inquiryId = OTHER_INQUIRY_ID)
            assertThat(captureFailure { replace() }).hasMessageThat().contains("inquiry")

            api.selectionResponse = VALID_SELECTION.copy(
                warehouseId = OTHER_WAREHOUSE_ID,
                items = VALID_SELECTION.items.map { it.copy(warehouseId = OTHER_WAREHOUSE_ID) },
            )
            assertThat(captureFailure { replace() }).hasMessageThat().contains("warehouse")

            api.selectionResponse = VALID_SELECTION.copy(
                rentalItemIds = listOf(CABIN_B_ID, CABIN_A_ID),
                items = listOf(CABIN_B, CABIN_A),
            )
            assertThat(captureFailure { replace() }).hasMessageThat().contains("items do not match")

            api.selectionResponse = VALID_SELECTION.copy(items = listOf(CABIN_B, CABIN_A))
            assertThat(captureFailure { replace() }).hasMessageThat().contains("item order")

            api.selectionResponse = VALID_SELECTION.copy(
                rentalItemIds = listOf(CABIN_A_ID, CABIN_A_ID),
                items = listOf(CABIN_A, CABIN_A),
            )
            assertThat(captureFailure { replace() }).hasMessageThat().contains("duplicate")
        }

    @Test
    fun `rejects blank or oversized text before an API call`() = runTest {
        val api = FakeAssistantApi()
        val repository = repository(api)

        val blankFailure = captureFailure { repository.sendMessage(CONVERSATION_ID, "   ") }
        val longFailure = captureFailure { repository.sendMessage(CONVERSATION_ID, "x".repeat(8_001)) }

        assertThat(blankFailure).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(longFailure).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(api.turnCalls).isEqualTo(0)
    }

    @Test
    fun `preserves cancellation from transport and message mapping`() = runTest {
        val api = FakeAssistantApi()
        val repository = repository(api)
        val cancellation = CancellationException("cancelled")
        api.turnFailure = cancellation

        val transportFailure = captureFailure {
            repository.sendMessage(CONVERSATION_ID, "Нужна бытовка")
        }
        val mappingFailure = try {
            repository.userMessage(cancellation)
            AssertionError("Expected cancellation")
        } catch (failure: Throwable) {
            failure
        }

        assertThat(transportFailure).isSameInstanceAs(cancellation)
        assertThat(mappingFailure).isSameInstanceAs(cancellation)
    }

    @Test
    fun `uses the injected problem message without a backend dependency`() {
        val repository = repository(FakeAssistantApi(), problemText = "Конфликт беседы")
        val failure = HttpException(
            Response.error<Unit>(
                409,
                "{}".toResponseBody("application/problem+json".toMediaType()),
            ),
        )

        assertThat(repository.userMessage(failure)).isEqualTo("Конфликт беседы")
    }

    private fun repository(
        api: FakeAssistantApi,
        problemText: String = "Ошибка помощника",
    ) = AssistantRepository(
        api = api,
        moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build(),
        problemMessage = { problemText },
    )

    private suspend fun captureFailure(block: suspend () -> Unit): Throwable = try {
        block()
        AssertionError("Expected operation to fail")
    } catch (failure: Throwable) {
        failure
    }
}

private class FakeAssistantApi : AssistantApi {
    var createRequest: CreateAssistantConversationRequest? = null
    var turnRequest: AssistantTurnRequest? = null
    var turnFailure: Throwable? = null
    var turnCalls = 0
    var createResponse = CREATE_RESPONSE
    var conversationResponse = VALID_DETAIL
    var selectionResponse = VALID_SELECTION
    var selectionConversationId: String? = null
    var selectionIdempotencyKey: String? = null
    var selectionRequest: AssistantCabinSelectionRequest? = null
    var selectionCalls = 0

    override suspend fun conversations(rentalOrderId: String?): List<AssistantConversation> =
        listOf(CONVERSATION)

    override suspend fun createConversation(
        request: CreateAssistantConversationRequest,
    ): CreateAssistantConversationResponse {
        createRequest = request
        return createResponse
    }

    override suspend fun conversation(conversationId: String): AssistantConversationDetail =
        conversationResponse

    override suspend fun archiveConversation(conversationId: String): Response<Unit> =
        Response.success(Unit)

    override suspend fun replaceSelection(
        conversationId: String,
        idempotencyKey: String,
        request: AssistantCabinSelectionRequest,
    ): AssistantCabinSelection {
        selectionCalls += 1
        selectionConversationId = conversationId
        selectionIdempotencyKey = idempotencyKey
        selectionRequest = request
        return selectionResponse
    }

    override suspend fun turn(
        conversationId: String,
        request: AssistantTurnRequest,
    ): Response<ResponseBody> {
        turnCalls += 1
        turnFailure?.let { throw it }
        turnRequest = request
        val stream = buildString {
            append("event: assistant.delta\n")
            append("data: {\"event\":\"assistant.delta\",\"conversationId\":\"$CONVERSATION_ID\",\"delta\":\"Готово\"}\n\n")
            append("event: turn.completed\n")
            append("data: {\"event\":\"turn.completed\",\"conversationId\":\"$CONVERSATION_ID\",\"messageId\":\"$MESSAGE_ID\"}\n\n")
        }
        return Response.success(stream.toResponseBody("text/event-stream".toMediaType()))
    }
}

private const val CONVERSATION_ID = "10000000-0000-4000-8000-000000000001"
private const val CLIENT_ID = "20000000-0000-4000-8000-000000000002"
private const val OTHER_CLIENT_ID = "20000000-0000-4000-8000-000000000099"
private const val INQUIRY_ID = "30000000-0000-4000-8000-000000000003"
private const val ORDER_ID = "40000000-0000-4000-8000-000000000004"
private const val MESSAGE_ID = "50000000-0000-4000-8000-000000000005"
private const val QUESTION_ID = "60000000-0000-4000-8000-000000000006"
private const val OPTION_ID = "70000000-0000-4000-8000-000000000007"
private const val WAREHOUSE_ID = "80000000-0000-4000-8000-000000000008"
private const val OTHER_WAREHOUSE_ID = "80000000-0000-4000-8000-000000000099"
private const val CABIN_A_ID = "90000000-0000-4000-8000-000000000009"
private const val CABIN_B_ID = "a0000000-0000-4000-8000-00000000000a"
private const val OTHER_INQUIRY_ID = "b0000000-0000-4000-8000-00000000000b"
private const val IDEMPOTENCY_KEY = "c0000000-0000-4000-8000-00000000000c"
private const val EXPIRES_AT = "2026-09-02T09:15:00Z"
private const val UPDATED_AT = "2026-09-02T09:05:00Z"

private val CONVERSATION = AssistantConversation(
    id = CONVERSATION_ID,
    version = 1,
    clientId = CLIENT_ID,
    rentalInquiryId = INQUIRY_ID,
    rentalOrderId = ORDER_ID,
    clientType = "LEGAL_ENTITY",
    clientDisplayName = "ООО Монтаж",
    archived = false,
    archivedAt = null,
    createdAt = "2026-09-02T09:00:00Z",
    updatedAt = "2026-09-02T09:00:00Z",
)

private val CREATE_RESPONSE = CreateAssistantConversationResponse(
    conversation = CONVERSATION,
    inquiry = AssistantRentalInquirySummary(INQUIRY_ID, "DRAFT"),
    client = AssistantClientSummary(CLIENT_ID, "LEGAL_ENTITY", "ООО Монтаж"),
)

private val SEARCH_GROUP = AssistantCabinSearchGroup(
    cabinType = "LDSP",
    quantity = 2,
)

private val VALID_NOTICE = AssistantCabinSearchNotice(
    code = AssistantCabinSearchNoticeCode.CABINS_PARTIALLY_FOUND,
    groups = listOf(SEARCH_GROUP),
    requestedQuantity = 2,
    foundQuantity = 1,
)

private val CABIN_A = AssistantAvailableCabin(
    id = CABIN_A_ID,
    version = 4,
    warehouseId = WAREHOUSE_ID,
    number = "БК-101",
    status = AssistantCabinStatus.FREE,
    rentalType = "LDSP",
    updatedAt = UPDATED_AT,
)

private val CABIN_B = AssistantAvailableCabin(
    id = CABIN_B_ID,
    version = 2,
    warehouseId = WAREHOUSE_ID,
    number = "БК-102",
    status = AssistantCabinStatus.FREE,
    rentalType = "LDSP",
    updatedAt = UPDATED_AT,
)

private val VALID_SEARCH_RESULT = AssistantCabinSearchResult(
    tool = "search_available_cabins",
    resultMode = AssistantCabinSearchResultMode.REPLACE,
    filterSuggestions = AssistantCabinFilterSuggestions(
        cabinTypes = listOf("LDSP"),
        finishes = emptyList(),
        dimensions = emptyList(),
        categories = emptyList(),
        characteristics = emptyList(),
    ),
    notices = listOf(VALID_NOTICE),
    data = AssistantCabinSearchProjection(
        warehouseId = WAREHOUSE_ID,
        expiresAt = EXPIRES_AT,
        groups = listOf(
            AssistantCabinSearchGroupResult(
                group = SEARCH_GROUP,
                cabins = listOf(CABIN_A),
            ),
        ),
    ),
)

private val VALID_SELECTION = AssistantCabinSelection(
    inquiryId = INQUIRY_ID,
    warehouseId = WAREHOUSE_ID,
    expiresAt = EXPIRES_AT,
    rentalItemIds = listOf(CABIN_A_ID, CABIN_B_ID),
    items = listOf(CABIN_A, CABIN_B),
)

private val RELEASED_SELECTION = AssistantCabinSelection(
    inquiryId = INQUIRY_ID,
    warehouseId = null,
    expiresAt = null,
    rentalItemIds = emptyList(),
    items = emptyList(),
)

private val VALID_MESSAGE = AssistantMessage(
    id = MESSAGE_ID,
    role = AssistantMessageRole.ASSISTANT,
    content = "Найдена одна из двух бытовок.",
    createdAt = UPDATED_AT,
    searchNotices = listOf(VALID_NOTICE),
)

private val VALID_DETAIL = AssistantConversationDetail(
    conversation = CONVERSATION,
    messages = listOf(VALID_MESSAGE),
    lastSearchResult = VALID_SEARCH_RESULT,
    clarifications = emptyList(),
    currentSelection = VALID_SELECTION,
)
