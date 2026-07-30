package dev.buhanzaz.rwms.assistant.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.proxy.HibernateProxy;
import org.hibernate.type.SqlTypes;
import tools.jackson.databind.JsonNode;

@Entity
@Table(name = "assistant_tool_call")
public class AssistantToolCall {
  @Id
  @Column(name = "id", nullable = false)
  private UUID id;

  @Column(name = "conversation_id", nullable = false)
  private UUID conversationId;

  @Column(name = "turn_message_id", nullable = false)
  private UUID turnMessageId;

  @Column(name = "provider_call_id", nullable = false, length = 255)
  private String providerCallId;

  @Column(name = "tool_name", nullable = false, length = 96)
  private String toolName;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "arguments_payload", nullable = false, columnDefinition = "jsonb")
  private JsonNode argumentsPayload;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "result_payload", columnDefinition = "jsonb")
  private JsonNode resultPayload;

  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false, length = 16)
  private AssistantToolCallStatus status;

  @Column(name = "failure_code", length = 96)
  private String failureCode;

  @Column(name = "created_at", nullable = false)
  private OffsetDateTime createdAt;

  @Column(name = "completed_at")
  private OffsetDateTime completedAt;

  protected AssistantToolCall() {}

  public static AssistantToolCall start(
      UUID conversationId,
      UUID turnMessageId,
      String providerCallId,
      String toolName,
      JsonNode argumentsPayload) {
    if (conversationId == null
        || turnMessageId == null
        || providerCallId == null
        || providerCallId.isBlank()
        || toolName == null
        || toolName.isBlank()
        || argumentsPayload == null) {
      throw new IllegalArgumentException("Assistant tool call data is incomplete");
    }
    AssistantToolCall value = new AssistantToolCall();
    value.id = UUID.randomUUID();
    value.conversationId = conversationId;
    value.turnMessageId = turnMessageId;
    value.providerCallId = providerCallId.trim();
    value.toolName = toolName.trim();
    value.argumentsPayload = argumentsPayload.deepCopy();
    value.status = AssistantToolCallStatus.STARTED;
    return value;
  }

  public void complete(JsonNode resultPayload) {
    if (status != AssistantToolCallStatus.STARTED || resultPayload == null) {
      throw new IllegalStateException("Tool call cannot be completed");
    }
    this.resultPayload = resultPayload.deepCopy();
    this.status = AssistantToolCallStatus.COMPLETED;
    this.completedAt = now();
  }

  public void fail(String code, JsonNode safeResultPayload) {
    if (status != AssistantToolCallStatus.STARTED) {
      throw new IllegalStateException("Tool call cannot be failed");
    }
    if (code == null || code.isBlank() || code.length() > 96) {
      throw new IllegalArgumentException("Tool failure code is invalid");
    }
    this.failureCode = code;
    this.resultPayload = safeResultPayload == null ? null : safeResultPayload.deepCopy();
    this.status = AssistantToolCallStatus.FAILED;
    this.completedAt = now();
  }

  @PrePersist
  void beforeInsert() {
    if (createdAt == null) createdAt = now();
  }

  public UUID getId() {
    return id;
  }

  public UUID getConversationId() {
    return conversationId;
  }

  public UUID getTurnMessageId() {
    return turnMessageId;
  }

  public String getProviderCallId() {
    return providerCallId;
  }

  public String getToolName() {
    return toolName;
  }

  public JsonNode getArgumentsPayload() {
    return argumentsPayload == null ? null : argumentsPayload.deepCopy();
  }

  public JsonNode getResultPayload() {
    return resultPayload == null ? null : resultPayload.deepCopy();
  }

  public AssistantToolCallStatus getStatus() {
    return status;
  }

  public String getFailureCode() {
    return failureCode;
  }

  public OffsetDateTime getCreatedAt() {
    return createdAt;
  }

  public OffsetDateTime getCompletedAt() {
    return completedAt;
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
    AssistantToolCall value = (AssistantToolCall) other;
    return id != null && Objects.equals(id, value.id);
  }

  @Override
  public final int hashCode() {
    return this instanceof HibernateProxy proxy
        ? proxy.getHibernateLazyInitializer().getPersistentClass().hashCode()
        : getClass().hashCode();
  }

  private static OffsetDateTime now() {
    return OffsetDateTime.now(ZoneOffset.UTC);
  }
}
