package dev.buhanzaz.rwms.asset.administrative;

import dev.buhanzaz.rwms.asset.api.AdministrativeAssetCorrectionApiModels.AdministrativeCorrectionAssetKind;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.proxy.HibernateProxy;

/**
 * Immutable administrator correction evidence. The row intentionally keeps
 * the reason and evidence link service-local; outbound asset facts contain
 * only the resulting state transitions.
 */
@Entity
@Table(name = "administrative_asset_correction")
public class AdministrativeAssetCorrection {
  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Column(name = "id", nullable = false)
  private UUID id;

  @Version
  @Column(name = "version", nullable = false)
  private long version;

  @Enumerated(EnumType.STRING)
  @Column(name = "asset_kind", nullable = false, length = 16)
  private AdministrativeCorrectionAssetKind assetKind;

  @Column(name = "asset_id", nullable = false)
  private UUID assetId;

  @Column(name = "source_warehouse_id", nullable = false)
  private UUID sourceWarehouseId;

  @Column(name = "target_warehouse_id", nullable = false)
  private UUID targetWarehouseId;

  @Column(name = "quantity")
  private Long quantity;

  @Column(name = "reason", nullable = false, length = 2000)
  private String reason;

  @Column(name = "evidence_link", nullable = false, length = 2000)
  private String evidenceLink;

  @Column(name = "request_sha256", nullable = false, length = 64)
  private String requestSha256;

  @Column(name = "actor_subject_id", nullable = false)
  private UUID actorSubjectId;

  @Column(name = "idempotency_key", nullable = false)
  private UUID idempotencyKey;

  @Column(name = "applied_at", nullable = false)
  private OffsetDateTime appliedAt;

  @Column(name = "created_at", nullable = false)
  private OffsetDateTime createdAt;

  protected AdministrativeAssetCorrection() {}

  public static AdministrativeAssetCorrection create(
      AdministrativeCorrectionAssetKind assetKind,
      UUID assetId,
      UUID sourceWarehouseId,
      UUID targetWarehouseId,
      Long quantity,
      String reason,
      String evidenceLink,
      String requestSha256,
      UUID actorSubjectId,
      UUID idempotencyKey,
      OffsetDateTime appliedAt) {
    if (assetKind == null
        || assetId == null
        || sourceWarehouseId == null
        || targetWarehouseId == null
        || sourceWarehouseId.equals(targetWarehouseId)
        || actorSubjectId == null
        || idempotencyKey == null
        || appliedAt == null
        || requestSha256 == null
        || !requestSha256.matches("[0-9a-f]{64}")) {
      throw new IllegalArgumentException("Administrative correction identity is invalid");
    }
    if (assetKind == AdministrativeCorrectionAssetKind.CABIN && quantity != null) {
      throw new IllegalArgumentException("Cabin correction must not have an equipment quantity");
    }
    if (assetKind == AdministrativeCorrectionAssetKind.EQUIPMENT
        && (quantity == null || quantity < 1)) {
      throw new IllegalArgumentException("Equipment correction quantity must be positive");
    }
    AdministrativeAssetCorrection value = new AdministrativeAssetCorrection();
    value.assetKind = assetKind;
    value.assetId = assetId;
    value.sourceWarehouseId = sourceWarehouseId;
    value.targetWarehouseId = targetWarehouseId;
    value.quantity = quantity;
    value.reason = requiredText(reason, "reason");
    value.evidenceLink = requiredText(evidenceLink, "evidenceLink");
    value.requestSha256 = requestSha256;
    value.actorSubjectId = actorSubjectId;
    value.idempotencyKey = idempotencyKey;
    value.appliedAt = appliedAt;
    value.createdAt = appliedAt;
    return value;
  }

  @PrePersist
  void prePersist() {
    OffsetDateTime timestamp = now();
    if (appliedAt == null) {
      appliedAt = timestamp;
    }
    if (createdAt == null) {
      createdAt = appliedAt;
    }
  }

  public UUID getId() {
    return id;
  }

  public long getVersion() {
    return version;
  }

  public AdministrativeCorrectionAssetKind getAssetKind() {
    return assetKind;
  }

  public UUID getAssetId() {
    return assetId;
  }

  public UUID getSourceWarehouseId() {
    return sourceWarehouseId;
  }

  public UUID getTargetWarehouseId() {
    return targetWarehouseId;
  }

  public Long getQuantity() {
    return quantity;
  }

  public String getReason() {
    return reason;
  }

  public String getEvidenceLink() {
    return evidenceLink;
  }

  public String getRequestSha256() {
    return requestSha256;
  }

  public UUID getActorSubjectId() {
    return actorSubjectId;
  }

  public UUID getIdempotencyKey() {
    return idempotencyKey;
  }

  public OffsetDateTime getAppliedAt() {
    return appliedAt;
  }

  public OffsetDateTime getCreatedAt() {
    return createdAt;
  }

  @Override
  public final boolean equals(Object other) {
    if (this == other) {
      return true;
    }
    if (other == null) {
      return false;
    }
    Class<?> otherClass =
        other instanceof HibernateProxy proxy
            ? proxy.getHibernateLazyInitializer().getPersistentClass()
            : other.getClass();
    Class<?> thisClass =
        this instanceof HibernateProxy proxy
            ? proxy.getHibernateLazyInitializer().getPersistentClass()
            : getClass();
    return thisClass == otherClass
        && id != null
        && Objects.equals(id, ((AdministrativeAssetCorrection) other).id);
  }

  @Override
  public final int hashCode() {
    return this instanceof HibernateProxy proxy
        ? proxy.getHibernateLazyInitializer().getPersistentClass().hashCode()
        : getClass().hashCode();
  }

  private static String requiredText(String value, String name) {
    String normalized = value == null ? "" : value.trim();
    if (normalized.isEmpty() || normalized.length() > 2000) {
      throw new IllegalArgumentException(name + " is required and must be at most 2000 characters");
    }
    return normalized;
  }

  private static OffsetDateTime now() {
    return OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
  }
}
