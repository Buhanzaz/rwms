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

/** Stores a proved relation from a source subject to a cabin without inventing an association from unrelated data. */
@Entity
@Table(name = "dossier_subject_association")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DossierSubjectAssociation {
  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Column(name = "id", nullable = false)
  private UUID id;

  @Version
  @Column(name = "row_version", nullable = false)
  private long rowVersion;

  @Column(name = "generation_id", nullable = false)
  private UUID generationId;

  @Enumerated(EnumType.STRING)
  @Column(name = "producer", nullable = false, length = 24)
  private DossierProducer producer;

  @Column(name = "source_type", nullable = false, length = 64)
  private String sourceType;

  @Column(name = "source_id", nullable = false)
  private UUID sourceId;

  @Column(name = "cabin_id", nullable = false)
  private UUID cabinId;

  @Column(name = "warehouse_id", nullable = false)
  private UUID warehouseId;

  @Column(name = "source_event_id", nullable = false)
  private UUID sourceEventId;

  @Column(name = "proven_at", nullable = false)
  private OffsetDateTime provenAt;

  public static DossierSubjectAssociation prove(
      UUID generationId,
      DossierProducer producer,
      String sourceType,
      UUID sourceId,
      UUID cabinId,
      UUID warehouseId,
      UUID sourceEventId,
      OffsetDateTime provenAt) {
    DossierSubjectAssociation association = new DossierSubjectAssociation();
    association.generationId = DossierSourceFact.require(generationId, "generationId");
    association.producer = DossierSourceFact.require(producer, "producer");
    association.sourceType = DossierSourceFact.requireText(sourceType, 64, "sourceType");
    association.sourceId = DossierSourceFact.require(sourceId, "sourceId");
    association.cabinId = DossierSourceFact.require(cabinId, "cabinId");
    association.warehouseId = DossierSourceFact.require(warehouseId, "warehouseId");
    association.sourceEventId = DossierSourceFact.require(sourceEventId, "sourceEventId");
    association.provenAt = DossierSourceFact.require(provenAt, "provenAt");
    return association;
  }

  public void reprove(
      UUID cabinId, UUID warehouseId, UUID sourceEventId, OffsetDateTime provenAt) {
    UUID provenCabinId = DossierSourceFact.require(cabinId, "cabinId");
    if (!this.cabinId.equals(provenCabinId)) {
      throw new IllegalStateException("DOSSIER_SUBJECT_IDENTITY_CONFLICT");
    }
    this.warehouseId = DossierSourceFact.require(warehouseId, "warehouseId");
    this.sourceEventId = DossierSourceFact.require(sourceEventId, "sourceEventId");
    this.provenAt = DossierSourceFact.require(provenAt, "provenAt");
  }
}
