package dev.buhanzaz.rwms.dossier.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "dossier_outbox_event")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DossierOutboxEvent {
  public static final String DESTINATION = "rwms.dossier.cabin-activity.v1";
  public static final String EVENT_TYPE = "dossier.cabin-activity.projected.v1";

  @Id
  @Column(name = "event_id", nullable = false)
  private UUID eventId;

  @Version
  @Column(name = "row_version", nullable = false)
  private long rowVersion;

  @Column(name = "cabin_id", nullable = false)
  private UUID cabinId;

  @Column(name = "aggregate_version", nullable = false)
  private long aggregateVersion;

  @Column(name = "source_event_id", nullable = false)
  private UUID sourceEventId;

  @Column(name = "destination", nullable = false, length = 200)
  private String destination;

  @Column(name = "event_type", nullable = false, length = 160)
  private String eventType;

  @Column(name = "payload_sha256", nullable = false, length = 64, columnDefinition = "char(64)")
  @JdbcTypeCode(Types.CHAR)
  private String payloadSha256;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "canonical_payload", nullable = false, columnDefinition = "jsonb")
  private String canonicalPayload;

  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false, length = 24)
  private DossierOutboxState status;

  @Column(name = "attempt_count", nullable = false)
  private int attemptCount;

  @Column(name = "next_attempt_at", nullable = false)
  private OffsetDateTime nextAttemptAt;

  @Column(name = "created_at", nullable = false)
  private OffsetDateTime createdAt;

  @Column(name = "published_at")
  private OffsetDateTime publishedAt;

  public static DossierOutboxEvent pending(
      UUID eventId,
      UUID cabinId,
      long aggregateVersion,
      UUID sourceEventId,
      String payloadSha256,
      String canonicalPayload,
      OffsetDateTime now) {
    if (aggregateVersion < 0) throw new IllegalArgumentException("aggregateVersion must be non-negative");
    DossierOutboxEvent event = new DossierOutboxEvent();
    event.eventId = DossierSourceFact.require(eventId, "eventId");
    event.cabinId = DossierSourceFact.require(cabinId, "cabinId");
    event.aggregateVersion = aggregateVersion;
    event.sourceEventId = DossierSourceFact.require(sourceEventId, "sourceEventId");
    event.destination = DESTINATION;
    event.eventType = EVENT_TYPE;
    event.payloadSha256 = DossierSourceFact.requireDigest(payloadSha256, "payloadSha256");
    event.canonicalPayload = DossierSourceFact.requireJsonObject(canonicalPayload, "canonicalPayload");
    event.status = DossierOutboxState.PENDING;
    event.nextAttemptAt = DossierSourceFact.require(now, "now");
    event.createdAt = now;
    return event;
  }

  public void retry(OffsetDateTime nextAttemptAt) {
    if (status == DossierOutboxState.PUBLISHED || status == DossierOutboxState.DLT) return;
    status = DossierOutboxState.RETRY;
    attemptCount = Math.addExact(attemptCount, 1);
    this.nextAttemptAt = DossierSourceFact.require(nextAttemptAt, "nextAttemptAt");
  }

  public void published(OffsetDateTime now) {
    if (status == DossierOutboxState.PUBLISHED) return;
    if (status == DossierOutboxState.DLT) {
      throw new IllegalStateException("Dead-lettered outbox requires explicit requeue");
    }
    status = DossierOutboxState.PUBLISHED;
    publishedAt = DossierSourceFact.require(now, "now");
  }

  public void deadLetter() {
    if (status == DossierOutboxState.PUBLISHED) throw new IllegalStateException("Published outbox cannot enter DLT");
    status = DossierOutboxState.DLT;
  }

  public void requeueFromDeadLetter(OffsetDateTime now) {
    if (status != DossierOutboxState.DLT) {
      throw new IllegalStateException("Only a dead-lettered outbox event can be requeued");
    }
    status = DossierOutboxState.RETRY;
    attemptCount = 0;
    nextAttemptAt = DossierSourceFact.require(now, "now");
  }
}
