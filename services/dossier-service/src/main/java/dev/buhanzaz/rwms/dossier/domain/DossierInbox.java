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

@Entity
@Table(name = "dossier_inbox")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DossierInbox {
  @Id
  @Column(name = "event_id", nullable = false)
  private UUID eventId;

  @Version
  @Column(name = "row_version", nullable = false)
  private long rowVersion;

  @Column(name = "payload_sha256", nullable = false, length = 64, columnDefinition = "char(64)")
  @JdbcTypeCode(Types.CHAR)
  private String payloadSha256;

  @Enumerated(EnumType.STRING)
  @Column(name = "decision", nullable = false, length = 32)
  private DossierInboxDecision decision;

  @Column(name = "attempt_count", nullable = false)
  private int attemptCount;

  @Column(name = "received_at", nullable = false)
  private OffsetDateTime receivedAt;

  @Column(name = "decided_at")
  private OffsetDateTime decidedAt;

  public static DossierInbox receive(UUID eventId, String payloadSha256, OffsetDateTime receivedAt) {
    DossierInbox inbox = new DossierInbox();
    inbox.eventId = DossierSourceFact.require(eventId, "eventId");
    inbox.payloadSha256 = DossierSourceFact.requireDigest(payloadSha256, "payloadSha256");
    inbox.decision = DossierInboxDecision.RECEIVED;
    inbox.attemptCount = 1;
    inbox.receivedAt = DossierSourceFact.require(receivedAt, "receivedAt");
    return inbox;
  }

  public boolean hasSamePayload(String digest) {
    return payloadSha256.equals(digest);
  }

  public void retry() {
    if (decision != DossierInboxDecision.RECEIVED) return;
    attemptCount = Math.addExact(attemptCount, 1);
  }

  public void decide(DossierInboxDecision decision, OffsetDateTime decidedAt) {
    if (decision == null || decision == DossierInboxDecision.RECEIVED) {
      throw new IllegalArgumentException("A terminal inbox decision is required");
    }
    if (this.decision != DossierInboxDecision.RECEIVED && this.decision != decision) {
      throw new IllegalStateException("Inbox decision is already terminal");
    }
    this.decision = decision;
    this.decidedAt = DossierSourceFact.require(decidedAt, "decidedAt");
  }

  public void recoverProcessed(OffsetDateTime decidedAt) {
    if (decision != DossierInboxDecision.QUARANTINED) {
      throw new IllegalStateException("Only a quarantined inbox decision can be recovered");
    }
    decision = DossierInboxDecision.PROCESSED;
    this.decidedAt = DossierSourceFact.require(decidedAt, "decidedAt");
  }

  public void deadLetterQuarantined(OffsetDateTime decidedAt) {
    if (decision == DossierInboxDecision.DLT) return;
    if (decision != DossierInboxDecision.QUARANTINED) {
      throw new IllegalStateException("Only a quarantined inbox decision can enter DLT");
    }
    decision = DossierInboxDecision.DLT;
    this.decidedAt = DossierSourceFact.require(decidedAt, "decidedAt");
  }

  public void deadLetterProcessedConflict(OffsetDateTime decidedAt) {
    if (decision == DossierInboxDecision.DLT) return;
    if (decision != DossierInboxDecision.PROCESSED) {
      throw new IllegalStateException("Only a processed deferred conflict can enter DLT");
    }
    decision = DossierInboxDecision.DLT;
    this.decidedAt = DossierSourceFact.require(decidedAt, "decidedAt");
  }

  public void recoverProcessingFailure(OffsetDateTime decidedAt) {
    if (decision != DossierInboxDecision.DLT) {
      throw new IllegalStateException("Only a failed inbox decision can be recovered");
    }
    decision = DossierInboxDecision.PROCESSED;
    this.decidedAt = DossierSourceFact.require(decidedAt, "decidedAt");
  }
}
