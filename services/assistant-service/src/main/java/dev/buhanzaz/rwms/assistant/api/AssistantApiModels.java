package dev.buhanzaz.rwms.assistant.api;

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

public final class AssistantApiModels {
  private AssistantApiModels() {}

  public record NewClientRequest(
      @NotBlank @Pattern(regexp = "INDIVIDUAL|LEGAL_ENTITY") String clientType,
      @NotBlank @Size(max = 512) String displayName,
      @NotBlank @Size(max = 32) String phone,
      @Email @Size(max = 320) String email) {}

  public record CreateConversationRequest(
      UUID conversationId, UUID clientId, @Valid NewClientRequest newClient) {
    @AssertTrue(message = "Exactly one of clientId or newClient is required")
    public boolean hasExactlyOneClientSource() {
      return (clientId == null) != (newClient == null);
    }
  }

  public record ConversationResponse(
      UUID id,
      long version,
      UUID clientId,
      UUID rentalInquiryId,
      String clientType,
      String clientDisplayName,
      boolean archived,
      OffsetDateTime archivedAt,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt) {}

  public record ClientSummary(UUID id, String clientType, String displayName) {}

  public record RentalInquirySummary(UUID id, String status) {}

  public record CreateConversationResponse(
      @NotNull ConversationResponse conversation,
      @NotNull RentalInquirySummary inquiry,
      @NotNull ClientSummary client) {}

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

  public record ConversationDetailResponse(
      @NotNull ConversationResponse conversation,
      @NotNull List<MessageResponse> messages,
      JsonNode lastSearchResult) {}

  public record TurnRequest(@NotBlank @Size(max = 8_000) String message) {}

  /**
   * Stable SSE envelope. The event name is also emitted as the SSE event field
   * so clients can use either EventSource listeners or the JSON body.
   */
  public record TurnEvent(
      @NotBlank String event,
      UUID conversationId,
      UUID messageId,
      UUID toolCallId,
      String delta,
      JsonNode result,
      String code) {}
}
