package dev.buhanzaz.rwms.logistics.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** Opaque asset-owned equipment hold evidence for exactly one shipment line. */
@Entity
@Table(
    name = "logistics_equipment_hold_reference",
    uniqueConstraints = {
      @UniqueConstraint(name = "uk_logistics_equipment_hold", columnNames = "hold_id"),
      @UniqueConstraint(
          name = "uk_logistics_equipment_hold_line_equipment",
          columnNames = {"line_id", "equipment_id"})
    })
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LogisticsEquipmentHoldReference {
  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Column(name = "id", nullable = false)
  private UUID id;

  @Version
  @Column(name = "row_version", nullable = false)
  private long rowVersion;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(
      name = "document_id",
      nullable = false,
      foreignKey = @ForeignKey(name = "fk_logistics_equipment_hold_document"))
  private LogisticsDocument document;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(
      name = "line_id",
      nullable = false,
      foreignKey = @ForeignKey(name = "fk_logistics_equipment_hold_line"))
  private LogisticsDocumentLine line;

  @Column(name = "hold_id", nullable = false)
  private UUID holdId;

  @Column(name = "equipment_id", nullable = false)
  private UUID equipmentId;

  @Column(name = "warehouse_id", nullable = false)
  private UUID warehouseId;

  @Column(name = "quantity", nullable = false)
  private long quantity;

  @Column(name = "expected_stock_version", nullable = false)
  private long expectedStockVersion;

  @Column(name = "hold_version", nullable = false)
  private long holdVersion;

  @Enumerated(EnumType.STRING)
  @Column(name = "hold_state", nullable = false, length = 24)
  private LogisticsEquipmentHoldState holdState;

  @Column(name = "created_at", nullable = false)
  private OffsetDateTime createdAt;

  @Column(name = "updated_at", nullable = false)
  private OffsetDateTime updatedAt;

  public static LogisticsEquipmentHoldReference active(
      LogisticsDocument document,
      LogisticsDocumentLine line,
      UUID holdId,
      UUID equipmentId,
      UUID warehouseId,
      long quantity,
      long expectedStockVersion,
      long holdVersion,
      OffsetDateTime createdAt) {
    if (document == null
        || line == null
        || holdId == null
        || equipmentId == null
        || warehouseId == null
        || createdAt == null) {
      throw new IllegalArgumentException("Equipment hold ownership and timing are required");
    }
    if (quantity < 1 || expectedStockVersion < 0 || holdVersion < 0) {
      throw new IllegalArgumentException("Equipment hold versions or quantity are invalid");
    }
    LogisticsEquipmentHoldReference reference = new LogisticsEquipmentHoldReference();
    reference.document = document;
    reference.line = line;
    reference.holdId = holdId;
    reference.equipmentId = equipmentId;
    reference.warehouseId = warehouseId;
    reference.quantity = quantity;
    reference.expectedStockVersion = expectedStockVersion;
    reference.holdVersion = holdVersion;
    reference.holdState = LogisticsEquipmentHoldState.ACTIVE;
    reference.createdAt = createdAt;
    reference.updatedAt = createdAt;
    return reference;
  }

  public void commit(long nextVersion, OffsetDateTime committedAt) {
    transition(LogisticsEquipmentHoldState.ACTIVE, LogisticsEquipmentHoldState.COMMITTED, nextVersion, committedAt);
  }

  public void release(long nextVersion, OffsetDateTime releasedAt) {
    if (holdState != LogisticsEquipmentHoldState.ACTIVE
        && holdState != LogisticsEquipmentHoldState.COMMITTED) {
      throw new IllegalStateException("Only an active or committed equipment hold can be released");
    }
    updateVersion(nextVersion, releasedAt);
    holdState = LogisticsEquipmentHoldState.RELEASED;
  }

  public void conflict() {
    if (holdState == LogisticsEquipmentHoldState.RELEASED) {
      throw new IllegalStateException("Released equipment hold cannot become conflicted");
    }
    holdState = LogisticsEquipmentHoldState.CONFLICT;
    updatedAt = now();
  }

  public void requireReconciliation() {
    if (holdState == LogisticsEquipmentHoldState.RELEASED) {
      throw new IllegalStateException("Released equipment hold cannot require reconciliation");
    }
    holdState = LogisticsEquipmentHoldState.RECONCILIATION_REQUIRED;
    updatedAt = now();
  }

  private void transition(
      LogisticsEquipmentHoldState expected,
      LogisticsEquipmentHoldState target,
      long nextVersion,
      OffsetDateTime at) {
    if (holdState != expected) throw new IllegalStateException("Equipment hold lifecycle transition is not allowed");
    updateVersion(nextVersion, at);
    holdState = target;
  }

  private void updateVersion(long nextVersion, OffsetDateTime at) {
    if (nextVersion < holdVersion || at == null) {
      throw new IllegalArgumentException("Equipment hold result is invalid");
    }
    holdVersion = nextVersion;
    updatedAt = at;
  }

  private static OffsetDateTime now() {
    return OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
  }
}
