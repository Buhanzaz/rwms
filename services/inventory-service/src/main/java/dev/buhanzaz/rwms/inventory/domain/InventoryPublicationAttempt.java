package dev.buhanzaz.rwms.inventory.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * JPA entity that persists inventory publication attempt in the inventory-owned database.
 */
@Entity
@Table(name = "inventory_publication_attempt")
public class InventoryPublicationAttempt {
  @Id @Column(name = "id", nullable = false) private UUID id;
  @Column(name = "publication_intent_id", nullable = false) private UUID publicationIntentId;
  @Column(name = "attempt_no", nullable = false) private int attemptNo;
  @Column(name = "idempotency_key", nullable = false) private UUID idempotencyKey;
  @Column(name = "transition_kind", nullable = false, length = 24) private String transitionKind;
  @Column(name = "request_sha256", nullable = false, length = 64)
  private String requestSha256;
  @Column(name = "started_at", nullable = false) private OffsetDateTime startedAt;

  protected InventoryPublicationAttempt() {}

  public InventoryPublicationAttempt(UUID intentId, int attemptNo, UUID idempotencyKey,
      String transitionKind, String requestSha256) {
    id = UUID.randomUUID(); publicationIntentId = intentId; this.attemptNo = attemptNo;
    this.idempotencyKey = idempotencyKey; this.transitionKind = transitionKind;
    this.requestSha256 = requestSha256; startedAt = OffsetDateTime.now(ZoneOffset.UTC);
  }

  public UUID getId() { return id; }
  public UUID getPublicationIntentId() { return publicationIntentId; }
  public UUID getIdempotencyKey() { return idempotencyKey; }
}
