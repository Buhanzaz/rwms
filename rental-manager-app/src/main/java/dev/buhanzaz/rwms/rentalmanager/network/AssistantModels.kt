package dev.buhanzaz.rwms.rentalmanager.network

import com.squareup.moshi.JsonDataException
import com.squareup.moshi.Moshi
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import okhttp3.ResponseBody

data class AssistantConversation(
    val id: String,
    val version: Long,
    val clientId: String,
    val rentalInquiryId: String,
    val rentalOrderId: String?,
    val clientType: String? = null,
    val clientDisplayName: String? = null,
    val archived: Boolean,
    val archivedAt: String? = null,
    val createdAt: String,
    val updatedAt: String,
)

enum class AssistantMessageRole {
    USER,
    ASSISTANT,
}

data class AssistantMessage(
    val id: String,
    val role: AssistantMessageRole,
    val content: String,
    val createdAt: String,
    val searchNotices: List<AssistantCabinSearchNotice> = emptyList(),
)

enum class AssistantClarificationKind {
    CABIN_TYPE,
    FINISH,
    DIMENSIONS,
    CATEGORY,
    SEARCH_MERGE,
}

enum class AssistantClarificationStatus {
    QUEUED,
    PENDING,
    ANSWERED,
    SUPERSEDED,
}

data class AssistantClarificationOption(
    val id: String,
    val label: String,
    val value: String,
)

data class AssistantClarificationQuestion(
    val id: String,
    val branchKey: String,
    val sequenceNumber: Int,
    val kind: AssistantClarificationKind,
    val prompt: String,
    val status: AssistantClarificationStatus,
    val options: List<AssistantClarificationOption>,
    val answeredOptionId: String?,
    val createdAt: String,
    val answeredAt: String?,
)

enum class AssistantCabinSearchResultMode {
    APPEND,
    REPLACE,
}

enum class AssistantCabinSearchNoticeCode {
    CABINS_NOT_FOUND,
    CABINS_PARTIALLY_FOUND,
}

enum class AssistantCabinStatus {
    FREE,
}

data class AssistantCabinSearchGroup(
    val cabinType: String? = null,
    val finish: String? = null,
    val dimensions: String? = null,
    val category: String? = null,
    val categories: List<String>? = null,
    val characteristics: String? = null,
    val linoleum: Boolean? = null,
    val quantity: Int,
)

data class AssistantAvailableCabin(
    val id: String,
    val version: Long,
    val warehouseId: String,
    val number: String? = null,
    val status: AssistantCabinStatus,
    val rentalType: String? = null,
    val dimensions: String? = null,
    val finishing: String? = null,
    val category: String? = null,
    val characteristics: String? = null,
    val linoleum: Boolean? = null,
    val passport: Map<String, Any?>? = null,
    val tags: List<String>? = null,
    val updatedAt: String,
)

data class AssistantCabinSearchGroupResult(
    val group: AssistantCabinSearchGroup,
    val cabins: List<AssistantAvailableCabin>,
)

data class AssistantCabinSearchProjection(
    val warehouseId: String,
    val expiresAt: String,
    val groups: List<AssistantCabinSearchGroupResult>,
)

data class AssistantCabinFilterSuggestions(
    val cabinTypes: List<String>,
    val finishes: List<String>,
    val dimensions: List<String>,
    val categories: List<String>,
    val characteristics: List<String>,
)

data class AssistantCabinSearchNotice(
    val code: AssistantCabinSearchNoticeCode,
    val groups: List<AssistantCabinSearchGroup>,
    val requestedQuantity: Int,
    val foundQuantity: Int,
)

data class AssistantCabinSearchResult(
    val tool: String,
    val resultMode: AssistantCabinSearchResultMode,
    val filterSuggestions: AssistantCabinFilterSuggestions,
    val notices: List<AssistantCabinSearchNotice>,
    val data: AssistantCabinSearchProjection,
)

