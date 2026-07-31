package dev.buhanzaz.rwms.maintenance.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.OffsetDateTime;
import java.util.Set;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Durable local work item for post-commit service effects. */
@Entity
@Table(
    name = "integration_reconciliation",
    uniqueConstraints =
        @UniqueConstraint(
            name = "uk_integration_reconciliation_key",
            columnNames = {"dependency_type", "operation_type", "idempotency_key"}))
public class MaintenanceReconciliation {
  private static final OffsetDateTime REVIEW_REQUIRED_NEXT_ATTEMPT =
      OffsetDateTime.parse("9999-12-31T23:59:59Z");
  private static final Set<String> DEPENDENCIES =
      Set.of("ASSET", "TASK_BOARD", "MEDIA", "LOGISTICS");
  private static final Set<String> MEDIA_OWNER_TYPES = Set.of(
      "MAINTENANCE_ESTIMATE",
      "MAINTENANCE_REPAIR",
      "MAINTENANCE_ACCEPTANCE");
  private static final Set<String> CLAIMABLE_STATES =
      Set.of("PENDING", "RETRY_PENDING", "RECONCILIATION_REQUIRED");

  @Id
  @Column(name = "id", nullable = false)
  private UUID id;

  @Column(name = "repair_id")
  private UUID repairId;

  @Column(name = "dependency_type", nullable = false, length = 32)
  private String dependencyType;

  @Column(name = "operation_type", nullable = false, length = 64)
  private String operationType;

  @Column(name = "idempotency_key", nullable = false)
  private UUID idempotencyKey;

  @Column(name = "state", nullable = false, length = 32)
  private String state;

  @Column(name = "attempt_count", nullable = false)
  private int attemptCount;

  @Column(name = "next_attempt_at", nullable = false)
  private OffsetDateTime nextAttemptAt;

  @Column(name = "last_error_code", length = 64)
  private String lastErrorCode;

  @Column(name = "response_snapshot", columnDefinition = "jsonb")
  @JdbcTypeCode(SqlTypes.JSON)
  private String responseSnapshot;

  @Column(name = "media_owner_type", length = 64)
  private String mediaOwnerType;

  @Column(name = "media_owner_id")
  private UUID mediaOwnerId;

  @Column(name = "media_warehouse_id")
  private UUID mediaWarehouseId;

  @Column(name = "media_owner_revision")
  private Long mediaOwnerRevision;

  @Column(name = "media_aggregate_version")
  private Long mediaAggregateVersion;

  @Column(name = "media_source_id")
  private UUID mediaSourceId;

  @Column(name = "media_source_version")
  private Long mediaSourceVersion;

  @Column(name = "media_proof_event_id")
  private UUID mediaProofEventId;

  @Column(name = "media_active")
  private Boolean mediaActive;

  @Column(name = "catalog_version_id")
  private UUID catalogVersionId;

  @Column(name = "catalog_node_id")
  private UUID catalogNodeId;

  @Column(name = "catalog_queue_id")
  private UUID catalogQueueId;

  @Column(name = "catalog_external_reference_id", length = 128)
  private String catalogExternalReferenceId;

  @Column(name = "review_version", nullable = false)
  private long reviewVersion;

  @Column(name = "review_subject_id")
  private UUID reviewSubjectId;

  @Column(name = "review_reason", length = 2000)
  private String reviewReason;

  @Column(name = "reviewed_at")
  private OffsetDateTime reviewedAt;

  @Column(name = "created_at", nullable = false)
  private OffsetDateTime createdAt;

  @Column(name = "updated_at", nullable = false)
  private OffsetDateTime updatedAt;

  protected MaintenanceReconciliation() {}

  public static MaintenanceReconciliation pending(
      UUID repairId,
      String dependencyType,
      String operationType,
      UUID idempotencyKey,
      String responseSnapshot,
      OffsetDateTime now) {
    return create(
        repairId,
        dependencyType,
        operationType,
        idempotencyKey,
        "PENDING",
        responseSnapshot,
        now,
        now);
  }

  public static MaintenanceReconciliation reconciliationRequired(
      UUID repairId,
      String dependencyType,
      String operationType,
      UUID idempotencyKey,
      String responseSnapshot,
      OffsetDateTime now) {
    return create(
        repairId,
        dependencyType,
        operationType,
        idempotencyKey,
        "RECONCILIATION_REQUIRED",
        responseSnapshot,
        now,
        REVIEW_REQUIRED_NEXT_ATTEMPT);
  }

