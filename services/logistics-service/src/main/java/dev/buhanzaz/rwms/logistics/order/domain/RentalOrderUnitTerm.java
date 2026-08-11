package dev.buhanzaz.rwms.logistics.order.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import java.time.LocalDate;
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
 * Rental duration and delivery dates for one cabin in an order.
 *
 * <p>The term is owned by the order rather than a logistics document: a single order can split its
 * cabins into several shipments, while each cabin belongs to exactly one active shipment at a time.
 * The linked shipment identifier is a local immutable reference used to prevent a cabin from
 * silently appearing in two shipment documents.
 */
@Entity
@Table(
    name = "rental_order_unit_term",
    uniqueConstraints =
        @UniqueConstraint(
            name = "uk_rental_order_unit_term",
            columnNames = {"order_id", "rental_item_id"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RentalOrderUnitTerm {
  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Column(name = "id", nullable = false)
  private UUID id;

  @Version
  @Column(name = "version", nullable = false)
  private long version;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(
      name = "order_id",
      nullable = false,
      foreignKey = @ForeignKey(name = "fk_rental_order_unit_term_order"))
  private RentalOrder order;

  @Column(name = "rental_item_id", nullable = false)
  private UUID rentalItemId;

  @Column(name = "rental_months", nullable = false)
  private long rentalMonths;

  /** The shipment that currently owns this cabin, if a shipment has been created. */
  @Column(name = "rental_shipment_id")
  private UUID rentalShipmentId;

  @Column(name = "shipment_date")
  private LocalDate shipmentDate;

  @Column(name = "return_date")
  private LocalDate returnDate;

  @Column(name = "created_at", nullable = false)
  private OffsetDateTime createdAt;

  @Column(name = "updated_at", nullable = false)
  private OffsetDateTime updatedAt;

  public static RentalOrderUnitTerm create(
      RentalOrder order, UUID rentalItemId, long rentalMonths) {
    RentalOrderUnitTerm term = new RentalOrderUnitTerm();
    term.order = Objects.requireNonNull(order, "order");
    term.rentalItemId = Objects.requireNonNull(rentalItemId, "rentalItemId");
    term.rentalMonths = requirePositiveMonths(rentalMonths);
    term.createdAt = now();
    term.updatedAt = term.createdAt;
    return term;
  }

  /** Changes the total rental duration before the shipment workflow has started. */
  public boolean changeRentalMonths(long nextRentalMonths) {
    long normalized = requirePositiveMonths(nextRentalMonths);
    if (rentalMonths == normalized) {
      return false;
    }
    rentalMonths = normalized;
    if (shipmentDate != null) {
      returnDate = calculateReturnDate(shipmentDate, normalized);
    }
    touch();
    return true;
  }

  /** Assigns this cabin to its selected shipment and derives the planned return date. */
  public boolean assignShipment(UUID nextRentalShipmentId, LocalDate nextShipmentDate) {
    UUID shipmentId = Objects.requireNonNull(nextRentalShipmentId, "rentalShipmentId");
    LocalDate date = Objects.requireNonNull(nextShipmentDate, "shipmentDate");
    if (rentalShipmentId != null && !rentalShipmentId.equals(shipmentId)) {
      throw new IllegalStateException("Rental term is already assigned to another shipment");
    }
    LocalDate nextReturnDate = calculateReturnDate(date, rentalMonths);
    if (shipmentId.equals(rentalShipmentId)
        && date.equals(shipmentDate)
        && nextReturnDate.equals(returnDate)) {
      return false;
    }
    rentalShipmentId = shipmentId;
    shipmentDate = date;
    returnDate = nextReturnDate;
    touch();
    return true;
  }

  /** Recalculates the due date when the draft shipment date is changed. */
  public boolean rescheduleShipment(UUID expectedRentalShipmentId, LocalDate nextShipmentDate) {
    UUID shipmentId = Objects.requireNonNull(expectedRentalShipmentId, "rentalShipmentId");
    LocalDate date = Objects.requireNonNull(nextShipmentDate, "shipmentDate");
    if (!shipmentId.equals(rentalShipmentId)) {
      throw new IllegalStateException("Rental term does not belong to this shipment");
    }
    LocalDate nextReturnDate = calculateReturnDate(date, rentalMonths);
    if (date.equals(shipmentDate) && nextReturnDate.equals(returnDate)) {
      return false;
    }
    shipmentDate = date;
    returnDate = nextReturnDate;
    touch();
    return true;
  }

  /** Releases a cancelled draft shipment so the cabin can be selected again. */
  public boolean clearShipment(UUID expectedRentalShipmentId) {
    UUID shipmentId = Objects.requireNonNull(expectedRentalShipmentId, "rentalShipmentId");
    if (!shipmentId.equals(rentalShipmentId)) {
      return false;
    }
    rentalShipmentId = null;
    shipmentDate = null;
    returnDate = null;
    touch();
    return true;
  }

  /** Transfers the unchanged commercial term to a same-order replacement cabin. */
  public void transferToRentalItem(UUID replacementRentalItemId) {
    UUID replacement = Objects.requireNonNull(replacementRentalItemId, "replacementRentalItemId");
    if (!replacement.equals(rentalItemId)) {
      rentalItemId = replacement;
      touch();
    }
  }

  /** Extends an already shipped cabin without changing its original shipment date. */
  public boolean extend(long additionalMonths) {
    long additional = requirePositiveMonths(additionalMonths);
    if (rentalShipmentId == null || shipmentDate == null || returnDate == null) {
      throw new IllegalStateException("Only a shipped rental term can be extended");
    }
    rentalMonths = Math.addExact(rentalMonths, additional);
    returnDate = calculateReturnDate(returnDate, additional);
    touch();
    return true;
  }

  private static long requirePositiveMonths(long value) {
    if (value < 1) {
      throw new IllegalArgumentException("rentalMonths must be positive");
    }
    return value;
  }

  private static LocalDate calculateReturnDate(LocalDate shipmentDate, long months) {
    try {
      return shipmentDate.plusMonths(months);
    } catch (RuntimeException exception) {
      throw new IllegalArgumentException("Rental term return date is out of range", exception);
    }
  }

  private void touch() {
    OffsetDateTime candidate = now();
    updatedAt = candidate.isAfter(updatedAt) ? candidate : updatedAt.plusNanos(1_000);
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
        && Objects.equals(id, ((RentalOrderUnitTerm) other).id);
  }

  @Override
  public final int hashCode() {
    return this instanceof HibernateProxy proxy
        ? proxy.getHibernateLazyInitializer().getPersistentClass().hashCode()
        : getClass().hashCode();
  }
}
