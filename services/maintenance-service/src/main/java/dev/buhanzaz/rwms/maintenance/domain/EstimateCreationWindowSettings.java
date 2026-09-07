package dev.buhanzaz.rwms.maintenance.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.time.OffsetDateTime;
import java.util.UUID;

/** Global limit for opening a new maintenance estimate after physical return. */
@Entity
@Table(name = "global_estimate_creation_window_settings")
public class EstimateCreationWindowSettings {
  public static final UUID SINGLETON_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
  public static final int DEFAULT_DAYS = 7;
  public static final int MIN_DAYS = 1;
  public static final int MAX_DAYS = 3650;

  @Id
  @Column(name = "id", nullable = false)
  private UUID id;

  @Version
  @Column(name = "version", nullable = false)
  private long version;

  @Min(MIN_DAYS)
  @Max(MAX_DAYS)
  @Column(name = "days", nullable = false)
  private int days;

  @Column(name = "created_at", nullable = false)
  private OffsetDateTime createdAt;

  @Column(name = "updated_at", nullable = false)
  private OffsetDateTime updatedAt;

  protected EstimateCreationWindowSettings() {}

  /** Creates the first persisted setting for all warehouses. */
  public static EstimateCreationWindowSettings create(int days) {
    EstimateCreationWindowSettings value = new EstimateCreationWindowSettings();
    value.id = SINGLETON_ID;
    value.days = requireDays(days);
    return value;
  }

  /** Replaces the creation window while retaining the singleton identity and version fence. */
  public void replace(int days) {
    this.days = requireDays(days);
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

  private static int requireDays(int days) {
    if (days < MIN_DAYS || days > MAX_DAYS) {
      throw new IllegalArgumentException(
          "days must be between %d and %d".formatted(MIN_DAYS, MAX_DAYS));
    }
    return days;
  }

  public UUID getId() {
    return id;
  }

  public long getVersion() {
    return version;
  }

  public int getDays() {
    return days;
  }

  public OffsetDateTime getCreatedAt() {
    return createdAt;
  }

  public OffsetDateTime getUpdatedAt() {
    return updatedAt;
  }
}
