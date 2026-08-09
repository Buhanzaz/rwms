package dev.buhanzaz.rwms.inventory.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * JPA entity that persists inventory media fact projection in the inventory-owned database.
 */
@Entity
@Table(name = "inventory_media_fact_projection")
@IdClass(InventoryMediaFactProjection.Key.class)
public class InventoryMediaFactProjection {
  @Id
  @Column(name = "media_id", nullable = false)
  private UUID mediaId;

  @Id
  @Column(name = "generation", nullable = false)
  private long generation;

  @Column(name = "media_aggregate_version", nullable = false)
  private long aggregateVersion;

  @Column(name = "owner_type", nullable = false, length = 32)
  private String ownerType;

  @Column(name = "owner_id", nullable = false)
  private UUID ownerId;

  @Column(name = "warehouse_id", nullable = false)
  private UUID warehouseId;

  @Column(name = "media_kind", nullable = false, length = 16)
  private String mediaKind;

  @Column(name = "media_status", nullable = false, length = 16)
  private String mediaStatus;

  @Column(name = "rotation_degrees", nullable = false)
  private short rotationDegrees;

  @Column(name = "updated_at", nullable = false)
  private OffsetDateTime updatedAt;

  protected InventoryMediaFactProjection() {}

  public static InventoryMediaFactProjection create(
      UUID mediaId,
      long generation,
      long aggregateVersion,
      UUID ownerId,
      UUID warehouseId,
      String mediaKind,
      String mediaStatus,
      int rotationDegrees) {
    if (mediaId == null || generation < 0) {
      throw new IllegalArgumentException("Media projection identity is invalid");
    }
    InventoryMediaFactProjection projection = new InventoryMediaFactProjection();
    projection.mediaId = mediaId;
    projection.generation = generation;
    projection.apply(
        aggregateVersion, ownerId, warehouseId, mediaKind, mediaStatus, rotationDegrees);
    return projection;
  }

  public void apply(
      long aggregateVersion,
      UUID ownerId,
      UUID warehouseId,
      String mediaKind,
      String mediaStatus,
      int rotationDegrees) {
    if (aggregateVersion < 1
        || ownerId == null
        || warehouseId == null
        || !Set.of("IMAGE", "VIDEO").contains(mediaKind)
        || !Set.of("PROCESSING", "READY", "FAILED", "DELETED").contains(mediaStatus)
        || !Set.of(0, 90, 180, 270).contains(rotationDegrees)) {
      throw new IllegalArgumentException("Media projection fact is invalid");
    }
    if (updatedAt != null && aggregateVersion <= this.aggregateVersion) {
      throw new IllegalStateException("Media projection aggregate version must advance");
    }
    this.aggregateVersion = aggregateVersion;
    this.ownerType = "INVENTORY_FINDING";
    this.ownerId = ownerId;
    this.warehouseId = warehouseId;
    this.mediaKind = mediaKind;
    this.mediaStatus = mediaStatus;
    this.rotationDegrees = (short) rotationDegrees;
    this.updatedAt = OffsetDateTime.now(ZoneOffset.UTC);
  }

  public UUID getMediaId() {
    return mediaId;
  }

  public long getGeneration() {
    return generation;
  }

  public long getAggregateVersion() {
    return aggregateVersion;
  }

  public String getMediaKind() {
    return mediaKind;
  }

  public String getMediaStatus() {
    return mediaStatus;
  }

  public static class Key implements Serializable {
    private UUID mediaId;
    private long generation;

    public Key() {}

    public Key(UUID mediaId, long generation) {
      this.mediaId = mediaId;
      this.generation = generation;
    }

    @Override
    public boolean equals(Object other) {
      return this == other
          || other instanceof Key key
              && generation == key.generation
              && Objects.equals(mediaId, key.mediaId);
    }

    @Override
    public int hashCode() {
      return Objects.hash(mediaId, generation);
    }
  }
}
