package dev.buhanzaz.rwms.dossier.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "dossier_activity")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DossierActivity {
  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Column(name = "id", nullable = false)
  private UUID id;

  @Column(name = "activity_id", nullable = false)
  private UUID activityId;

  @Column(name = "generation_id", nullable = false)
  private UUID generationId;

  @Column(name = "source_event_id", nullable = false)
  private UUID sourceEventId;

  @Column(name = "cabin_id", nullable = false)
  private UUID cabinId;

  @Column(name = "warehouse_id", nullable = false)
  private UUID warehouseId;

  @Enumerated(EnumType.STRING)
  @Column(name = "activity_code", nullable = false, length = 64)
  private DossierActivityCode activityCode;

  @Enumerated(EnumType.STRING)
  @Column(name = "source_producer", nullable = false, length = 24)
  private DossierProducer sourceProducer;

  @Column(name = "source_aggregate_type", nullable = false, length = 64)
  private String sourceAggregateType;

  @Column(name = "source_aggregate_id", nullable = false)
  private UUID sourceAggregateId;

  @Column(name = "source_secondary_id")
  private UUID sourceSecondaryId;

  @Column(name = "occurred_at")
  private OffsetDateTime occurredAt;

  @Column(name = "recorded_at", nullable = false)
  private OffsetDateTime recordedAt;

  @Column(name = "actor_subject_id")
  private UUID actorSubjectId;

  @Column(name = "actor_principal_type", length = 64)
  private String actorPrincipalType;

  @Column(name = "actor_profile_revision", length = 64)
  private String actorProfileRevision;

  @Column(name = "correlation_id", nullable = false)
  private UUID correlationId;

  @Column(name = "causation_id")
  private UUID causationId;

  @Column(name = "created_at", nullable = false)
  private OffsetDateTime createdAt;

  public static DossierActivity project(
      UUID activityId,
      UUID generationId,
      UUID sourceEventId,
      UUID cabinId,
      UUID warehouseId,
      DossierActivityCode activityCode,
      DossierProducer sourceProducer,
      String sourceAggregateType,
      UUID sourceAggregateId,
      UUID sourceSecondaryId,
      OffsetDateTime occurredAt,
      OffsetDateTime recordedAt,
      UUID actorSubjectId,
      String actorPrincipalType,
      String actorProfileRevision,
      UUID correlationId,
      UUID causationId,
      OffsetDateTime createdAt) {
    DossierActivity activity = new DossierActivity();
    activity.activityId = DossierSourceFact.require(activityId, "activityId");
    activity.generationId = DossierSourceFact.require(generationId, "generationId");
    activity.sourceEventId = DossierSourceFact.require(sourceEventId, "sourceEventId");
    activity.cabinId = DossierSourceFact.require(cabinId, "cabinId");
    activity.warehouseId = DossierSourceFact.require(warehouseId, "warehouseId");
    activity.activityCode = DossierSourceFact.require(activityCode, "activityCode");
    activity.sourceProducer = DossierSourceFact.require(sourceProducer, "sourceProducer");
    activity.sourceAggregateType = DossierSourceFact.requireText(sourceAggregateType, 64, "sourceAggregateType");
    activity.sourceAggregateId = DossierSourceFact.require(sourceAggregateId, "sourceAggregateId");
    activity.sourceSecondaryId = sourceSecondaryId;
    activity.occurredAt = occurredAt;
    activity.recordedAt = DossierSourceFact.require(recordedAt, "recordedAt");
    activity.actorSubjectId = actorSubjectId;
    activity.actorPrincipalType = actorPrincipalType;
    activity.actorProfileRevision = actorProfileRevision;
    activity.correlationId = DossierSourceFact.require(correlationId, "correlationId");
    activity.causationId = causationId;
    activity.createdAt = DossierSourceFact.require(createdAt, "createdAt");
    return activity;
  }
}
