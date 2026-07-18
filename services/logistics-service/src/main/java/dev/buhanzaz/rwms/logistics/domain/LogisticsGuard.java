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
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Local record of an asset-owned operation lease. An ACTIVE row is written only
 * after asset-service has confirmed an active lease and fencing token.
 */
@Entity
@Table(name = "logistics_guard")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LogisticsGuard {
  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Column(name = "id", nullable = false)
  private UUID id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(
      name = "document_id",
      nullable = false,
      foreignKey = @ForeignKey(name = "fk_logistics_guard_document"))
  private LogisticsDocument document;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(
      name = "line_id",
      nullable = false,
      foreignKey = @ForeignKey(name = "fk_logistics_guard_line"))
  private LogisticsDocumentLine line;

  @Column(name = "asset_id", nullable = false)
  private UUID assetId;

  @Enumerated(EnumType.STRING)
  @Column(name = "guard_state", nullable = false, length = 32)
  private LogisticsGuardState guardState;

  @Column(name = "lease_id")
  private UUID leaseId;

  @Column(name = "lease_version")
  private Long leaseVersion;

  @Column(name = "fence_token")
  private Long fenceToken;

  @Column(name = "observed_asset_version")
  private Long observedAssetVersion;

  @Column(name = "acquired_at")
  private OffsetDateTime acquiredAt;

  @Column(name = "released_at")
  private OffsetDateTime releasedAt;

  @Column(name = "created_at", nullable = false)
  private OffsetDateTime createdAt;

  @Column(name = "updated_at", nullable = false)
  private OffsetDateTime updatedAt;

  public static LogisticsGuard active(
      LogisticsDocument document,
      LogisticsDocumentLine line,
      UUID leaseId,
      long leaseVersion,
      long fenceToken,
      long observedAssetVersion,
      OffsetDateTime acquiredAt) {
    if (document == null || line == null || leaseId == null || acquiredAt == null) {
      throw new IllegalArgumentException("Guard ownership, lease and timing are required");
    }
    if (leaseVersion < 0 || fenceToken < 1 || observedAssetVersion < 0) {
      throw new IllegalArgumentException("Guard fence and observed version are invalid");
    }
    LogisticsGuard guard = new LogisticsGuard();
    guard.document = document;
    guard.line = line;
    guard.assetId = line.getAssetId();
    guard.guardState = LogisticsGuardState.ACTIVE;
    guard.leaseId = leaseId;
    guard.leaseVersion = leaseVersion;
    guard.fenceToken = fenceToken;
    guard.observedAssetVersion = observedAssetVersion;
    guard.acquiredAt = acquiredAt;
    return guard;
  }

  public void conflict() {
    if (guardState == LogisticsGuardState.RELEASED) {
      throw new IllegalStateException("Released guard cannot become conflicted");
    }
    guardState = LogisticsGuardState.CONFLICT;
  }

  public void recordObservedAssetVersion(long version) {
    if (guardState != LogisticsGuardState.ACTIVE || version < 0) {
      throw new IllegalStateException("Only an active guard can record an asset version");
    }
    observedAssetVersion = version;
  }

  public void requireReconciliation() {
    if (guardState == LogisticsGuardState.RELEASED) {
      throw new IllegalStateException("Released guard cannot require reconciliation");
    }
    guardState = LogisticsGuardState.RECONCILIATION_REQUIRED;
  }

  public void release() {
    if (guardState != LogisticsGuardState.ACTIVE) {
      throw new IllegalStateException("Only an active guard can be released");
    }
    guardState = LogisticsGuardState.RELEASED;
    releasedAt = currentTime();
  }

  @PrePersist
  void beforeInsert() {
    OffsetDateTime now = currentTime();
    createdAt = now;
    updatedAt = now;
  }

  @PreUpdate
  void beforeUpdate() {
    updatedAt = currentTime();
  }

  private static OffsetDateTime currentTime() {
    return OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
  }
}
