package dev.buhanzaz.rwms.assistant.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.proxy.HibernateProxy;

/**
 * Service-owned conversation aggregate record that links one rental user, one logistics-owned
 * inquiry and, when supplied, the existing rental order being amended.
 */
@Entity
@Table(name = "assistant_conversation")
public class AssistantConversation {
  @Id
  @Column(name = "id", nullable = false)
  private UUID id;

  @Version
  @Column(name = "version", nullable = false)
  private long version;

  @Column(name = "owner_subject_id", nullable = false)
  private UUID ownerSubjectId;

  @Column(name = "client_id", nullable = false)
  private UUID clientId;

  @Column(name = "rental_inquiry_id", nullable = false)
  private UUID rentalInquiryId;

  @Column(name = "rental_order_id")
  private UUID rentalOrderId;

  @Column(name = "client_type", length = 64)
  private String clientType;

  @Column(name = "client_display_name", length = 255)
  private String clientDisplayName;

  @Column(name = "archived", nullable = false)
  private boolean archived;

  @Column(name = "archived_at")
  private OffsetDateTime archivedAt;

  @Column(name = "created_at", nullable = false)
  private OffsetDateTime createdAt;

  @Column(name = "updated_at", nullable = false)
  private OffsetDateTime updatedAt;

  protected AssistantConversation() {}

  /** Creates an inquiry-only conversation without an existing rental order link. */
  public static AssistantConversation create(
      UUID id,
      UUID ownerSubjectId,
      UUID clientId,
      UUID rentalInquiryId,
      String clientType,
      String clientDisplayName) {
    return create(
        id, ownerSubjectId, clientId, rentalInquiryId, null, clientType, clientDisplayName);
  }

  /** Creates a conversation with immutable client, inquiry and optional rental order links. */
  public static AssistantConversation create(
      UUID id,
      UUID ownerSubjectId,
      UUID clientId,
      UUID rentalInquiryId,
      UUID rentalOrderId,
      String clientType,
      String clientDisplayName) {
    AssistantConversation value = new AssistantConversation();
    value.id = require(id, "conversationId");
    value.ownerSubjectId = require(ownerSubjectId, "ownerSubjectId");
    value.clientId = require(clientId, "clientId");
    value.rentalInquiryId = require(rentalInquiryId, "rentalInquiryId");
    value.rentalOrderId = rentalOrderId;
    value.clientType = optional(clientType, 64);
    value.clientDisplayName = optional(clientDisplayName, 255);
    value.archived = false;
    return value;
  }

  public boolean archive() {
    if (archived) return false;
    archived = true;
    archivedAt = now();
    return true;
  }

  public void recordActivity() {
    if (archived) {
      throw new IllegalStateException("Archived conversations cannot receive turns");
    }
    updatedAt = now();
  }

  @PrePersist
  void beforeInsert() {
    OffsetDateTime current = now();
    createdAt = current;
    updatedAt = current;
    if (archived) {
      if (archivedAt == null) archivedAt = current;
    } else {
      archivedAt = null;
    }
  }

  @PreUpdate
  void beforeUpdate() {
    updatedAt = now();
    if (archived && archivedAt == null) archivedAt = updatedAt;
    if (!archived) archivedAt = null;
  }

  public UUID getId() {
    return id;
  }

  public long getVersion() {
    return version;
  }

  public UUID getOwnerSubjectId() {
    return ownerSubjectId;
  }

  public UUID getClientId() {
    return clientId;
  }

  public UUID getRentalInquiryId() {
    return rentalInquiryId;
  }

  public UUID getRentalOrderId() {
    return rentalOrderId;
  }

  public String getClientType() {
    return clientType;
  }

  public String getClientDisplayName() {
    return clientDisplayName;
  }

  public boolean isArchived() {
    return archived;
  }

  public OffsetDateTime getArchivedAt() {
    return archivedAt;
  }

  public OffsetDateTime getCreatedAt() {
    return createdAt;
  }

  public OffsetDateTime getUpdatedAt() {
    return updatedAt;
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
    AssistantConversation value = (AssistantConversation) other;
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

  private static String optional(String value, int maximumLength) {
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
