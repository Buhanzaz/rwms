package dev.buhanzaz.rwms.logistics.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.sql.Types;
import java.time.OffsetDateTime;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;

/**
 * Durable evidence for exactly one service-to-service request. The request is
 * committed before it is attempted, so an uncertain response can safely replay the same
 * target-service idempotency key. A short-lived lease token and monotonic fence make recovery
 * safe across replicas without changing that request identity.
 */
@Entity
@Table(name = "logistics_external_attempt")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LogisticsExternalAttempt {
  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Column(name = "id", nullable = false)
  private UUID id;

  @Version
  @Column(name = "row_version", nullable = false)
  private long rowVersion;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(
      name = "document_id",
      nullable = false,
      foreignKey = @ForeignKey(name = "fk_logistics_external_attempt_document"))
  private LogisticsDocument document;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(
      name = "line_id",
      foreignKey = @ForeignKey(name = "fk_logistics_external_attempt_line"))
  private LogisticsDocumentLine line;

  @Column(name = "operation_id", nullable = false)
  private UUID operationId;

  @Enumerated(EnumType.STRING)
  @Column(name = "target_service", nullable = false, length = 32)
  private LogisticsTargetService targetService;

  @Column(name = "operation_type", nullable = false, length = 64)
  private String operationType;

  @JdbcTypeCode(Types.CHAR)
  @Column(name = "request_sha256", nullable = false, length = 64)
  private String requestSha256;

  @JdbcTypeCode(Types.CHAR)
  @Column(name = "response_sha256", length = 64)
  private String responseSha256;

  @Enumerated(EnumType.STRING)
  @Column(name = "result", nullable = false, length = 32)
  private LogisticsExternalAttemptResult result;

  @Column(name = "retry_count", nullable = false)
  private int retryCount;

  @Column(name = "next_attempt_at")
  private OffsetDateTime nextAttemptAt;

  /**
   * Random capability presented by the worker that currently owns this attempt. It is cleared with
   * the lease and never reused for a later claim.
   */
  @Column(name = "lease_token")
  private UUID leaseToken;

  /**
   * Monotonic fence for the attempt lease. A later claimant always receives a strictly greater
   * value, so an expired worker cannot record a newer worker's result.
   */
  @Column(name = "lease_fence", nullable = false)
  private long leaseFence;

  /**
   * The exclusive-ownership deadline. The attempt is eligible for recovery only after this time
   * has passed.
   */
  @Column(name = "lease_expires_at")
  private OffsetDateTime leaseExpiresAt;

  @Column(name = "correlation_id", nullable = false)
  private UUID correlationId;

  @Column(name = "causation_id")
  private UUID causationId;

  @Column(name = "created_at", nullable = false)
  private OffsetDateTime createdAt;

  @Column(name = "completed_at")
  private OffsetDateTime completedAt;

  public static LogisticsExternalAttempt create(
      LogisticsDocument document,
      LogisticsDocumentLine line,
      LogisticsTargetService targetService,
      String operationType,
      String requestSha256,
      UUID correlationId,
      UUID causationId,
      OffsetDateTime createdAt) {
    if (document == null || targetService == null || correlationId == null || createdAt == null) {
      throw new IllegalArgumentException("Attempt ownership and timing are required");
    }
    if (operationType == null || operationType.isBlank() || operationType.length() > 64) {
      throw new IllegalArgumentException("operationType is required");
    }
    if (requestSha256 == null || !requestSha256.matches("[0-9a-f]{64}")) {
      throw new IllegalArgumentException("requestSha256 must be a SHA-256 digest");
    }
    LogisticsExternalAttempt attempt = new LogisticsExternalAttempt();
    attempt.document = document;
    attempt.line = line;
    attempt.operationId = UUID.randomUUID();
    attempt.targetService = targetService;
    attempt.operationType = operationType;
    attempt.requestSha256 = requestSha256;
    attempt.result = LogisticsExternalAttemptResult.PENDING;
    attempt.retryCount = 0;
    attempt.nextAttemptAt = createdAt;
    attempt.correlationId = correlationId;
    attempt.causationId = causationId;
    attempt.createdAt = createdAt;
    return attempt;
  }

  /**
   * Marks a successfully verified external response terminal and clears any current lease. Callers
   * must first prove their exact claim under a pessimistic lock.
   */
  public void confirm(String responseSha256, OffsetDateTime completedAt) {
    if (result == LogisticsExternalAttemptResult.CONFIRMED) return;
    requireDigest(responseSha256, "responseSha256");
    requireCompletion(completedAt);
    result = LogisticsExternalAttemptResult.CONFIRMED;
    this.responseSha256 = responseSha256;
    nextAttemptAt = null;
    this.completedAt = completedAt;
    clearLease();
  }

  /**
   * Schedules a retry and releases the completed worker's lease. Callers must first prove their
   * exact claim under a pessimistic lock.
   */
  public void retry(OffsetDateTime nextAttemptAt) {
    if (result == LogisticsExternalAttemptResult.CONFIRMED
        || result == LogisticsExternalAttemptResult.PERMANENT_REJECTION
        || result == LogisticsExternalAttemptResult.RECONCILIATION_REQUIRED) {
      return;
    }
    if (nextAttemptAt == null) throw new IllegalArgumentException("nextAttemptAt is required");
    result = LogisticsExternalAttemptResult.RETRY;
    retryCount = Math.addExact(retryCount, 1);
    this.nextAttemptAt = nextAttemptAt;
    clearLease();
  }

  /**
   * Records a terminal rejection and clears any current lease after exact claim verification.
   */
  public void reject(String responseSha256, OffsetDateTime completedAt) {
    if (result == LogisticsExternalAttemptResult.CONFIRMED) return;
    requireDigest(responseSha256, "responseSha256");
    requireCompletion(completedAt);
    result = LogisticsExternalAttemptResult.PERMANENT_REJECTION;
    this.responseSha256 = responseSha256;
    nextAttemptAt = null;
    this.completedAt = completedAt;
    clearLease();
  }

  /**
   * Records an unknown terminal outcome and clears any current lease after exact claim
   * verification.
   */
  public void requireReconciliation(String responseSha256, OffsetDateTime completedAt) {
    if (result == LogisticsExternalAttemptResult.CONFIRMED) return;
    requireDigest(responseSha256, "responseSha256");
    requireCompletion(completedAt);
    result = LogisticsExternalAttemptResult.RECONCILIATION_REQUIRED;
    this.responseSha256 = responseSha256;
    nextAttemptAt = null;
    this.completedAt = completedAt;
    clearLease();
  }

  public boolean isDue(OffsetDateTime now) {
    return (result == LogisticsExternalAttemptResult.PENDING
            || result == LogisticsExternalAttemptResult.RETRY)
        && (nextAttemptAt == null || !nextAttemptAt.isAfter(now));
  }

  /**
   * Returns whether a recovery worker may acquire this attempt at {@code now}. An expired lease is
   * deliberately claimable so a stopped replica cannot strand durable work forever.
   */
  public boolean isClaimable(OffsetDateTime now) {
    if (now == null) throw new IllegalArgumentException("now is required");
    return isDue(now) && (leaseExpiresAt == null || !leaseExpiresAt.isAfter(now));
  }

  /**
   * Acquires a new fenced lease after the caller has selected and locked this due row. The caller
   * must flush before exposing the matching optimistic row version to a remote worker.
   */
  public void claim(UUID leaseToken, OffsetDateTime leaseExpiresAt, OffsetDateTime now) {
    if (leaseToken == null || leaseExpiresAt == null || now == null) {
      throw new IllegalArgumentException("Lease token, expiry and current time are required");
    }
    if (!leaseExpiresAt.isAfter(now)) {
      throw new IllegalArgumentException("Lease expiry must be after the claim time");
    }
    if (!isClaimable(now)) {
      throw new IllegalStateException("External attempt is not claimable");
    }
    this.leaseToken = leaseToken;
    leaseFence = Math.addExact(leaseFence, 1);
    this.leaseExpiresAt = leaseExpiresAt;
  }

  /**
   * Returns whether this managed row still belongs to the immutable lease capability presented by
   * a worker. The request digest is included so a row identity can never be confused with another
   * request if a future migration reuses an operation identifier incorrectly.
   */
  public boolean matchesClaim(
      UUID expectedOperationId,
      UUID expectedLeaseToken,
      long expectedLeaseFence,
      long expectedRowVersion,
      String expectedRequestSha256,
      OffsetDateTime now) {
    return expectedOperationId != null
        && expectedLeaseToken != null
        && expectedRequestSha256 != null
        && now != null
        && expectedOperationId.equals(operationId)
        && expectedLeaseToken.equals(leaseToken)
        && expectedLeaseFence == leaseFence
        && expectedRowVersion == rowVersion
        && expectedRequestSha256.equals(requestSha256)
        && leaseExpiresAt != null
        && leaseExpiresAt.isAfter(now)
        && (result == LogisticsExternalAttemptResult.PENDING
            || result == LogisticsExternalAttemptResult.RETRY);
  }

  /**
   * Releases a valid lease without counting an owner-local prerequisite as a remote failure. The
   * bounded defer prevents the same blocked row from monopolising a recovery page.
   */
  public void defer(OffsetDateTime nextAttemptAt) {
    if (nextAttemptAt == null) throw new IllegalArgumentException("nextAttemptAt is required");
    if (result != LogisticsExternalAttemptResult.PENDING
        && result != LogisticsExternalAttemptResult.RETRY) {
      throw new IllegalStateException("Only retryable attempts can be deferred");
    }
    this.nextAttemptAt = nextAttemptAt;
    clearLease();
  }

  private void clearLease() {
    leaseToken = null;
    leaseExpiresAt = null;
  }

  private static void requireDigest(String value, String field) {
    if (value == null || !value.matches("[0-9a-f]{64}")) {
      throw new IllegalArgumentException(field + " must be a SHA-256 digest");
    }
  }

  private static void requireCompletion(OffsetDateTime value) {
    if (value == null) throw new IllegalArgumentException("completedAt is required");
  }
}
