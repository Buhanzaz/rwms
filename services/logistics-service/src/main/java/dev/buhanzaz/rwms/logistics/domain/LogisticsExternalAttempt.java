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
 * committed before it is attempted, so an uncertain response can safely replay
 * the same target-service idempotency key.
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

  public void confirm(String responseSha256, OffsetDateTime completedAt) {
    if (result == LogisticsExternalAttemptResult.CONFIRMED) return;
    requireDigest(responseSha256, "responseSha256");
    requireCompletion(completedAt);
    result = LogisticsExternalAttemptResult.CONFIRMED;
    this.responseSha256 = responseSha256;
    nextAttemptAt = null;
    this.completedAt = completedAt;
  }

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
  }

  public void reject(String responseSha256, OffsetDateTime completedAt) {
    if (result == LogisticsExternalAttemptResult.CONFIRMED) return;
    requireDigest(responseSha256, "responseSha256");
    requireCompletion(completedAt);
    result = LogisticsExternalAttemptResult.PERMANENT_REJECTION;
    this.responseSha256 = responseSha256;
    nextAttemptAt = null;
    this.completedAt = completedAt;
  }

  public void requireReconciliation(String responseSha256, OffsetDateTime completedAt) {
    if (result == LogisticsExternalAttemptResult.CONFIRMED) return;
    requireDigest(responseSha256, "responseSha256");
    requireCompletion(completedAt);
    result = LogisticsExternalAttemptResult.RECONCILIATION_REQUIRED;
    this.responseSha256 = responseSha256;
    nextAttemptAt = null;
    this.completedAt = completedAt;
  }

  public boolean isDue(OffsetDateTime now) {
    return (result == LogisticsExternalAttemptResult.PENDING
            || result == LogisticsExternalAttemptResult.RETRY)
        && (nextAttemptAt == null || !nextAttemptAt.isAfter(now));
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
