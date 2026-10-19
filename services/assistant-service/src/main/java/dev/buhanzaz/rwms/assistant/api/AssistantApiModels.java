package dev.buhanzaz.rwms.assistant.api;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/**
 * Groups public assistant REST and SSE transport records so conversations and tool persistence
 * entities remain internal.
 */
public final class AssistantApiModels {
  private AssistantApiModels() {}

  /** Inline client identity accepted when a conversation creates its logistics inquiry. */
  public record NewClientRequest(
      @NotBlank @Pattern(regexp = "INDIVIDUAL|LEGAL_ENTITY") String clientType,
      @NotBlank @Size(max = 512) String displayName,
      @NotBlank @Size(max = 32) @Pattern(regexp = "^(?:\\+|8)[0-9() .-]{6,31}$") String phone,
      @Pattern(regexp = "(?s).*\\S.*") @Size(max = 255) String contactPerson,
      @Email @Size(max = 320) String email,
      @Pattern(regexp = "(?s).*\\S.*") @Size(max = 2000) String comment,
      @Pattern(regexp = "(?s).*\\S.*") @Size(max = 255) String source) {
    @AssertTrue(message = "contactPerson is required for a legal entity")
    @JsonIgnore
    public boolean isContactPersonRequirementSatisfied() {
      return !"LEGAL_ENTITY".equals(clientType)
          || (contactPerson != null && !contactPerson.isBlank());
    }
  }

  /**
   * Creates one conversation from either an existing client or a validated inline client. An
   * order-linked conversation can use only the immutable existing order client.
   */
  public record CreateConversationRequest(
      UUID conversationId, UUID clientId, @Valid NewClientRequest newClient, UUID rentalOrderId) {
    public CreateConversationRequest(
        UUID conversationId, UUID clientId, NewClientRequest newClient) {
      this(conversationId, clientId, newClient, null);
    }

    @AssertTrue(message = "Exactly one of clientId or newClient is required")
    @JsonIgnore
    public boolean isClientSourceExclusive() {
      return (clientId == null) != (newClient == null);
    }

    @AssertTrue(message = "An order-linked conversation requires an existing client")
    @JsonIgnore
    public boolean isOrderClientImmutable() {
      return rentalOrderId == null || (clientId != null && newClient == null);
    }
  }

