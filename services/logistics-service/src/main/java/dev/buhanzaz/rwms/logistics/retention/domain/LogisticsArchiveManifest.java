package dev.buhanzaz.rwms.logistics.retention.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.proxy.HibernateProxy;

/** Checksum manifest for an immutable encrypted object written to a private archive. */
@Entity
@Table(name = "logistics_archive_manifest")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LogisticsArchiveManifest {
  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Column(name = "id", nullable = false)
  private UUID id;

  @Version
  @Column(name = "version", nullable = false)
  private long version;

  @Enumerated(EnumType.STRING)
  @Column(name = "dataset", nullable = false, length = 32)
  private LogisticsRetentionDataset dataset;

  @Column(name = "period_start", nullable = false)
  private OffsetDateTime periodStart;

  @Column(name = "period_end", nullable = false)
  private OffsetDateTime periodEnd;

  @Column(name = "object_key", nullable = false, length = 1_000)
  private String objectKey;

  @Column(name = "sha256", nullable = false, length = 64)
  private String sha256;

  @Column(name = "row_count", nullable = false)
  private long rowCount;

  @Enumerated(EnumType.STRING)
  @Column(name = "state", nullable = false, length = 16)
  private LogisticsArchiveManifestState state;

  @Column(name = "created_by_subject_id", nullable = false)
  private UUID createdBySubjectId;

  @Column(name = "created_at", nullable = false)
  private OffsetDateTime createdAt;

  @Column(name = "verified_by_subject_id")
  private UUID verifiedBySubjectId;

  @Column(name = "verified_at")
  private OffsetDateTime verifiedAt;

  /** Records the immutable external archive identity after its bytes and checksum exist. */
  public static LogisticsArchiveManifest record(
      LogisticsRetentionDataset dataset,
      OffsetDateTime periodStart,
      OffsetDateTime periodEnd,
      String objectKey,
      String sha256,
      long rowCount,
      UUID actorSubjectId) {
    OffsetDateTime start = Objects.requireNonNull(periodStart, "periodStart");
    OffsetDateTime end = Objects.requireNonNull(periodEnd, "periodEnd");
    if (!start.isBefore(end) || rowCount < 0) {
      throw new IllegalArgumentException("Archive manifest range is invalid");
    }
    LogisticsArchiveManifest manifest = new LogisticsArchiveManifest();
    manifest.dataset = Objects.requireNonNull(dataset, "dataset");
    manifest.periodStart = start;
    manifest.periodEnd = end;
    manifest.objectKey = requiredText(objectKey, 1_000, "objectKey");
    manifest.sha256 = requiredHash(sha256);
    manifest.rowCount = rowCount;
    manifest.state = LogisticsArchiveManifestState.RECORDED;
    manifest.createdBySubjectId = Objects.requireNonNull(actorSubjectId, "actorSubjectId");
    manifest.createdAt = now();
    return manifest;
  }

  /** Records an independent checksum verification under the manifest version fence. */
  public void verify(long expectedVersion, UUID actorSubjectId) {
    if (version != expectedVersion)
      throw new IllegalStateException("Archive manifest version changed");
    if (state == LogisticsArchiveManifestState.VERIFIED) return;
    state = LogisticsArchiveManifestState.VERIFIED;
    verifiedBySubjectId = Objects.requireNonNull(actorSubjectId, "actorSubjectId");
    verifiedAt = now();
  }

  private static String requiredText(String value, int maximum, String field) {
    String normalized = value == null ? "" : value.trim();
    if (normalized.isEmpty() || normalized.length() > maximum) {
      throw new IllegalArgumentException(field + " is invalid");
    }
    return normalized;
  }

  private static String requiredHash(String value) {
    String normalized = value == null ? "" : value.trim();
    if (!normalized.matches("[0-9a-f]{64}")) {
      throw new IllegalArgumentException("sha256 is invalid");
    }
    return normalized;
  }

  private static OffsetDateTime now() {
    return OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
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
        && Objects.equals(id, ((LogisticsArchiveManifest) other).id);
  }

  @Override
  public final int hashCode() {
    return this instanceof HibernateProxy proxy
        ? proxy.getHibernateLazyInitializer().getPersistentClass().hashCode()
        : getClass().hashCode();
  }
}
