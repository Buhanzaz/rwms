package dev.buhanzaz.rwms.inventory.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * JPA entity that persists inventory session in the inventory-owned database.
 */
@Entity
@Table(name = "inventory_session")
public class InventorySession {
  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Column(name = "id", nullable = false)
  private UUID id;

  @Version
  @Column(name = "session_revision", nullable = false)
  private long revision;

  @Column(name = "warehouse_id", nullable = false)
  private UUID warehouseId;

  @Column(name = "warehouse_version_snapshot", nullable = false)
  private long warehouseVersion;

  @Column(name = "warehouse_time_zone", nullable = false, length = 64)
  private String warehouseTimeZone;

  @Column(name = "business_date", nullable = false)
  private LocalDate businessDate;

  @Enumerated(EnumType.STRING)
  @Column(name = "lifecycle", nullable = false, length = 16)
  private SessionLifecycle lifecycle;

  @Enumerated(EnumType.STRING)
  @Column(name = "review_stage", nullable = false, length = 16)
  private InventoryReviewStage reviewStage;

  @Column(name = "start_operation_id", nullable = false)
  private UUID startOperationId;

  @Column(name = "start_idempotency_key", nullable = false)
  private UUID startIdempotencyKey;

  @Column(name = "start_request_sha256", nullable = false, length = 64)
  private String startRequestSha256;

  @Column(name = "expected_population_count", nullable = false)
  private int expectedPopulationCount;

  @Column(name = "expected_population_sha256", nullable = false, length = 64)
  private String expectedPopulationSha256;

  @Column(name = "started_by_subject_id", nullable = false)
  private UUID startedBySubjectId;

