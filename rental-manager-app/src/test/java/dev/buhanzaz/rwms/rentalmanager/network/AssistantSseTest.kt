package dev.buhanzaz.rwms.rentalmanager.network

import com.google.common.truth.Truth.assertThat
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import java.util.UUID
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Test

class AssistantSseTest {
    private val parser = AssistantSseParser(
        Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build(),
    )

    @Test
    fun `parses bounded text and a required completed terminal`() = runTest {
        val body = buildString {
            append(event("turn.started", """{"event":"turn.started","conversationId":"$CONVERSATION_ID","messageId":"$MESSAGE_ID"}"""))
            append(event("assistant.delta", """{"event":"assistant.delta","conversationId":"$CONVERSATION_ID","delta":"Добрый "}"""))
            append(event("assistant.delta", """{"event":"assistant.delta","conversationId":"$CONVERSATION_ID","delta":"день"}"""))
            append(event("turn.completed", """{"event":"turn.completed","conversationId":"$CONVERSATION_ID","messageId":"$ASSISTANT_MESSAGE_ID"}"""))
        }.sseBody()

        val result = parser.parse(body, UUID.fromString(CONVERSATION_ID))

        assertThat(result.assistantText).isEqualTo("Добрый день")
        assertThat(result.events).hasSize(4)
        assertThat(result.terminal).isEqualTo(
            AssistantTurnCompleted(CONVERSATION_ID, ASSISTANT_MESSAGE_ID),
        )
    }

    @Test
    fun `parses the typed clarification while dropping tool-owned result data`() = runTest {
        val body = buildString {
            append(event("clarification.requested", """
                {
                  "event":"clarification.requested",
                  "conversationId":"$CONVERSATION_ID",
                  "toolCallId":"$TOOL_CALL_ID",
                  "clarification":$CLARIFICATION_JSON
                }
            """.trimIndent()))
            append(event("search.result", """
                {
                  "event":"search.result",
                  "conversationId":"$CONVERSATION_ID",
                  "toolCallId":"$TOOL_CALL_ID",
                  "result":{"tool":"search_available_cabins","data":{"groups":[]}}
                }
            """.trimIndent()))
            append(event("turn.completed", """{"event":"turn.completed","conversationId":"$CONVERSATION_ID","messageId":null}"""))
        }.sseBody()

        val result = parser.parse(body, UUID.fromString(CONVERSATION_ID))

        val clarification = result.events.filterIsInstance<AssistantClarificationRequested>().single()
        assertThat(clarification.clarification.kind).isEqualTo(AssistantClarificationKind.FINISH)
        assertThat(clarification.clarification.options.map { it.label })
            .containsExactly("ОСБ", "ЛДСП").inOrder()
        val tool = result.events.filterIsInstance<AssistantToolActivity>().single()
        assertThat(tool.event).isEqualTo("search.result")
    }

    @Test
    fun `accepts null fields from the stable server envelope but rejects another conversation`() = runTest {
        val nullableEnvelope = event(
            "turn.completed",
            """
                {
                  "event":"turn.completed",
                  "conversationId":"$OTHER_CONVERSATION_ID",
                  "messageId":null,
                  "toolCallId":null,
                  "delta":null,
                  "result":null,
                  "code":null,
                  "clarification":null
                }
            """.trimIndent(),
        ).sseBody()

        val failure = captureFailure {
            parser.parse(nullableEnvelope, UUID.fromString(CONVERSATION_ID))
        }

        assertThat(failure).isInstanceOf(AssistantSseProtocolException::class.java)
        assertThat(failure).hasMessageThat().contains("does not match")
    }

    @Test
    fun `rejects EOF without terminal and any event after terminal`() = runTest {
        val missingTerminal = event(
            "assistant.delta",
            """{"event":"assistant.delta","conversationId":"$CONVERSATION_ID","delta":"Черновик"}""",
        ).sseBody()
        val afterTerminal = (
            event(
                "turn.completed",
                """{"event":"turn.completed","conversationId":"$CONVERSATION_ID","messageId":null}""",
            ) + event(
                "assistant.delta",
                """{"event":"assistant.delta","conversationId":"$CONVERSATION_ID","delta":"Поздно"}""",
            )
        ).sseBody()

        val missingFailure = captureFailure {
            parser.parse(missingTerminal, UUID.fromString(CONVERSATION_ID))
        }
        val afterFailure = captureFailure {
            parser.parse(afterTerminal, UUID.fromString(CONVERSATION_ID))
        }

        assertThat(missingFailure).hasMessageThat().contains("without a terminal")
        assertThat(afterFailure).hasMessageThat().contains("after its terminal")
    }

    @Test
    fun `returns a typed failed terminal`() = runTest {
        val body = event(
            "turn.failed",
            """{"event":"turn.failed","conversationId":"$CONVERSATION_ID","code":"PROVIDER_FAILED"}""",
        ).sseBody()

        val result = parser.parse(body, UUID.fromString(CONVERSATION_ID))

        assertThat(result.terminal).isEqualTo(
            AssistantTurnFailed(CONVERSATION_ID, AssistantTurnFailureCode.PROVIDER_FAILED),
        )
    }

    private suspend fun captureFailure(block: suspend () -> Unit): Throwable = try {
        block()
        AssertionError("Expected Assistant SSE parsing to fail")
    } catch (failure: Throwable) {
        failure
    }

    private fun String.sseBody() = toResponseBody("text/event-stream".toMediaType())

    private fun event(name: String, data: String): String =
        "event: $name\n" + data.lineSequence().joinToString("\n") { "data: $it" } + "\n\n"
}

private const val CONVERSATION_ID = "10000000-0000-4000-8000-000000000001"
private const val OTHER_CONVERSATION_ID = "10000000-0000-4000-8000-000000000099"
private const val MESSAGE_ID = "50000000-0000-4000-8000-000000000005"
private const val ASSISTANT_MESSAGE_ID = "50000000-0000-4000-8000-000000000006"
private const val TOOL_CALL_ID = "90000000-0000-4000-8000-000000000009"
private const val QUESTION_ID = "60000000-0000-4000-8000-000000000006"
private const val OPTION_A_ID = "70000000-0000-4000-8000-000000000007"
private const val OPTION_B_ID = "80000000-0000-4000-8000-000000000008"

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
