package dev.buhanzaz.rwms.logistics.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * An operator's explicit request to reconcile an ambiguous workflow. It never
 * claims that a physical correction or a reverse movement has been performed.
 */
@Entity
@Table(name = "logistics_reconciliation_request")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LogisticsReconciliationRequest {
  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Column(name = "id", nullable = false)
  private UUID id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(
      name = "document_id",
      nullable = false,
      foreignKey = @ForeignKey(name = "fk_logistics_reconciliation_request_document"))
  private LogisticsDocument document;

  @Column(name = "request_reason", nullable = false, length = 500)
  private String requestReason;

  @Column(name = "requested_by_subject_id", nullable = false)
  private UUID requestedBySubjectId;

  @Column(name = "correlation_id", nullable = false)
  private UUID correlationId;

  @Column(name = "created_at", nullable = false)
  private OffsetDateTime createdAt;

  public static LogisticsReconciliationRequest create(
      LogisticsDocument document,
      String requestReason,
      UUID requestedBySubjectId,
      UUID correlationId,
      OffsetDateTime createdAt) {
    if (document == null || requestedBySubjectId == null || correlationId == null || createdAt == null) {
      throw new IllegalArgumentException("Reconciliation request ownership and timing are required");
    }
    String normalized = requestReason == null ? "" : requestReason.trim();
    if (normalized.isEmpty() || normalized.length() > 500) {
      throw new IllegalArgumentException("Reconciliation request reason is invalid");
    }
    LogisticsReconciliationRequest request = new LogisticsReconciliationRequest();
    request.document = document;
    request.requestReason = normalized;
    request.requestedBySubjectId = requestedBySubjectId;
    request.correlationId = correlationId;
    request.createdAt = createdAt;
    return request;
  }
}
