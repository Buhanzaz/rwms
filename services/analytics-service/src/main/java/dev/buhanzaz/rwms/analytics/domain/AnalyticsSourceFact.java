package dev.buhanzaz.rwms.analytics.domain;

import dev.buhanzaz.rwms.analytics.eventing.AnalyticsValidatedEvent;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.sql.Types;
import java.time.OffsetDateTime;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "analytics_source_fact")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AnalyticsSourceFact {
  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Column(name = "id", nullable = false)
  private UUID id;

  @Column(name = "event_id", nullable = false, unique = true)
  private UUID eventId;

  @Column(name = "source_topic", nullable = false, length = 200)
  private String sourceTopic;

  @Column(name = "source_partition", nullable = false)
  private int sourcePartition;

  @Column(name = "source_offset", nullable = false)
  private long sourceOffset;

  @Column(name = "aggregate_id", nullable = false)
  private UUID aggregateId;

  @Column(name = "aggregate_version", nullable = false)
  private long aggregateVersion;

  @Column(name = "envelope_sha256", nullable = false, length = 64, columnDefinition = "char(64)")
  @JdbcTypeCode(Types.CHAR)
  private String envelopeSha256;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "canonical_envelope", nullable = false, columnDefinition = "jsonb")
  private String canonicalEnvelope;

  @Column(name = "recorded_at", nullable = false)
  private OffsetDateTime recordedAt;

  @Column(name = "ingested_at", nullable = false)
  private OffsetDateTime ingestedAt;

  public static AnalyticsSourceFact record(AnalyticsValidatedEvent event, OffsetDateTime now) {
    AnalyticsSourceFact fact = new AnalyticsSourceFact();
    fact.eventId = AnalyticsDomainRules.uuid(event.eventId(), "eventId");
    fact.sourceTopic = AnalyticsDomainRules.text(event.topic(), 200, "sourceTopic");
    if (event.partition() < 0 || event.offset() < 0 || event.aggregateVersion() < 0) {
      throw new IllegalArgumentException("Source coordinates and aggregateVersion must be non-negative");
    }
    fact.sourcePartition = event.partition();
    fact.sourceOffset = event.offset();
    fact.aggregateId = AnalyticsDomainRules.uuid(event.aggregateId(), "aggregateId");
    fact.aggregateVersion = event.aggregateVersion();
    fact.envelopeSha256 =
        AnalyticsDomainRules.digest(event.envelopeSha256(), "envelopeSha256");
    fact.canonicalEnvelope =
        AnalyticsDomainRules.text(event.canonicalEnvelope(), Integer.MAX_VALUE, "canonicalEnvelope");
    fact.recordedAt = AnalyticsDomainRules.require(event.recordedAt(), "recordedAt");
    fact.ingestedAt = AnalyticsDomainRules.require(now, "now");
    return fact;
  }
}