  public static MaintenanceReconciliation mediaOwnerProof(
      String ownerType,
      UUID ownerId,
      UUID warehouseId,
      long ownerRevision,
      long aggregateVersion,
      UUID sourceId,
      long sourceVersion,
      UUID proofEventId,
      boolean active,
      String requestSnapshot,
      OffsetDateTime now) {
    if (!MEDIA_OWNER_TYPES.contains(ownerType)
        || ownerId == null
        || warehouseId == null
        || ownerRevision < 0
        || aggregateVersion < 0
        || sourceId == null
        || sourceVersion < 0
        || proofEventId == null) {
      throw new IllegalArgumentException("Media owner proof identity is required");
    }
    MaintenanceReconciliation value = create(
        null,
        "MEDIA",
        "UPSERT_MEDIA_OWNER_PROOF",
        proofEventId,
        "PENDING",
        requestSnapshot,
        now,
        now);
    value.mediaOwnerType = ownerType;
    value.mediaOwnerId = ownerId;
    value.mediaWarehouseId = warehouseId;
    value.mediaOwnerRevision = ownerRevision;
    value.mediaAggregateVersion = aggregateVersion;
    value.mediaSourceId = sourceId;
    value.mediaSourceVersion = sourceVersion;
    value.mediaProofEventId = proofEventId;
    value.mediaActive = active;
    return value;
  }

  public static MaintenanceReconciliation catalogPosition(
      String operationType,
      UUID idempotencyKey,
      UUID catalogVersionId,
      UUID catalogNodeId,
      UUID queueId,
      String externalReferenceId,
      String requestSnapshot,
      OffsetDateTime now) {
    if (!("REGISTER_CATALOG_POSITION".equals(operationType)
            || "DELETE_CATALOG_POSITION".equals(operationType))
        || catalogVersionId == null
        || catalogNodeId == null
        || queueId == null
        || externalReferenceId == null
        || externalReferenceId.isBlank()
        || externalReferenceId.length() > 128) {
      throw new IllegalArgumentException("Catalog-position reconciliation identity is required");
    }
    MaintenanceReconciliation value =
        create(
            null,
            "TASK_BOARD",
            operationType,
            idempotencyKey,
            "PENDING",
            requestSnapshot,
            now,
            now);
    value.catalogVersionId = catalogVersionId;
    value.catalogNodeId = catalogNodeId;
    value.catalogQueueId = queueId;
    value.catalogExternalReferenceId = externalReferenceId.trim();
    return value;
  }

  private static MaintenanceReconciliation create(
      UUID repairId,
      String dependencyType,
      String operationType,
      UUID idempotencyKey,
      String state,
      String responseSnapshot,
      OffsetDateTime now,
      OffsetDateTime nextAttemptAt) {
    if (!DEPENDENCIES.contains(dependencyType)
        || operationType == null
        || operationType.isBlank()
        || operationType.length() > 64
        || idempotencyKey == null
        || responseSnapshot == null
        || now == null
        || nextAttemptAt == null) {
      throw new IllegalArgumentException("Reconciliation identity is required");
    }
    MaintenanceReconciliation value = new MaintenanceReconciliation();
    value.id = UUID.randomUUID();
    value.repairId = repairId;
    value.dependencyType = dependencyType;
    value.operationType = operationType;
    value.idempotencyKey = idempotencyKey;
    value.state = state;
    value.attemptCount = 0;
    value.nextAttemptAt = nextAttemptAt;
    value.responseSnapshot = responseSnapshot;
    value.reviewVersion = 0;
    value.createdAt = now;
    value.updatedAt = now;
    return value;
  }

  public void confirm(int expectedAttemptCount, String responseSnapshot, OffsetDateTime now) {
    requireClaim(expectedAttemptCount);
    if (responseSnapshot == null || now == null) {
      throw new IllegalArgumentException("Confirmed reconciliation response is required");
    }
    state = "CONFIRMED";
    attemptCount = Math.addExact(attemptCount, 1);
    this.responseSnapshot = responseSnapshot;
    lastErrorCode = null;
    updatedAt = now;
  }

  public boolean fail(
      int expectedAttemptCount,
      int maximumAttempts,
      String failureCode,
      OffsetDateTime now) {
    requireClaim(expectedAttemptCount);
    if (maximumAttempts < 1
        || failureCode == null
        || failureCode.isBlank()
        || failureCode.length() > 64
        || now == null) {
      throw new IllegalArgumentException("Reconciliation failure is invalid");
    }
    attemptCount = Math.addExact(attemptCount, 1);
    boolean quarantined = attemptCount >= maximumAttempts;
    state = quarantined ? "QUARANTINED" : "RETRY_PENDING";
    int backoffSeconds = 1 << Math.min(expectedAttemptCount, 2);
    nextAttemptAt = now.plusSeconds(backoffSeconds);
    lastErrorCode = failureCode;
    updatedAt = now;
    return quarantined;
  }

  public void defer(
      int expectedAttemptCount, OffsetDateTime nextAttemptAt, OffsetDateTime now) {
    requireClaim(expectedAttemptCount);
    if (nextAttemptAt == null || now == null || !nextAttemptAt.isAfter(now)) {
      throw new IllegalArgumentException("Deferred reconciliation time is invalid");
    }
    this.nextAttemptAt = nextAttemptAt;
    updatedAt = now;
  }

