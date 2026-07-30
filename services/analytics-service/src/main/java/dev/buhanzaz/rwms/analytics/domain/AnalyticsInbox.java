package dev.buhanzaz.rwms.analytics.domain;

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

@Entity
@Table(name = "analytics_inbox")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AnalyticsInbox {
  @Id
  @Column(name = "event_id", nullable = false)
  private UUID eventId;

  @Version
  @Column(name = "row_version", nullable = false)
  private long rowVersion;

  @Column(name = "envelope_sha256", nullable = false, length = 64, columnDefinition = "char(64)")
  @JdbcTypeCode(Types.CHAR)
  private String envelopeSha256;

  @Enumerated(EnumType.STRING)
  @Column(name = "decision", nullable = false, length = 24)
  private AnalyticsInboxDecision decision;

  @Column(name = "attempt_count", nullable = false)
  private int attemptCount;

  @Column(name = "received_at", nullable = false)
  private OffsetDateTime receivedAt;

  @Column(name = "decided_at")
  private OffsetDateTime decidedAt;

  public static AnalyticsInbox receive(
      UUID eventId, String envelopeSha256, OffsetDateTime receivedAt) {
    AnalyticsInbox inbox = new AnalyticsInbox();
    inbox.eventId = AnalyticsDomainRules.uuid(eventId, "eventId");
    inbox.envelopeSha256 = AnalyticsDomainRules.digest(envelopeSha256, "envelopeSha256");
    inbox.decision = AnalyticsInboxDecision.RECEIVED;
    inbox.attemptCount = 1;
    inbox.receivedAt = AnalyticsDomainRules.require(receivedAt, "receivedAt");
    return inbox;
  }

  public boolean hasSameEnvelope(String digest) {
    return envelopeSha256.equals(digest);
  }

  public boolean isTerminal() {
    return decision == AnalyticsInboxDecision.PROCESSED
        || decision == AnalyticsInboxDecision.STALE
        || decision == AnalyticsInboxDecision.DLT;
  }

  public void retry() {
    attemptCount = Math.addExact(attemptCount, 1);
  }

  public void hold(OffsetDateTime now) {
    decide(AnalyticsInboxDecision.HELD, now);
  }

  public void processed(OffsetDateTime now) {
    decide(AnalyticsInboxDecision.PROCESSED, now);
  }

  public void stale(OffsetDateTime now) {
    decide(AnalyticsInboxDecision.STALE, now);
  }

  public void deadLetter(OffsetDateTime now) {
    decide(AnalyticsInboxDecision.DLT, now);
  }

  private void decide(AnalyticsInboxDecision next, OffsetDateTime now) {
    if (decision == AnalyticsInboxDecision.DLT && next != AnalyticsInboxDecision.DLT) {
      throw new IllegalStateException("DLT inbox decision is terminal");
    }
    if (isTerminal() && decision != next) {
      throw new IllegalStateException("Inbox decision is already terminal");
    }
    decision = AnalyticsDomainRules.require(next, "decision");
    decidedAt = AnalyticsDomainRules.require(now, "now");
  }
}
