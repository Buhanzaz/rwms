package dev.buhanzaz.rwms.analytics.domain;

import dev.buhanzaz.rwms.analytics.eventing.AnalyticsTopics;
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

/** Persists a hash-only consumer failure for controlled publication to the analytics-owned DLT. */
@Entity
@Table(name = "analytics_sanitized_dead_letter")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AnalyticsSanitizedDeadLetter {
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
  private AnalyticsDltFailureCode failureCode;

  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false, length = 24)
  private AnalyticsOutboxState status;

  @Column(name = "attempt_count", nullable = false)
  private int attemptCount;

  @Column(name = "next_attempt_at", nullable = false)
  private OffsetDateTime nextAttemptAt;

  @Column(name = "failed_at", nullable = false)
  private OffsetDateTime failedAt;

  @Column(name = "published_at")
  private OffsetDateTime publishedAt;

  public static AnalyticsSanitizedDeadLetter pending(
      UUID eventId,
      UUID aggregateId,
      String sourceTopic,
      int sourcePartition,
      long sourceOffset,
      String recordKeySha256,
      String messageSha256,
      AnalyticsDltFailureCode failureCode,
      OffsetDateTime now) {
    if (sourcePartition < 0 || sourceOffset < 0) {
      throw new IllegalArgumentException("Source coordinates must be non-negative");
    }
    AnalyticsSanitizedDeadLetter value = new AnalyticsSanitizedDeadLetter();
    value.sourceEventId = eventId;
    value.sourceAggregateId = aggregateId;
    value.sourceTopic = AnalyticsDomainRules.text(sourceTopic, 200, "sourceTopic");
    value.sourcePartition = sourcePartition;
    value.sourceOffset = sourceOffset;
    value.destination = AnalyticsTopics.DLT;
    value.recordKeySha256 =
        AnalyticsDomainRules.digest(recordKeySha256, "recordKeySha256");
    value.messageSha256 = AnalyticsDomainRules.digest(messageSha256, "messageSha256");
    value.failureCode = AnalyticsDomainRules.require(failureCode, "failureCode");
    value.id =
        UUID.nameUUIDFromBytes(
            ("analytics-dlt-v1\u0000"
                    + sourceTopic
                    + "\u0000"
                    + sourcePartition
                    + "\u0000"
                    + sourceOffset
                    + "\u0000"
                    + messageSha256
                    + "\u0000"
                    + failureCode)
                .getBytes(StandardCharsets.UTF_8));
    value.status = AnalyticsOutboxState.PENDING;
    value.nextAttemptAt = AnalyticsDomainRules.require(now, "now");
    value.failedAt = now;
    return value;
  }

  public void retry(OffsetDateTime nextAttemptAt) {
    if (status == AnalyticsOutboxState.PUBLISHED || status == AnalyticsOutboxState.DLT) return;
    status = AnalyticsOutboxState.RETRY;
    attemptCount = Math.addExact(attemptCount, 1);
    this.nextAttemptAt = AnalyticsDomainRules.require(nextAttemptAt, "nextAttemptAt");
  }

  public void published(OffsetDateTime now) {
    if (status == AnalyticsOutboxState.PUBLISHED) return;
    if (status == AnalyticsOutboxState.DLT) {
      throw new IllegalStateException("Terminal DLT row cannot be published");
    }
    status = AnalyticsOutboxState.PUBLISHED;
    publishedAt = AnalyticsDomainRules.require(now, "now");
  }

  public void terminalFailure() {
    if (status != AnalyticsOutboxState.PUBLISHED) status = AnalyticsOutboxState.DLT;
  }
}
