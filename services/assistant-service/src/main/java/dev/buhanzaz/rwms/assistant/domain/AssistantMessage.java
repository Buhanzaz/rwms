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
import org.hibernate.proxy.HibernateProxy;

@Entity
@Table(name = "assistant_message")
public class AssistantMessage {
  @Id
  @Column(name = "id", nullable = false)
  private UUID id;

  @Column(name = "conversation_id", nullable = false)
  private UUID conversationId;

  @Enumerated(EnumType.STRING)
  @Column(name = "role", nullable = false, length = 16)
  private AssistantMessageRole role;

  @Column(name = "content", nullable = false, columnDefinition = "text")
  private String content;

  @Column(name = "created_at", nullable = false)
  private OffsetDateTime createdAt;

  protected AssistantMessage() {}

  public static AssistantMessage user(UUID conversationId, String content) {
    return create(conversationId, AssistantMessageRole.USER, content);
  }

  public static AssistantMessage assistant(UUID conversationId, String content) {
    return create(conversationId, AssistantMessageRole.ASSISTANT, content);
  }

  private static AssistantMessage create(UUID conversationId, AssistantMessageRole role, String content) {
    if (conversationId == null || role == null) {
      throw new IllegalArgumentException("Assistant message data is incomplete");
    }
    String normalized = content == null ? null : content.trim();
    if (normalized == null || normalized.isEmpty()) {
      throw new IllegalArgumentException("Assistant message content is required");
    }
    if (normalized.length() > 32_000) {
      throw new IllegalArgumentException("Assistant message content is too long");
    }
    AssistantMessage value = new AssistantMessage();
    value.id = UUID.randomUUID();
    value.conversationId = conversationId;
    value.role = role;
    value.content = normalized;
    return value;
  }

  @PrePersist
  void beforeInsert() {
    if (createdAt == null) createdAt = OffsetDateTime.now(ZoneOffset.UTC);
  }

  public UUID getId() {
    return id;
  }

  public UUID getConversationId() {
    return conversationId;
  }

  public AssistantMessageRole getRole() {
    return role;
  }

  public String getContent() {
    return content;
  }

  public OffsetDateTime getCreatedAt() {
    return createdAt;
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
    AssistantMessage value = (AssistantMessage) other;
    return id != null && Objects.equals(id, value.id);
  }

  @Override
  public final int hashCode() {
    return this instanceof HibernateProxy proxy
        ? proxy.getHibernateLazyInitializer().getPersistentClass().hashCode()
        : getClass().hashCode();
  }
}
