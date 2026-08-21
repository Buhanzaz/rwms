package dev.buhanzaz.rwms.inventory.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/** Durable phase and source fence for one server-owned cabin disposition review. */
@Entity
@Table(name = "inventory_cabin_disposition_review")
public class InventoryCabinDispositionReview {
  @Id
  @Column(name = "inventory_id", nullable = false)
  private UUID inventoryId;

  @Version
  @Column(name = "review_revision", nullable = false)
  private long revision;

  @Enumerated(EnumType.STRING)
  @Column(name = "phase", nullable = false, length = 16)
  private InventoryCabinDispositionReviewPhase phase;

  @Column(name = "source_sha256", nullable = false, length = 64)
  private String sourceSha256;

  @Column(name = "returns_sha256", length = 64)
  private String returnsSha256;

  @Column(name = "shipments_sha256", length = 64)
  private String shipmentsSha256;

  @Column(name = "created_at", nullable = false)
  private OffsetDateTime createdAt;

  @Column(name = "updated_at", nullable = false)
  private OffsetDateTime updatedAt;

  protected InventoryCabinDispositionReview() {}

  /** Starts a fresh review for the exact current finding source vector. */
  public static InventoryCabinDispositionReview start(UUID inventoryId, String sourceSha256) {
    if (inventoryId == null) {
      throw new IllegalArgumentException("Inventory disposition review identity is required");
    }
    InventoryCabinDispositionReview value = new InventoryCabinDispositionReview();
    value.inventoryId = inventoryId;
    value.sourceSha256 = sha256(sourceSha256);
    value.phase = InventoryCabinDispositionReviewPhase.RETURNS;
    return value;
  }

  /** Invalidates prior decisions after any cabin source fact changes. */
  public void rebuild(String nextSourceSha256) {
    sourceSha256 = sha256(nextSourceSha256);
    phase = InventoryCabinDispositionReviewPhase.RETURNS;
    returnsSha256 = null;
    shipmentsSha256 = null;
  }

  /** Freezes the exact historical-return decision and opens shipment review. */
  public void confirmReturns(String decisionSha256) {
    if (phase != InventoryCabinDispositionReviewPhase.RETURNS) {
      throw new IllegalStateException("Inventory return review is not active");
    }
    returnsSha256 = sha256(decisionSha256);
    phase = InventoryCabinDispositionReviewPhase.SHIPMENTS;
  }

  /** Freezes shipment and automatic write-off decisions as one completed review. */
  public void confirmShipments(String decisionSha256) {
    if (phase != InventoryCabinDispositionReviewPhase.SHIPMENTS) {
      throw new IllegalStateException("Inventory shipment review is not active");
    }
    shipmentsSha256 = sha256(decisionSha256);
    phase = InventoryCabinDispositionReviewPhase.COMPLETED;
  }

  /** Advances completed evidence after the sequenced furniture observation-only mutation. */
  public void carryForwardFurnitureSource(String nextSourceSha256) {
    if (phase != InventoryCabinDispositionReviewPhase.COMPLETED) {
      throw new IllegalStateException("Only completed cabin disposition evidence can advance");
    }
    sourceSha256 = sha256(nextSourceSha256);
  }

  private static String sha256(String value) {
    if (value == null || !value.matches("^[0-9a-f]{64}$")) {
      throw new IllegalArgumentException("Canonical SHA-256 is required");
    }
    return value;
  }

  @PrePersist
  void beforeInsert() {
    OffsetDateTime current = now();
    createdAt = current;
    updatedAt = current;
  }

  @PreUpdate
  void beforeUpdate() {
    updatedAt = now();
  }

  private static OffsetDateTime now() {
    return OffsetDateTime.now(ZoneOffset.UTC);
  }

  public UUID getInventoryId() {
    return inventoryId;
  }

  public long getRevision() {
    return revision;
  }

  public InventoryCabinDispositionReviewPhase getPhase() {
    return phase;
  }

  public String getSourceSha256() {
    return sourceSha256;
  }

  public String getReturnsSha256() {
    return returnsSha256;
  }

  public String getShipmentsSha256() {
    return shipmentsSha256;
  }
}
