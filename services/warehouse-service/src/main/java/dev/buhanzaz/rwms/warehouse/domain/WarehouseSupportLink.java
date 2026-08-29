package dev.buhanzaz.rwms.warehouse.domain;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.hibernate.proxy.HibernateProxy;

/**
 * Directed warehouse-owned support edge from a resource-providing warehouse to a served warehouse.
 *
 * <p>The link is an explicit edge rather than a parent relation, so one served warehouse can have
 * multiple sources and one source can serve multiple warehouses.
 */
@Entity
@Table(
    name = "warehouse_support_link",
    uniqueConstraints =
        @UniqueConstraint(
            name = "uk_warehouse_support_link_direction",
            columnNames = {"support_warehouse_id", "served_warehouse_id"}))
public class WarehouseSupportLink {
  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Column(name = "id", nullable = false)
  private UUID id;

  @Version
  @Column(name = "version", nullable = false)
  private long version;

  @NotNull
  @Column(name = "support_warehouse_id", nullable = false)
  private UUID supportWarehouseId;

  @NotNull
  @Column(name = "served_warehouse_id", nullable = false)
  private UUID servedWarehouseId;

  @Column(name = "active", nullable = false)
  private boolean active;

  @Min(1)
  @Column(name = "priority", nullable = false)
  private int priority;

  @Column(name = "allow_drivers", nullable = false)
  private boolean allowDrivers;

  @Column(name = "allow_vehicles", nullable = false)
  private boolean allowVehicles;

  @Column(name = "allow_inventory", nullable = false)
  private boolean allowInventory;

  @Column(name = "allow_direct_fulfillment", nullable = false)
  private boolean allowDirectFulfillment;

  @Column(name = "allow_interwarehouse_transfer", nullable = false)
  private boolean allowInterwarehouseTransfer;

  @Column(name = "allow_contractor_fallback", nullable = false)
  private boolean allowContractorFallback;

  @ElementCollection(fetch = FetchType.LAZY)
  @CollectionTable(
      name = "warehouse_support_link_weekday",
      joinColumns = @JoinColumn(name = "support_link_id"))
  @Enumerated(EnumType.STRING)
  @Column(name = "weekday", nullable = false, length = 9)
  private Set<DayOfWeek> allowedWeekdays = new LinkedHashSet<>();

  @ElementCollection(fetch = FetchType.LAZY)
  @CollectionTable(
      name = "warehouse_support_link_allowed_date",
      joinColumns = @JoinColumn(name = "support_link_id"))
  @Column(name = "allowed_date", nullable = false)
  private Set<LocalDate> allowedDates = new LinkedHashSet<>();

  @ElementCollection(fetch = FetchType.LAZY)
  @CollectionTable(
      name = "warehouse_support_link_excluded_date",
      joinColumns = @JoinColumn(name = "support_link_id"))
  @Column(name = "excluded_date", nullable = false)
  private Set<LocalDate> excludedDates = new LinkedHashSet<>();

  @Column(name = "service_start")
  private LocalTime serviceStart;

  @Column(name = "service_end")
  private LocalTime serviceEnd;

  @NotNull
  @Column(name = "created_at", nullable = false)
  private OffsetDateTime createdAt;

  @NotNull
  @Column(name = "updated_at", nullable = false)
  private OffsetDateTime updatedAt;

  protected WarehouseSupportLink() {}

  /**
   * Creates one directed edge.
   *
   * @param supportWarehouseId warehouse providing resources
   * @param servedWarehouseId representative warehouse being served
   * @param definition edge policy
   * @return new support link
   */
  public static WarehouseSupportLink create(
      UUID supportWarehouseId,
      UUID servedWarehouseId,
      WarehouseSupportLinkDefinition definition) {
    validateDirection(supportWarehouseId, servedWarehouseId);
    WarehouseSupportLink link = new WarehouseSupportLink();
    link.supportWarehouseId = supportWarehouseId;
    link.servedWarehouseId = servedWarehouseId;
    link.assign(definition);
    return link;
  }

  /**
   * Replaces the policy while preserving the stable directed edge identity.
   *
   * @param definition replacement policy
   * @return whether persisted state changed
   */
  public boolean replace(WarehouseSupportLinkDefinition definition) {
    Objects.requireNonNull(definition, "definition is required");
    if (definition.equals(definition())) return false;
    assign(definition);
    return true;
  }

