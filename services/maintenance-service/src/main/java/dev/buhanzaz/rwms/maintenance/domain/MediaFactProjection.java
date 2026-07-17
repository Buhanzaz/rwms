package dev.buhanzaz.rwms.maintenance.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "media_fact_projection")
public class MediaFactProjection {
  @Id
  @Column(name = "media_id", nullable = false)
  private UUID mediaId;

  @Column(name = "generation", nullable = false)
  private long generation;

  @Column(name = "owner_type", nullable = false, length = 64)
  private String ownerType;

  @Column(name = "owner_id", nullable = false)
  private UUID ownerId;

  @Column(name = "warehouse_id", nullable = false)
  private UUID warehouseId;

  @Column(name = "media_status", nullable = false, length = 16)
  private String mediaStatus;

  @Column(name = "safe_metadata", nullable = false, columnDefinition = "jsonb")
  @JdbcTypeCode(SqlTypes.JSON)
  private String safeMetadata;

  @Column(name = "aggregate_version", nullable = false)
  private long aggregateVersion;

  @Column(name = "updated_at", nullable = false)
  private OffsetDateTime updatedAt;

  protected MediaFactProjection() {}

  public static MediaFactProjection create(
      UUID mediaId,
      long generation,
      String ownerType,
      UUID ownerId,
      UUID warehouseId,
      String mediaStatus,
      String safeMetadata,
      long aggregateVersion) {
    MediaFactProjection value = new MediaFactProjection();
    value.mediaId = mediaId;
    value.apply(generation, ownerType, ownerId, warehouseId, mediaStatus, safeMetadata, aggregateVersion);
    return value;
  }

  public void apply(
      long generation,
      String ownerType,
      UUID ownerId,
      UUID warehouseId,
      String mediaStatus,
      String safeMetadata,
      long aggregateVersion) {
    if (generation < 0 || ownerType == null || ownerId == null || warehouseId == null
        || mediaStatus == null || aggregateVersion < 0) {
      throw new IllegalArgumentException("Media fact is invalid");
    }
    if (aggregateVersion <= this.aggregateVersion && updatedAt != null) return;
    this.generation = generation;
    this.ownerType = ownerType;
    this.ownerId = ownerId;
    this.warehouseId = warehouseId;
    this.mediaStatus = mediaStatus;
    this.safeMetadata = safeMetadata == null ? "{}" : safeMetadata;
    this.aggregateVersion = aggregateVersion;
    this.updatedAt = MaintenanceTime.now();
  }

  public UUID getMediaId() { return mediaId; }
  public long getGeneration() { return generation; }
  public String getOwnerType() { return ownerType; }
  public UUID getOwnerId() { return ownerId; }
  public UUID getWarehouseId() { return warehouseId; }
  public String getMediaStatus() { return mediaStatus; }
  public String getSafeMetadata() { return safeMetadata; }
  public long getAggregateVersion() { return aggregateVersion; }
  public OffsetDateTime getUpdatedAt() { return updatedAt; }
}
