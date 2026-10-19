package dev.buhanzaz.rwms.maintenance.domain;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Durable coordinator for one immutable completed-inventory maintenance outcome.
 *
 * <p>The row survives dependency failures and records only phase transitions; historical
 * maintenance aggregates remain in their owning tables.
 */
@Entity
@Table(name = "inventory_authoritative_outcome")
public class InventoryAuthoritativeOutcome {
  @EmbeddedId private InventoryPublicationSourceId id;

  @Version
  @Column(name = "version", nullable = false)
  private long version;

  @Column(name = "request_sha256", nullable = false, length = 64)
  private String requestSha256;

  @Column(name = "request_snapshot", nullable = false, columnDefinition = "jsonb")
  @JdbcTypeCode(SqlTypes.JSON)
  private String requestSnapshot;

  @Column(name = "warehouse_id", nullable = false)
  private UUID warehouseId;

  @Column(name = "asset_id", nullable = false)
  private UUID assetId;

  @Column(name = "inventory_completed_at", nullable = false)
  private OffsetDateTime inventoryCompletedAt;

  @Column(name = "final_plan_sha256", nullable = false, length = 64)
  private String finalPlanSha256;

  @Column(name = "finding_revision", nullable = false)
  private long findingRevision;

  @Column(name = "authoritative_asset_version", nullable = false)
  private long authoritativeAssetVersion;

  @Column(name = "desired_status", nullable = false, length = 32)
  private String desiredStatus;

  @Column(name = "outcome_kind", nullable = false, length = 16)
  private String outcomeKind;

  @Column(name = "phase", nullable = false, length = 32)
  private String phase;

  @Column(name = "target_repair_id")
  private UUID targetRepairId;

  @Column(name = "response_snapshot", columnDefinition = "jsonb")
  @JdbcTypeCode(SqlTypes.JSON)
  private String responseSnapshot;

  @Column(name = "created_at", nullable = false)
  private OffsetDateTime createdAt;

  @Column(name = "updated_at", nullable = false)
  private OffsetDateTime updatedAt;

  @Column(name = "applied_at")
  private OffsetDateTime appliedAt;

  protected InventoryAuthoritativeOutcome() {}

  /** Creates the permanent source coordinator before any remote effect is attempted. */
  public static InventoryAuthoritativeOutcome prepare(
      InventoryPublicationSourceId id,
      String requestSha256,
      String requestSnapshot,
      UUID warehouseId,
      UUID assetId,
      OffsetDateTime inventoryCompletedAt,
      String finalPlanSha256,
      long findingRevision,
      long authoritativeAssetVersion,
      String desiredStatus,
      String outcomeKind) {
    if (id == null
        || !sha256(requestSha256)
        || requestSnapshot == null
        || warehouseId == null
        || assetId == null
        || inventoryCompletedAt == null
        || !sha256(finalPlanSha256)
        || findingRevision < 1
        || authoritativeAssetVersion < 0
        || !validKind(outcomeKind, desiredStatus)) {
      throw new IllegalArgumentException("Authoritative inventory outcome is incomplete");
    }
    InventoryAuthoritativeOutcome value = new InventoryAuthoritativeOutcome();
    value.id = id;
    value.requestSha256 = requestSha256;
    value.requestSnapshot = requestSnapshot;
    value.warehouseId = warehouseId;
    value.assetId = assetId;
    value.inventoryCompletedAt = MaintenanceTime.postgresPrecision(inventoryCompletedAt);
    value.finalPlanSha256 = finalPlanSha256;
    value.findingRevision = findingRevision;
    value.authoritativeAssetVersion = authoritativeAssetVersion;
    value.desiredStatus = desiredStatus;
    value.outcomeKind = outcomeKind;
    value.phase = "PREPARED";
    return value;
  }

  /** Rejects reuse of the immutable source with changed request evidence. */
  public void requireSameRequest(String requestSha256) {
    if (!Objects.equals(this.requestSha256, requestSha256)) {
      throw new IllegalArgumentException("AUTHORITATIVE_OUTCOME_REQUEST_MISMATCH");
    }
  }

