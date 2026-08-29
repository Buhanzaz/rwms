package dev.buhanzaz.rwms.logistics.customer.capacity.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Objects;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.proxy.HibernateProxy;

/** One anonymous generated delivery point that consumes route capacity on an exact local day. */
@Entity
@Table(
    name = "customer_warehouse_capacity_job",
    uniqueConstraints =
        @UniqueConstraint(
            name = "uk_customer_warehouse_capacity_job_source",
            columnNames = {"snapshot_id", "source_job_id"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class WarehouseCapacityJob {
  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Column(name = "id", nullable = false)
  private UUID id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(
      name = "snapshot_id",
      nullable = false,
      foreignKey = @ForeignKey(name = "fk_customer_warehouse_capacity_job_snapshot"))
  private WarehouseCapacitySnapshot snapshot;

  @Column(name = "source_job_id", nullable = false)
  private UUID sourceJobId;

  @Enumerated(EnumType.STRING)
  @Column(name = "task_type", nullable = false, length = 16)
  private WarehouseCapacityTaskType taskType;

  @Column(name = "delivery_date", nullable = false)
  private LocalDate deliveryDate;

  @Column(name = "latitude", nullable = false, precision = 8, scale = 6)
  private BigDecimal latitude;

  @Column(name = "longitude", nullable = false, precision = 9, scale = 6)
  private BigDecimal longitude;

  @Column(name = "cabin_count", nullable = false)
  private int cabinCount;

  @Column(name = "window_start", nullable = false)
  private LocalTime windowStart;

  @Column(name = "window_end", nullable = false)
  private LocalTime windowEnd;

  @Column(name = "service_minutes", nullable = false)
  private int serviceMinutes;

  @Column(name = "trailer_access_allowed", nullable = false)
  private boolean trailerAccessAllowed;

  @Column(name = "priority", nullable = false)
  private int priority;

  @Column(name = "mandatory", nullable = false)
  private boolean mandatory;

  /** Copies a validated transport job into the owning replacement aggregate. */
  static WarehouseCapacityJob create(
      WarehouseCapacitySnapshot snapshot, Facts facts) {
    WarehouseCapacityJob job = new WarehouseCapacityJob();
    job.snapshot = Objects.requireNonNull(snapshot, "snapshot");
    job.replace(facts);
    return job;
  }

  /** Replaces anonymous route facts while preserving the stable simulator job identity. */
  void replace(Facts facts) {
    Objects.requireNonNull(facts, "facts");
    sourceJobId = facts.sourceJobId();
    taskType = facts.taskType();
    deliveryDate = facts.deliveryDate();
    latitude = facts.latitude();
    longitude = facts.longitude();
    cabinCount = facts.cabinCount();
    windowStart = facts.windowStart();
    windowEnd = facts.windowEnd();
    serviceMinutes = facts.serviceMinutes();
    trailerAccessAllowed = facts.trailerAccessAllowed();
    priority = facts.priority();
    mandatory = facts.mandatory();
  }

  /** Boundary-independent immutable facts used to replace one simulator task. */
  public record Facts(
      UUID sourceJobId,
      WarehouseCapacityTaskType taskType,
      LocalDate deliveryDate,
      BigDecimal latitude,
      BigDecimal longitude,
      int cabinCount,
      LocalTime windowStart,
      LocalTime windowEnd,
      int serviceMinutes,
      boolean trailerAccessAllowed,
      int priority,
      boolean mandatory) {
    public Facts {
      Objects.requireNonNull(sourceJobId, "sourceJobId");
      Objects.requireNonNull(taskType, "taskType");
      Objects.requireNonNull(deliveryDate, "deliveryDate");
      Objects.requireNonNull(latitude, "latitude");
      Objects.requireNonNull(longitude, "longitude");
      Objects.requireNonNull(windowStart, "windowStart");
      Objects.requireNonNull(windowEnd, "windowEnd");
      if (latitude.compareTo(BigDecimal.valueOf(-90)) < 0
          || latitude.compareTo(BigDecimal.valueOf(90)) > 0
          || longitude.compareTo(BigDecimal.valueOf(-180)) < 0
          || longitude.compareTo(BigDecimal.valueOf(180)) > 0
          || cabinCount < 1
          || serviceMinutes < 1
          || priority < 0
          || !windowStart.isBefore(windowEnd)
          || (taskType == WarehouseCapacityTaskType.DELIVERY && !mandatory)) {
        throw new IllegalArgumentException("Planning capacity job is invalid");
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
        && Objects.equals(id, ((WarehouseCapacityJob) other).id);
  }

  @Override
  public final int hashCode() {
    return this instanceof HibernateProxy proxy
        ? proxy.getHibernateLazyInitializer().getPersistentClass().hashCode()
        : getClass().hashCode();
  }
}