data class AssistantCabinSelection(
    val inquiryId: String,
    val warehouseId: String?,
    val expiresAt: String?,
    val rentalItemIds: List<String>,
    val items: List<AssistantAvailableCabin>,
)

data class AssistantCabinSelectionRequest(
    val warehouseId: String,
    val rentalItemIds: List<String>,
)

/** Authoritative assistant projection; search and holds remain owned by backend services. */
data class AssistantConversationDetail(
    val conversation: AssistantConversation,
    val messages: List<AssistantMessage>,
    val lastSearchResult: AssistantCabinSearchResult? = null,
    val clarifications: List<AssistantClarificationQuestion>,
    val currentSelection: AssistantCabinSelection? = null,
)

data class CreateAssistantConversationRequest(
    val conversationId: String? = null,
    val clientId: String,
    val rentalOrderId: String? = null,
)

data class AssistantRentalInquirySummary(
    val id: String,
    val status: String? = null,
)

data class AssistantClientSummary(
    val id: String,
    val clientType: String? = null,
    val displayName: String? = null,
)

data class CreateAssistantConversationResponse(
    val conversation: AssistantConversation,
    val inquiry: AssistantRentalInquirySummary,
    val client: AssistantClientSummary,
)

data class AssistantClarificationAnswer(
    val questionId: String,
    val optionId: String,
)

/** Exactly one property is populated by the assistant repository. */
data class AssistantTurnRequest(
    val message: String? = null,
    val clarificationAnswer: AssistantClarificationAnswer? = null,
)

sealed interface AssistantTurnEvent {
    val conversationId: String
}

data class AssistantTurnStarted(
    override val conversationId: String,
    val messageId: String,
) : AssistantTurnEvent

data class AssistantTextDelta(
    override val conversationId: String,
    val delta: String,
) : AssistantTurnEvent

/** A validated tool envelope; tool-owned result data is deliberately not copied into this app. */
data class AssistantToolActivity(
    override val conversationId: String,
    val event: String,
    val toolCallId: String,
    val code: String? = null,
) : AssistantTurnEvent

data class AssistantClarificationRequested(
    override val conversationId: String,
    val toolCallId: String,
    val clarification: AssistantClarificationQuestion,
) : AssistantTurnEvent

data class AssistantClarificationAnswered(
    override val conversationId: String,
    val messageId: String,
    val clarification: AssistantClarificationQuestion,
) : AssistantTurnEvent

sealed interface AssistantTurnTerminal : AssistantTurnEvent

data class AssistantTurnCompleted(
    override val conversationId: String,
    val messageId: String?,
) : AssistantTurnTerminal

enum class AssistantTurnFailureCode {
    CONVERSATION_UNAVAILABLE,
    PROVIDER_FAILED,
    TURN_FAILED,
}

data class AssistantTurnFailed(
    override val conversationId: String,
    val code: AssistantTurnFailureCode,
) : AssistantTurnTerminal

data class AssistantTurnResult(
    val events: List<AssistantTurnEvent>,
    val assistantText: String,
    val terminal: AssistantTurnTerminal,
)

class AssistantSseProtocolException(message: String, cause: Throwable? = null) :
    IOException(message, cause)

/**
 * Parses one non-reconnectable POST turn. Limits prevent an invalid peer from retaining an
 * unbounded response in memory, and EOF is accepted only after one terminal event.
 */
internal class AssistantSseParser(moshi: Moshi) {
    private val anyAdapter = moshi.adapter(Any::class.java)
    private val clarificationAdapter = moshi.adapter(AssistantClarificationQuestion::class.java)

