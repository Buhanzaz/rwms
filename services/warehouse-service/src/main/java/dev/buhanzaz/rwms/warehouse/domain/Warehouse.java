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
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.hibernate.proxy.HibernateProxy;

/** Owns warehouse identity, normalized metadata and one-way lifecycle/version transitions. */
@Entity
@Table(
    name = "warehouse",
    uniqueConstraints =
        @UniqueConstraint(
            name = "uk_warehouse_normalized_name",
            columnNames = "normalized_name"))
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

  @DecimalMin("-90.000000")
  @DecimalMax("90.000000")
  @Digits(integer = 2, fraction = 6)
  @Column(name = "latitude", precision = 8, scale = 6)
  private BigDecimal latitude;

  @DecimalMin("-180.000000")
  @DecimalMax("180.000000")
  @Digits(integer = 3, fraction = 6)
  @Column(name = "longitude", precision = 9, scale = 6)
  private BigDecimal longitude;

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

  /** Marks an object that must be exposed to the future manufacture management system. */
  @Column(name = "production", nullable = false)
  private boolean production;

  /** Marks an object that must be exposed as a primary RWMS warehouse. */
  @Column(name = "main_warehouse", nullable = false)
  private boolean mainWarehouse;

  /** Parent selected for a representative warehouse. */
  @Column(name = "representative_parent_warehouse_id")
  private UUID representativeParentWarehouseId;

  /** Forces aggregate-version increments when the owned support-link collection changes. */
  @Column(name = "support_link_revision", nullable = false)
  private long supportLinkRevision;

  @Column(name = "sort_order")
  private Integer sortOrder;

  @NotNull
  @Column(name = "created_at", nullable = false)
  private OffsetDateTime createdAt;

  @NotNull
  @Column(name = "updated_at", nullable = false)
  private OffsetDateTime updatedAt;

  protected Warehouse() {}

  /**
   * Creates a primary RWMS warehouse without optional coordinates.
   *
   * @param name display name
   * @param city human-readable city
   * @param address optional human-readable address
   * @param timeZone canonical IANA timezone
   * @param sortOrder optional non-negative directory ordering value
   * @return new ordinary warehouse aggregate
   */
  public static Warehouse create(
      String name,
      String city,
      String address,
      ZoneId timeZone,
      Integer sortOrder) {
    return create(name, city, address, null, null, timeZone, sortOrder, false, true, null);
  }

  /**
   * Creates an object with its independent production and primary-RWMS classifications.
   *
   * <p>A representative object has neither classification and references exactly one object that
   * has at least one of them. Parent eligibility is checked by the application service under its
   * lock; this aggregate enforces the local shape only.
   */
  public static Warehouse create(
      String name,
      String city,
      String address,
      BigDecimal latitude,
      BigDecimal longitude,
      ZoneId timeZone,
      Integer sortOrder,
      boolean production,
      boolean mainWarehouse,
      UUID representativeParentWarehouseId) {
    Warehouse warehouse = new Warehouse();
    warehouse.assign(
        name,
        city,
        address,
        latitude,
        longitude,
        timeZone,
        sortOrder,
        production,
        mainWarehouse,
        representativeParentWarehouseId);
    return warehouse;
  }

  /** Fully replaces mutable metadata and the independent object classifications. */
  public Mutation replace(
      String name,
      String city,
      String address,
      BigDecimal latitude,
      BigDecimal longitude,
      Integer sortOrder,
      boolean production,
      boolean mainWarehouse,
      UUID representativeParentWarehouseId) {
    CanonicalName canonicalName = canonicalName(name);
    String normalizedCity = normalizeRequired(city, "city", 255);
    String normalizedAddress = normalizeOptional(address, 1000);
    Coordinates coordinates = coordinates(latitude, longitude);
    validateSortOrder(sortOrder);
    validateClassification(production, mainWarehouse, representativeParentWarehouseId, id);
    boolean changed =
        !Objects.equals(this.name, canonicalName.displayName())
            || !Objects.equals(this.normalizedName, canonicalName.normalizedName())
            || !Objects.equals(this.city, normalizedCity)
            || !Objects.equals(this.address, normalizedAddress)
            || !Objects.equals(this.latitude, coordinates.latitude())
            || !Objects.equals(this.longitude, coordinates.longitude())
            || this.production != production
            || this.mainWarehouse != mainWarehouse
            || !Objects.equals(this.representativeParentWarehouseId, representativeParentWarehouseId)
            || !Objects.equals(this.sortOrder, sortOrder);
    if (!changed) return Mutation.NONE;
    this.name = canonicalName.displayName();
    this.normalizedName = canonicalName.normalizedName();
    this.city = normalizedCity;
    this.address = normalizedAddress;
    this.latitude = coordinates.latitude();
    this.longitude = coordinates.longitude();
    this.production = production;
    this.mainWarehouse = mainWarehouse;
    this.representativeParentWarehouseId = representativeParentWarehouseId;
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

  /** Records a changed support-link collection under this aggregate's version fence. */
  public void recordSupportLinkDecision() {
    supportLinkRevision++;
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
      BigDecimal latitude,
      BigDecimal longitude,
      ZoneId timeZone,
      Integer sortOrder,
      boolean production,
      boolean mainWarehouse,
      UUID representativeParentWarehouseId) {
    CanonicalName canonicalName = canonicalName(name);
    this.name = canonicalName.displayName();
    this.normalizedName = canonicalName.normalizedName();
    this.city = normalizeRequired(city, "city", 255);
    this.address = normalizeOptional(address, 1000);
    Coordinates coordinates = coordinates(latitude, longitude);
    this.latitude = coordinates.latitude();
    this.longitude = coordinates.longitude();
    this.timeZone = normalizeTimeZone(timeZone);
    validateSortOrder(sortOrder);
    this.lifecycleState = WarehouseLifecycleState.ACTIVE;
    this.lifecycleRevision = 0;
    this.active = true;
    validateClassification(production, mainWarehouse, representativeParentWarehouseId, id);
    this.production = production;
    this.mainWarehouse = mainWarehouse;
    this.representativeParentWarehouseId = representativeParentWarehouseId;
    this.sortOrder = sortOrder;
  }

  private void normalizePersistedState() {
    CanonicalName canonicalName = canonicalName(name);
    name = canonicalName.displayName();
    normalizedName = canonicalName.normalizedName();
    city = normalizeRequired(city, "city", 255);
    address = normalizeOptional(address, 1000);
    Coordinates coordinates = coordinates(latitude, longitude);
    latitude = coordinates.latitude();
    longitude = coordinates.longitude();
    timeZone = normalizeTimeZone(ZoneId.of(timeZone));
    validateSortOrder(sortOrder);
    if (lifecycleState == null) throw new IllegalArgumentException("lifecycleState is required");
    if (lifecycleRevision < 0) throw new IllegalArgumentException("lifecycleRevision must not be negative");
    if (supportLinkRevision < 0) {
      throw new IllegalArgumentException("supportLinkRevision must not be negative");
    }
    validateClassification(production, mainWarehouse, representativeParentWarehouseId, id);
    active = lifecycleState == WarehouseLifecycleState.ACTIVE;
  }

  private static void validateClassification(
      boolean production,
      boolean mainWarehouse,
      UUID representativeParentWarehouseId,
      UUID warehouseId) {
    if (representativeParentWarehouseId == null && !production && !mainWarehouse) {
      throw new IllegalArgumentException(
          "An object must be production, a main warehouse, or a representative");
    }
    if (representativeParentWarehouseId != null && (production || mainWarehouse)) {
      throw new IllegalArgumentException(
          "A representative object cannot also be production or a main warehouse");
    }
    if (warehouseId != null && warehouseId.equals(representativeParentWarehouseId)) {
      throw new IllegalArgumentException(
          "An object cannot be its own representative parent");
    }
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

  private static Coordinates coordinates(BigDecimal latitude, BigDecimal longitude) {
    if ((latitude == null) != (longitude == null)) {
      throw new IllegalArgumentException("latitude and longitude must be supplied together");
    }
    if (latitude == null) return new Coordinates(null, null);
    BigDecimal normalizedLatitude = normalizeCoordinate(latitude, "latitude", 90);
    BigDecimal normalizedLongitude = normalizeCoordinate(longitude, "longitude", 180);
    if (normalizedLatitude.signum() == 0 && normalizedLongitude.signum() == 0) {
      throw new IllegalArgumentException("latitude and longitude must not both be zero");
    }
    return new Coordinates(normalizedLatitude, normalizedLongitude);
  }

  private static BigDecimal normalizeCoordinate(
      BigDecimal value, String field, int absoluteMaximum) {
    BigDecimal normalized;
    try {
      normalized = value.setScale(6, RoundingMode.UNNECESSARY);
    } catch (ArithmeticException exception) {
      throw new IllegalArgumentException(field + " must have at most 6 decimal places", exception);
    }
    BigDecimal maximum = BigDecimal.valueOf(absoluteMaximum).setScale(6);
    if (normalized.abs().compareTo(maximum) > 0) {
      throw new IllegalArgumentException(field + " is outside the WGS84 range");
    }
    return normalized;
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

  public BigDecimal getLatitude() {
    return latitude;
  }

  public BigDecimal getLongitude() {
    return longitude;
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

  /** Returns whether this warehouse references a responsible parent as a representative. */
  public boolean isRepresentative() {
    return representativeParentWarehouseId != null;
  }

  /** Returns whether the object is exposed to the future manufacture management system. */
  public boolean isProduction() {
    return production;
  }

  /** Returns whether the object is exposed as a primary RWMS warehouse. */
  public boolean isMainWarehouse() {
    return mainWarehouse;
  }

  /** Returns the parent selected for a representative object. */
  public UUID getRepresentativeParentWarehouseId() {
    return representativeParentWarehouseId;
  }

  public long getSupportLinkRevision() {
    return supportLinkRevision;
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

  /** Canonical all-or-none coordinate pair that excludes the {@code 0,0} unset placeholder. */
  private record Coordinates(BigDecimal latitude, BigDecimal longitude) {}
}
