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
import java.time.OffsetDateTime;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** Visible audit record for an ambiguous external effect. */
@Entity
@Table(name = "logistics_reconciliation")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LogisticsReconciliation {
  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Column(name = "id", nullable = false)
  private UUID id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(
      name = "document_id",
      nullable = false,
      foreignKey = @ForeignKey(name = "fk_logistics_reconciliation_document"))
  private LogisticsDocument document;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(
      name = "line_id",
      foreignKey = @ForeignKey(name = "fk_logistics_reconciliation_line"))
  private LogisticsDocumentLine line;

  @Enumerated(EnumType.STRING)
  @Column(name = "state", nullable = false, length = 16)
  private LogisticsReconciliationState state;

  @Column(name = "reason_code", nullable = false, length = 64)
  private String reasonCode;

  @Column(name = "opened_at", nullable = false)
  private OffsetDateTime openedAt;

  @Column(name = "resolved_at")
  private OffsetDateTime resolvedAt;

  @Column(name = "resolution_reason", length = 500)
  private String resolutionReason;

  @Column(name = "resolved_by_subject_id")
  private UUID resolvedBySubjectId;

  public static LogisticsReconciliation open(
      LogisticsDocument document,
      LogisticsDocumentLine line,
      String reasonCode,
      OffsetDateTime openedAt) {
    if (document == null || openedAt == null) {
      throw new IllegalArgumentException("Reconciliation document and timing are required");
    }
    if (reasonCode == null || !reasonCode.matches("[A-Z][A-Z0-9_]{0,63}")) {
      throw new IllegalArgumentException("reasonCode is invalid");
    }
    LogisticsReconciliation reconciliation = new LogisticsReconciliation();
    reconciliation.document = document;
    reconciliation.line = line;
    reconciliation.state = LogisticsReconciliationState.OPEN;
    reconciliation.reasonCode = reasonCode;
    reconciliation.openedAt = openedAt;
    return reconciliation;
  }
}
