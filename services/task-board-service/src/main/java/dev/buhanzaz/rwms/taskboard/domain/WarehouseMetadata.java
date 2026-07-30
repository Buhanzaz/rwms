package dev.buhanzaz.rwms.taskboard.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotBlank;
import java.time.ZoneId;
import java.util.UUID;

@Entity
@Table(name = "warehouse_metadata")
public class WarehouseMetadata extends AbstractVersionedEntity {
  @Column(name = "source_version", nullable = false)
  private long sourceVersion;

  @NotBlank
  @Column(name = "time_zone", nullable = false, length = 64)
  private String timeZone;

  @Column(name = "active", nullable = false)
  private boolean active;

  protected WarehouseMetadata() {}

  public static WarehouseMetadata fromFact(
      UUID warehouseId, long sourceVersion, String timeZone, boolean active) {
    var metadata = new WarehouseMetadata();
    metadata.assignReviewedId(warehouseId);
    metadata.applyFact(sourceVersion, timeZone, active);
    return metadata;
  }

  public void applyFact(long sourceVersion, String timeZone, boolean active) {
    if (sourceVersion < 0) {
      throw new IllegalArgumentException("Версия склада не может быть отрицательной");
    }
    String normalized = ZoneId.of(timeZone).getId();
    if (normalized.length() > 64) {
      throw new IllegalArgumentException("Часовой пояс склада слишком длинный");
    }
    this.sourceVersion = sourceVersion;
    this.timeZone = normalized;
    this.active = active;
  }

  public long getSourceVersion() {
    return sourceVersion;
  }

  public String getTimeZone() {
    return timeZone;
  }

  public boolean isActive() {
    return active;
  }
}