    suspend fun parse(
        body: ResponseBody,
        expectedConversationId: UUID,
        onEvent: suspend (AssistantTurnEvent) -> Unit = {},
    ): AssistantTurnResult {
        val declaredLength = body.contentLength()
        if (declaredLength > MAX_STREAM_CHARS) {
            throw protocol("Assistant SSE stream is too large")
        }

        val events = mutableListOf<AssistantTurnEvent>()
        val text = StringBuilder()
        var terminal: AssistantTurnTerminal? = null
        var eventName: String? = null
        val dataLines = mutableListOf<String>()
        var totalChars = 0L

        suspend fun dispatch() {
            if (eventName == null && dataLines.isEmpty()) return
            val sseName = eventName ?: throw protocol("Assistant SSE event name is missing")
            if (dataLines.isEmpty()) throw protocol("Assistant SSE event data is missing")
            if (events.size >= MAX_EVENTS) throw protocol("Assistant SSE has too many events")
            if (terminal != null) throw protocol("Assistant SSE contains an event after its terminal")

            val event = decodeEvent(
                sseName = sseName,
                json = dataLines.joinToString("\n"),
                expectedConversationId = expectedConversationId,
            )
            events += event
            if (event is AssistantTextDelta) {
                if (text.length + event.delta.length > MAX_ASSISTANT_TEXT_CHARS) {
                    throw protocol("Assistant text is too large")
                }
                text.append(event.delta)
            }
            if (event is AssistantTurnTerminal) terminal = event
            onEvent(event)
            eventName = null
            dataLines.clear()
        }

        suspend fun acceptLine(line: String) {
            if (line.isEmpty()) {
                dispatch()
                return
            }
            if (line.startsWith(':')) return
            val separator = line.indexOf(':')
            val field = if (separator < 0) line else line.substring(0, separator)
            var value = if (separator < 0) "" else line.substring(separator + 1)
            if (value.startsWith(' ')) value = value.substring(1)
            when (field) {
                "event" -> {
                    if (eventName != null) throw protocol("Assistant SSE event name is duplicated")
                    eventName = value.takeIf(String::isNotEmpty)
                        ?: throw protocol("Assistant SSE event name is empty")
                }
                "data" -> dataLines += value
                else -> throw protocol("Unsupported Assistant SSE field: $field")
            }
        }

        body.charStream().use { reader ->
            val line = StringBuilder()
            var previousWasCarriageReturn = false
            while (true) {
                currentCoroutineContext().ensureActive()
                val next = reader.read()
                if (next < 0) break
                totalChars += 1
                if (totalChars > MAX_STREAM_CHARS) {
                    throw protocol("Assistant SSE stream is too large")
                }
                val character = next.toChar()
                if (previousWasCarriageReturn) {
                    previousWasCarriageReturn = false
                    if (character == '\n') continue
                }
                when (character) {
                    '\r' -> {
                        acceptLine(line.toString())
                        line.setLength(0)
                        previousWasCarriageReturn = true
                    }
                    '\n' -> {
                        acceptLine(line.toString())
                        line.setLength(0)
                    }
                    else -> {
                        if (line.length >= MAX_LINE_CHARS) {
                            throw protocol("Assistant SSE line is too large")
                        }
                        line.append(character)
                    }
                }
            }
            if (line.isNotEmpty()) acceptLine(line.toString())
            dispatch()
        }

        return AssistantTurnResult(
            events = events.toList(),
            assistantText = text.toString(),
            terminal = terminal ?: throw protocol("Assistant SSE ended without a terminal event"),
        )
    }

