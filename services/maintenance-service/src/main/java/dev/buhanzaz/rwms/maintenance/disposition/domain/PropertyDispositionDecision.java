package dev.buhanzaz.rwms.maintenance.disposition.domain;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.proxy.HibernateProxy;
import org.hibernate.type.SqlTypes;

/**
 * One immutable business decision for one root asset.
 *
 * <p>Approval is intentionally separate from the effect in asset-service. This aggregate can
 * therefore truthfully expose a decision as approved while movement or an asset effect is still
 * pending or quarantined.
 */
@Entity
@Table(name = "property_disposition_decision")
public class PropertyDispositionDecision {
  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Column(name = "id", nullable = false)
  private UUID id;

  @Version
  @Column(name = "version", nullable = false)
  private long version;

  @Column(name = "recovery_version", nullable = false)
  private long recoveryVersion;

  @Column(name = "warehouse_id", nullable = false)
  private UUID warehouseId;

  @Enumerated(EnumType.STRING)
  @Column(name = "asset_kind", nullable = false, length = 16)
  private PropertyDispositionAssetKind assetKind;

  @Column(name = "asset_id", nullable = false)
  private UUID assetId;

  @Column(name = "asset_display_name", nullable = false, length = 255)
  private String assetDisplayName;

  @Enumerated(EnumType.STRING)
  @Column(name = "disposition_kind", nullable = false, length = 16)
  private PropertyDispositionKind kind;

  @Enumerated(EnumType.STRING)
  @Column(name = "source", nullable = false, length = 16)
  private PropertyDispositionSource source;

  @Enumerated(EnumType.STRING)
  @Column(name = "state", nullable = false, length = 24)
  private PropertyDispositionState state;

  @Enumerated(EnumType.STRING)
  @Column(name = "asset_effect_state", nullable = false, length = 16)
  private PropertyDispositionAssetEffectState assetEffectState;

  @Enumerated(EnumType.STRING)
  @Column(name = "contents_mode", length = 32)
  private PropertyDispositionContentsMode contentsMode;

  @Column(name = "expected_asset_version", nullable = false)
  private long expectedAssetVersion;

  @Column(name = "expected_source_balance_version")
  private Long expectedSourceBalanceVersion;

  @Column(name = "quantity")
  private Long quantity;

  @Column(name = "reason", nullable = false, length = 2000)
  private String reason;

  @Column(name = "evidence_link", length = 2048)
  private String evidenceLink;

  @Column(name = "source_repair_id")
  private UUID sourceRepairId;

  @Column(name = "root_repair_id")
  private UUID rootRepairId;

  @Column(name = "inventory_id")
  private UUID inventoryId;

  @Column(name = "finding_id")
  private UUID findingId;

  @Column(name = "initiated_by_subject_id")
  private UUID initiatedBySubjectId;

  @Column(name = "idempotency_key")
  private UUID idempotencyKey;

  @JdbcTypeCode(SqlTypes.CHAR)
  @Column(name = "request_sha256", nullable = false, length = 64, columnDefinition = "char(64)")
  private String requestSha256;

  @Column(name = "initiated_by_actor_snapshot", nullable = false, columnDefinition = "jsonb")
  @JdbcTypeCode(SqlTypes.JSON)
  private String initiatedByActorSnapshot;

  @Column(name = "reviewed_by_actor_snapshot", columnDefinition = "jsonb")
  @JdbcTypeCode(SqlTypes.JSON)
  private String reviewedByActorSnapshot;

  @Column(name = "review_comment", length = 2000)
  private String reviewComment;

  @Column(name = "rejection_reason", length = 2000)
  private String rejectionReason;

  @Column(name = "movement_task_id")
  private UUID movementTaskId;

  @Column(name = "effect_id")
  private UUID effectId;

  @Column(name = "failure_code", length = 128)
  private String failureCode;

  @Column(name = "failure_detail", length = 2000)
  private String failureDetail;

  @Enumerated(EnumType.STRING)
  @Column(name = "quarantine_resume_state", length = 24)
  private PropertyDispositionState quarantineResumeState;

