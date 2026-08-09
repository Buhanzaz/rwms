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
import java.sql.Types;
import java.time.OffsetDateTime;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;

/** Journals a validated source fact whose cabin subject is missing, quarantined or otherwise not provable. */
@Entity
@Table(name = "dossier_unlinked_fact")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DossierUnlinkedFact {
  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Column(name = "id", nullable = false)
  private UUID id;

  @Version
  @Column(name = "row_version", nullable = false)
  private long rowVersion;

  @Column(name = "generation_id", nullable = false)
  private UUID generationId;

  @Column(name = "source_event_id", nullable = false)
  private UUID sourceEventId;

  @Column(name = "subject_cabin_id")
  private UUID subjectCabinId;

  @Enumerated(EnumType.STRING)
  @Column(name = "reason", nullable = false, length = 40)
  private DossierUnlinkedReason reason;

  @Enumerated(EnumType.STRING)
  @Column(name = "source_producer", nullable = false, length = 24)
  private DossierProducer sourceProducer;

  @Column(name = "source_aggregate_type", nullable = false, length = 64)
  private String sourceAggregateType;

  @Column(name = "source_aggregate_id", nullable = false)
  private UUID sourceAggregateId;

  @Column(name = "payload_sha256", nullable = false, length = 64, columnDefinition = "char(64)")
  @JdbcTypeCode(Types.CHAR)
  private String payloadSha256;

  @Column(name = "recorded_at", nullable = false)
  private OffsetDateTime recordedAt;

  @Column(name = "resolved_at")
  private OffsetDateTime resolvedAt;

  public static DossierUnlinkedFact record(
      UUID generationId,
      UUID sourceEventId,
      UUID subjectCabinId,
      DossierUnlinkedReason reason,
      DossierProducer sourceProducer,
      String sourceAggregateType,
      UUID sourceAggregateId,
      String payloadSha256,
      OffsetDateTime recordedAt) {
    DossierUnlinkedFact fact = new DossierUnlinkedFact();
    fact.generationId = DossierSourceFact.require(generationId, "generationId");
    fact.sourceEventId = DossierSourceFact.require(sourceEventId, "sourceEventId");
    fact.subjectCabinId = subjectCabinId;
    fact.reason = DossierSourceFact.require(reason, "reason");
    fact.sourceProducer = DossierSourceFact.require(sourceProducer, "sourceProducer");
    fact.sourceAggregateType = DossierSourceFact.requireText(sourceAggregateType, 64, "sourceAggregateType");
    fact.sourceAggregateId = DossierSourceFact.require(sourceAggregateId, "sourceAggregateId");
    fact.payloadSha256 = DossierSourceFact.requireDigest(payloadSha256, "payloadSha256");
    fact.recordedAt = DossierSourceFact.require(recordedAt, "recordedAt");
    return fact;
  }

  public void resolve(OffsetDateTime resolvedAt) {
    if (this.resolvedAt != null) return;
    this.resolvedAt = DossierSourceFact.require(resolvedAt, "resolvedAt");
  }
}
