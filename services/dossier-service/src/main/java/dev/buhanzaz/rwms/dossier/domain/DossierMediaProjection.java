package dev.buhanzaz.rwms.dossier.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.OffsetDateTime;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** Read-model media row attached to a cabin activity generation; it is never the media-service source of truth. */
@Entity
@Table(name = "dossier_media_projection")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DossierMediaProjection {
  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Column(name = "id", nullable = false)
  private UUID id;

  @Version
  @Column(name = "row_version", nullable = false)
  private long rowVersion;

  @Column(name = "generation_id", nullable = false)
  private UUID generationId;

  @Column(name = "cabin_id", nullable = false)
  private UUID cabinId;

  @Column(name = "warehouse_id", nullable = false)
  private UUID warehouseId;

  @Column(name = "media_id", nullable = false)
  private UUID mediaId;

  @Column(name = "folder_id", nullable = false)
  private UUID folderId;

  @Column(name = "inventory_finding_id", nullable = false)
  private UUID inventoryFindingId;

  @Column(name = "media_generation", nullable = false)
  private long mediaGeneration;

  @Column(name = "source_aggregate_version", nullable = false)
  private long sourceAggregateVersion;

  @Enumerated(EnumType.STRING)
  @Column(name = "state", nullable = false, length = 24)
  private DossierMediaState state;

  @Column(name = "source_event_id", nullable = false)
  private UUID sourceEventId;

  @Column(name = "updated_at", nullable = false)
  private OffsetDateTime updatedAt;

  public static DossierMediaProjection project(
      UUID generationId,
      UUID cabinId,
      UUID warehouseId,
      UUID mediaId,
      UUID folderId,
      UUID inventoryFindingId,
      long mediaGeneration,
      long sourceAggregateVersion,
      DossierMediaState state,
      UUID sourceEventId,
      OffsetDateTime now) {
    if (mediaGeneration < 0 || sourceAggregateVersion < 0) {
      throw new IllegalArgumentException("Media and source aggregate versions must be non-negative");
    }
    DossierMediaProjection projection = new DossierMediaProjection();
    projection.generationId = DossierSourceFact.require(generationId, "generationId");
    projection.cabinId = DossierSourceFact.require(cabinId, "cabinId");
    projection.warehouseId = DossierSourceFact.require(warehouseId, "warehouseId");
    projection.mediaId = DossierSourceFact.require(mediaId, "mediaId");
    projection.folderId = DossierSourceFact.require(folderId, "folderId");
    projection.inventoryFindingId = DossierSourceFact.require(inventoryFindingId, "inventoryFindingId");
    projection.mediaGeneration = mediaGeneration;
    projection.sourceAggregateVersion = sourceAggregateVersion;
    projection.state = DossierSourceFact.require(state, "state");
    projection.sourceEventId = DossierSourceFact.require(sourceEventId, "sourceEventId");
    projection.updatedAt = DossierSourceFact.require(now, "now");
    return projection;
  }

  public boolean apply(
      UUID folderId,
      long generation,
      long sourceAggregateVersion,
      DossierMediaState state,
      UUID sourceEventId,
      OffsetDateTime now) {
    if (generation < 0 || sourceAggregateVersion < 0) {
      throw new IllegalArgumentException("Media and source aggregate versions must be non-negative");
    }
    DossierSourceFact.require(state, "state");
    DossierSourceFact.require(folderId, "folderId");
    DossierSourceFact.require(sourceEventId, "sourceEventId");
    if (sourceAggregateVersion < this.sourceAggregateVersion) {
      return false;
    }
    if (sourceAggregateVersion == this.sourceAggregateVersion) {
      if (generation == mediaGeneration
          && this.state == state
          && this.folderId.equals(folderId)
          && this.sourceEventId.equals(sourceEventId)) {
        return false;
      }
      throw new IllegalStateException("MEDIA_GENERATION_CONFLICT");
    }
    if (!this.folderId.equals(folderId)) {
      throw new IllegalStateException("MEDIA_FOLDER_CONFLICT");
    }
    if (generation < mediaGeneration) {
      throw new IllegalStateException("MEDIA_GENERATION_CONFLICT");
    }
    if (this.state == DossierMediaState.DELETED
        || generation > Math.addExact(mediaGeneration, 1)) {
      throw new IllegalStateException("MEDIA_GENERATION_CONFLICT");
    }
    if (generation == mediaGeneration && !allowsSameGenerationTransition(this.state, state)) {
      throw new IllegalStateException("MEDIA_GENERATION_CONFLICT");
    }
    mediaGeneration = generation;
    this.sourceAggregateVersion = sourceAggregateVersion;
    this.state = state;
    this.sourceEventId = sourceEventId;
    updatedAt = DossierSourceFact.require(now, "now");
    return true;
  }

  private static boolean allowsSameGenerationTransition(
      DossierMediaState current, DossierMediaState target) {
    if (current == target) return true;
    if (target == DossierMediaState.DELETED) return current != DossierMediaState.DELETED;
    return current == DossierMediaState.PROCESSING
        && (target == DossierMediaState.READY || target == DossierMediaState.FAILED);
  }
}