  @Column(name = "recovery_reason", length = 2000)
  private String recoveryReason;

  @Column(name = "recovery_actor_snapshot", columnDefinition = "jsonb")
  @JdbcTypeCode(SqlTypes.JSON)
  private String recoveryActorSnapshot;

  @Column(name = "reviewed_at")
  private Instant reviewedAt;

  @Column(name = "quarantined_at")
  private Instant quarantinedAt;

  @Column(name = "recovered_at")
  private Instant recoveredAt;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  @OneToMany(
      mappedBy = "decision",
      fetch = FetchType.LAZY,
      cascade = CascadeType.ALL,
      orphanRemoval = true)
  @OrderBy("equipmentId ASC")
  private List<PropertyDispositionContentSnapshotLine> contents = new ArrayList<>();

  protected PropertyDispositionDecision() {}

  public static PropertyDispositionDecision initiate(PropertyDispositionDecisionDraft draft) {
    validateDraft(draft);

    PropertyDispositionDecision value = new PropertyDispositionDecision();
    value.warehouseId = draft.warehouseId();
    value.assetKind = draft.assetKind();
    value.assetId = draft.assetId();
    value.assetDisplayName = required(draft.assetDisplayName(), "Asset display name", 255);
    value.kind = draft.kind();
    value.source = draft.source();
    value.state = PropertyDispositionState.PENDING_APPROVAL;
    value.assetEffectState = PropertyDispositionAssetEffectState.NOT_STARTED;
    value.contentsMode = draft.contentsMode();
    value.expectedAssetVersion = draft.expectedAssetVersion();
    value.expectedSourceBalanceVersion = draft.expectedSourceBalanceVersion();
    value.quantity = draft.quantity();
    value.reason = required(draft.reason(), "Disposition reason", 2000);
    value.evidenceLink = optional(draft.evidenceLink(), 2048);
    value.sourceRepairId = draft.sourceRepairId();
    value.rootRepairId = draft.rootRepairId();
    value.inventoryId = draft.inventoryId();
    value.findingId = draft.findingId();
    value.initiatedBySubjectId = draft.initiatedBySubjectId();
    value.idempotencyKey = draft.idempotencyKey();
    value.requestSha256 = sha256(draft.requestSha256());
    value.initiatedByActorSnapshot = actorSnapshot(draft.initiatedByActorSnapshot());
    Instant now = now();
    value.createdAt = now;
    value.updatedAt = now;

    for (PropertyDispositionContentSnapshotLineDraft line : draft.contents()) {
      PropertyDispositionContentSnapshotLine snapshot =
          PropertyDispositionContentSnapshotLine.snapshot(line);
      snapshot.attachTo(value);
      value.contents.add(snapshot);
    }
    return value;
  }

  /** Records an admin decision only; physical movement and asset effect remain unstarted. */
  public boolean approve(
      long expectedVersion, String reviewerActorSnapshot, String nullableReviewComment) {
    String reviewer = actorSnapshot(reviewerActorSnapshot);
    String comment = optional(nullableReviewComment, 2000);
    if (state == PropertyDispositionState.APPROVED
        && Objects.equals(reviewedByActorSnapshot, reviewer)
        && Objects.equals(reviewComment, comment)) {
      return false;
    }
    requireExpectedVersion(expectedVersion);
    requireState(PropertyDispositionState.PENDING_APPROVAL, "approve");
    state = PropertyDispositionState.APPROVED;
    reviewedByActorSnapshot = reviewer;
    reviewComment = comment;
    rejectionReason = null;
    reviewedAt = now();
    return true;
  }