    private fun decodeEvent(
        sseName: String,
        json: String,
        expectedConversationId: UUID,
    ): AssistantTurnEvent {
        val envelope = try {
            anyAdapter.fromJson(json).asObject("Assistant SSE event")
        } catch (failure: AssistantSseProtocolException) {
            throw failure
        } catch (failure: IOException) {
            throw protocol("Assistant SSE data is not valid JSON", failure)
        } catch (failure: JsonDataException) {
            throw protocol("Assistant SSE data does not match its contract", failure)
        }
        envelope.ensureOnlyEnvelopeKeys()
        val jsonName = envelope.requiredString("event")
        if (jsonName != sseName) throw protocol("Assistant SSE event names do not match")
        val conversationId = envelope.requiredUuid("conversationId")
        if (conversationId != expectedConversationId) {
            throw protocol("Assistant SSE conversation does not match the requested conversation")
        }
        val canonicalConversationId = conversationId.toString()

        return when (jsonName) {
            "turn.started" -> {
                envelope.ensureShape(setOf("event", "conversationId", "messageId"))
                AssistantTurnStarted(canonicalConversationId, envelope.requiredUuid("messageId").toString())
            }
            "assistant.delta" -> {
                envelope.ensureShape(setOf("event", "conversationId", "delta"))
                AssistantTextDelta(canonicalConversationId, envelope.requiredString("delta"))
            }
            "tool.started", "search.result", "selection.updated", "tool.completed" -> {
                val allowed = if (jsonName == "tool.completed") {
                    setOf("event", "conversationId", "toolCallId", "result", "code")
                } else if (jsonName == "tool.started") {
                    setOf("event", "conversationId", "toolCallId")
                } else {
                    setOf("event", "conversationId", "toolCallId", "result")
                }
                envelope.ensureShape(allowed)
                if (jsonName != "tool.started") envelope.requiredObject("result")
                val code = envelope.optionalString("code")
                AssistantToolActivity(
                    conversationId = canonicalConversationId,
                    event = jsonName,
                    toolCallId = envelope.requiredUuid("toolCallId").toString(),
                    code = code,
                )
            }
            "clarification.requested" -> {
                envelope.ensureShape(
                    setOf("event", "conversationId", "toolCallId", "clarification"),
                )
                AssistantClarificationRequested(
                    conversationId = canonicalConversationId,
                    toolCallId = envelope.requiredUuid("toolCallId").toString(),
                    clarification = envelope.requiredClarification(),
                )
            }
            "clarification.answered" -> {
                envelope.ensureShape(
                    setOf("event", "conversationId", "messageId", "clarification"),
                )
                AssistantClarificationAnswered(
                    conversationId = canonicalConversationId,
                    messageId = envelope.requiredUuid("messageId").toString(),
                    clarification = envelope.requiredClarification(),
                )
            }
            "turn.completed" -> {
                envelope.ensureShape(setOf("event", "conversationId", "messageId"))
                AssistantTurnCompleted(
                    conversationId = canonicalConversationId,
                    messageId = envelope.optionalUuid("messageId")?.toString(),
                )
            }
            "turn.failed" -> {
                envelope.ensureShape(setOf("event", "conversationId", "code"))
                val code = try {
                    AssistantTurnFailureCode.valueOf(envelope.requiredString("code"))
                } catch (failure: IllegalArgumentException) {
                    throw protocol("Assistant SSE failure code is invalid", failure)
                }
                AssistantTurnFailed(canonicalConversationId, code)
            }
            else -> throw protocol("Unsupported Assistant SSE event: $jsonName")
        }
    }

    private fun Map<String, Any?>.requiredClarification(): AssistantClarificationQuestion {
        val raw = requiredObject("clarification")
        raw.ensureExactKeys(CLARIFICATION_KEYS, "clarification")
        val rawOptions = raw["options"] as? List<*>
            ?: throw protocol("Assistant clarification options are invalid")
        rawOptions.forEach { option ->
            option.asObject("Assistant clarification option")
                .ensureExactKeys(CLARIFICATION_OPTION_KEYS, "clarification option")
        }
        val clarification = try {
            clarificationAdapter.fromJsonValue(raw)
                ?: throw protocol("Assistant clarification is missing")
        } catch (failure: AssistantSseProtocolException) {
            throw failure
        } catch (failure: RuntimeException) {
            throw protocol("Assistant clarification does not match its contract", failure)
        }
        clarification.validate()
        return clarification
    }

