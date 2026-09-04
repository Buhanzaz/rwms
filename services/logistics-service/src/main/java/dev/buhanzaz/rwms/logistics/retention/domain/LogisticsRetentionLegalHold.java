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

/** Durable deletion fence for a complete retention dataset or one bounded scope key. */
@Entity
@Table(name = "logistics_retention_legal_hold")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LogisticsRetentionLegalHold {
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

  @Column(name = "scope_key", length = 256)
  private String scopeKey;

  @Column(name = "reason", nullable = false, length = 2_000)
  private String reason;

  @Column(name = "placed_by_subject_id", nullable = false)
  private UUID placedBySubjectId;

  @Column(name = "placed_at", nullable = false)
  private OffsetDateTime placedAt;

  @Column(name = "released_by_subject_id")
  private UUID releasedBySubjectId;

  @Column(name = "released_at")
  private OffsetDateTime releasedAt;

  @Column(name = "release_reason", length = 2_000)
  private String releaseReason;

  /** Creates an active legal hold; a null scope key fences the complete dataset. */
  public static LogisticsRetentionLegalHold place(
      LogisticsRetentionDataset dataset, String scopeKey, String reason, UUID actorSubjectId) {
    LogisticsRetentionLegalHold hold = new LogisticsRetentionLegalHold();
    hold.dataset = Objects.requireNonNull(dataset, "dataset");
    hold.scopeKey = optionalText(scopeKey, 256, "scopeKey");
    hold.reason = requiredText(reason, 2_000, "reason");
    hold.placedBySubjectId = Objects.requireNonNull(actorSubjectId, "actorSubjectId");
    hold.placedAt = now();
    return hold;
  }

  /** Releases the fence while preserving who made both decisions and why. */
  public void release(long expectedVersion, UUID actorSubjectId, String reason) {
    if (version != expectedVersion) throw new IllegalStateException("Legal hold version changed");
    if (releasedAt != null) throw new IllegalStateException("Legal hold is already released");
    releasedBySubjectId = Objects.requireNonNull(actorSubjectId, "actorSubjectId");
    releaseReason = requiredText(reason, 2_000, "releaseReason");
    releasedAt = now();
  }

  private static String requiredText(String value, int maximum, String field) {
    String normalized = value == null ? "" : value.trim();
    if (normalized.isEmpty() || normalized.length() > maximum) {
      throw new IllegalArgumentException(field + " is invalid");
    }
    return normalized;
  }

  private static String optionalText(String value, int maximum, String field) {
    if (value == null) return null;
    String normalized = value.trim();
    if (normalized.isEmpty()) return null;
    if (normalized.length() > maximum) throw new IllegalArgumentException(field + " is invalid");
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
        && Objects.equals(id, ((LogisticsRetentionLegalHold) other).id);
  }

  @Override
  public final int hashCode() {
    return this instanceof HibernateProxy proxy
        ? proxy.getHibernateLazyInitializer().getPersistentClass().hashCode()
        : getClass().hashCode();
  }
}