  /** Rejects a pending proposal. The mandatory rejection reason is distinct from an approval note. */
  public boolean reject(long expectedVersion, String reviewerActorSnapshot, String rejectionReason) {
    String reviewer = actorSnapshot(reviewerActorSnapshot);
    String normalizedReason = required(rejectionReason, "Rejection reason", 2000);
    if (state == PropertyDispositionState.REJECTED
        && Objects.equals(reviewedByActorSnapshot, reviewer)
        && Objects.equals(this.rejectionReason, normalizedReason)) {
      return false;
    }
    requireExpectedVersion(expectedVersion);
    requireState(PropertyDispositionState.PENDING_APPROVAL, "reject");
    state = PropertyDispositionState.REJECTED;
    assetEffectState = PropertyDispositionAssetEffectState.NOT_STARTED;
    reviewedByActorSnapshot = reviewer;
    reviewComment = null;
    this.rejectionReason = normalizedReason;
    reviewedAt = now();
    return true;
  }

  /** Binds the logistics task that must complete before an asset effect may begin. */
  public boolean startMovement(long expectedVersion, UUID taskId) {
    if (taskId == null) {
      throw new IllegalArgumentException("Movement task ID is required");
    }
    if (state == PropertyDispositionState.MOVEMENT_PENDING
        && Objects.equals(movementTaskId, taskId)) {
      return false;
    }
    requireExpectedVersion(expectedVersion);
    requireState(PropertyDispositionState.APPROVED, "start a movement");
    if (!requiresMovement()) {
      throw new PropertyDispositionConflictException(
          "A movement task is not allowed when no cabin contents are selected for stock");
    }
    movementTaskId = taskId;
    state = PropertyDispositionState.MOVEMENT_PENDING;
    return true;
  }

  /** Opens the asset effect only after the owning logistics workflow reports movement complete. */
  public boolean completeMovement(long expectedVersion) {
    if (state == PropertyDispositionState.EFFECT_PENDING
        && assetEffectState == PropertyDispositionAssetEffectState.PENDING) {
      return false;
    }
    requireExpectedVersion(expectedVersion);
    requireState(PropertyDispositionState.MOVEMENT_PENDING, "complete a movement");
    state = PropertyDispositionState.EFFECT_PENDING;
    assetEffectState = PropertyDispositionAssetEffectState.PENDING;
    return true;
  }

  /** Opens an asset effect for a decision that has no selected contents movement. */
  public boolean startAssetEffect(long expectedVersion) {
    if (state == PropertyDispositionState.EFFECT_PENDING
        && assetEffectState == PropertyDispositionAssetEffectState.PENDING) {
      return false;
    }
    requireExpectedVersion(expectedVersion);
    requireState(PropertyDispositionState.APPROVED, "start an asset effect");
    if (requiresMovement()) {
      throw new PropertyDispositionConflictException(
          "Selected cabin contents must complete logistics movement before the asset effect");
    }
    state = PropertyDispositionState.EFFECT_PENDING;
    assetEffectState = PropertyDispositionAssetEffectState.PENDING;
    return true;
  }

  /** Marks the decision effective only after an idempotent asset-service effect confirms it. */
  public boolean markEffective(long expectedVersion, UUID appliedEffectId) {
    if (appliedEffectId == null) {
      throw new IllegalArgumentException("Applied asset effect ID is required");
    }
    if (state == PropertyDispositionState.EFFECTIVE
        && Objects.equals(effectId, appliedEffectId)) {
      return false;
    }
    requireExpectedVersion(expectedVersion);
    requireState(PropertyDispositionState.EFFECT_PENDING, "mark an asset effect effective");
    if (assetEffectState != PropertyDispositionAssetEffectState.PENDING) {
      throw new PropertyDispositionConflictException("Asset effect is not pending");
    }
    effectId = appliedEffectId;
    assetEffectState = PropertyDispositionAssetEffectState.APPLIED;
    state = PropertyDispositionState.EFFECTIVE;
    return true;
  }