    private fun AssistantClarificationQuestion.validate() {
        requireCanonicalUuid(id, "clarification id")
        if (branchKey.isBlank() || branchKey.length > 255) {
            throw protocol("Assistant clarification branch is invalid")
        }
        if (sequenceNumber < 1) throw protocol("Assistant clarification sequence is invalid")
        if (prompt.isBlank() || prompt.length > 2_000) {
            throw protocol("Assistant clarification prompt is invalid")
        }
        if (options.size !in 2..30) throw protocol("Assistant clarification options are invalid")
        options.forEach { option ->
            requireCanonicalUuid(option.id, "clarification option id")
            if (option.label.isBlank() || option.label.length > 255 ||
                option.value.isBlank() || option.value.length > 255
            ) {
                throw protocol("Assistant clarification option is invalid")
            }
        }
        answeredOptionId?.let { requireCanonicalUuid(it, "answered clarification option id") }
    }

    private fun Map<String, Any?>.ensureOnlyEnvelopeKeys() {
        val unexpected = keys - ENVELOPE_KEYS
        if (unexpected.isNotEmpty()) {
            throw protocol("Assistant SSE event contains unsupported properties")
        }
    }

    private fun Map<String, Any?>.ensureShape(allowedNonNullKeys: Set<String>) {
        val unexpected = entries.any { (key, value) ->
            key !in allowedNonNullKeys && value != null
        }
        if (unexpected) throw protocol("Assistant SSE event shape does not match its type")
        allowedNonNullKeys.forEach { key ->
            if (!containsKey(key) && key != "code") {
                throw protocol("Assistant SSE event is missing $key")
            }
        }
    }

    private fun Map<String, Any?>.ensureExactKeys(expected: Set<String>, label: String) {
        if (keys != expected) throw protocol("Assistant $label does not match its contract")
    }

    private fun Map<String, Any?>.requiredString(key: String): String =
        (this[key] as? String) ?: throw protocol("Assistant SSE $key is invalid")

    private fun Map<String, Any?>.optionalString(key: String): String? {
        val value = this[key] ?: return null
        return value as? String ?: throw protocol("Assistant SSE $key is invalid")
    }

    private fun Map<String, Any?>.requiredUuid(key: String): UUID =
        requireCanonicalUuid(requiredString(key), key)

    private fun Map<String, Any?>.optionalUuid(key: String): UUID? {
        if (!containsKey(key)) throw protocol("Assistant SSE event is missing $key")
        val value = this[key] ?: return null
        return requireCanonicalUuid(value as? String ?: throw protocol("Assistant SSE $key is invalid"), key)
    }

    private fun Map<String, Any?>.requiredObject(key: String): Map<String, Any?> =
        this[key].asObject("Assistant SSE $key")

    private fun Any?.asObject(label: String): Map<String, Any?> {
        val raw = this as? Map<*, *> ?: throw protocol("$label is not an object")
        if (raw.keys.any { it !is String }) throw protocol("$label contains an invalid key")
        @Suppress("UNCHECKED_CAST")
        return raw as Map<String, Any?>
    }

    private fun requireCanonicalUuid(value: String, label: String): UUID {
        val parsed = try {
            UUID.fromString(value)
        } catch (failure: IllegalArgumentException) {
            throw protocol("Assistant SSE $label is not a UUID", failure)
        }
        if (!parsed.toString().equals(value, ignoreCase = true)) {
            throw protocol("Assistant SSE $label is not a canonical UUID")
        }
        return parsed
    }

    private fun protocol(message: String, cause: Throwable? = null) =
        AssistantSseProtocolException(message, cause)

    private companion object {
        const val MAX_STREAM_CHARS = 1_048_576L
        const val MAX_LINE_CHARS = 65_536
        const val MAX_ASSISTANT_TEXT_CHARS = 262_144
        const val MAX_EVENTS = 512

        val ENVELOPE_KEYS = setOf(
            "event",
            "conversationId",
            "messageId",
            "toolCallId",
            "delta",
            "result",
            "code",
            "clarification",
        )
        val CLARIFICATION_KEYS = setOf(
            "id",
            "branchKey",
            "sequenceNumber",
            "kind",
            "prompt",
            "status",
            "options",
            "answeredOptionId",
            "createdAt",
            "answeredAt",
        )
        val CLARIFICATION_OPTION_KEYS = setOf("id", "label", "value")
    }
}
