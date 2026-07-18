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
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import java.time.OffsetDateTime;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Logistics keeps only an opaque, current-generation media reference. Media
 * ownership and readiness remain verified by media-service.
 */
@Entity
@Table(
    name = "logistics_media_reference",
    uniqueConstraints =
        @UniqueConstraint(
            name = "uk_logistics_media_reference",
            columnNames = {"line_id", "media_id", "purpose"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LogisticsMediaReference {
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
      foreignKey = @ForeignKey(name = "fk_logistics_media_reference_document"))
  private LogisticsDocument document;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(
      name = "line_id",
      nullable = false,
      foreignKey = @ForeignKey(name = "fk_logistics_media_reference_line"))
  private LogisticsDocumentLine line;

  @Column(name = "media_id", nullable = false)
  private UUID mediaId;

  @Column(name = "generation")
  private Long generation;

  @Enumerated(EnumType.STRING)
  @Column(name = "purpose", nullable = false, length = 32)
  private LogisticsMediaPurpose purpose;

  @Enumerated(EnumType.STRING)
  @Column(name = "readiness", nullable = false, length = 24)
  private LogisticsMediaReadiness readiness;

  @Column(name = "owner_verified_at")
  private OffsetDateTime ownerVerifiedAt;

  @Column(name = "created_at", nullable = false)
  private OffsetDateTime createdAt;

  public static LogisticsMediaReference pending(
      LogisticsDocument document,
      LogisticsDocumentLine line,
      UUID mediaId,
      long generation,
      LogisticsMediaPurpose purpose,
      OffsetDateTime createdAt) {
    if (document == null || line == null || mediaId == null || purpose == null || createdAt == null) {
      throw new IllegalArgumentException("Media reference ownership and timing are required");
    }
    if (generation < 1) throw new IllegalArgumentException("Media generation must be positive");
    LogisticsMediaReference reference = new LogisticsMediaReference();
    reference.document = document;
    reference.line = line;
    reference.mediaId = mediaId;
    reference.generation = generation;
    reference.purpose = purpose;
    reference.readiness = LogisticsMediaReadiness.PENDING;
    reference.createdAt = createdAt;
    return reference;
  }

  public void ready(OffsetDateTime verifiedAt) {
    if (verifiedAt == null) throw new IllegalArgumentException("verifiedAt is required");
    if (readiness == LogisticsMediaReadiness.REJECTED
        || readiness == LogisticsMediaReadiness.RECONCILIATION_REQUIRED) {
      throw new IllegalStateException("Rejected media cannot become ready");
    }
    readiness = LogisticsMediaReadiness.READY;
    ownerVerifiedAt = verifiedAt;
  }

  public void reject() {
    if (readiness == LogisticsMediaReadiness.READY) {
      throw new IllegalStateException("Verified media cannot be rejected locally");
    }
    readiness = LogisticsMediaReadiness.REJECTED;
  }

  public void requireReconciliation() {
    if (readiness == LogisticsMediaReadiness.READY) {
      throw new IllegalStateException("Verified media cannot require reconciliation");
    }
    readiness = LogisticsMediaReadiness.RECONCILIATION_REQUIRED;
  }
}
