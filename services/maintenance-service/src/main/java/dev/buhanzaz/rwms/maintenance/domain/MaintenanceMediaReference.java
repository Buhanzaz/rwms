package dev.buhanzaz.rwms.maintenance.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** JPA reference to a media revision used as maintenance evidence; media-service owns the content. */
@Entity
@Table(name = "maintenance_media_reference")
@IdClass(MaintenanceMediaReferenceId.class)
public class MaintenanceMediaReference {
  @Id
  @Column(name = "aggregate_type", nullable = false, length = 32)
  private String aggregateType;

  @Id
  @Column(name = "aggregate_id", nullable = false)
  private UUID aggregateId;

  @Id
  @Column(name = "media_id", nullable = false)
  private UUID mediaId;

  @Column(name = "generation", nullable = false)
  private long generation;

  @Column(name = "owner_type", nullable = false, length = 64)
  private String ownerType;

  @Column(name = "warehouse_id", nullable = false)
  private UUID warehouseId;

  @Column(name = "safe_metadata", nullable = false, columnDefinition = "jsonb")
  @JdbcTypeCode(SqlTypes.JSON)
  private String safeMetadata;

  @Column(name = "attached_at", nullable = false)
  private OffsetDateTime attachedAt;

  protected MaintenanceMediaReference() {}

  public MaintenanceMediaReference(
      String aggregateType,
      UUID aggregateId,
      UUID mediaId,
      long generation,
      String ownerType,
      UUID warehouseId,
      String safeMetadata) {
    if (aggregateType == null || aggregateId == null || mediaId == null || generation < 0
        || ownerType == null || warehouseId == null) {
      throw new IllegalArgumentException("Maintenance media reference is invalid");
    }
    this.aggregateType = aggregateType;
    this.aggregateId = aggregateId;
    this.mediaId = mediaId;
    this.generation = generation;
    this.ownerType = ownerType;
    this.warehouseId = warehouseId;
    this.safeMetadata = safeMetadata == null ? "{}" : safeMetadata;
  }

  @PrePersist
  void beforeInsert() { attachedAt = MaintenanceTime.now(); }

  public String getAggregateType() { return aggregateType; }
  public UUID getAggregateId() { return aggregateId; }
  public UUID getMediaId() { return mediaId; }
  public long getGeneration() { return generation; }
  public String getOwnerType() { return ownerType; }
  public UUID getWarehouseId() { return warehouseId; }
  public String getSafeMetadata() { return safeMetadata; }
  public OffsetDateTime getAttachedAt() { return attachedAt; }
}
