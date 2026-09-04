package dev.buhanzaz.rwms.rentalmanager.network

import com.google.common.truth.Truth.assertThat
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import java.util.UUID
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory

class AssistantApiTest {
    private lateinit var server: MockWebServer
    private lateinit var api: AssistantApi

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()
        api = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .client(OkHttpClient())
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(AssistantApi::class.java)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `list and detail use the canonical conversation endpoints`() = runTest {
        server.enqueue(jsonResponse("[$CONVERSATION_JSON]"))
        server.enqueue(jsonResponse(DETAIL_JSON))

        val conversations = api.conversations(ORDER_ID)
        val detail = api.conversation(CONVERSATION_ID)

        assertThat(conversations.single().clientDisplayName).isEqualTo("ООО Монтаж")
        assertThat(detail.messages.single().role).isEqualTo(AssistantMessageRole.USER)
        assertThat(detail.messages.single().searchNotices.single().code)
            .isEqualTo(AssistantCabinSearchNoticeCode.CABINS_PARTIALLY_FOUND)
        assertThat(detail.lastSearchResult?.resultMode)
            .isEqualTo(AssistantCabinSearchResultMode.REPLACE)
        assertThat(detail.lastSearchResult?.data?.groups?.single()?.cabins?.single()?.status)
            .isEqualTo(AssistantCabinStatus.FREE)
        assertThat(detail.currentSelection?.rentalItemIds).containsExactly(CABIN_A_ID)
        assertThat(detail.clarifications.single().status)
            .isEqualTo(AssistantClarificationStatus.PENDING)
        assertThat(server.takeRequest().path)
            .isEqualTo("/api/assistant/v1/conversations?rentalOrderId=$ORDER_ID")
        assertThat(server.takeRequest().path)
            .isEqualTo("/api/assistant/v1/conversations/$CONVERSATION_ID")
    }

    @Test
    fun `selection replacement uses exact path idempotency header and complete sorted body`() =
        runTest {
            server.enqueue(jsonResponse(SELECTION_JSON))

            val selection = api.replaceSelection(
                conversationId = CONVERSATION_ID,
                idempotencyKey = IDEMPOTENCY_KEY,
                request = AssistantCabinSelectionRequest(
                    warehouseId = WAREHOUSE_ID,
                    rentalItemIds = listOf(CABIN_A_ID, CABIN_B_ID),
                ),
            )

            assertThat(selection.inquiryId).isEqualTo(INQUIRY_ID)
            assertThat(selection.items.map(AssistantAvailableCabin::id))
                .containsExactly(CABIN_A_ID, CABIN_B_ID)
                .inOrder()
            val request = server.takeRequest()
            assertThat(request.method).isEqualTo("PUT")
            assertThat(request.path)
                .isEqualTo("/api/assistant/v1/conversations/$CONVERSATION_ID/selection")
            val idempotencyHeader = requireNotNull(request.getHeader("Idempotency-Key"))
            assertThat(UUID.fromString(idempotencyHeader).toString()).isEqualTo(IDEMPOTENCY_KEY)
            assertThat(request.body.readUtf8()).isEqualTo(
                "{\"warehouseId\":\"$WAREHOUSE_ID\"," +
                    "\"rentalItemIds\":[\"$CABIN_A_ID\",\"$CABIN_B_ID\"]}",
            )
        }

    @Test
    fun `create sends only the existing client source and archive uses delete`() = runTest {
        server.enqueue(MockResponse().setResponseCode(201).addHeader("Content-Type", "application/json")
            .setBody(CREATE_RESPONSE_JSON))
        server.enqueue(MockResponse().setResponseCode(204))

        val created = api.createConversation(
            CreateAssistantConversationRequest(
                conversationId = CONVERSATION_ID,
                clientId = CLIENT_ID,
                rentalOrderId = ORDER_ID,
            ),
        )
        val archived = api.archiveConversation(CONVERSATION_ID)

        assertThat(created.conversation.id).isEqualTo(CONVERSATION_ID)
        assertThat(archived.code()).isEqualTo(204)
        val createRequest = server.takeRequest()
        assertThat(createRequest.method).isEqualTo("POST")
        assertThat(createRequest.path).isEqualTo("/api/assistant/v1/conversations")
        val createBody = createRequest.body.readUtf8()
        assertThat(createBody).contains("\"clientId\":\"$CLIENT_ID\"")
        assertThat(createBody).doesNotContain("newClient")
        val archiveRequest = server.takeRequest()
        assertThat(archiveRequest.method).isEqualTo("DELETE")
        assertThat(archiveRequest.path)
            .isEqualTo("/api/assistant/v1/conversations/$CONVERSATION_ID")
    }

    @Test
    fun `turn posts an exact clarification answer and requests SSE`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "text/event-stream")
                .setBody(terminalStream(CONVERSATION_ID)),
        )

        val response = api.turn(
            conversationId = CONVERSATION_ID,
            request = AssistantTurnRequest(
                clarificationAnswer = AssistantClarificationAnswer(QUESTION_ID, OPTION_A_ID),
            ),
        )

        response.body()?.close()
        val request = server.takeRequest()
        assertThat(request.method).isEqualTo("POST")
        assertThat(request.path)
            .isEqualTo("/api/assistant/v1/conversations/$CONVERSATION_ID/turns")
        assertThat(request.getHeader("Accept")).isEqualTo("text/event-stream")
        val body = request.body.readUtf8()
        assertThat(body).contains("\"clarificationAnswer\"")
        assertThat(body).contains("\"questionId\":\"$QUESTION_ID\"")
        assertThat(body).contains("\"optionId\":\"$OPTION_A_ID\"")
        assertThat(body).doesNotContain("\"message\"")
    }

    private fun jsonResponse(body: String): MockResponse = MockResponse()
        .setResponseCode(200)
        .addHeader("Content-Type", "application/json")
        .setBody(body)
}

