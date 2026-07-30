package dev.buhanzaz.rwms.maintenance.domain;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.OffsetDateTime;
import java.util.UUID;
import lombok.Getter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Immutable maintenance-owned receipt of one logistics return-line shortage snapshot. */
@Entity
@Table(name = "logistics_return_shortage")
@Getter
public class LogisticsReturnShortage {
  @EmbeddedId
  private LogisticsReturnShortageId id;

  @Version
  @Column(name = "version", nullable = false)
  private long version;

  @Column(name = "warehouse_id", nullable = false)
  private UUID warehouseId;

  @Column(name = "rental_item_id", nullable = false)
  private UUID rentalItemId;

  @Column(name = "rental_item_version_snapshot", nullable = false)
  private long rentalItemVersionSnapshot;

  @Column(name = "estimate_id", nullable = false)
  private UUID estimateId;

  @Column(name = "source_sha256", nullable = false, length = 64)
  private String sourceSha256;

  @Column(name = "snapshot_sha256", nullable = false, length = 64)
  private String snapshotSha256;

  @Column(name = "shortage_snapshot", nullable = false, columnDefinition = "jsonb")
  @JdbcTypeCode(SqlTypes.JSON)
  private String shortageSnapshot;

  @Column(name = "created_at", nullable = false)
  private OffsetDateTime createdAt;

  protected LogisticsReturnShortage() {}

  public static LogisticsReturnShortage receive(
      LogisticsReturnShortageId id,
      UUID warehouseId,
      UUID rentalItemId,
      long rentalItemVersionSnapshot,
      String sourceSha256,
      String snapshotSha256,
      String shortageSnapshot) {
    if (id == null
        || warehouseId == null
        || rentalItemId == null
        || rentalItemVersionSnapshot < 0
        || !sha256(sourceSha256)
        || !sha256(snapshotSha256)
        || shortageSnapshot == null
        || shortageSnapshot.isBlank()) {
      throw new IllegalArgumentException("Logistics return shortage source is incomplete");
    }
    LogisticsReturnShortage value = new LogisticsReturnShortage();
    value.id = id;
    value.warehouseId = warehouseId;
    value.rentalItemId = rentalItemId;
    value.rentalItemVersionSnapshot = rentalItemVersionSnapshot;
    value.sourceSha256 = sourceSha256;
    value.snapshotSha256 = snapshotSha256;
    value.shortageSnapshot = shortageSnapshot;
    return value;
  }

  public void bindEstimate(UUID estimateId) {
    if (estimateId == null) {
      throw new IllegalArgumentException("Logistics return estimate ID is required");
    }
    if (this.estimateId != null && !this.estimateId.equals(estimateId)) {
      throw new IllegalStateException(
          "Logistics return shortage is already bound to another estimate");
    }
    this.estimateId = estimateId;
  }

  @PrePersist
  void beforeInsert() {
    createdAt = MaintenanceTime.now();
  }

  private static boolean sha256(String value) {
    return value != null && value.matches("[0-9a-f]{64}");
  }
}
