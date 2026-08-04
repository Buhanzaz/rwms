package dev.buhanzaz.rwms.asset.disposition;

import dev.buhanzaz.rwms.asset.disposition.PropertyDispositionApiModels.PropertyAssetKind;
import dev.buhanzaz.rwms.asset.disposition.PropertyDispositionApiModels.PropertyDispositionContentsMode;
import dev.buhanzaz.rwms.asset.disposition.PropertyDispositionApiModels.PropertyDispositionFenceState;
import dev.buhanzaz.rwms.asset.disposition.PropertyDispositionApiModels.PropertyDispositionKind;
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
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.proxy.HibernateProxy;

/**
 * Asset-owned durable fence for one maintenance decision.  The decision and
 * its input fingerprint never change; only PREPARED -> APPLIED is legal.
 */
@Entity
@Table(name = "property_disposition_fence")
public class PropertyDispositionFence {
  @Id
  @Column(name = "decision_id", nullable = false)
  private UUID decisionId;

  @Version
  @Column(name = "version", nullable = false)
  private long version;

  @Column(name = "request_sha256", nullable = false, length = 64)
  private String requestSha256;

  @Column(name = "warehouse_id", nullable = false)
  private UUID warehouseId;

  @Enumerated(EnumType.STRING)
  @Column(name = "asset_kind", nullable = false, length = 16)
  private PropertyAssetKind assetKind;

  @Column(name = "asset_id", nullable = false)
  private UUID assetId;

  @Enumerated(EnumType.STRING)
  @Column(name = "disposition", nullable = false, length = 16)
  private PropertyDispositionKind disposition;

  @Column(name = "expected_asset_version")
  private Long expectedAssetVersion;

  @Column(name = "source_balance_id")
  private UUID sourceBalanceId;

  @Column(name = "expected_source_balance_version")
  private Long expectedSourceBalanceVersion;

  @Column(name = "quantity")
  private Long quantity;

  @Enumerated(EnumType.STRING)
  @Column(name = "contents_mode", length = 32)
  private PropertyDispositionContentsMode contentsMode;

  @Column(name = "maintenance_lease_id")
  private UUID maintenanceLeaseId;

  @Column(name = "maintenance_lease_fencing_token")
  private Long maintenanceLeaseFencingToken;

  @Column(name = "maintenance_lease_owner_type", length = 64)
  private String maintenanceLeaseOwnerType;

  @Column(name = "maintenance_lease_owner_id")
  private UUID maintenanceLeaseOwnerId;

  @Column(name = "primary_hold_id")
  private UUID primaryHoldId;

  @Enumerated(EnumType.STRING)
  @Column(name = "state", nullable = false, length = 16)
  private PropertyDispositionFenceState state;

  @Column(name = "prepared_at", nullable = false)
  private OffsetDateTime preparedAt;

  @Column(name = "applied_at")
  private OffsetDateTime appliedAt;

  @Column(name = "effect_id")
  private UUID effectId;

  @Column(name = "completed_movement_task_id")
  private UUID completedMovementTaskId;

  @Column(name = "created_at", nullable = false)
  private OffsetDateTime createdAt;

  @Column(name = "updated_at", nullable = false)
  private OffsetDateTime updatedAt;

  protected PropertyDispositionFence() {}

  public static PropertyDispositionFence prepare(
      UUID decisionId,
      String requestSha256,
      UUID warehouseId,
      PropertyAssetKind assetKind,
      UUID assetId,
      PropertyDispositionKind disposition,
      Long expectedAssetVersion,
      UUID sourceBalanceId,
      Long expectedSourceBalanceVersion,
      Long quantity,
      PropertyDispositionContentsMode contentsMode,
      UUID maintenanceLeaseId,
      Long maintenanceLeaseFencingToken,
      String maintenanceLeaseOwnerType,
      UUID maintenanceLeaseOwnerId,
      UUID primaryHoldId,
      OffsetDateTime preparedAt) {
    if (decisionId == null
        || warehouseId == null
        || assetKind == null
        || assetId == null
        || disposition == null
        || preparedAt == null
        || requestSha256 == null
        || !requestSha256.matches("[0-9a-f]{64}")) {
      throw new IllegalArgumentException("Property disposition fence identity is invalid");
    }
    PropertyDispositionFence value = new PropertyDispositionFence();
    value.decisionId = decisionId;
    value.requestSha256 = requestSha256;
    value.warehouseId = warehouseId;
    value.assetKind = assetKind;
    value.assetId = assetId;
    value.disposition = disposition;
    value.expectedAssetVersion = expectedAssetVersion;
    value.sourceBalanceId = sourceBalanceId;
    value.expectedSourceBalanceVersion = expectedSourceBalanceVersion;
    value.quantity = quantity;
    value.contentsMode = contentsMode;
    value.maintenanceLeaseId = maintenanceLeaseId;
    value.maintenanceLeaseFencingToken = maintenanceLeaseFencingToken;
    value.maintenanceLeaseOwnerType = maintenanceLeaseOwnerType;
    value.maintenanceLeaseOwnerId = maintenanceLeaseOwnerId;
    value.primaryHoldId = primaryHoldId;
    value.state = PropertyDispositionFenceState.PREPARED;
    value.preparedAt = preparedAt;
    value.createdAt = preparedAt;
    value.updatedAt = preparedAt;
    return value;
  }