  /**
   * Tests the edge at a served-warehouse local date and time.
   *
   * @param date served-warehouse local date
   * @param time served-warehouse local time
   * @return whether this edge is currently permitted
   */
  public boolean allows(LocalDate date, LocalTime time) {
    return definition().allows(date, time);
  }

  /** Returns the immutable policy projection used by transport mappers and comparisons. */
  public WarehouseSupportLinkDefinition definition() {
    return new WarehouseSupportLinkDefinition(
        active,
        priority,
        allowDrivers,
        allowVehicles,
        allowInventory,
        allowDirectFulfillment,
        allowInterwarehouseTransfer,
        allowContractorFallback,
        allowedWeekdays,
        allowedDates,
        excludedDates,
        serviceStart,
        serviceEnd);
  }

  @PrePersist
  void beforeInsert() {
    validatePersistedState();
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    createdAt = now;
    updatedAt = now;
  }

  @PreUpdate
  void beforeUpdate() {
    validatePersistedState();
    updatedAt = OffsetDateTime.now(ZoneOffset.UTC);
  }

  private void assign(WarehouseSupportLinkDefinition definition) {
    Objects.requireNonNull(definition, "definition is required");
    active = definition.active();
    priority = definition.priority();
    allowDrivers = definition.allowDrivers();
    allowVehicles = definition.allowVehicles();
    allowInventory = definition.allowInventory();
    allowDirectFulfillment = definition.allowDirectFulfillment();
    allowInterwarehouseTransfer = definition.allowInterwarehouseTransfer();
    allowContractorFallback = definition.allowContractorFallback();
    allowedWeekdays = new LinkedHashSet<>(definition.allowedWeekdays());
    allowedDates = new LinkedHashSet<>(definition.allowedDates());
    excludedDates = new LinkedHashSet<>(definition.excludedDates());
    serviceStart = definition.serviceStart();
    serviceEnd = definition.serviceEnd();
  }

  private void validatePersistedState() {
    validateDirection(supportWarehouseId, servedWarehouseId);
    definition();
  }

  private static void validateDirection(UUID supportWarehouseId, UUID servedWarehouseId) {
    if (supportWarehouseId == null) {
      throw new IllegalArgumentException("supportWarehouseId is required");
    }
    if (servedWarehouseId == null) {
      throw new IllegalArgumentException("servedWarehouseId is required");
    }
    if (supportWarehouseId.equals(servedWarehouseId)) {
      throw new IllegalArgumentException("A warehouse cannot support itself");
    }
  }

  public UUID getId() {
    return id;
  }

  public long getVersion() {
    return version;
  }

  public UUID getSupportWarehouseId() {
    return supportWarehouseId;
  }

  public UUID getServedWarehouseId() {
    return servedWarehouseId;
  }

  public boolean isActive() {
    return active;
  }

  public int getPriority() {
    return priority;
  }

  public boolean isAllowDrivers() {
    return allowDrivers;
  }

  public boolean isAllowVehicles() {
    return allowVehicles;
  }

  public boolean isAllowInventory() {
    return allowInventory;
  }

  public boolean isAllowDirectFulfillment() {
    return allowDirectFulfillment;
  }

  public boolean isAllowInterwarehouseTransfer() {
    return allowInterwarehouseTransfer;
  }

  public boolean isAllowContractorFallback() {
    return allowContractorFallback;
  }

  public Set<DayOfWeek> getAllowedWeekdays() {
    return Set.copyOf(allowedWeekdays);
  }

  public Set<LocalDate> getAllowedDates() {
    return Set.copyOf(allowedDates);
  }

  public Set<LocalDate> getExcludedDates() {
    return Set.copyOf(excludedDates);
  }

  public LocalTime getServiceStart() {
    return serviceStart;
  }

  public LocalTime getServiceEnd() {
    return serviceEnd;
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
    WarehouseSupportLink link = (WarehouseSupportLink) other;
    return id != null && Objects.equals(id, link.id);
  }

  @Override
  public final int hashCode() {
    return this instanceof HibernateProxy proxy
        ? proxy.getHibernateLazyInitializer().getPersistentClass().hashCode()
        : getClass().hashCode();
  }
}
