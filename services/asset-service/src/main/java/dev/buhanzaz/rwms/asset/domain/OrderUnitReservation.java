package dev.buhanzaz.rwms.asset.domain;

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

/**
 * JPA entity that persists order unit reservation in the asset-owned database.
 */
@Entity
@Table(name = "order_unit_reservation")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OrderUnitReservation {
  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Column(name = "id", nullable = false)
  private UUID id;

  @Version
  @Column(name = "version", nullable = false)
  private long version;

  @Column(name = "order_id", nullable = false)
  private UUID orderId;

  @Column(name = "rental_item_id", nullable = false)
  private UUID rentalItemId;

  @Column(name = "warehouse_id", nullable = false)
  private UUID warehouseId;

  @Column(name = "client_id")
  private UUID clientId;

  @Column(name = "tenant_snapshot", length = 512)
  private String tenantSnapshot;

  @Column(name = "draft_reservation_expires_at")
  private OffsetDateTime draftReservationExpiresAt;

  @Enumerated(EnumType.STRING)
  @Column(name = "state", nullable = false, length = 16)
  private OrderUnitReservationState state;

  @Column(name = "added_by_subject_id", nullable = false)
  private UUID addedBySubjectId;

  @Column(name = "added_by_role", nullable = false, length = 32)
  private String addedByRole;

  @Column(name = "released_by_subject_id")
  private UUID releasedBySubjectId;

  @Column(name = "released_by_role", length = 32)
  private String releasedByRole;

  @Column(name = "created_at", nullable = false)
  private OffsetDateTime createdAt;

  @Column(name = "released_at")
  private OffsetDateTime releasedAt;

  @Column(name = "updated_at", nullable = false)
  private OffsetDateTime updatedAt;

  public static OrderUnitReservation create(
      UUID orderId,
      UUID rentalItemId,
      UUID warehouseId,
      UUID actorSubjectId,
      String actorRole) {
    return createInternal(
        orderId, rentalItemId, warehouseId, null, null, null, actorSubjectId, actorRole);
  }

  public static OrderUnitReservation create(
      UUID orderId,
      UUID rentalItemId,
      UUID warehouseId,
      UUID clientId,
      String tenantSnapshot,
      OffsetDateTime draftReservationExpiresAt,
      UUID actorSubjectId,
      String actorRole) {
    return createInternal(
        orderId,
        rentalItemId,
        warehouseId,
        Objects.requireNonNull(clientId, "clientId"),
        requireTenantSnapshot(tenantSnapshot),
        draftReservationExpiresAt,
        actorSubjectId,
        actorRole);
  }

  private static OrderUnitReservation createInternal(
      UUID orderId,
      UUID rentalItemId,
      UUID warehouseId,
      UUID clientId,
      String tenantSnapshot,
      OffsetDateTime draftReservationExpiresAt,
      UUID actorSubjectId,
      String actorRole) {
    OrderUnitReservation reservation = new OrderUnitReservation();
    reservation.orderId = Objects.requireNonNull(orderId, "orderId");
    reservation.rentalItemId = Objects.requireNonNull(rentalItemId, "rentalItemId");
    reservation.warehouseId = Objects.requireNonNull(warehouseId, "warehouseId");
    reservation.clientId = clientId;
    reservation.tenantSnapshot = tenantSnapshot;
    reservation.draftReservationExpiresAt = draftReservationExpiresAt;
    reservation.state = OrderUnitReservationState.ACTIVE;
    reservation.addedBySubjectId = Objects.requireNonNull(actorSubjectId, "actorSubjectId");
    reservation.addedByRole = requireRole(actorRole);
    reservation.createdAt = now();
    reservation.updatedAt = reservation.createdAt;
    return reservation;
  }

  /** Copies the order/client/draft projection while assigning a replacement cabin and actor. */
  public static OrderUnitReservation replace(
      OrderUnitReservation previous,
      UUID replacementRentalItemId,
      UUID actorSubjectId,
      String actorRole) {
    Objects.requireNonNull(previous, "previous");
    return replace(
        previous,
        replacementRentalItemId,
        previous.warehouseId,
        actorSubjectId,
        actorRole);
  }

  /**
   * Creates the active side of one atomic replacement at the replacement cabin's physical source.
   * The previous reservation remains immutable evidence and may belong to a different warehouse.
   */
  public static OrderUnitReservation replace(
      OrderUnitReservation previous,
      UUID replacementRentalItemId,
      UUID replacementWarehouseId,
      UUID actorSubjectId,
      String actorRole) {
    Objects.requireNonNull(previous, "previous");
    if (!previous.isActive()) {
      throw new IllegalArgumentException("Only an active order reservation can be replaced");
    }
    return createInternal(
        previous.orderId,
        Objects.requireNonNull(replacementRentalItemId, "replacementRentalItemId"),
        Objects.requireNonNull(replacementWarehouseId, "replacementWarehouseId"),
        previous.clientId,
        previous.tenantSnapshot,
        previous.draftReservationExpiresAt,
        actorSubjectId,
        actorRole);
  }

  public boolean updateClientProjection(UUID nextClientId, String nextTenantSnapshot) {
    UUID requiredClientId = Objects.requireNonNull(nextClientId, "clientId");
    String requiredTenantSnapshot = requireTenantSnapshot(nextTenantSnapshot);
    if (Objects.equals(clientId, requiredClientId)
        && Objects.equals(tenantSnapshot, requiredTenantSnapshot)) {
      return false;
    }
    clientId = requiredClientId;
    tenantSnapshot = requiredTenantSnapshot;
    updatedAt = now();
    return true;
  }

  /** A saved booking clears the expiry; draft retries never prolong an existing hold. */
  public boolean synchronizeDraftReservationExpiry(OffsetDateTime nextExpiresAt) {
    if (nextExpiresAt == null) {
      if (draftReservationExpiresAt == null) return false;
      draftReservationExpiresAt = null;
      updatedAt = now();
      return true;
    }
    if (draftReservationExpiresAt != null) return false;
    draftReservationExpiresAt = nextExpiresAt;
    updatedAt = now();
    return true;
  }

  public boolean isDraftReservationExpiredAt(OffsetDateTime timestamp) {
    return state == OrderUnitReservationState.ACTIVE
        && draftReservationExpiresAt != null
        && !draftReservationExpiresAt.isAfter(Objects.requireNonNull(timestamp, "timestamp"));
  }

  public boolean release(UUID actorSubjectId, String actorRole) {
    if (state == OrderUnitReservationState.RELEASED) return false;
    state = OrderUnitReservationState.RELEASED;
    releasedBySubjectId = Objects.requireNonNull(actorSubjectId, "actorSubjectId");
    releasedByRole = "LOGISTICS_SERVICE".equals(actorRole) ? actorRole : requireRole(actorRole);
    releasedAt = now();
    updatedAt = releasedAt;
    return true;
  }

  public boolean isActive() {
    return state == OrderUnitReservationState.ACTIVE;
  }

  private static String requireRole(String value) {
    if (value == null
        || !java.util.Set.of(
                "SYSTEM_ADMIN",
                "WMS_ADMIN",
                "WAREHOUSE_MANAGER",
                "RENTAL_MANAGER",
                "CUSTOMER",
                "VIEWER")
            .contains(value)) {
      throw new IllegalArgumentException("actorRole is invalid");
    }
    return value;
  }

  private static String requireTenantSnapshot(String value) {
    String normalized = value == null ? "" : value.trim();
    if (normalized.isEmpty() || normalized.length() > 512) {
      throw new IllegalArgumentException("tenantSnapshot is invalid");
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
        && Objects.equals(id, ((OrderUnitReservation) other).id);
  }

  @Override
  public final int hashCode() {
    return this instanceof HibernateProxy proxy
        ? proxy.getHibernateLazyInitializer().getPersistentClass().hashCode()
        : getClass().hashCode();
  }
}
