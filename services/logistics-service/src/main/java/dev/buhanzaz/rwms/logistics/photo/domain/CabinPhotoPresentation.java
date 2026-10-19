package dev.buhanzaz.rwms.logistics.photo.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.proxy.HibernateProxy;
import org.hibernate.type.SqlTypes;

/**
 * Immutable logistics-owned snapshot behind one non-expiring public cabin photo presentation.
 * Identity, warehouse/version fencing and creator-scoped idempotency stay private; only selected
 * presentation metadata is mapped to the anonymous response.
 */
@Entity
@Table(
    name = "cabin_photo_presentation",
    uniqueConstraints =
        @UniqueConstraint(
            name = "uk_cabin_photo_presentation_subject_key",
            columnNames = {"created_by_subject_id", "idempotency_key"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CabinPhotoPresentation {
  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Column(name = "id", nullable = false)
  private UUID id;

  @Version
  @Column(name = "version", nullable = false)
  private long version;

  @Column(name = "cabin_id", nullable = false)
  private UUID cabinId;

  @Column(name = "cabin_number", nullable = false, length = 128)
  private String cabinNumber;

  @Column(name = "warehouse_id", nullable = false)
  private UUID warehouseId;

  @Column(name = "rental_item_version", nullable = false)
  private long rentalItemVersion;

  @Column(name = "created_by_subject_id", nullable = false)
  private UUID createdBySubjectId;

  @Column(name = "idempotency_key", nullable = false)
  private UUID idempotencyKey;

  @Column(name = "request_sha256", nullable = false, length = 64)
  private String requestSha256;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "photo_snapshot_json", nullable = false, columnDefinition = "jsonb")
  private String photoSnapshotJson;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "metadata_snapshot_json", nullable = false, columnDefinition = "jsonb")
  private String metadataSnapshotJson;

  @Column(name = "created_at", nullable = false)
  private OffsetDateTime createdAt;

  /** Creates one immutable snapshot after all owner and dependency checks have succeeded. */
  public static CabinPhotoPresentation create(
      UUID cabinId,
      String cabinNumber,
      UUID warehouseId,
      long rentalItemVersion,
      UUID createdBySubjectId,
      UUID idempotencyKey,
      String requestSha256,
      String photoSnapshotJson,
      String metadataSnapshotJson,
      OffsetDateTime createdAt) {
    if (rentalItemVersion < 0) {
      throw new IllegalArgumentException("rentalItemVersion is invalid");
    }
    CabinPhotoPresentation presentation = new CabinPhotoPresentation();
    presentation.cabinId = Objects.requireNonNull(cabinId, "cabinId");
    presentation.cabinNumber = requireText(cabinNumber, 128, "cabinNumber");
    presentation.warehouseId = Objects.requireNonNull(warehouseId, "warehouseId");
    presentation.rentalItemVersion = rentalItemVersion;
    presentation.createdBySubjectId =
        Objects.requireNonNull(createdBySubjectId, "createdBySubjectId");
    presentation.idempotencyKey = Objects.requireNonNull(idempotencyKey, "idempotencyKey");
    presentation.requestSha256 = requireHash(requestSha256);
    presentation.photoSnapshotJson = requireSnapshot(photoSnapshotJson);
    presentation.metadataSnapshotJson = requireMetadataSnapshot(metadataSnapshotJson);
    presentation.createdAt = Objects.requireNonNull(createdAt, "createdAt");
    return presentation;
  }

  /** Returns whether a creator-scoped idempotency replay has exactly the original request. */
  public boolean matchesRequest(String candidateSha256) {
    return requestSha256.equals(candidateSha256);
  }

  private static String requireText(String value, int maximum, String field) {
    String normalized = value == null ? "" : value.trim();
    if (normalized.isEmpty() || normalized.length() > maximum) {
      throw new IllegalArgumentException(field + " is invalid");
    }
    return normalized;
  }

  private static String requireHash(String value) {
    if (value == null || !value.matches("[0-9a-f]{64}")) {
      throw new IllegalArgumentException("requestSha256 is invalid");
    }
    return value;
  }

  private static String requireSnapshot(String value) {
    String normalized = value == null ? "" : value.trim();
    if (!normalized.startsWith("[") || !normalized.endsWith("]") || normalized.length() > 128_000) {
      throw new IllegalArgumentException("photoSnapshotJson is invalid");
    }
    return normalized;
  }

  private static String requireMetadataSnapshot(String value) {
    String normalized = value == null ? "" : value.trim();
    if (!normalized.startsWith("{")
        || !normalized.endsWith("}")
        || normalized.length() > 32_000) {
      throw new IllegalArgumentException("metadataSnapshotJson is invalid");
    }
    return normalized;
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
    return thisClass == otherClass
        && id != null
        && Objects.equals(id, ((CabinPhotoPresentation) other).id);
  }

  @Override
  public final int hashCode() {
    return this instanceof HibernateProxy proxy
        ? proxy.getHibernateLazyInitializer().getPersistentClass().hashCode()
        : getClass().hashCode();
  }
}