  public void resume(
      long expectedReviewVersion,
      UUID reviewSubjectId,
      String reviewReason,
      OffsetDateTime now) {
    if (reviewVersion != expectedReviewVersion) {
      throw new IllegalArgumentException("REVIEW_VERSION");
    }
    if (!"QUARANTINED".equals(state)) {
      throw new IllegalArgumentException("REVIEW_STATE");
    }
    if (reviewSubjectId == null
        || reviewReason == null
        || reviewReason.isBlank()
        || reviewReason.trim().length() > 2000
        || now == null) {
      throw new IllegalArgumentException("Reviewed reconciliation resume metadata is invalid");
    }
    state = "RETRY_PENDING";
    attemptCount = 0;
    nextAttemptAt = now;
    lastErrorCode = null;
    reviewVersion = Math.addExact(reviewVersion, 1);
    this.reviewSubjectId = reviewSubjectId;
    this.reviewReason = reviewReason.trim();
    reviewedAt = now;
    updatedAt = now;
  }

  public void requireStableIdentity(UUID repairId) {
    if (!java.util.Objects.equals(this.repairId, repairId)) {
      throw new IllegalArgumentException("STABLE_IDENTITY");
    }
  }

  public void requireStableMediaSource(
      String ownerType,
      UUID ownerId,
      UUID warehouseId,
      UUID sourceId,
      long sourceVersion,
      boolean active) {
    if (!"MEDIA".equals(dependencyType)
        || !java.util.Objects.equals(mediaOwnerType, ownerType)
        || !java.util.Objects.equals(mediaOwnerId, ownerId)
        || !java.util.Objects.equals(mediaWarehouseId, warehouseId)
        || !java.util.Objects.equals(mediaSourceId, sourceId)
        || !java.util.Objects.equals(mediaSourceVersion, sourceVersion)
        || !java.util.Objects.equals(mediaActive, active)
        || responseSnapshot == null) {
      throw new IllegalArgumentException("STABLE_MEDIA_SOURCE");
    }
  }

  public void requireStableCatalogPosition(
      String operationType,
      UUID catalogVersionId,
      UUID catalogNodeId,
      UUID queueId,
      String externalReferenceId) {
    if (!"TASK_BOARD".equals(dependencyType)
        || !java.util.Objects.equals(this.operationType, operationType)
        || !java.util.Objects.equals(this.catalogVersionId, catalogVersionId)
        || !java.util.Objects.equals(this.catalogNodeId, catalogNodeId)
        || !java.util.Objects.equals(this.catalogQueueId, queueId)
        || !java.util.Objects.equals(this.catalogExternalReferenceId, externalReferenceId)
        || responseSnapshot == null) {
      throw new IllegalArgumentException("STABLE_CATALOG_POSITION");
    }
  }

  private void requireClaim(int expectedAttemptCount) {
    if (!CLAIMABLE_STATES.contains(state) || attemptCount != expectedAttemptCount) {
      throw new IllegalArgumentException("CLAIM_CHANGED");
    }
  }

  public UUID getId() {
    return id;
  }

  public UUID getRepairId() {
    return repairId;
  }

  public String getDependencyType() {
    return dependencyType;
  }

  public String getOperationType() {
    return operationType;
  }

  public UUID getIdempotencyKey() {
    return idempotencyKey;
  }

  public String getState() {
    return state;
  }

  public int getAttemptCount() {
    return attemptCount;
  }

  public OffsetDateTime getNextAttemptAt() {
    return nextAttemptAt;
  }

  public String getResponseSnapshot() {
    return responseSnapshot;
  }

  public String getMediaOwnerType() {
    return mediaOwnerType;
  }

  public UUID getMediaOwnerId() {
    return mediaOwnerId;
  }

  public UUID getMediaWarehouseId() {
    return mediaWarehouseId;
  }

  public Long getMediaOwnerRevision() {
    return mediaOwnerRevision;
  }

  public Long getMediaAggregateVersion() {
    return mediaAggregateVersion;
  }

  public UUID getMediaSourceId() {
    return mediaSourceId;
  }

  public Long getMediaSourceVersion() {
    return mediaSourceVersion;
  }

  public UUID getMediaProofEventId() {
    return mediaProofEventId;
  }

  public Boolean getMediaActive() {
    return mediaActive;
  }

  public UUID getCatalogVersionId() {
    return catalogVersionId;
  }

  public UUID getCatalogNodeId() {
    return catalogNodeId;
  }

  public UUID getCatalogQueueId() {
    return catalogQueueId;
  }

  public String getCatalogExternalReferenceId() {
    return catalogExternalReferenceId;
  }

  public long getReviewVersion() {
    return reviewVersion;
  }

  public UUID getReviewSubjectId() {
    return reviewSubjectId;
  }

  public String getReviewReason() {
    return reviewReason;
  }

  public OffsetDateTime getReviewedAt() {
    return reviewedAt;
  }

  public OffsetDateTime getCreatedAt() {
    return createdAt;
  }

  public OffsetDateTime getUpdatedAt() {
    return updatedAt;
  }
}
