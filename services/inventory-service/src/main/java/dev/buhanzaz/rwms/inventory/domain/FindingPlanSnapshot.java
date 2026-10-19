package dev.buhanzaz.rwms.inventory.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * JPA entity that persists finding plan snapshot in the inventory-owned database.
 */
@Entity
@Table(name = "finding_plan_snapshot")
@IdClass(FindingPlanSnapshot.Key.class)
public class FindingPlanSnapshot {
  @Id @Column(name = "finding_id", nullable = false) private UUID findingId;
  @Id @Column(name = "finding_revision", nullable = false) private long findingRevision;
  @Column(name = "inventory_id", nullable = false) private UUID inventoryId;
  @Column(name = "plan_mode", nullable = false, length = 16) private String planMode;
  @Column(name = "movement_to_repair", nullable = false)
  private boolean movementToRepair;
  @Column(name = "force_capital_repair", nullable = false)
  private boolean forceCapitalRepair;
  @Enumerated(EnumType.STRING)
  @Column(name = "logistics_planning_mode", length = 16)
  private LogisticsPlanningMode logisticsPlanningMode;
  @Column(name = "logistics_scheduled_date") private LocalDate logisticsScheduledDate;
  @Column(name = "catalog_version_id", nullable = false) private UUID catalogVersionId;
  @Column(name = "plan_fingerprint_sha256", nullable = false, length = 64)
  private String fingerprint;
  @Column(name = "snapshot_schema_version", nullable = false)
  private short snapshotSchemaVersion;
  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "source_snapshot", nullable = false, columnDefinition = "jsonb")
  private String sourceSnapshot;
  @Column(name = "frozen_at", nullable = false) private OffsetDateTime frozenAt;

  protected FindingPlanSnapshot() {}

  public FindingPlanSnapshot(
      UUID findingId,
      long findingRevision,
      UUID inventoryId,
      String planMode,
      boolean movementToRepair,
      LogisticsPlanningMode logisticsPlanningMode,
      LocalDate logisticsScheduledDate,
      UUID catalogVersionId,
      String fingerprint,
      String sourceSnapshot) {
    this(
        findingId,
        findingRevision,
        inventoryId,
        planMode,
        movementToRepair,
        logisticsPlanningMode,
        logisticsScheduledDate,
        catalogVersionId,
        fingerprint,
        sourceSnapshot,
        false,
        sourceSnapshot != null && sourceSnapshot.contains("\"movementToShipment\"") ? 1 : 2);
  }

  public FindingPlanSnapshot(
      UUID findingId,
      long findingRevision,
      UUID inventoryId,
      String planMode,
      boolean movementToRepair,
      LogisticsPlanningMode logisticsPlanningMode,
      LocalDate logisticsScheduledDate,
      UUID catalogVersionId,
      String fingerprint,
      String sourceSnapshot,
      int snapshotSchemaVersion) {
    this(
        findingId,
        findingRevision,
        inventoryId,
        planMode,
        movementToRepair,
        logisticsPlanningMode,
        logisticsScheduledDate,
        catalogVersionId,
        fingerprint,
        sourceSnapshot,
        false,
        snapshotSchemaVersion);
  }

  /** Persists one immutable frozen plan together with its explicit capital-repair choice. */
  public FindingPlanSnapshot(
      UUID findingId,
      long findingRevision,
      UUID inventoryId,
      String planMode,
      boolean movementToRepair,
      LogisticsPlanningMode logisticsPlanningMode,
      LocalDate logisticsScheduledDate,
      UUID catalogVersionId,
      String fingerprint,
      String sourceSnapshot,
      boolean forceCapitalRepair,
      int snapshotSchemaVersion) {
    this.findingId = findingId;
    this.findingRevision = findingRevision;
    this.inventoryId = inventoryId;
    this.planMode = planMode;
    validateLogisticsPlanning(movementToRepair, logisticsPlanningMode, logisticsScheduledDate);
    this.movementToRepair = movementToRepair;
    this.forceCapitalRepair = forceCapitalRepair;
    this.logisticsPlanningMode = logisticsPlanningMode;
    this.logisticsScheduledDate = logisticsScheduledDate;
    this.catalogVersionId = catalogVersionId;
    this.fingerprint = fingerprint;
    this.sourceSnapshot = sourceSnapshot;
    if (snapshotSchemaVersion != 1 && snapshotSchemaVersion != 2) {
      throw new IllegalArgumentException("Frozen plan schema version is invalid");
    }
    this.snapshotSchemaVersion = (short) snapshotSchemaVersion;
    frozenAt = OffsetDateTime.now(ZoneOffset.UTC);
  }

  public String getSourceSnapshot() {
    return sourceSnapshot;
  }

  public String getFingerprint() {
    return fingerprint;
  }

  public UUID getFindingId() {
    return findingId;
  }

  public long getFindingRevision() {
    return findingRevision;
  }

  public String getPlanMode() {
    return planMode;
  }

  public boolean isMovementToRepair() {
    return movementToRepair;
  }

  public boolean isForceCapitalRepair() {
    return forceCapitalRepair;
  }

  public LogisticsPlanningMode getLogisticsPlanningMode() {
    return logisticsPlanningMode;
  }

  public LocalDate getLogisticsScheduledDate() {
    return logisticsScheduledDate;
  }

  public UUID getCatalogVersionId() {
    return catalogVersionId;
  }

  public int getSnapshotSchemaVersion() {
    return snapshotSchemaVersion;
  }

  private static void validateLogisticsPlanning(
      boolean movementToRepair, LogisticsPlanningMode mode, LocalDate scheduledDate) {
    if (!LogisticsPlanningMode.validInboundPlanning(
        movementToRepair, mode, scheduledDate)) {
      throw new IllegalArgumentException("Inventory logistics planning is invalid");
    }
  }

  public static class Key implements Serializable {
    private UUID findingId;
    private long findingRevision;
    public Key() {}
    @Override public boolean equals(Object other) { return this == other || other instanceof Key key && findingRevision == key.findingRevision && Objects.equals(findingId, key.findingId); }
    @Override public int hashCode() { return Objects.hash(findingId, findingRevision); }
  }
}
