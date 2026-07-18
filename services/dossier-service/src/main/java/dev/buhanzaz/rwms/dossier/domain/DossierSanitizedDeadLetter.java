package dev.buhanzaz.rwms.dossier.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.nio.charset.StandardCharsets;
import java.sql.Types;
import java.time.OffsetDateTime;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;

@Entity
@Table(name = "dossier_sanitized_dead_letter")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DossierSanitizedDeadLetter {
  @Id
  @Column(name = "id", nullable = false)
  private UUID id;

  @Version
  @Column(name = "row_version", nullable = false)
  private long rowVersion;

  @Column(name = "source_event_id")
  private UUID sourceEventId;

  @Column(name = "source_aggregate_id")
  private UUID sourceAggregateId;

  @Column(name = "source_topic", nullable = false, length = 200)
  private String sourceTopic;

  @Column(name = "source_partition", nullable = false)
  private int sourcePartition;

  @Column(name = "source_offset", nullable = false)
  private long sourceOffset;

  @Column(name = "destination", nullable = false, length = 240)
  private String destination;

  @Column(name = "record_key_sha256", nullable = false, length = 64, columnDefinition = "char(64)")
  @JdbcTypeCode(Types.CHAR)
  private String recordKeySha256;

  @Column(name = "message_sha256", nullable = false, length = 64, columnDefinition = "char(64)")
  @JdbcTypeCode(Types.CHAR)
  private String messageSha256;

  @Enumerated(EnumType.STRING)
  @Column(name = "failure_code", nullable = false, length = 40)
  private DossierDltFailureCode failureCode;

  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false, length = 24)
  private DossierOutboxState status;

  @Column(name = "attempt_count", nullable = false)
  private int attemptCount;

  @Column(name = "next_attempt_at", nullable = false)
  private OffsetDateTime nextAttemptAt;

  @Column(name = "failed_at", nullable = false)
  private OffsetDateTime failedAt;

  @Column(name = "published_at")
  private OffsetDateTime publishedAt;

  public static DossierSanitizedDeadLetter pending(
      UUID sourceEventId,
      UUID sourceAggregateId,
      String sourceTopic,
      int sourcePartition,
      long sourceOffset,
      String recordKeySha256,
      String messageSha256,
      DossierDltFailureCode failureCode,
      OffsetDateTime now) {
    if (sourcePartition < 0 || sourceOffset < 0) {
      throw new IllegalArgumentException("Kafka coordinates must be non-negative");
    }
    DossierSanitizedDeadLetter letter = new DossierSanitizedDeadLetter();
    letter.sourceEventId = sourceEventId;
    letter.sourceAggregateId = sourceAggregateId;
    letter.sourceTopic = DossierSourceFact.requireText(sourceTopic, 200, "sourceTopic");
    letter.recordKeySha256 = DossierSourceFact.requireDigest(recordKeySha256, "recordKeySha256");
    letter.messageSha256 = DossierSourceFact.requireDigest(messageSha256, "messageSha256");
    letter.failureCode = DossierSourceFact.require(failureCode, "failureCode");
    letter.id =
        UUID.nameUUIDFromBytes(
            ("dossier-dlt-v1\u0000"
                    + letter.sourceTopic
                    + "\u0000"
                    + sourcePartition
                    + "\u0000"
                    + sourceOffset
                    + "\u0000"
                    + letter.messageSha256
                    + "\u0000"
                    + letter.failureCode.name())
                .getBytes(StandardCharsets.UTF_8));
    letter.sourcePartition = sourcePartition;
    letter.sourceOffset = sourceOffset;
    letter.destination = sourceTopic + ".dossier-projection-v1.dlt";
    letter.status = DossierOutboxState.PENDING;
    letter.nextAttemptAt = DossierSourceFact.require(now, "now");
    letter.failedAt = now;
    return letter;
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
      throw new IllegalStateException("Terminal dead letter requires explicit requeue");
    }
    status = DossierOutboxState.PUBLISHED;
    publishedAt = DossierSourceFact.require(now, "now");
  }

  public void deadLetter() {
    if (status == DossierOutboxState.PUBLISHED) {
      throw new IllegalStateException("Published dead letter cannot become terminally failed");
    }
    status = DossierOutboxState.DLT;
  }

  public void requeueFromDeadLetter(OffsetDateTime now) {
    if (status != DossierOutboxState.DLT) {
      throw new IllegalStateException("Only a terminal dead letter can be requeued");
    }
    status = DossierOutboxState.RETRY;
    attemptCount = 0;
    nextAttemptAt = DossierSourceFact.require(now, "now");
  }
}