  public void apply(UUID nextEffectId, UUID nextCompletedMovementTaskId, OffsetDateTime appliedAt) {
    if (state != PropertyDispositionFenceState.PREPARED) {
      throw new IllegalStateException("Property disposition fence is no longer prepared");
    }
    if (nextEffectId == null || appliedAt == null) {
      throw new IllegalArgumentException("Property disposition effect identity is required");
    }
    effectId = nextEffectId;
    completedMovementTaskId = nextCompletedMovementTaskId;
    this.appliedAt = appliedAt;
    state = PropertyDispositionFenceState.APPLIED;
    updatedAt = appliedAt;
  }

  public boolean hasMatchingApplyTask(UUID taskId) {
    return Objects.equals(completedMovementTaskId, taskId);
  }

  @PrePersist
  void prePersist() {
    OffsetDateTime timestamp = now();
    if (createdAt == null) createdAt = timestamp;
    if (updatedAt == null) updatedAt = timestamp;
    if (preparedAt == null) preparedAt = timestamp;
  }

  @PreUpdate
  void preUpdate() {
    updatedAt = now();
  }

  public UUID getDecisionId() { return decisionId; }
  public long getVersion() { return version; }
  public String getRequestSha256() { return requestSha256; }
  public UUID getWarehouseId() { return warehouseId; }
  public PropertyAssetKind getAssetKind() { return assetKind; }
  public UUID getAssetId() { return assetId; }
  public PropertyDispositionKind getDisposition() { return disposition; }
  public Long getExpectedAssetVersion() { return expectedAssetVersion; }
  public UUID getSourceBalanceId() { return sourceBalanceId; }
  public Long getExpectedSourceBalanceVersion() { return expectedSourceBalanceVersion; }
  public Long getQuantity() { return quantity; }
  public PropertyDispositionContentsMode getContentsMode() { return contentsMode; }
  public UUID getMaintenanceLeaseId() { return maintenanceLeaseId; }
  public Long getMaintenanceLeaseFencingToken() { return maintenanceLeaseFencingToken; }
  public String getMaintenanceLeaseOwnerType() { return maintenanceLeaseOwnerType; }
  public UUID getMaintenanceLeaseOwnerId() { return maintenanceLeaseOwnerId; }
  public UUID getPrimaryHoldId() { return primaryHoldId; }
  public PropertyDispositionFenceState getState() { return state; }
  public OffsetDateTime getPreparedAt() { return preparedAt; }
  public OffsetDateTime getAppliedAt() { return appliedAt; }
  public UUID getEffectId() { return effectId; }
  public UUID getCompletedMovementTaskId() { return completedMovementTaskId; }
  public OffsetDateTime getCreatedAt() { return createdAt; }
  public OffsetDateTime getUpdatedAt() { return updatedAt; }

  @Override
  public final boolean equals(Object other) {
    if (this == other) return true;
    if (other == null) return false;
    Class<?> otherClass = other instanceof HibernateProxy proxy
        ? proxy.getHibernateLazyInitializer().getPersistentClass()
        : other.getClass();
    Class<?> thisClass = this instanceof HibernateProxy proxy
        ? proxy.getHibernateLazyInitializer().getPersistentClass()
        : getClass();
    return thisClass == otherClass
        && decisionId != null
        && Objects.equals(decisionId, ((PropertyDispositionFence) other).decisionId);
  }

  @Override
  public final int hashCode() {
    return this instanceof HibernateProxy proxy
        ? proxy.getHibernateLazyInitializer().getPersistentClass().hashCode()
        : getClass().hashCode();
  }

  private static OffsetDateTime now() {
    return OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
  }
}