  /** Marks every predecessor effect as settled before a replacement target can be created. */
  public void markEffectsSettled() {
    if ("EFFECTS_SETTLED".equals(phase)
        || "TARGET_CREATED".equals(phase)
        || "APPLIED".equals(phase)) {
      return;
    }
    if (!"PREPARED".equals(phase)) {
      throw new IllegalStateException("Authoritative inventory outcome has an invalid phase");
    }
    phase = "EFFECTS_SETTLED";
  }

  /** Binds the sole full-plan repair created for a work-producing finding. */
  public void attachTarget(UUID repairId) {
    if (!"WORK".equals(outcomeKind) || repairId == null) {
      throw new IllegalArgumentException("Authoritative inventory work target is invalid");
    }
    if (targetRepairId != null && !targetRepairId.equals(repairId)) {
      throw new IllegalStateException("Authoritative inventory work target cannot change");
    }
    if ("TARGET_CREATED".equals(phase) || "APPLIED".equals(phase)) return;
    if (!"EFFECTS_SETTLED".equals(phase)) {
      throw new IllegalStateException("Predecessor effects are not settled");
    }
    targetRepairId = repairId;
    phase = "TARGET_CREATED";
  }

  /** Stores the immutable response only after all local and remote effects are complete. */
  public void apply(String responseSnapshot) {
    if (responseSnapshot == null) {
      throw new IllegalArgumentException("Authoritative inventory response is required");
    }
    if ("APPLIED".equals(phase)) {
      if (!Objects.equals(this.responseSnapshot, responseSnapshot)) {
        throw new IllegalStateException("Authoritative inventory response cannot change");
      }
      return;
    }
    boolean ready = "NO_WORK".equals(outcomeKind)
        ? "EFFECTS_SETTLED".equals(phase)
        : "TARGET_CREATED".equals(phase);
    if (!ready) {
      throw new IllegalStateException("Authoritative inventory outcome is not ready to apply");
    }
    phase = "APPLIED";
    this.responseSnapshot = responseSnapshot;
    appliedAt = MaintenanceTime.now();
  }

  private static boolean sha256(String value) {
    return value != null && value.matches("[0-9a-f]{64}");
  }

  private static boolean validKind(String kind, String desiredStatus) {
    return ("NO_WORK".equals(kind) && "FREE".equals(desiredStatus))
        || ("WORK".equals(kind)
            && ("REPAIR".equals(desiredStatus) || "CAPITAL_REPAIR".equals(desiredStatus)));
  }

  @PrePersist
  void beforeInsert() {
    OffsetDateTime now = MaintenanceTime.now();
    createdAt = now;
    updatedAt = now;
  }

  @PreUpdate
  void beforeUpdate() {
    updatedAt = MaintenanceTime.now();
  }

  public InventoryPublicationSourceId getId() { return id; }
  public long getVersion() { return version; }
  public String getRequestSha256() { return requestSha256; }
  public String getRequestSnapshot() { return requestSnapshot; }
  public UUID getWarehouseId() { return warehouseId; }
  public UUID getAssetId() { return assetId; }
  public OffsetDateTime getInventoryCompletedAt() { return inventoryCompletedAt; }
  public String getFinalPlanSha256() { return finalPlanSha256; }
  public long getFindingRevision() { return findingRevision; }
  public long getAuthoritativeAssetVersion() { return authoritativeAssetVersion; }
  public String getDesiredStatus() { return desiredStatus; }
  public String getOutcomeKind() { return outcomeKind; }
  public String getPhase() { return phase; }
  public UUID getTargetRepairId() { return targetRepairId; }
  public String getResponseSnapshot() { return responseSnapshot; }
  public OffsetDateTime getCreatedAt() { return createdAt; }
  public OffsetDateTime getUpdatedAt() { return updatedAt; }
  public OffsetDateTime getAppliedAt() { return appliedAt; }
}
