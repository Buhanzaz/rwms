package dev.buhanzaz.rwms.warehouse.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;
import org.hibernate.proxy.HibernateProxy;

@Entity
@Table(
    name = "warehouse",
    uniqueConstraints =
        @UniqueConstraint(name = "uk_warehouse_normalized_name", columnNames = "normalized_name"))
public class Warehouse {
  private static final Pattern DISPLAY_NAME_WHITESPACE =
      Pattern.compile("\\s+", Pattern.UNICODE_CHARACTER_CLASS);

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Column(name = "id", nullable = false)
  private UUID id;

  @Version
  @Column(name = "version", nullable = false)
  private long version;

  @NotBlank
  @Column(name = "name", nullable = false, length = 255)
  private String name;

  @NotBlank
  @Column(name = "normalized_name", nullable = false, length = 255)
  private String normalizedName;

  @NotBlank
  @Column(name = "city", nullable = false, length = 255)
  private String city;

  @Column(name = "address", length = 1000)
  private String address;

  @NotBlank
  @Column(name = "time_zone", nullable = false, length = 64)
  private String timeZone;

  @Column(name = "active", nullable = false)
  private boolean active = true;

  @Column(name = "sort_order")
  private Integer sortOrder;

  @NotNull
  @Column(name = "created_at", nullable = false)
  private OffsetDateTime createdAt;

  @NotNull
  @Column(name = "updated_at", nullable = false)
  private OffsetDateTime updatedAt;

  protected Warehouse() {}

  public static Warehouse create(
      String name,
      String city,
      String address,
      ZoneId timeZone,
      Integer sortOrder) {
    Warehouse warehouse = new Warehouse();
    warehouse.assign(name, city, address, timeZone, true, sortOrder);
    return warehouse;
  }

  public Mutation replace(
      String name,
      String city,
      String address,
      ZoneId timeZone,
      boolean active,
      Integer sortOrder) {
    CanonicalName canonicalName = canonicalName(name);
    String normalizedCity = normalizeRequired(city, "city", 255);
    String normalizedAddress = normalizeOptional(address, 1000);
    String normalizedTimeZone = normalizeTimeZone(timeZone);
    validateSortOrder(sortOrder);
    boolean deactivating = this.active && !active;
    boolean changed =
        !Objects.equals(this.name, canonicalName.displayName())
            || !Objects.equals(this.normalizedName, canonicalName.normalizedName())
            || !Objects.equals(this.city, normalizedCity)
            || !Objects.equals(this.address, normalizedAddress)
            || !Objects.equals(this.timeZone, normalizedTimeZone)
            || this.active != active
            || !Objects.equals(this.sortOrder, sortOrder);
    if (!changed) return Mutation.NONE;
    this.name = canonicalName.displayName();
    this.normalizedName = canonicalName.normalizedName();
    this.city = normalizedCity;
    this.address = normalizedAddress;
    this.timeZone = normalizedTimeZone;
    this.active = active;
    this.sortOrder = sortOrder;
    return deactivating ? Mutation.DEACTIVATED : Mutation.CHANGED;
  }

  public boolean deactivate() {
    if (!active) return false;
    active = false;
    return true;
  }

  @PrePersist
  void beforeInsert() {
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    createdAt = now;
    updatedAt = now;
    normalizePersistedState();
  }

  @PreUpdate
  void beforeUpdate() {
    updatedAt = OffsetDateTime.now(ZoneOffset.UTC);
    normalizePersistedState();
  }

  private void assign(
      String name,
      String city,
      String address,
      ZoneId timeZone,
      boolean active,
      Integer sortOrder) {
    CanonicalName canonicalName = canonicalName(name);
    this.name = canonicalName.displayName();
    this.normalizedName = canonicalName.normalizedName();
    this.city = normalizeRequired(city, "city", 255);
    this.address = normalizeOptional(address, 1000);
    this.timeZone = normalizeTimeZone(timeZone);
    validateSortOrder(sortOrder);
    this.active = active;
    this.sortOrder = sortOrder;
  }

  private void normalizePersistedState() {
    CanonicalName canonicalName = canonicalName(name);
    name = canonicalName.displayName();
    normalizedName = canonicalName.normalizedName();
    city = normalizeRequired(city, "city", 255);
    address = normalizeOptional(address, 1000);
    timeZone = normalizeTimeZone(ZoneId.of(timeZone));
    validateSortOrder(sortOrder);
  }

  private static String normalizeTimeZone(ZoneId value) {
    if (value == null) throw new IllegalArgumentException("timeZone is required");
    String normalized = value.getId();
    if (normalized.length() > 64) throw new IllegalArgumentException("timeZone is too long");
    return normalized;
  }

  public static String normalizeName(String value) {
    return canonicalName(value).normalizedName();
  }

  private static CanonicalName canonicalName(String value) {
    if (value == null) throw new IllegalArgumentException("name is required");
    String displayName = DISPLAY_NAME_WHITESPACE.matcher(value).replaceAll(" ").trim();
    if (displayName.isEmpty()) throw new IllegalArgumentException("name is required");
    if (displayName.length() > 255) throw new IllegalArgumentException("name is too long");
    String normalizedName = displayName.toLowerCase(Locale.ROOT);
    if (normalizedName.length() > 255) {
      throw new IllegalArgumentException("name is too long after normalization");
    }
    return new CanonicalName(displayName, normalizedName);
  }

  private static String normalizeRequired(String value, String field, int maximumLength) {
    String normalized = normalizeOptional(value, maximumLength);
    if (normalized == null) throw new IllegalArgumentException(field + " is required");
    return normalized;
  }

  private static String normalizeOptional(String value, int maximumLength) {
    if (value == null) return null;
    String normalized = value.trim();
    if (normalized.isEmpty()) return null;
    if (normalized.length() > maximumLength) throw new IllegalArgumentException("value is too long");
    return normalized;
  }

  private static void validateSortOrder(Integer value) {
    if (value != null && value < 0) throw new IllegalArgumentException("sortOrder must not be negative");
  }

  public UUID getId() {
    return id;
  }

  public long getVersion() {
    return version;
  }

  public String getName() {
    return name;
  }

  public String getNormalizedName() {
    return normalizedName;
  }

  public String getCity() {
    return city;
  }

  public String getAddress() {
    return address;
  }

  public String getTimeZone() {
    return timeZone;
  }

  public boolean isActive() {
    return active;
  }

  public Integer getSortOrder() {
    return sortOrder;
  }

  public OffsetDateTime getCreatedAt() {
    return createdAt;
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
    if (thisClass != otherClass) return false;
    Warehouse warehouse = (Warehouse) other;
    return id != null && Objects.equals(id, warehouse.id);
  }

  @Override
  public final int hashCode() {
    return this instanceof HibernateProxy proxy
        ? proxy.getHibernateLazyInitializer().getPersistentClass().hashCode()
        : getClass().hashCode();
  }

  public enum Mutation {
    NONE,
    CHANGED,
    DEACTIVATED
  }

  private record CanonicalName(String displayName, String normalizedName) {}
}