  /** Owner-visible assistant conversation summary. */
  public record ConversationResponse(
      UUID id,
      long version,
      UUID clientId,
      UUID rentalInquiryId,
      UUID rentalOrderId,
      String clientType,
      String clientDisplayName,
      boolean archived,
      OffsetDateTime archivedAt,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt) {
    public ConversationResponse(
        UUID id,
        long version,
        UUID clientId,
        UUID rentalInquiryId,
        String clientType,
        String clientDisplayName,
        boolean archived,
        OffsetDateTime archivedAt,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {
      this(
          id,
          version,
          clientId,
          rentalInquiryId,
          null,
          clientType,
          clientDisplayName,
          archived,
          archivedAt,
          createdAt,
          updatedAt);
    }
  }

  /** Minimal client identity returned after conversation creation. */
  public record ClientSummary(UUID id, String clientType, String displayName) {}

  /** Logistics inquiry identity and optional current state. */
  public record RentalInquirySummary(UUID id, String status) {}

  /** Conversation creation result spanning the assistant and logistics identities. */
  public record CreateConversationResponse(
      @NotNull ConversationResponse conversation,
      @NotNull RentalInquirySummary inquiry,
      @NotNull ClientSummary client) {}

  /** Persisted user or assistant message plus structured search notices owned by its turn. */
  public record MessageResponse(
      UUID id,
      String role,
      String content,
      OffsetDateTime createdAt,
      @NotNull List<JsonNode> searchNotices) {
    public MessageResponse {
      searchNotices =
          searchNotices == null
              ? List.of()
              : searchNotices.stream().map(JsonNode::deepCopy).toList();
    }
  }

  /** Reloadable conversation history, active questions and authoritative held selection. */
  public record ConversationDetailResponse(
      @NotNull ConversationResponse conversation,
      @NotNull List<MessageResponse> messages,
      JsonNode lastSearchResult,
      @NotNull List<ClarificationQuestionResponse> clarifications,
      CabinSelectionResponse currentSelection) {
    public ConversationDetailResponse {
      clarifications = clarifications == null ? List.of() : List.copyOf(clarifications);
      lastSearchResult = lastSearchResult == null ? null : lastSearchResult.deepCopy();
    }
  }

  /** Identifies the exact persisted option selected by an interactive button. */
  public record ClarificationAnswerRequest(@NotNull UUID questionId, @NotNull UUID optionId) {}

  /** Accepts either a free-text user message or one server-authoritative button answer. */
  public record TurnRequest(
      @Pattern(regexp = "(?s).*\\S.*") @Size(max = 8_000) String message,
      @Valid ClarificationAnswerRequest clarificationAnswer) {
    public TurnRequest(String message) {
      this(message, null);
    }

    @AssertTrue(message = "Exactly one of message or clarificationAnswer is required")
    @JsonIgnore
    public boolean isTurnInputExclusive() {
      return (message != null && !message.isBlank()) != (clarificationAnswer != null);
    }
  }

  /** Stable button identity and the exact metadata value submitted back to the assistant. */
  public record ClarificationOptionResponse(UUID id, String label, String value) {}

  /** Persisted queue entry exposed after answer or while it is the sole actionable head. */
  public record ClarificationQuestionResponse(
      UUID id,
      String branchKey,
      int sequenceNumber,
      String kind,
      String prompt,
      String status,
      @NotNull List<ClarificationOptionResponse> options,
      UUID answeredOptionId,
      OffsetDateTime createdAt,
      OffsetDateTime answeredAt) {
    public ClarificationQuestionResponse(
        UUID id,
        String branchKey,
        String kind,
        String prompt,
        String status,
        List<ClarificationOptionResponse> options,
        UUID answeredOptionId,
        OffsetDateTime createdAt,
        OffsetDateTime answeredAt) {
      this(
          id, branchKey, 1, kind, prompt, status, options, answeredOptionId, createdAt, answeredAt);
    }

    public ClarificationQuestionResponse {
      options = options == null ? List.of() : List.copyOf(options);
    }
  }

  /** Replaces the authoritative inquiry selection; an empty ID list releases every hold. */
  public record CabinSelectionRequest(
      @NotNull UUID warehouseId, @NotNull @Size(max = 100) List<@NotNull UUID> rentalItemIds) {
    public CabinSelectionRequest {
      rentalItemIds = rentalItemIds == null ? null : List.copyOf(rentalItemIds);
    }
  }

  /** Logistics-owned current held selection returned without copying availability state locally. */
  public record CabinSelectionResponse(
      UUID inquiryId,
      UUID warehouseId,
      OffsetDateTime expiresAt,
      @NotNull List<UUID> rentalItemIds,
      @NotNull List<JsonNode> items) {
    public CabinSelectionResponse {
      rentalItemIds = rentalItemIds == null ? List.of() : List.copyOf(rentalItemIds);
      items = items == null ? List.of() : items.stream().map(JsonNode::deepCopy).toList();
    }
  }

  /**
   * Stable SSE envelope. The event name is also emitted as the SSE event field so clients can use
   * either EventSource listeners or the JSON body.
   */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record TurnEvent(
      @NotBlank String event,
      UUID conversationId,
      UUID messageId,
      UUID toolCallId,
      String delta,
      JsonNode result,
      String code,
      ClarificationQuestionResponse clarification) {
    public TurnEvent(
        String event,
        UUID conversationId,
        UUID messageId,
        UUID toolCallId,
        String delta,
        JsonNode result,
        String code) {
      this(event, conversationId, messageId, toolCallId, delta, result, code, null);
    }
  }
}