  /**
   * Preserves the failure and the exact safe resume point. A retry must be requested explicitly by
   * an administrator through {@link #recover(long, long, String, String)}.
   */
  public boolean quarantine(long expectedVersion, String code, String detail) {
    String normalizedCode = required(code, "Quarantine failure code", 128);
    String normalizedDetail = required(detail, "Quarantine failure detail", 2000);
    if (state == PropertyDispositionState.QUARANTINED
        && Objects.equals(failureCode, normalizedCode)
        && Objects.equals(failureDetail, normalizedDetail)) {
      return false;
    }
    requireExpectedVersion(expectedVersion);
    if (state != PropertyDispositionState.APPROVED
        && state != PropertyDispositionState.MOVEMENT_PENDING
        && state != PropertyDispositionState.EFFECT_PENDING) {
      throw new PropertyDispositionConflictException(
          "Only an approved, movement-pending, or effect-pending disposition can be quarantined");
    }
    quarantineResumeState = state;
    failureCode = normalizedCode;
    failureDetail = normalizedDetail;
    quarantinedAt = now();
    state = PropertyDispositionState.QUARANTINED;
    assetEffectState = PropertyDispositionAssetEffectState.QUARANTINED;
    return true;
  }

  /**
   * Reopens the exact prior safe point after a human confirms recovery evidence. The aggregate and
   * recovery fences prevent stale operators from reviving a newer failure.
   */
  public void recover(
      long expectedVersion,
      long expectedRecoveryVersion,
      String recoveryActorSnapshot,
      String mandatoryRecoveryReason) {
    requireExpectedVersion(expectedVersion);
    if (expectedRecoveryVersion < 0 || expectedRecoveryVersion != recoveryVersion) {
      throw new PropertyDispositionVersionConflictException("Disposition recovery version is stale");
    }
    requireState(PropertyDispositionState.QUARANTINED, "recover");
    if (quarantineResumeState == null) {
      throw new PropertyDispositionConflictException("Quarantined disposition has no safe resume state");
    }
    if (recoveryVersion == Long.MAX_VALUE) {
      throw new PropertyDispositionConflictException("Disposition recovery version is exhausted");
    }
    recoveryReason = required(mandatoryRecoveryReason, "Recovery reason", 2000);
    this.recoveryActorSnapshot = actorSnapshot(recoveryActorSnapshot);
    recoveredAt = now();
    state = quarantineResumeState;
    assetEffectState =
        state == PropertyDispositionState.EFFECT_PENDING
            ? PropertyDispositionAssetEffectState.PENDING
            : PropertyDispositionAssetEffectState.NOT_STARTED;
    recoveryVersion++;
  }

  public boolean requiresMovement() {
    return contents.stream().anyMatch(line -> line.getMoveQuantity() > 0);
  }

  /** Exact, subject-bound replay check for a permanent manual idempotency record. */
  public boolean matchesManualReplay(UUID subjectId, UUID key, String hash) {
    return source == PropertyDispositionSource.MANUAL
        && Objects.equals(initiatedBySubjectId, subjectId)
        && Objects.equals(idempotencyKey, key)
        && validSha256(hash)
        && Objects.equals(requestSha256, hash.trim());
  }

  /** Same manual idempotency identity with a different request body is a conflict, never a replay. */
  public boolean hasManualReplayMismatch(UUID subjectId, UUID key, String hash) {
    return source == PropertyDispositionSource.MANUAL
        && Objects.equals(initiatedBySubjectId, subjectId)
        && Objects.equals(idempotencyKey, key)
        && (!validSha256(hash) || !Objects.equals(requestSha256, hash.trim()));
  }

