package dev.buhanzaz.rwms.asset.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.proxy.HibernateProxy;
import org.hibernate.type.SqlTypes;

/** One Flyway-created global palette; colors never alter cabin lifecycle semantics. */
@Entity
@Table(name = "cabin_status_colors")
public class CabinStatusColors {
  public static final UUID SINGLETON_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
  private static final Set<String> STATUSES =
      Arrays.stream(RentalItemStatus.values())
          .map(Enum::name)
          .collect(Collectors.toUnmodifiableSet());

  @Id
  @Column(name = "id", nullable = false)
  private UUID id;

  @Version
  @Column(name = "version", nullable = false)
  private long version;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "colors", nullable = false, columnDefinition = "jsonb")
  private Map<String, String> colors;

  @Column(name = "updated_at", nullable = false)
  private OffsetDateTime updatedAt;

  protected CabinStatusColors() {}

  /**
   * Replaces the complete palette atomically, rejecting missing/unknown states and non-hex values.
   */
  public boolean replace(Map<String, String> replacement) {
    if (replacement == null || !replacement.keySet().equals(STATUSES)) {
      throw new IllegalArgumentException("A color is required for every canonical cabin status");
    }
    Map<String, String> normalized = new LinkedHashMap<>();
    for (RentalItemStatus status : RentalItemStatus.values()) {
      String color = replacement.get(status.name());
      if (color == null || !color.matches("^#[0-9a-fA-F]{6}$")) {
        throw new IllegalArgumentException("Cabin status color must use #RRGGBB");
      }
      normalized.put(status.name(), color.toUpperCase(Locale.ROOT));
    }
    if (normalized.equals(colors)) return false;
    colors = normalized;
    return true;
  }

  @PreUpdate
  void preUpdate() {
    updatedAt = OffsetDateTime.now(ZoneOffset.UTC);
  }

  public UUID getId() {
    return id;
  }

  public long getVersion() {
    return version;
  }

  public Map<String, String> getColors() {
    return Map.copyOf(colors);
  }

  public OffsetDateTime getUpdatedAt() {
    return updatedAt;
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
        && getId() != null
        && Objects.equals(getId(), ((CabinStatusColors) other).getId());
  }

  @Override
  public final int hashCode() {
    return this instanceof HibernateProxy proxy
        ? proxy.getHibernateLazyInitializer().getPersistentClass().hashCode()
        : getClass().hashCode();
  }
}
