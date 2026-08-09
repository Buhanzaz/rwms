package dev.buhanzaz.rwms.inventory.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * JPA entity that persists inventory source attachment in the inventory-owned database.
 */
@Entity
@Table(name = "inventory_source_attachment")
public class InventorySourceAttachment {
  @Id
  @Column(name = "id", nullable = false)
  private UUID id;

  @Column(name = "inventory_id", nullable = false)
  private UUID inventoryId;

  @Column(name = "finding_id", nullable = false)
  private UUID findingId;

  @Column(name = "source_key", nullable = false, length = 73)
  private String sourceKey;

  @Column(name = "source_revision", nullable = false)
  private long sourceRevision;

  @Column(name = "technical_attempt_id", nullable = false)
  private UUID technicalAttemptId;

  @Column(name = "request_sha256", nullable = false, length = 64)
  private String requestSha256;

  @Column(name = "state", nullable = false, length = 24)
  private String state;

  @Column(name = "created_asset_id")
  private UUID createdAssetId;

  @Column(name = "created_asset_version")
  private Long createdAssetVersion;

  @Column(name = "response_sha256", length = 64)
  private String responseSha256;

  @Column(name = "last_failure_code", length = 64)
  private String lastFailureCode;

  @Column(name = "created_at", nullable = false)
  private OffsetDateTime createdAt;

  @Column(name = "updated_at", nullable = false)
  private OffsetDateTime updatedAt;

  protected InventorySourceAttachment() {}

  public static InventorySourceAttachment pending(
      UUID inventoryId,
      UUID findingId,
      long sourceRevision,
      UUID technicalAttemptId,
      String requestSha256) {
    InventorySourceAttachment value = new InventorySourceAttachment();
    value.id = UUID.randomUUID();
    value.inventoryId = inventoryId;
    value.findingId = findingId;
    value.sourceKey = inventoryId + ":" + findingId;
    value.sourceRevision = sourceRevision;
    value.technicalAttemptId = technicalAttemptId;
    value.requestSha256 = requestSha256;
    value.state = "PENDING";
    return value;
  }

  public void attach(UUID assetId, long assetVersion, String responseHash) {
    if (assetId == null
        || assetVersion < 0
        || responseHash == null
        || !responseHash.matches("^[0-9a-f]{64}$")) {
      throw new IllegalArgumentException("Created asset attachment is invalid");
    }
    if ("ATTACHED".equals(state)) {
      if (!assetId.equals(createdAssetId)
          || !Long.valueOf(assetVersion).equals(createdAssetVersion)
          || !responseHash.equals(responseSha256)) {
        throw new IllegalStateException("Created asset attachment is immutable");
      }
      return;
    }
    if (!"PENDING".equals(state) && !"CREATED".equals(state)) {
      throw new IllegalStateException("Created asset cannot be attached in the current state");
    }
    createdAssetId = assetId;
    createdAssetVersion = assetVersion;
    responseSha256 = responseHash;
    lastFailureCode = null;
    state = "ATTACHED";
  }

  @PrePersist
  void beforeInsert() {
    createdAt = OffsetDateTime.now(ZoneOffset.UTC);
    updatedAt = createdAt;
  }

  @PreUpdate
  void beforeUpdate() {
    updatedAt = OffsetDateTime.now(ZoneOffset.UTC);
  }

  public String getRequestSha256() {
    return requestSha256;
  }

  public boolean isAttached() {
    return "ATTACHED".equals(state);
  }

  public UUID getCreatedAssetId() {
    return createdAssetId;
  }

  public Long getCreatedAssetVersion() {
    return createdAssetVersion;
  }
}
