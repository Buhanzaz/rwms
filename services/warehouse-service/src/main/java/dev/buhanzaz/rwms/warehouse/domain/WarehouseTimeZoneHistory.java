package dev.buhanzaz.rwms.warehouse.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.proxy.HibernateProxy;

/** Immutable effective-dated timezone decision owned by warehouse-service. */
@Entity
@Table(
    name = "warehouse_time_zone_history",
    uniqueConstraints =
        @UniqueConstraint(
            name = "uk_warehouse_time_zone_history_effective",
            columnNames = {"warehouse_id", "effective_from"}))
public class WarehouseTimeZoneHistory {
  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Column(name = "id", nullable = false)
  private UUID id;

  @NotNull
  @Column(name = "warehouse_id", nullable = false)
  private UUID warehouseId;

  @NotNull
  @Column(name = "effective_from", nullable = false)
  private OffsetDateTime effectiveFrom;

  @NotBlank
  @Column(name = "time_zone", nullable = false, length = 64)
  private String timeZone;

  @NotNull
  @Column(name = "recorded_at", nullable = false)
  private OffsetDateTime recordedAt;

  protected WarehouseTimeZoneHistory() {}

  public static WarehouseTimeZoneHistory record(
      UUID warehouseId, OffsetDateTime effectiveFrom, ZoneId timeZone, OffsetDateTime recordedAt) {
    if (warehouseId == null) throw new IllegalArgumentException("warehouseId is required");
    if (effectiveFrom == null) throw new IllegalArgumentException("effectiveFrom is required");
    if (recordedAt == null) throw new IllegalArgumentException("recordedAt is required");
    WarehouseTimeZoneHistory entry = new WarehouseTimeZoneHistory();
    entry.warehouseId = warehouseId;
    entry.effectiveFrom = effectiveFrom;
    entry.timeZone = Warehouse.requireCanonicalTimeZone(timeZone == null ? null : timeZone.getId()).getId();
    entry.recordedAt = recordedAt;
    return entry;
  }

  public UUID getId() {
    return id;
  }

  public UUID getWarehouseId() {
    return warehouseId;
  }

  public OffsetDateTime getEffectiveFrom() {
    return effectiveFrom;
  }

  public String getTimeZone() {
    return timeZone;
  }

  public OffsetDateTime getRecordedAt() {
    return recordedAt;
  }

  @Override
  public final boolean equals(Object other) {
    if (this == other) return true;
    if (other == null) return false;
    Class<?> otherClass =
        other instanceof HibernateProxy proxy
            ? proxy.getHibernateLazyInitializer().getPersistentClass()
            : other.getClass();
    Class<?> thisClass =
        this instanceof HibernateProxy proxy
            ? proxy.getHibernateLazyInitializer().getPersistentClass()
            : getClass();
    if (thisClass != otherClass) return false;
    WarehouseTimeZoneHistory history = (WarehouseTimeZoneHistory) other;
    return id != null && Objects.equals(id, history.id);
  }

  @Override
  public final int hashCode() {
    return this instanceof HibernateProxy proxy
        ? proxy.getHibernateLazyInitializer().getPersistentClass().hashCode()
        : getClass().hashCode();
  }
}