  @Column(name = "started_by_display_name", nullable = false, length = 255)
  private String startedByDisplayName;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "started_actor_ref", nullable = false, columnDefinition = "jsonb")
  private String startedActorRef;

  @Column(name = "started_at", nullable = false)
  private OffsetDateTime startedAt;

  @Column(name = "furniture_asset_snapshot_sha256", length = 64)
  private String furnitureAssetSnapshotSha256;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "furniture_asset_snapshot", columnDefinition = "jsonb")
  private String furnitureAssetSnapshot;

  @Column(name = "furniture_review_sha256", length = 64)
  private String furnitureReviewSha256;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "furniture_stock_observation", columnDefinition = "jsonb")
  private String furnitureStockObservation;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "furniture_reviewed_by_actor_ref", columnDefinition = "jsonb")
  private String furnitureReviewedByActorRef;

  @Column(name = "furniture_reviewed_at")
  private OffsetDateTime furnitureReviewedAt;

  @Column(name = "completion_validation_sha256", length = 64)
  private String completionValidationSha256;

  @Column(name = "completion_acknowledgement_sha256", length = 64)
  private String completionAcknowledgementSha256;

  @Column(name = "validated_at")
  private OffsetDateTime validatedAt;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "completed_by_actor_ref", columnDefinition = "jsonb")
  private String completedByActorRef;

  @Column(name = "completed_at")
  private OffsetDateTime completedAt;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "cancelled_by_actor_ref", columnDefinition = "jsonb")
  private String cancelledByActorRef;

  @Column(name = "cancellation_reason", length = 2000)
  private String cancellationReason;

  @Column(name = "cancelled_at")
  private OffsetDateTime cancelledAt;

  @Column(name = "created_at", nullable = false)
  private OffsetDateTime createdAt;

  @Column(name = "updated_at", nullable = false)
  private OffsetDateTime updatedAt;

  protected InventorySession() {}

  public static InventorySession start(
      UUID warehouseId,
      long warehouseVersion,
      String warehouseTimeZone,
      LocalDate businessDate,
      UUID operationId,
      UUID idempotencyKey,
      String requestSha256,
      int expectedPopulationCount,
      String expectedPopulationSha256,
      UUID subjectId,
      String displayName,
      String actorRef) {
    if (warehouseId == null
        || warehouseVersion < 0
        || warehouseTimeZone == null
        || warehouseTimeZone.isBlank()
        || businessDate == null
        || operationId == null
        || idempotencyKey == null
        || subjectId == null
        || expectedPopulationCount < 0) {
      throw new IllegalArgumentException("Inventory session start data is incomplete");
    }
    InventorySession value = new InventorySession();
    value.warehouseId = warehouseId;
    value.warehouseVersion = warehouseVersion;
    value.warehouseTimeZone = warehouseTimeZone;
    value.businessDate = businessDate;
    value.lifecycle = SessionLifecycle.ACTIVE;
    value.reviewStage = InventoryReviewStage.CABINS;
    value.startOperationId = operationId;
    value.startIdempotencyKey = idempotencyKey;
    value.startRequestSha256 = requireSha256(requestSha256);
    value.expectedPopulationCount = expectedPopulationCount;
    value.expectedPopulationSha256 = requireSha256(expectedPopulationSha256);
    value.startedBySubjectId = subjectId;
    value.startedByDisplayName = required(displayName, 255, "started by display name");
    value.startedActorRef = requireActor(actorRef);
    return value;
  }

  public static InventorySession start(
      UUID warehouseId,
      long warehouseVersion,
      String warehouseTimeZone,
      LocalDate businessDate,
      UUID operationId,
      UUID idempotencyKey,
      String requestSha256,
      int expectedPopulationCount,
      String expectedPopulationSha256,
      UUID subjectId,
      String actorRef) {
    return start(
        warehouseId,
        warehouseVersion,
        warehouseTimeZone,
        businessDate,
        operationId,
        idempotencyKey,
        requestSha256,
        expectedPopulationCount,
        expectedPopulationSha256,
        subjectId,
        subjectId.toString(),
        actorRef);
  }

  public void complete(
      String validationSha256,
      String acknowledgementSha256,
      OffsetDateTime validationTime,
      String actorRef) {
    requireActive();
    completionValidationSha256 = requireSha256(validationSha256);
    completionAcknowledgementSha256 = requireSha256(acknowledgementSha256);
    validatedAt = requireTime(validationTime);
    completedByActorRef = requireActor(actorRef);
    completedAt = now();
    if (completedAt.isBefore(validatedAt)) completedAt = validatedAt;
    // PostgreSQL stores microseconds. Freeze that exact fence before building recovery requests;
    // rounding up keeps completion at or after validation, including higher-precision callers.
    completedAt = completedAt.plusNanos(999).truncatedTo(ChronoUnit.MICROS);
    lifecycle = SessionLifecycle.COMPLETED;
  }

  public void beginFurnitureReview(String snapshotSha256, String snapshot) {
    requireActive();
    if (reviewStage == InventoryReviewStage.FURNITURE && furnitureReviewSha256 != null) {
      throw new IllegalStateException("Furniture review is already confirmed");
    }
    reviewStage = InventoryReviewStage.FURNITURE;
    furnitureAssetSnapshotSha256 = requireSha256(snapshotSha256);
    furnitureAssetSnapshot = jsonObject(snapshot, "furniture asset snapshot");
    furnitureReviewSha256 = null;
    furnitureStockObservation = null;
    furnitureReviewedByActorRef = null;
    furnitureReviewedAt = null;
  }

  public void confirmFurnitureReview(
      String snapshotSha256, String reviewSha256, String stockObservation, String actorRef) {
    requireActive();
    requireFurnitureReviewStage();
    if (!requireSha256(snapshotSha256).equals(furnitureAssetSnapshotSha256)) {
      throw new IllegalStateException("Furniture review snapshot is stale");
    }
    furnitureReviewSha256 = requireSha256(reviewSha256);
    furnitureStockObservation = jsonObject(stockObservation, "furniture stock observation");
    furnitureReviewedByActorRef = requireActor(actorRef);
    furnitureReviewedAt = now();
  }

  /**
   * A resolved registry conflict changes the cabin baseline, so the frozen furniture review is no
   * longer safe to use. The inspection and repair facts intentionally remain untouched.
   */
  public void restartCabinReview() {
    requireActive();
    if (reviewStage != InventoryReviewStage.FURNITURE) {
      throw new IllegalStateException("Furniture review has not started");
    }
    reviewStage = InventoryReviewStage.CABINS;
    furnitureAssetSnapshotSha256 = null;
    furnitureAssetSnapshot = null;
    furnitureReviewSha256 = null;
    furnitureStockObservation = null;
    furnitureReviewedByActorRef = null;
    furnitureReviewedAt = null;
  }

  public void requireCabinReviewStage() {
    requireActive();
    if (reviewStage != InventoryReviewStage.CABINS) {
      throw new IllegalStateException("Cabin review is frozen after furniture review starts");
    }
  }

  public void requireFurnitureReviewStage() {
    requireActive();
    if (reviewStage != InventoryReviewStage.FURNITURE) {
      throw new IllegalStateException("Furniture review has not started");
    }
  }

  public void requireConfirmedFurnitureReview() {
    requireFurnitureReviewStage();
    if (furnitureReviewSha256 == null
        || furnitureStockObservation == null
        || furnitureReviewedByActorRef == null
        || furnitureReviewedAt == null) {
      throw new IllegalStateException("Furniture review is not confirmed");
    }
  }

  public void cancel(String reason, String actorRef) {
    requireActive();
    if (reason == null || reason.isBlank() || reason.trim().length() > 2000) {
      throw new IllegalArgumentException("Cancellation reason is required");
    }
    cancellationReason = reason.trim();
    cancelledByActorRef = requireActor(actorRef);
    cancelledAt = now();
    lifecycle = SessionLifecycle.CANCELLED;
  }

  public void touch() {
    requireActive();
    updatedAt = now();
  }

  public void changeExpectedPopulation(int delta) {
    requireActive();
    int next = Math.addExact(expectedPopulationCount, delta);
    if (next < 0) {
      throw new IllegalStateException("Expected inventory population cannot be negative");
    }
    expectedPopulationCount = next;
    updatedAt = now();
  }

  private void requireActive() {
    if (lifecycle != SessionLifecycle.ACTIVE) {
      throw new IllegalStateException("Only an active inventory session may transition");
    }
  }

  @PrePersist
  void beforeInsert() {
    OffsetDateTime current = now();
    startedAt = current;
    createdAt = current;
    updatedAt = current;
  }

  @PreUpdate
  void beforeUpdate() {
    updatedAt = now();
  }

  private static String requireSha256(String value) {
    if (value == null || !value.matches("^[0-9a-f]{64}$")) {
      throw new IllegalArgumentException("Canonical SHA-256 is required");
    }
    return value;
  }

  private static String requireActor(String value) {
    if (value == null || value.isBlank()) throw new IllegalArgumentException("Actor is required");
    return value;
  }

  private static String jsonObject(String value, String field) {
    String normalized = required(value, 1_000_000, field);
    if (!normalized.startsWith("{")) {
      throw new IllegalArgumentException(field + " must be a JSON object");
    }
    return normalized;
  }

  private static String required(String value, int maximum, String field) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(field + " is required");
    }
    String normalized = value.trim();
    if (normalized.length() > maximum) {
      throw new IllegalArgumentException(field + " is too long");
    }
    return normalized;
  }

  private static OffsetDateTime requireTime(OffsetDateTime value) {
    if (value == null) throw new IllegalArgumentException("Validation time is required");
    return value;
  }

  private static OffsetDateTime now() {
    return OffsetDateTime.now(ZoneOffset.UTC);
  }

  public UUID getId() {
    return id;
  }

  public long getRevision() {
    return revision;
  }

  public UUID getWarehouseId() {
    return warehouseId;
  }

  public long getWarehouseVersion() {
    return warehouseVersion;
  }

  public String getWarehouseTimeZone() {
    return warehouseTimeZone;
  }

  public LocalDate getBusinessDate() {
    return businessDate;
  }

  public SessionLifecycle getLifecycle() {
    return lifecycle;
  }

  public InventoryReviewStage getReviewStage() {
    return reviewStage;
  }

  public int getExpectedPopulationCount() {
    return expectedPopulationCount;
  }

  public UUID getStartedBySubjectId() {
    return startedBySubjectId;
  }

  public String getStartedByDisplayName() {
    return startedByDisplayName;
  }

  public OffsetDateTime getStartedAt() {
    return startedAt;
  }

  public String getFurnitureAssetSnapshotSha256() {
    return furnitureAssetSnapshotSha256;
  }

  public String getFurnitureAssetSnapshot() {
    return furnitureAssetSnapshot;
  }

  public String getFurnitureReviewSha256() {
    return furnitureReviewSha256;
  }

  public String getFurnitureStockObservation() {
    return furnitureStockObservation;
  }

  public String getFurnitureReviewedByActorRef() {
    return furnitureReviewedByActorRef;
  }

  public OffsetDateTime getFurnitureReviewedAt() {
    return furnitureReviewedAt;
  }

  public OffsetDateTime getCompletedAt() {
    return completedAt;
  }

  public String getCancellationReason() {
    return cancellationReason;
  }

  public OffsetDateTime getCancelledAt() {
    return cancelledAt;
  }

  public String getStartRequestSha256() {
    return startRequestSha256;
  }
}
