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
import java.util.UUID;

@Entity
@Table(name = "finding_media_reference")
@IdClass(FindingMediaReference.Key.class)
public class FindingMediaReference {
  @Id @Column(name = "finding_id", nullable = false) private UUID findingId;
  @Id @Column(name = "finding_revision", nullable = false) private long findingRevision;
  @Id @Column(name = "media_id", nullable = false) private UUID mediaId;
  @Id @Column(name = "generation", nullable = false) private long generation;

  @Column(name = "media_kind", nullable = false, length = 16)
  private String mediaKind;

  @Column(name = "media_status", nullable = false, length = 16)
  private String mediaStatus;

  @Column(name = "attached_at", nullable = false)
  private OffsetDateTime attachedAt;

  protected FindingMediaReference() {}

  public FindingMediaReference(
      UUID findingId, long findingRevision, UUID mediaId, long generation, String mediaKind) {
    this.findingId = findingId;
    this.findingRevision = findingRevision;
    this.mediaId = mediaId;
    this.generation = generation;
    this.mediaKind = mediaKind;
    mediaStatus = "READY";
    attachedAt = OffsetDateTime.now(ZoneOffset.UTC);
  }

  public UUID getMediaId() {
    return mediaId;
  }

  public UUID getFindingId() {
    return findingId;
  }

  public long getGeneration() {
    return generation;
  }

  public String getMediaKind() {
    return mediaKind;
  }

  public static class Key implements Serializable {
    private UUID findingId;
    private long findingRevision;
    private UUID mediaId;
    private long generation;

    public Key() {}

    @Override
    public boolean equals(Object other) {
      if (this == other) return true;
      if (!(other instanceof Key key)) return false;
      return findingRevision == key.findingRevision
          && generation == key.generation
          && Objects.equals(findingId, key.findingId)
          && Objects.equals(mediaId, key.mediaId);
    }

    @Override
    public int hashCode() {
      return Objects.hash(findingId, findingRevision, mediaId, generation);
    }
  }
}
