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

/** Single-pointer record selecting the verified dossier projection generation served by public queries. */
@Entity
@Table(name = "dossier_active_generation")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DossierActiveGeneration {
  public static final String POINTER_NAME = "DOSSIER";

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Column(name = "id", nullable = false)
  private UUID id;

  @Version
  @Column(name = "row_version", nullable = false)
  private long rowVersion;

  @Column(name = "pointer_name", nullable = false, length = 32)
  private String pointerName;

  @Column(name = "generation_id", nullable = false)
  private UUID generationId;

  @Column(name = "updated_at", nullable = false)
  private OffsetDateTime updatedAt;

  public static DossierActiveGeneration initial(UUID generationId, OffsetDateTime now) {
    DossierActiveGeneration pointer = new DossierActiveGeneration();
    pointer.pointerName = POINTER_NAME;
    pointer.generationId = DossierSourceFact.require(generationId, "generationId");
    pointer.updatedAt = DossierSourceFact.require(now, "now");
    return pointer;
  }

  public void activate(UUID generationId, OffsetDateTime now) {
    if (this.generationId.equals(generationId)) return;
    this.generationId = DossierSourceFact.require(generationId, "generationId");
    updatedAt = DossierSourceFact.require(now, "now");
  }
}
