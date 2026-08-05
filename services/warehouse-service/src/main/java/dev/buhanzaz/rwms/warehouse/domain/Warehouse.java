package dev.buhanzaz.rwms.warehouse.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
import java.util.Set;
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
  private static final Set<String> CANONICAL_IANA_ZONE_IDS = ZoneId.getAvailableZoneIds();

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

  /**
   * A private domain revision for append-only timezone decisions. It deliberately is not exposed
   * as a separate public field: the aggregate @Version is the concurrency fence visible to callers.
   */
  @Column(name = "time_zone_revision", nullable = false)
  private long timeZoneRevision;

  @NotNull
  @Enumerated(EnumType.STRING)
  @Column(name = "lifecycle_state", nullable = false, length = 16)
  private WarehouseLifecycleState lifecycleState = WarehouseLifecycleState.ACTIVE;

  /** Forces aggregate-version increments for immutable lifecycle readiness confirmations. */
  @Column(name = "lifecycle_revision", nullable = false)
  private long lifecycleRevision;

  /**
   * Compatibility projection for pre-lifecycle consumers. It is true only while new incoming
   * operations are allowed and is never accepted as a lifecycle command.
   */
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
    warehouse.assign(name, city, address, timeZone, sortOrder);
    return warehouse;
  }

  public Mutation replace(String name, String city, String address, Integer sortOrder) {
    CanonicalName canonicalName = canonicalName(name);
    String normalizedCity = normalizeRequired(city, "city", 255);
    String normalizedAddress = normalizeOptional(address, 1000);
    validateSortOrder(sortOrder);
    boolean changed =
        !Objects.equals(this.name, canonicalName.displayName())
            || !Objects.equals(this.normalizedName, canonicalName.normalizedName())
            || !Objects.equals(this.city, normalizedCity)
            || !Objects.equals(this.address, normalizedAddress)
            || !Objects.equals(this.sortOrder, sortOrder);
    if (!changed) return Mutation.NONE;
    this.name = canonicalName.displayName();
    this.normalizedName = canonicalName.normalizedName();
    this.city = normalizedCity;
    this.address = normalizedAddress;
    this.sortOrder = sortOrder;
    return Mutation.CHANGED;
  }

  /**
   * Corrects the current timezone only before the warehouse has operated. Once operations exist,
   * the application layer must append an effective-dated history entry instead.
   */
  public boolean correctTimeZone(ZoneId value) {
    String normalized = normalizeTimeZone(value);
    if (Objects.equals(timeZone, normalized)) return false;
    timeZone = normalized;
    timeZoneRevision++;
    return true;
  }

  /** Forces an aggregate version increment for an append-only future timezone decision. */
  public void recordTimeZoneDecision() {
    timeZoneRevision++;
  }

  /** Starts the one-way drain. New incoming operations stop; outgoing operations remain valid. */
  public boolean startDraining() {
    if (lifecycleState != WarehouseLifecycleState.ACTIVE) return false;
    lifecycleState = WarehouseLifecycleState.DRAINING;
    active = false;
    lifecycleRevision++;
    return true;
  }

  /** Completes the one-way lifecycle after every resource owner has confirmed readiness. */
  public boolean completeInactivation() {
    if (lifecycleState != WarehouseLifecycleState.DRAINING) return false;
    lifecycleState = WarehouseLifecycleState.INACTIVE;
    active = false;
    lifecycleRevision++;
    return true;
  }

  /** Forces an aggregate version increment when immutable readiness evidence is appended. */
  public void recordLifecycleReadiness() {
    lifecycleRevision++;
  }

  public boolean allowsIncomingOperations() {
    return lifecycleState == WarehouseLifecycleState.ACTIVE;
  }

  public boolean allowsOutgoingOperations() {
    return lifecycleState != WarehouseLifecycleState.INACTIVE;
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
      Integer sortOrder) {
    CanonicalName canonicalName = canonicalName(name);
    this.name = canonicalName.displayName();
    this.normalizedName = canonicalName.normalizedName();
    this.city = normalizeRequired(city, "city", 255);
    this.address = normalizeOptional(address, 1000);
    this.timeZone = normalizeTimeZone(timeZone);
    validateSortOrder(sortOrder);
    this.lifecycleState = WarehouseLifecycleState.ACTIVE;
    this.lifecycleRevision = 0;
    this.active = true;
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
    if (lifecycleState == null) throw new IllegalArgumentException("lifecycleState is required");
    if (lifecycleRevision < 0) throw new IllegalArgumentException("lifecycleRevision must not be negative");
    active = lifecycleState == WarehouseLifecycleState.ACTIVE;
  }

  private static String normalizeTimeZone(ZoneId value) {
    if (value == null) throw new IllegalArgumentException("timeZone is required");
    String normalized = value.getId();
    if (!CANONICAL_IANA_ZONE_IDS.contains(normalized)) {
      throw new IllegalArgumentException("timeZone must be a canonical IANA identifier");
    }
    if (normalized.length() > 64) throw new IllegalArgumentException("timeZone is too long");
    return normalized;
  }

  public static ZoneId requireCanonicalTimeZone(String value) {
    if (value == null || value.isBlank()) throw new IllegalArgumentException("timeZone is required");
    ZoneId parsed = ZoneId.of(value.trim());
    normalizeTimeZone(parsed);
    return parsed;
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

  public long getTimeZoneRevision() {
    return timeZoneRevision;
  }

  public WarehouseLifecycleState getLifecycleState() {
    return lifecycleState;
  }

  public long getLifecycleRevision() {
    return lifecycleRevision;
  }

  public boolean isActive() {
    return lifecycleState == WarehouseLifecycleState.ACTIVE;
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
    CHANGED
  }

  private record CanonicalName(String displayName, String normalizedName) {}
}
