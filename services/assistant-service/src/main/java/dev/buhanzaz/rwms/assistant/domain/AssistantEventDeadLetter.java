package dev.buhanzaz.rwms.assistant.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.proxy.HibernateProxy;
import org.hibernate.type.SqlTypes;

/**
 * Sanitized, source-coordinate-unique evidence for a rejected assistant booking-event receipt.
 * Rejected raw values are never retained by this entity.
 */
@Entity
@Table(name = "assistant_event_dead_letter")
public class AssistantEventDeadLetter {
  @Id
  @Column(name = "dlt_id", nullable = false)
  private UUID id;

  @Column(name = "source_topic", nullable = false, length = 249)
  private String sourceTopic;

  @Column(name = "source_partition", nullable = false)
  private int sourcePartition;

  @Column(name = "source_offset", nullable = false)
  private long sourceOffset;

  @Column(name = "source_event_id")
  private UUID sourceEventId;

  @Column(name = "message_sha256", nullable = false, length = 64, columnDefinition = "char(64)")
  @JdbcTypeCode(SqlTypes.CHAR)
  private String messageSha256;

  @Column(name = "failure_code", nullable = false, length = 64)
  private String failureCode;

  @Enumerated(EnumType.STRING)
  @Column(name = "replay_state", nullable = false, length = 32)
  private AssistantEventReplayState replayState;

  @Column(name = "review_version", nullable = false)
  private long reviewVersion;

  @Column(name = "reviewed_by_subject_id")
  private UUID reviewedBySubjectId;

  @Column(name = "reviewed_at")
  private OffsetDateTime reviewedAt;

  @Column(name = "replayed_at")
  private OffsetDateTime replayedAt;

  @Column(name = "created_at", nullable = false)
  private OffsetDateTime createdAt;

  protected AssistantEventDeadLetter() {}

  public UUID getId() {
    return id;
  }

  public String getSourceTopic() {
    return sourceTopic;
  }

  public int getSourcePartition() {
    return sourcePartition;
  }

  public long getSourceOffset() {
    return sourceOffset;
  }

  public UUID getSourceEventId() {
    return sourceEventId;
  }

  public String getMessageSha256() {
    return messageSha256 == null ? null : messageSha256.trim();
  }

  public String getFailureCode() {
    return failureCode;
  }

  public AssistantEventReplayState getReplayState() {
    return replayState;
  }

  public long getReviewVersion() {
    return reviewVersion;
  }

  public UUID getReviewedBySubjectId() {
    return reviewedBySubjectId;
  }

  public OffsetDateTime getReviewedAt() {
    return reviewedAt;
  }

  public OffsetDateTime getReplayedAt() {
    return replayedAt;
  }

  public OffsetDateTime getCreatedAt() {
    return createdAt;
  }

  /**
   * Approves the exact expected review version. Repeating the same reviewer request is harmless,
   * while a competing version or decision is rejected.
   */
  public boolean approve(long expectedReviewVersion, UUID reviewerSubjectId, OffsetDateTime now) {
    Objects.requireNonNull(reviewerSubjectId, "reviewerSubjectId");
    if ((replayState == AssistantEventReplayState.APPROVED
            || replayState == AssistantEventReplayState.REPLAYED)
        && reviewVersion == expectedReviewVersion + 1
        && reviewerSubjectId.equals(reviewedBySubjectId)) {
      return true;
    }
    if (replayState != AssistantEventReplayState.AWAITING_REVIEW
        || reviewVersion != expectedReviewVersion) {
      return false;
    }
    replayState = AssistantEventReplayState.APPROVED;
    reviewVersion = Math.addExact(reviewVersion, 1);
    reviewedBySubjectId = reviewerSubjectId;
    reviewedAt = Objects.requireNonNull(now, "now");
    return true;
  }

  /** Applies one version-fenced rejection and makes an identical retry idempotent. */
  public boolean reject(long expectedReviewVersion, UUID reviewerSubjectId, OffsetDateTime now) {
    Objects.requireNonNull(reviewerSubjectId, "reviewerSubjectId");
    if (replayState == AssistantEventReplayState.REJECTED
        && reviewVersion == expectedReviewVersion + 1
        && reviewerSubjectId.equals(reviewedBySubjectId)) {
      return true;
    }
    if (replayState != AssistantEventReplayState.AWAITING_REVIEW
        || reviewVersion != expectedReviewVersion) {
      return false;
    }
    replayState = AssistantEventReplayState.REJECTED;
    reviewVersion = Math.addExact(reviewVersion, 1);
    reviewedBySubjectId = reviewerSubjectId;
    reviewedAt = Objects.requireNonNull(now, "now");
    return true;
  }

  /** Marks an approved replay complete without advancing its operator review version. */
  public boolean markReplayed(long approvedReviewVersion, OffsetDateTime now) {
    if (replayState == AssistantEventReplayState.REPLAYED
        && reviewVersion == approvedReviewVersion) {
      return true;
    }
    if (replayState != AssistantEventReplayState.APPROVED
        || reviewVersion != approvedReviewVersion) {
      return false;
    }
    replayState = AssistantEventReplayState.REPLAYED;
    replayedAt = Objects.requireNonNull(now, "now");
    return true;
  }

  @Override
  public final boolean equals(Object other) {
    if (this == other) return true;
    if (other == null) return false;
    Class<?> otherClass =
        other instanceof HibernateProxy proxy
            ? proxy.getHibernateLazyInitializer().getPersistentClass()
            : other.getClass();
    Class<?> thisClass =
        this instanceof HibernateProxy proxy
            ? proxy.getHibernateLazyInitializer().getPersistentClass()
            : getClass();
    if (thisClass != otherClass) return false;
    AssistantEventDeadLetter value = (AssistantEventDeadLetter) other;
    return id != null && Objects.equals(id, value.id);
  }

  @Override
  public final int hashCode() {
    return this instanceof HibernateProxy proxy
        ? proxy.getHibernateLazyInitializer().getPersistentClass().hashCode()
        : getClass().hashCode();
  }
}
