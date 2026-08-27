package dev.buhanzaz.rwms.logistics.customer.capacity.domain;

import dev.buhanzaz.rwms.logistics.planning.api.PlanningIntegrationApiModels.PlanningCapacityJobRequest;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
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

@Entity
@Table(
    name = "customer_scenario_capacity_job",
    uniqueConstraints =
        @UniqueConstraint(
            name = "uk_customer_scenario_capacity_job_source",
            columnNames = {"snapshot_id", "source_job_id"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
/** One anonymous generated delivery point that consumes route capacity on an exact local day. */
public class ScenarioCapacityJob {
  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Column(name = "id", nullable = false)
  private UUID id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "snapshot_id", nullable = false)
  private ScenarioCapacitySnapshot snapshot;

  @Column(name = "source_job_id", nullable = false)
  private UUID sourceJobId;

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

  /** Copies a validated transport job into the owning replacement aggregate. */
  static ScenarioCapacityJob create(
      ScenarioCapacitySnapshot snapshot, PlanningCapacityJobRequest request) {
    ScenarioCapacityJob job = new ScenarioCapacityJob();
    job.snapshot = Objects.requireNonNull(snapshot, "snapshot");
    job.replace(request);
    return job;
  }

  /** Replaces anonymous route facts while preserving the stable simulator job identity. */
  void replace(PlanningCapacityJobRequest request) {
    sourceJobId = Objects.requireNonNull(request.sourceJobId(), "sourceJobId");
    deliveryDate = Objects.requireNonNull(request.deliveryDate(), "deliveryDate");
    latitude = Objects.requireNonNull(request.latitude(), "latitude");
    longitude = Objects.requireNonNull(request.longitude(), "longitude");
    cabinCount = request.cabinCount();
    windowStart = Objects.requireNonNull(request.windowStart(), "windowStart");
    windowEnd = Objects.requireNonNull(request.windowEnd(), "windowEnd");
    serviceMinutes = request.serviceMinutes();
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
        && Objects.equals(id, ((ScenarioCapacityJob) other).id);
  }

  @Override
  public final int hashCode() {
    return this instanceof HibernateProxy proxy
        ? proxy.getHibernateLazyInitializer().getPersistentClass().hashCode()
        : getClass().hashCode();
  }
}