  private static void validateDraft(PropertyDispositionDecisionDraft draft) {
    if (draft == null
        || draft.warehouseId() == null
        || draft.assetKind() == null
        || draft.assetId() == null
        || draft.kind() == null
        || draft.source() == null
        || draft.expectedAssetVersion() < 0) {
      throw new IllegalArgumentException("Disposition decision identity is incomplete");
    }
    if (!validSha256(draft.requestSha256())) {
      throw new IllegalArgumentException("Disposition request SHA-256 is invalid");
    }
    if ((draft.initiatedBySubjectId() == null) != (draft.idempotencyKey() == null)) {
      throw new IllegalArgumentException(
          "Disposition subject and idempotency key must either both be present or both be absent");
    }
    if (draft.source() == PropertyDispositionSource.MANUAL
        && (draft.initiatedBySubjectId() == null || draft.idempotencyKey() == null)) {
      throw new IllegalArgumentException(
          "Manual disposition requires subject-bound idempotency identity");
    }
    if (draft.assetKind() == PropertyDispositionAssetKind.EQUIPMENT) {
      if (draft.quantity() == null
          || draft.quantity() <= 0
          || draft.expectedSourceBalanceVersion() == null
          || draft.expectedSourceBalanceVersion() < 0
          || draft.contentsMode() != null
          || !draft.contents().isEmpty()) {
        throw new IllegalArgumentException("Equipment disposition requires quantity and source balance fence");
      }
    } else if (draft.quantity() != null || draft.expectedSourceBalanceVersion() != null) {
      throw new IllegalArgumentException("Cabin disposition must not contain equipment quantity or balance fence");
    }

    if (draft.assetKind() == PropertyDispositionAssetKind.CABIN) {
      if (draft.contents().isEmpty() && draft.contentsMode() != null) {
        throw new IllegalArgumentException("Cabin contents mode requires an observed contents snapshot");
      }
      if (!draft.contents().isEmpty() && draft.contentsMode() == null) {
        throw new IllegalArgumentException("Cabin contents snapshot requires a disposition mode");
      }
      validateContentLines(draft.contentsMode(), draft.contents());
    }

    if (draft.source() == PropertyDispositionSource.INVENTORY
        && (draft.inventoryId() == null || draft.findingId() == null)) {
      throw new IllegalArgumentException("Inventory disposition requires inventory and finding identities");
    }
  }

  private static void validateContentLines(
      PropertyDispositionContentsMode mode,
      List<PropertyDispositionContentSnapshotLineDraft> lines) {
    Set<UUID> equipmentIds = new HashSet<>();
    for (PropertyDispositionContentSnapshotLineDraft line : lines) {
      if (line == null || line.equipmentId() == null || !equipmentIds.add(line.equipmentId())) {
        throw new IllegalArgumentException("Cabin contents must contain unique equipment lines");
      }
      if (line.currentQuantity() <= 0
          || line.moveQuantity() < 0
          || line.moveQuantity() > line.currentQuantity()
          || line.expectedBalanceVersion() < 0) {
        throw new IllegalArgumentException("Cabin contents quantities or balance fence are invalid");
      }
      if (mode == PropertyDispositionContentsMode.DISPOSE_WITH_CABIN
          && line.moveQuantity() != 0) {
        throw new IllegalArgumentException(
            "Cabin contents cannot move to stock when disposed with the cabin");
      }
    }
  }

  private void requireExpectedVersion(long expectedVersion) {
    if (expectedVersion < 0 || expectedVersion != version) {
      throw new PropertyDispositionVersionConflictException("Disposition version is stale");
    }
  }

  private void requireState(PropertyDispositionState expected, String operation) {
    if (state != expected) {
      throw new PropertyDispositionConflictException(
          "Disposition must be %s to %s".formatted(expected, operation));
    }
  }

  private static String actorSnapshot(String value) {
    return required(value, "Actor snapshot", 16_384);
  }

  private static String sha256(String value) {
    if (!validSha256(value)) {
      throw new IllegalArgumentException("Disposition SHA-256 is invalid");
    }
    return value.trim();
  }

  private static boolean validSha256(String value) {
    return value != null && value.trim().matches("[0-9a-f]{64}");
  }

  private static String required(String value, String field, int maximumLength) {
    String normalized = optional(value, maximumLength);
    if (normalized == null) {
      throw new IllegalArgumentException(field + " is required");
    }
    return normalized;
  }

  private static String optional(String value, int maximumLength) {
    if (value == null) return null;
    String normalized = value.trim();
    if (normalized.isEmpty()) return null;
    if (normalized.length() > maximumLength) {
      throw new IllegalArgumentException("Disposition text is too long");
    }
    return normalized;
  }

  @PrePersist
  void beforeInsert() {
    Instant now = now();
    if (createdAt == null) createdAt = now;
    updatedAt = now;
  }

