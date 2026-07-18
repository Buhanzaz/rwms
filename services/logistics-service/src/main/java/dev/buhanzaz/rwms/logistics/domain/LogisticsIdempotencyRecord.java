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
import jakarta.persistence.UniqueConstraint;
import java.sql.Types;
import java.time.OffsetDateTime;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;

@Entity
@Table(
    name = "logistics_idempotency_record",
    uniqueConstraints =
        @UniqueConstraint(
            name = "uk_logistics_idempotency_subject_operation_key",
            columnNames = {"subject_id", "operation_name", "idempotency_key"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LogisticsIdempotencyRecord {
  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Column(name = "id", nullable = false)
  private UUID id;

  @Column(name = "subject_id", nullable = false)
  private UUID subjectId;

  @Column(name = "operation_name", nullable = false, length = 64)
  private String operationName;

  @Column(name = "idempotency_key", nullable = false)
  private UUID idempotencyKey;

  @JdbcTypeCode(Types.CHAR)
  @Column(name = "request_sha256", nullable = false, length = 64)
  private String requestSha256;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(
      name = "document_id",
      nullable = false,
      foreignKey = @ForeignKey(name = "fk_logistics_idempotency_document"))
  private LogisticsDocument document;

  @Column(name = "response_document_version", nullable = false)
  private long responseDocumentVersion;

  @Column(name = "created_at", nullable = false)
  private OffsetDateTime createdAt;

  @Column(name = "expires_at", nullable = false)
  private OffsetDateTime expiresAt;

  public static LogisticsIdempotencyRecord create(
      UUID subjectId,
      String operationName,
      UUID idempotencyKey,
      String requestSha256,
      LogisticsDocument document,
      OffsetDateTime createdAt,
      OffsetDateTime expiresAt) {
    if (subjectId == null || idempotencyKey == null || document == null) {
      throw new IllegalArgumentException("Idempotency ownership fields are required");
    }
    if (operationName == null
        || !operationName.matches(
            "(CREATE_(RETURN|SHIPMENT|TRANSFER)|REGISTER_RETURN|ACCEPT_RETURN|REQUEST_RETURN_ESTIMATE|PLAN_SHIPMENT|CONFIRM_SHIPMENT|CANCEL_SHIPMENT|DEPART_TRANSFER_LINE|ARRIVE_TRANSFER_LINE|CANCEL_TRANSFER|RECONCILE_DOCUMENT)")) {
      throw new IllegalArgumentException("Unsupported idempotency operation");
    }
    if (requestSha256 == null || !requestSha256.matches("[0-9a-f]{64}")) {
      throw new IllegalArgumentException("requestSha256 must be a SHA-256 digest");
    }
    if (createdAt == null || expiresAt == null || !expiresAt.isAfter(createdAt)) {
      throw new IllegalArgumentException("Idempotency expiry must be after creation");
    }
    LogisticsIdempotencyRecord record = new LogisticsIdempotencyRecord();
    record.subjectId = subjectId;
    record.operationName = operationName;
    record.idempotencyKey = idempotencyKey;
    record.requestSha256 = requestSha256;
    record.document = document;
    record.responseDocumentVersion = document.getVersion();
    record.createdAt = createdAt;
    record.expiresAt = expiresAt;
    return record;
  }

  public boolean matches(String requestSha256) {
    return this.requestSha256.equals(requestSha256);
  }
}
