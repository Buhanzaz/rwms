package dev.buhanzaz.rwms.dossier.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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

@Entity
@Table(name = "dossier_cabin_publication_head")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DossierCabinPublicationHead {
  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Column(name = "id", nullable = false)
  private UUID id;

  @Version
  @Column(name = "row_version", nullable = false)
  private long rowVersion;

  @Column(name = "cabin_id", nullable = false, unique = true)
  private UUID cabinId;

  @Column(name = "published_version", nullable = false)
  private long publishedVersion;

  @Column(name = "updated_at", nullable = false)
  private OffsetDateTime updatedAt;

  public static DossierCabinPublicationHead start(UUID cabinId, OffsetDateTime now) {
    DossierCabinPublicationHead head = new DossierCabinPublicationHead();
    head.cabinId = DossierSourceFact.require(cabinId, "cabinId");
    head.publishedVersion = -1;
    head.updatedAt = DossierSourceFact.require(now, "now");
    return head;
  }

  public long next(OffsetDateTime now) {
    publishedVersion = Math.addExact(publishedVersion, 1);
    updatedAt = DossierSourceFact.require(now, "now");
    return publishedVersion;
  }
}
