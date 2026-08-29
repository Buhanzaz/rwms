package dev.buhanzaz.rwms.logistics.customer.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
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
 * Persisted server-calculated delivery offer and its hold/confirmation fence. Confirmed rows are
 * real workload and never depend on a planner container identity.
 */
@Entity
@Table(name = "customer_delivery_slot")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CustomerDeliverySlot {
  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Column(name = "id", nullable = false)
  private UUID id;

  @Version
  @Column(name = "version", nullable = false)
  private long version;

  @Column(name = "customer_subject_id", nullable = false)
  private UUID customerSubjectId;

  @Column(name = "inquiry_id", nullable = false)
  private UUID inquiryId;

  @Column(name = "warehouse_id", nullable = false)
  private UUID warehouseId;

  @Column(name = "delivery_date", nullable = false)
  private LocalDate deliveryDate;

  @Enumerated(EnumType.STRING)
  @Column(name = "slot_kind", nullable = false, length = 32)
  private CustomerDeliverySlotKind kind;

  @Column(name = "window_start", nullable = false)
  private LocalTime windowStart;

  @Column(name = "window_end", nullable = false)
  private LocalTime windowEnd;

  @Column(name = "delivery_address", nullable = false, length = 1_000)
  private String deliveryAddress;

  @Column(name = "latitude", nullable = false, precision = 8, scale = 6)
  private BigDecimal latitude;

  @Column(name = "longitude", nullable = false, precision = 9, scale = 6)
  private BigDecimal longitude;

  @Column(name = "cabin_count", nullable = false)
  private int cabinCount;

  @Column(name = "one_way_travel_seconds", nullable = false)
  private long oneWayTravelSeconds;

  @Column(name = "travel_zone_hours", nullable = false)
  private int travelZoneHours;

  @Column(name = "capacity_remaining", nullable = false)
  private int capacityRemaining;

  @Column(name = "site_cabin_capacity", nullable = false)
  private int siteCabinCapacity;

  @Column(name = "delivery_price_rubles")
  private Long deliveryPriceRubles;

  @Column(name = "price_zone_id")
  private UUID priceZoneId;

  @Column(name = "price_isochrone_minutes")
  private Integer priceIsochroneMinutes;

  @Column(name = "road_route_confirmed", nullable = false)
  private boolean roadRouteConfirmed;

  @Column(name = "private_site_access_confirmed", nullable = false)
  private boolean privateSiteAccessConfirmed;

  @Column(name = "failed_trip_charge_acknowledged", nullable = false)
  private boolean failedTripChargeAcknowledged;

  @Column(name = "route_height_meters", nullable = false)
  private double routeHeightMeters;

  @Column(name = "route_width_meters", nullable = false)
  private double routeWidthMeters;

  @Column(name = "route_length_meters", nullable = false)
  private double routeLengthMeters;

  @Column(name = "route_weight_tons", nullable = false)
  private double routeWeightTons;

  @Column(name = "route_axle_load_tons", nullable = false)
  private double routeAxleLoadTons;

  @Column(name = "route_axle_count", nullable = false)
  private int routeAxleCount;

  @Enumerated(EnumType.STRING)
  @Column(name = "state", nullable = false, length = 32)
  private CustomerDeliverySlotState state;

  @Column(name = "expires_at", nullable = false)
  private OffsetDateTime expiresAt;

  @Column(name = "booking_id")
  private UUID bookingId;

  @Column(name = "order_id")
  private UUID orderId;

  @Column(name = "created_at", nullable = false)
  private OffsetDateTime createdAt;

  @Column(name = "updated_at", nullable = false)
  private OffsetDateTime updatedAt;

  /** Creates a short-lived offer after route-capacity validation, before customer attestations. */
  public static CustomerDeliverySlot offer(
      UUID customerSubjectId,
      UUID inquiryId,
      UUID warehouseId,
      LocalDate deliveryDate,
      CustomerDeliverySlotKind kind,
      LocalTime windowStart,
      LocalTime windowEnd,
      String deliveryAddress,
      BigDecimal latitude,
      BigDecimal longitude,
      int cabinCount,
      long oneWayTravelSeconds,
      int travelZoneHours,
      int capacityRemaining,
      int siteCabinCapacity,
      Long deliveryPriceRubles,
      UUID priceZoneId,
      Integer priceIsochroneMinutes,
      boolean privateSiteAccessConfirmed,
      boolean failedTripChargeAcknowledged,
      double routeHeightMeters,
      double routeWidthMeters,
      double routeLengthMeters,
      double routeWeightTons,
      double routeAxleLoadTons,
      int routeAxleCount,
      OffsetDateTime expiresAt) {
    CustomerDeliverySlot slot = new CustomerDeliverySlot();
    slot.customerSubjectId = Objects.requireNonNull(customerSubjectId, "customerSubjectId");
    slot.inquiryId = Objects.requireNonNull(inquiryId, "inquiryId");
    slot.warehouseId = Objects.requireNonNull(warehouseId, "warehouseId");
    slot.deliveryDate = Objects.requireNonNull(deliveryDate, "deliveryDate");
    slot.kind = Objects.requireNonNull(kind, "kind");
    slot.windowStart = Objects.requireNonNull(windowStart, "windowStart");
    slot.windowEnd = Objects.requireNonNull(windowEnd, "windowEnd");
    if (!windowStart.isBefore(windowEnd)) throw new IllegalArgumentException("slot window is invalid");
    slot.deliveryAddress = required(deliveryAddress, 1_000, "deliveryAddress");
    slot.latitude = Objects.requireNonNull(latitude, "latitude");
    slot.longitude = Objects.requireNonNull(longitude, "longitude");
    if (cabinCount < 1
        || oneWayTravelSeconds < 0
        || travelZoneHours < 1
        || capacityRemaining < 0
        || siteCabinCapacity < 1
        || siteCabinCapacity > 2
        || deliveryPriceRubles == null
        || deliveryPriceRubles < 0
        || ((priceZoneId == null) == (priceIsochroneMinutes == null))
        || (priceIsochroneMinutes != null
            && priceIsochroneMinutes != 60
            && priceIsochroneMinutes != 120
            && priceIsochroneMinutes != 180
            && priceIsochroneMinutes != 240)
        || routeHeightMeters <= 0
        || routeWidthMeters <= 0
        || routeLengthMeters <= 0
        || routeWeightTons <= 0
        || routeAxleLoadTons <= 0
        || routeAxleCount < 2) {
      throw new IllegalArgumentException("slot capacity facts are invalid");
    }
    slot.cabinCount = cabinCount;
    slot.oneWayTravelSeconds = oneWayTravelSeconds;
    slot.travelZoneHours = travelZoneHours;
    slot.capacityRemaining = capacityRemaining;
    slot.siteCabinCapacity = siteCabinCapacity;
    slot.deliveryPriceRubles = deliveryPriceRubles;
    slot.priceZoneId = priceZoneId;
    slot.priceIsochroneMinutes = priceIsochroneMinutes;
    slot.roadRouteConfirmed = true;
    slot.privateSiteAccessConfirmed = privateSiteAccessConfirmed;
    slot.failedTripChargeAcknowledged = failedTripChargeAcknowledged;
    slot.routeHeightMeters = routeHeightMeters;
    slot.routeWidthMeters = routeWidthMeters;
    slot.routeLengthMeters = routeLengthMeters;
    slot.routeWeightTons = routeWeightTons;
    slot.routeAxleLoadTons = routeAxleLoadTons;
    slot.routeAxleCount = routeAxleCount;
    slot.state = CustomerDeliverySlotState.OFFERED;
    slot.expiresAt = Objects.requireNonNull(expiresAt, "expiresAt");
    slot.createdAt = now();
    slot.updatedAt = slot.createdAt;
    return slot;
  }

  /**
   * Preserves internal callers that predate explicit ordinary isochrone pricing. A former
   * unpriced offer receives the canonical default tier while an existing special-zone quote keeps
   * its zone source.
   */
  public static CustomerDeliverySlot offer(
      UUID customerSubjectId,
      UUID inquiryId,
      UUID warehouseId,
      LocalDate deliveryDate,
      CustomerDeliverySlotKind kind,
      LocalTime windowStart,
      LocalTime windowEnd,
      String deliveryAddress,
      BigDecimal latitude,
      BigDecimal longitude,
      int cabinCount,
      long oneWayTravelSeconds,
      int travelZoneHours,
      int capacityRemaining,
      int siteCabinCapacity,
      Long deliveryPriceRubles,
      UUID priceZoneId,
      boolean privateSiteAccessConfirmed,
      boolean failedTripChargeAcknowledged,
      double routeHeightMeters,
      double routeWidthMeters,
      double routeLengthMeters,
      double routeWeightTons,
      double routeAxleLoadTons,
      int routeAxleCount,
      OffsetDateTime expiresAt) {
    Integer tier = priceZoneId == null ? defaultIsochroneTier(oneWayTravelSeconds) : null;
    Long price =
        deliveryPriceRubles == null && tier != null
            ? defaultIsochronePrice(tier)
            : deliveryPriceRubles;
    return offer(
        customerSubjectId,
        inquiryId,
        warehouseId,
        deliveryDate,
        kind,
        windowStart,
        windowEnd,
        deliveryAddress,
        latitude,
        longitude,
        cabinCount,
        oneWayTravelSeconds,
        travelZoneHours,
        capacityRemaining,
        siteCabinCapacity,
        price,
        priceZoneId,
        tier,
        privateSiteAccessConfirmed,
        failedTripChargeAcknowledged,
        routeHeightMeters,
        routeWidthMeters,
        routeLengthMeters,
        routeWeightTons,
        routeAxleLoadTons,
        routeAxleCount,
        expiresAt);
  }

  /**
   * Converts a still-valid offer to the single checkout hold after merging the hold-time route
   * attestations with any compatible search-time attestations.
   */
  public void hold(
      OffsetDateTime heldUntil,
      int remainingCapacity,
      boolean privateSiteAccessConfirmed,
      boolean failedTripChargeAcknowledged) {
    if (state != CustomerDeliverySlotState.OFFERED && state != CustomerDeliverySlotState.HELD) {
      throw new IllegalStateException("Delivery slot cannot be held");
    }
    this.privateSiteAccessConfirmed |= privateSiteAccessConfirmed;
    this.failedTripChargeAcknowledged |= failedTripChargeAcknowledged;
    requireRouteAttestations();
    state = CustomerDeliverySlotState.HELD;
    expiresAt = Objects.requireNonNull(heldUntil, "heldUntil");
    capacityRemaining = remainingCapacity;
    updatedAt = now();
  }

  /** Protects capacity while a durable downstream booking receipt is being reconciled. */
  public void protectCheckout(UUID bookingId, UUID orderId) {
    if (state == CustomerDeliverySlotState.CONFIRMED) {
      if (Objects.equals(this.bookingId, bookingId)
          && (orderId == null || Objects.equals(this.orderId, orderId))) return;
      throw new IllegalStateException("Delivery slot is confirmed by another booking");
    }
    if (state == CustomerDeliverySlotState.CHECKOUT_PENDING) {
      if (Objects.equals(this.bookingId, bookingId) && Objects.equals(this.orderId, orderId)) return;
      throw new IllegalStateException("Delivery slot is protected by another booking");
    }
    if (state != CustomerDeliverySlotState.HELD || expiresAt.isBefore(now())) {
      throw new IllegalStateException("Delivery slot hold is no longer active");
    }
    requireRouteAttestations();
    this.bookingId = Objects.requireNonNull(bookingId, "bookingId");
    this.orderId = orderId;
    state = CustomerDeliverySlotState.CHECKOUT_PENDING;
    expiresAt = OffsetDateTime.of(deliveryDate.plusDays(1), LocalTime.MIDNIGHT, ZoneOffset.UTC);
    updatedAt = now();
  }

  /**
   * Replaces the checkout command's provisional reservation identity with the durable presentation
   * booking receipt. Replays with the already-bound receipt are no-ops.
   */
  public void bindCheckoutBooking(UUID reservationKey, UUID bookingId, UUID orderId) {
    UUID requiredBookingId = Objects.requireNonNull(bookingId, "bookingId");
    if (state == CustomerDeliverySlotState.CONFIRMED) {
      if (Objects.equals(this.bookingId, requiredBookingId)
          && (orderId == null || Objects.equals(this.orderId, orderId))) return;
      throw new IllegalStateException("Delivery slot is confirmed by another booking");
    }
    if (state != CustomerDeliverySlotState.CHECKOUT_PENDING) {
      throw new IllegalStateException("Delivery slot has no pending checkout reservation");
    }
    if (Objects.equals(this.bookingId, requiredBookingId)) {
      if (this.orderId == null && orderId != null) {
        this.orderId = orderId;
        updatedAt = now();
      } else if (orderId != null && !Objects.equals(this.orderId, orderId)) {
        throw new IllegalStateException("Delivery slot has a conflicting booking order");
      }
      return;
    }
    if (!Objects.equals(this.bookingId, reservationKey)) {
      throw new IllegalStateException("Delivery slot belongs to another checkout command");
    }
    this.bookingId = requiredBookingId;
    this.orderId = orderId;
    updatedAt = now();
  }

  /** Makes a successfully booked delivery immutable workload for the planner. */
  public void confirm(UUID bookingId, UUID orderId) {
    if (state == CustomerDeliverySlotState.CONFIRMED) {
      if (Objects.equals(this.bookingId, bookingId) && Objects.equals(this.orderId, orderId)) return;
      throw new IllegalStateException("Delivery slot is confirmed by another booking");
    }
    if (state != CustomerDeliverySlotState.CHECKOUT_PENDING
        || !Objects.equals(this.bookingId, bookingId)) {
      throw new IllegalStateException("Delivery slot is not protected by this booking");
    }
    this.bookingId = Objects.requireNonNull(bookingId, "bookingId");
    this.orderId = Objects.requireNonNull(orderId, "orderId");
    state = CustomerDeliverySlotState.CONFIRMED;
    expiresAt = OffsetDateTime.of(deliveryDate.plusDays(1), LocalTime.MIDNIGHT, ZoneOffset.UTC);
    updatedAt = now();
  }

  /** Releases an offer or hold without deleting its audit identity. */
  public void release() {
    if (state == CustomerDeliverySlotState.CONFIRMED) {
      throw new IllegalStateException("Confirmed delivery workload cannot be released implicitly");
    }
    state = CustomerDeliverySlotState.RELEASED;
    bookingId = null;
    orderId = null;
    updatedAt = now();
  }

  /** Returns whether this row currently consumes route capacity. */
  public boolean consumesCapacity(OffsetDateTime timestamp) {
    return state == CustomerDeliverySlotState.CONFIRMED
        || state == CustomerDeliverySlotState.CHECKOUT_PENDING
        || (state == CustomerDeliverySlotState.HELD && expiresAt.isAfter(timestamp));
  }

  private void requireRouteAttestations() {
    if (!roadRouteConfirmed
        || !privateSiteAccessConfirmed
        || !failedTripChargeAcknowledged) {
      throw new IllegalStateException("Delivery route attestations are incomplete");
    }
  }

  private static String required(String value, int maximum, String field) {
    String normalized = value == null ? "" : value.trim();
    if (normalized.isEmpty() || normalized.length() > maximum) {
      throw new IllegalArgumentException(field + " is invalid");
    }
    return normalized;
  }

  private static int defaultIsochroneTier(long oneWayTravelSeconds) {
    if (oneWayTravelSeconds <= 3_600) return 60;
    if (oneWayTravelSeconds <= 7_200) return 120;
    if (oneWayTravelSeconds <= 10_800) return 180;
    return 240;
  }

  private static long defaultIsochronePrice(int tier) {
    return switch (tier) {
      case 60 -> 10_000;
      case 120 -> 15_000;
      case 180 -> 20_000;
      case 240 -> 25_000;
      default -> throw new IllegalArgumentException("Unsupported isochrone price tier");
    };
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
        && Objects.equals(id, ((CustomerDeliverySlot) other).id);
  }

  @Override
  public final int hashCode() {
    return this instanceof HibernateProxy proxy
        ? proxy.getHibernateLazyInitializer().getPersistentClass().hashCode()
        : getClass().hashCode();
  }
}