private const val CONVERSATION_ID = "10000000-0000-4000-8000-000000000001"
private const val CLIENT_ID = "20000000-0000-4000-8000-000000000002"
private const val INQUIRY_ID = "30000000-0000-4000-8000-000000000003"
private const val ORDER_ID = "40000000-0000-4000-8000-000000000004"
private const val MESSAGE_ID = "50000000-0000-4000-8000-000000000005"
private const val QUESTION_ID = "60000000-0000-4000-8000-000000000006"
private const val OPTION_A_ID = "70000000-0000-4000-8000-000000000007"
private const val OPTION_B_ID = "80000000-0000-4000-8000-000000000008"
private const val WAREHOUSE_ID = "90000000-0000-4000-8000-000000000009"
private const val CABIN_A_ID = "a0000000-0000-4000-8000-00000000000a"
private const val CABIN_B_ID = "b0000000-0000-4000-8000-00000000000b"
private const val IDEMPOTENCY_KEY = "c0000000-0000-4000-8000-00000000000c"

private val SEARCH_GROUP_JSON = """
    {
      "cabinType":"LDSP",
      "quantity":2
    }
""".trimIndent()

private val SEARCH_NOTICE_JSON = """
    {
      "code":"CABINS_PARTIALLY_FOUND",
      "groups":[$SEARCH_GROUP_JSON],
      "requestedQuantity":2,
      "foundQuantity":1
    }
""".trimIndent()

private fun cabinJson(id: String, number: String): String = """
    {
      "id":"$id",
      "version":3,
      "warehouseId":"$WAREHOUSE_ID",
      "number":"$number",
      "status":"FREE",
      "rentalType":"LDSP",
      "dimensions":"6x2.4",
      "updatedAt":"2026-09-02T09:02:00Z"
    }
""".trimIndent()

private val CABIN_A_JSON = cabinJson(CABIN_A_ID, "БК-101")
private val CABIN_B_JSON = cabinJson(CABIN_B_ID, "БК-102")

private val SEARCH_RESULT_JSON = """
    {
      "tool":"search_available_cabins",
      "resultMode":"REPLACE",
      "filterSuggestions":{
        "cabinTypes":["LDSP"],
        "finishes":[],
        "dimensions":["6x2.4"],
        "categories":[],
        "characteristics":[]
      },
      "notices":[$SEARCH_NOTICE_JSON],
      "data":{
        "warehouseId":"$WAREHOUSE_ID",
        "expiresAt":"2026-09-02T09:12:00Z",
        "groups":[{"group":$SEARCH_GROUP_JSON,"cabins":[$CABIN_A_JSON]}]
      }
    }
""".trimIndent()

private val SELECTION_JSON = """
    {
      "inquiryId":"$INQUIRY_ID",
      "warehouseId":"$WAREHOUSE_ID",
      "expiresAt":"2026-09-02T09:12:00Z",
      "rentalItemIds":["$CABIN_A_ID","$CABIN_B_ID"],
      "items":[$CABIN_A_JSON,$CABIN_B_JSON]
    }
""".trimIndent()

private val CONVERSATION_JSON = """
    {
      "id":"$CONVERSATION_ID",
      "version":2,
      "clientId":"$CLIENT_ID",
      "rentalInquiryId":"$INQUIRY_ID",
      "rentalOrderId":"$ORDER_ID",
      "clientType":"LEGAL_ENTITY",
      "clientDisplayName":"ООО Монтаж",
      "archived":false,
      "archivedAt":null,
      "createdAt":"2026-09-02T09:00:00Z",
      "updatedAt":"2026-09-02T09:05:00Z"
    }
""".trimIndent()

private val CLARIFICATION_JSON = """
    {
      "id":"$QUESTION_ID",
      "branchKey":"finish:osb",
      "sequenceNumber":1,
      "kind":"FINISH",
      "prompt":"Какая отделка нужна?",
      "status":"PENDING",
      "options":[
        {"id":"$OPTION_A_ID","label":"ОСБ","value":"OSB"},
        {"id":"$OPTION_B_ID","label":"ЛДСП","value":"LDSP"}
      ],
      "answeredOptionId":null,
      "createdAt":"2026-09-02T09:01:00Z",
      "answeredAt":null
    }
""".trimIndent()

private val DETAIL_JSON = """
    {
      "conversation":$CONVERSATION_JSON,
      "messages":[{
        "id":"$MESSAGE_ID",
        "role":"USER",
        "content":"Нужна бытовка",
        "createdAt":"2026-09-02T09:00:00Z",
        "searchNotices":[$SEARCH_NOTICE_JSON]
      }],
      "lastSearchResult":$SEARCH_RESULT_JSON,
      "clarifications":[$CLARIFICATION_JSON],
      "currentSelection":{
        "inquiryId":"$INQUIRY_ID",
        "warehouseId":"$WAREHOUSE_ID",
        "expiresAt":"2026-09-02T09:12:00Z",
        "rentalItemIds":["$CABIN_A_ID"],
        "items":[$CABIN_A_JSON]
      }
    }
""".trimIndent()

private val CREATE_RESPONSE_JSON = """
    {
      "conversation":$CONVERSATION_JSON,
      "inquiry":{"id":"$INQUIRY_ID","status":"DRAFT"},
      "client":{"id":"$CLIENT_ID","clientType":"LEGAL_ENTITY","displayName":"ООО Монтаж"}
    }
""".trimIndent()

private fun terminalStream(conversationId: String): String =
    "event: turn.completed\n" +
        "data: {\"event\":\"turn.completed\",\"conversationId\":\"$conversationId\",\"messageId\":null}\n\n"
