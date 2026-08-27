package dev.buhanzaz.rwms.logistics.customer.capacity.domain;

import dev.buhanzaz.rwms.logistics.planning.api.PlanningIntegrationApiModels.PlanningCapacityJobRequest;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.proxy.HibernateProxy;

@Entity
@Table(
    name = "customer_scenario_capacity_snapshot",
    uniqueConstraints =
        @UniqueConstraint(
            name = "uk_customer_scenario_capacity_snapshot_warehouse",
            columnNames = "warehouse_id"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
/**
 * Logistics-owned active simulator-capacity projection for one warehouse.
 *
 * <p>The projection contains no rental order, client, cabin, or driver identity. Replacing it
 * changes only CustomerApp slot feasibility; real slots and orders remain independent durable
 * workload.
 */
public class ScenarioCapacitySnapshot {
  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Column(name = "id", nullable = false)
  private UUID id;

  @Version
  @Column(name = "version", nullable = false)
  private long version;

  @Column(name = "warehouse_id", nullable = false)
  private UUID warehouseId;

  @Column(name = "source_scenario_id", nullable = false)
  private UUID sourceScenarioId;

  @Column(name = "source_generation", nullable = false)
  private long sourceGeneration;

  @Column(name = "source_revision", nullable = false, length = 64)
  private String sourceRevision;

  @Column(name = "created_at", nullable = false)
  private OffsetDateTime createdAt;

  @Column(name = "updated_at", nullable = false)
  private OffsetDateTime updatedAt;

  @OneToMany(mappedBy = "snapshot", cascade = CascadeType.ALL, orphanRemoval = true)
  @OrderBy("deliveryDate ASC, windowStart ASC, sourceJobId ASC")
  private List<ScenarioCapacityJob> jobs = new ArrayList<>();

  /** Creates one warehouse projection from a complete, validated replacement command. */
  public static ScenarioCapacitySnapshot create(
      UUID warehouseId,
      UUID sourceScenarioId,
      long sourceGeneration,
      String sourceRevision,
      List<PlanningCapacityJobRequest> jobs,
      OffsetDateTime now) {
    ScenarioCapacitySnapshot snapshot = new ScenarioCapacitySnapshot();
    snapshot.warehouseId = Objects.requireNonNull(warehouseId, "warehouseId");
    snapshot.createdAt = Objects.requireNonNull(now, "now");
    snapshot.replace(sourceScenarioId, sourceGeneration, sourceRevision, jobs, now);
    return snapshot;
  }

  /** Atomically replaces the active simulator facts without mutating any real booking workload. */
  public void replace(
      UUID sourceScenarioId,
      long sourceGeneration,
      String sourceRevision,
      List<PlanningCapacityJobRequest> jobs,
      OffsetDateTime now) {
    this.sourceScenarioId = Objects.requireNonNull(sourceScenarioId, "sourceScenarioId");
    if (sourceGeneration < 1) {
      throw new IllegalArgumentException("sourceGeneration must be positive");
    }
    this.sourceGeneration = sourceGeneration;
    this.sourceRevision = requireSha256(sourceRevision, "sourceRevision");
    Map<UUID, ScenarioCapacityJob> existingBySource = new HashMap<>();
    this.jobs.forEach(job -> existingBySource.put(job.getSourceJobId(), job));
    Set<UUID> requestedSources = new HashSet<>();
    for (PlanningCapacityJobRequest request : Objects.requireNonNull(jobs, "jobs")) {
      requestedSources.add(request.sourceJobId());
      ScenarioCapacityJob existing = existingBySource.get(request.sourceJobId());
      if (existing == null) this.jobs.add(ScenarioCapacityJob.create(this, request));
      else existing.replace(request);
    }
    this.jobs.removeIf(job -> !requestedSources.contains(job.getSourceJobId()));
    this.jobs.sort(
        Comparator.comparing(ScenarioCapacityJob::getDeliveryDate)
            .thenComparing(ScenarioCapacityJob::getWindowStart)
            .thenComparing(ScenarioCapacityJob::getSourceJobId));
    this.updatedAt = Objects.requireNonNull(now, "now");
  }

  private static String requireSha256(String value, String field) {
    if (value == null || !value.matches("[0-9a-f]{64}")) {
      throw new IllegalArgumentException(field + " must be a lowercase SHA-256 digest");
    }
    return value;
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
        && Objects.equals(id, ((ScenarioCapacitySnapshot) other).id);
  }

  @Override
  public final int hashCode() {
    return this instanceof HibernateProxy proxy
        ? proxy.getHibernateLazyInitializer().getPersistentClass().hashCode()
        : getClass().hashCode();
  }
}