  @PreUpdate
  void beforeUpdate() {
    updatedAt = now();
  }

  private static Instant now() {
    return Instant.now().truncatedTo(ChronoUnit.MICROS);
  }

  public UUID getId() {
    return id;
  }

  public long getVersion() {
    return version;
  }

  public long getRecoveryVersion() {
    return recoveryVersion;
  }

  public UUID getWarehouseId() {
    return warehouseId;
  }

  public PropertyDispositionAssetKind getAssetKind() {
    return assetKind;
  }

  public UUID getAssetId() {
    return assetId;
  }

  public String getAssetDisplayName() {
    return assetDisplayName;
  }

  public PropertyDispositionKind getKind() {
    return kind;
  }

  public PropertyDispositionSource getSource() {
    return source;
  }

  public PropertyDispositionState getState() {
    return state;
  }

  public PropertyDispositionAssetEffectState getAssetEffectState() {
    return assetEffectState;
  }

  public PropertyDispositionContentsMode getContentsMode() {
    return contentsMode;
  }

  public long getExpectedAssetVersion() {
    return expectedAssetVersion;
  }

  public Long getExpectedSourceBalanceVersion() {
    return expectedSourceBalanceVersion;
  }

  public Long getQuantity() {
    return quantity;
  }

  public String getReason() {
    return reason;
  }

  public String getEvidenceLink() {
    return evidenceLink;
  }

  public UUID getSourceRepairId() {
    return sourceRepairId;
  }

  public UUID getRootRepairId() {
    return rootRepairId;
  }

  public UUID getInventoryId() {
    return inventoryId;
  }

  public UUID getFindingId() {
    return findingId;
  }

  public UUID getInitiatedBySubjectId() {
    return initiatedBySubjectId;
  }

  public UUID getIdempotencyKey() {
    return idempotencyKey;
  }

  public String getRequestSha256() {
    return requestSha256;
  }

  public String getInitiatedByActorSnapshot() {
    return initiatedByActorSnapshot;
  }

  public String getReviewedByActorSnapshot() {
    return reviewedByActorSnapshot;
  }

  public String getReviewComment() {
    return reviewComment;
  }

  public String getRejectionReason() {
    return rejectionReason;
  }

  public UUID getMovementTaskId() {
    return movementTaskId;
  }

  public UUID getEffectId() {
    return effectId;
  }

  public String getFailureCode() {
    return failureCode;
  }

  public String getFailureDetail() {
    return failureDetail;
  }

  public PropertyDispositionState getQuarantineResumeState() {
    return quarantineResumeState;
  }

  public String getRecoveryReason() {
    return recoveryReason;
  }

  public String getRecoveryActorSnapshot() {
    return recoveryActorSnapshot;
  }

  public Instant getReviewedAt() {
    return reviewedAt;
  }

  public Instant getQuarantinedAt() {
    return quarantinedAt;
  }

  public Instant getRecoveredAt() {
    return recoveredAt;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }

  public List<PropertyDispositionContentSnapshotLine> getContents() {
    return List.copyOf(contents);
  }

  @Override
  public final boolean equals(Object other) {
    if (this == other) return true;
    if (other == null) return false;
    Class<?> thisClass = effectiveClass(this);
    Class<?> otherClass = effectiveClass(other);
    if (thisClass != otherClass) return false;
    PropertyDispositionDecision value = (PropertyDispositionDecision) other;
    return id != null && Objects.equals(id, value.id);
  }

  @Override
  public final int hashCode() {
    return effectiveClass(this).hashCode();
  }

  @Override
  public String toString() {
    return "PropertyDispositionDecision{"
        + "id="
        + id
        + ", warehouseId="
        + warehouseId
        + ", assetKind="
        + assetKind
        + ", assetId="
        + assetId
        + ", kind="
        + kind
        + ", state="
        + state
        + '}';
  }

  private static Class<?> effectiveClass(Object value) {
    return value instanceof HibernateProxy proxy
        ? proxy.getHibernateLazyInitializer().getPersistentClass()
        : value.getClass();
  }
}
