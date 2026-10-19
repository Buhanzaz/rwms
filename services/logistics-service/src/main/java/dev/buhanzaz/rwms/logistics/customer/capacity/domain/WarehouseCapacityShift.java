package dev.buhanzaz.rwms.logistics.customer.capacity.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Objects;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.proxy.HibernateProxy;

/** One anonymous active driver shift that bounds real CustomerApp delivery capacity. */
@Entity
@Table(
    name = "customer_warehouse_capacity_shift",
    uniqueConstraints =
        @UniqueConstraint(
            name = "uk_customer_warehouse_capacity_shift_source",
            columnNames = {"snapshot_id", "source_shift_id"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class WarehouseCapacityShift {
  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Column(name = "id", nullable = false)
  private UUID id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(
      name = "snapshot_id",
      nullable = false,
      foreignKey = @ForeignKey(name = "fk_customer_warehouse_capacity_shift_snapshot"))
  private WarehouseCapacitySnapshot snapshot;

  @Column(name = "source_shift_id", nullable = false)
  private UUID sourceShiftId;

  @Column(name = "delivery_date", nullable = false)
  private LocalDate deliveryDate;

  @Column(name = "shift_start", nullable = false)
  private LocalTime shiftStart;

  @Column(name = "shift_end", nullable = false)
  private LocalTime shiftEnd;

  @Column(name = "break_minutes", nullable = false)
  private int breakMinutes;

  @Column(name = "cabin_capacity", nullable = false)
  private int cabinCapacity;

  /** Copies a validated transport shift into the owning replacement aggregate. */
  static WarehouseCapacityShift create(
      WarehouseCapacitySnapshot snapshot, Facts facts) {
    WarehouseCapacityShift shift = new WarehouseCapacityShift();
    shift.snapshot = Objects.requireNonNull(snapshot, "snapshot");
    shift.replace(facts);
    return shift;
  }

  /** Replaces anonymous shift facts while preserving the stable simulator shift identity. */
  void replace(Facts facts) {
    Objects.requireNonNull(facts, "facts");
    sourceShiftId = facts.sourceShiftId();
    deliveryDate = facts.deliveryDate();
    shiftStart = facts.shiftStart();
    shiftEnd = facts.shiftEnd();
    breakMinutes = facts.breakMinutes();
    cabinCapacity = facts.cabinCapacity();
    if (!shiftStart.isBefore(shiftEnd)
        || breakMinutes < 0
        || breakMinutes >= java.time.Duration.between(shiftStart, shiftEnd).toMinutes()
        || cabinCapacity < 1
        || cabinCapacity > 2) {
      throw new IllegalArgumentException("Planning capacity shift is invalid");
    }
  }

  /** Boundary-independent immutable facts used to replace one anonymous driver shift. */
  public record Facts(
      UUID sourceShiftId,
      LocalDate deliveryDate,
      LocalTime shiftStart,
      LocalTime shiftEnd,
      int breakMinutes,
      int cabinCapacity) {
    public Facts {
      Objects.requireNonNull(sourceShiftId, "sourceShiftId");
      Objects.requireNonNull(deliveryDate, "deliveryDate");
      Objects.requireNonNull(shiftStart, "shiftStart");
      Objects.requireNonNull(shiftEnd, "shiftEnd");
      if (!shiftStart.isBefore(shiftEnd)
          || breakMinutes < 0
          || breakMinutes >= java.time.Duration.between(shiftStart, shiftEnd).toMinutes()
          || cabinCapacity < 1
          || cabinCapacity > 2) {
        throw new IllegalArgumentException("Planning capacity shift is invalid");
      }
    }
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
        && Objects.equals(id, ((WarehouseCapacityShift) other).id);
  }

  @Override
  public final int hashCode() {
    return this instanceof HibernateProxy proxy
        ? proxy.getHibernateLazyInitializer().getPersistentClass().hashCode()
        : getClass().hashCode();
  }
}
