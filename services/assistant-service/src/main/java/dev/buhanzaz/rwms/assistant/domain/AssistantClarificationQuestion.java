package dev.buhanzaz.rwms.assistant.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.proxy.HibernateProxy;
import org.hibernate.type.SqlTypes;
import tools.jackson.databind.JsonNode;

/**
 * Persists one ordered cabin clarification with immutable exact options. Availability remains
 * logistics-owned; this record stores only assistant interaction state.
 */
@Entity
@Table(name = "assistant_clarification_question")
public class AssistantClarificationQuestion {
  @Id
  @Column(name = "id", nullable = false)
  private UUID id;

  @Version
  @Column(name = "version", nullable = false)
  private long version;

  @Column(name = "conversation_id", nullable = false)
  private UUID conversationId;

  @Column(name = "turn_message_id", nullable = false)
  private UUID turnMessageId;

  @Column(name = "tool_call_id", nullable = false)
  private UUID toolCallId;

  @Column(name = "branch_key", nullable = false, length = 255)
  private String branchKey;

  @Column(name = "sequence_number", nullable = false)
  private int sequenceNumber;

  @Enumerated(EnumType.STRING)
  @Column(name = "kind", nullable = false, length = 32)
  private AssistantClarificationKind kind;

  @Column(name = "prompt", nullable = false, length = 2000)
  private String prompt;

  @Column(name = "warehouse_id", nullable = false)
  private UUID warehouseId;

  @Column(name = "cabin_type", length = 255)
  private String cabinType;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "options_payload", nullable = false, columnDefinition = "jsonb")
  private JsonNode optionsPayload;

  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false, length = 16)
  private AssistantClarificationStatus status;

  @Column(name = "answered_option_id")
  private UUID answeredOptionId;

  @Column(name = "created_at", nullable = false)
  private OffsetDateTime createdAt;

  @Column(name = "answered_at")
  private OffsetDateTime answeredAt;

  protected AssistantClarificationQuestion() {}

  /** Creates one queued question whose options were already validated against current metadata. */
  public static AssistantClarificationQuestion create(
      UUID id,
      UUID conversationId,
      UUID turnMessageId,
      UUID toolCallId,
      String branchKey,
      int sequenceNumber,
      AssistantClarificationKind kind,
      String prompt,
      UUID warehouseId,
      String cabinType,
      JsonNode optionsPayload,
      AssistantClarificationStatus initialStatus) {
    AssistantClarificationQuestion value = new AssistantClarificationQuestion();
    value.id = require(id, "questionId");
    value.conversationId = require(conversationId, "conversationId");
    value.turnMessageId = require(turnMessageId, "turnMessageId");
    value.toolCallId = require(toolCallId, "toolCallId");
    value.branchKey = requiredText(branchKey, "branchKey", 255);
    if (sequenceNumber < 1) {
      throw new IllegalArgumentException("sequenceNumber must be positive");
    }
    value.sequenceNumber = sequenceNumber;
    value.kind = Objects.requireNonNull(kind, "kind");
    value.prompt = requiredText(prompt, "prompt", 2000);
    value.warehouseId = require(warehouseId, "warehouseId");
    value.cabinType = optionalText(cabinType, 255);
    if (optionsPayload == null || !optionsPayload.isArray() || optionsPayload.size() < 2) {
      throw new IllegalArgumentException("At least two clarification options are required");
    }
    value.optionsPayload = optionsPayload.deepCopy();
    if (initialStatus != AssistantClarificationStatus.PENDING
        && initialStatus != AssistantClarificationStatus.QUEUED) {
      throw new IllegalArgumentException("A new clarification must be queued or pending");
    }
    value.status = initialStatus;
    return value;
  }

  /** Answers this pending question with one of its persisted option identifiers. */
  public boolean answer(UUID optionId) {
    UUID requiredOptionId = require(optionId, "optionId");
    boolean optionExists = false;
    for (JsonNode option : optionsPayload) {
      if (requiredOptionId.toString().equals(option.path("id").asText())) {
        optionExists = true;
        break;
      }
    }
    if (!optionExists) {
      throw new IllegalArgumentException("Clarification option is not part of the question");
    }
    if (status != AssistantClarificationStatus.PENDING) {
      throw new IllegalStateException("Clarification question is no longer active");
    }
    status = AssistantClarificationStatus.ANSWERED;
    answeredOptionId = requiredOptionId;
    answeredAt = now();
    return true;
  }

  /** Activates this question after the preceding queue entry has been answered. */
  public void activate() {
    if (status != AssistantClarificationStatus.QUEUED) {
      throw new IllegalStateException("Only a queued clarification can be activated");
    }
    status = AssistantClarificationStatus.PENDING;
  }

  @PrePersist
  void beforeInsert() {
    if (createdAt == null) createdAt = now();
  }

  public UUID getId() {
    return id;
  }

  public long getVersion() {
    return version;
  }

  public UUID getConversationId() {
    return conversationId;
  }

  public UUID getTurnMessageId() {
    return turnMessageId;
  }

  public UUID getToolCallId() {
    return toolCallId;
  }

  public String getBranchKey() {
    return branchKey;
  }

  public int getSequenceNumber() {
    return sequenceNumber;
  }

  public AssistantClarificationKind getKind() {
    return kind;
  }

  public String getPrompt() {
    return prompt;
  }

  public UUID getWarehouseId() {
    return warehouseId;
  }

  public String getCabinType() {
    return cabinType;
  }

  public JsonNode getOptionsPayload() {
    return optionsPayload.deepCopy();
  }

  public AssistantClarificationStatus getStatus() {
    return status;
  }

  public UUID getAnsweredOptionId() {
    return answeredOptionId;
  }

  public OffsetDateTime getCreatedAt() {
    return createdAt;
  }

  public OffsetDateTime getAnsweredAt() {
    return answeredAt;
  }

  @Override
  public final boolean equals(Object other) {
    if (this == other) return true;
    if (other == null) return false;
    Class<?> otherClass =
        other instanceof HibernateProxy proxy
            ? proxy.getHibernateLazyInitializer().getPersistentClass()
            : other.getClass();
    Class<?> thisClass =
        this instanceof HibernateProxy proxy
            ? proxy.getHibernateLazyInitializer().getPersistentClass()
            : getClass();
    if (thisClass != otherClass) return false;
    AssistantClarificationQuestion value = (AssistantClarificationQuestion) other;
    return id != null && Objects.equals(id, value.id);
  }

  @Override
  public final int hashCode() {
    return this instanceof HibernateProxy proxy
        ? proxy.getHibernateLazyInitializer().getPersistentClass().hashCode()
        : getClass().hashCode();
  }

  private static UUID require(UUID value, String field) {
    if (value == null) throw new IllegalArgumentException(field + " is required");
    return value;
  }

  private static String requiredText(String value, String field, int maximumLength) {
    String normalized = optionalText(value, maximumLength);
    if (normalized == null) throw new IllegalArgumentException(field + " is required");
    return normalized;
  }

  private static String optionalText(String value, int maximumLength) {
    if (value == null) return null;
    String normalized = value.trim();
    if (normalized.isEmpty()) return null;
    if (normalized.length() > maximumLength) {
      throw new IllegalArgumentException("value is too long");
    }
    return normalized;
  }

  private static OffsetDateTime now() {
    return OffsetDateTime.now(ZoneOffset.UTC);
  }
}
