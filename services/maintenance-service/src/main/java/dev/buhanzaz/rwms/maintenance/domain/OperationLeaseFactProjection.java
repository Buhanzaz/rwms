package dev.buhanzaz.rwms.maintenance.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;

/** Monotonic operation-lease facts used only to detect reconciliation requirements. */
@Entity
@Table(name = "operation_lease_fact_projection")
public class OperationLeaseFactProjection {
  @Id
  @Column(name = "lease_id", nullable = false)
  private UUID leaseId;

  @Column(name = "rental_item_id", nullable = false)
  private UUID rentalItemId;

  @Column(name = "fencing_token", nullable = false)
  private long fencingToken;

  @Column(name = "lease_state", nullable = false, length = 16)
  private String leaseState;

  @Column(name = "aggregate_version", nullable = false)
  private long aggregateVersion;

  @Column(name = "updated_at", nullable = false)
  private OffsetDateTime updatedAt;

  protected OperationLeaseFactProjection() {}

  public static OperationLeaseFactProjection create(
      UUID leaseId,
      UUID rentalItemId,
      long fencingToken,
      String leaseState,
      long aggregateVersion) {
    OperationLeaseFactProjection value = new OperationLeaseFactProjection();
    value.leaseId = leaseId;
    value.apply(rentalItemId, fencingToken, leaseState, aggregateVersion);
    return value;
  }

  public boolean apply(
      UUID rentalItemId, long fencingToken, String leaseState, long aggregateVersion) {
    if (rentalItemId == null || fencingToken < 1 || leaseState == null
        || !java.util.Set.of("ACTIVE", "RELEASED", "EXPIRED").contains(leaseState)
        || aggregateVersion < 0) {
      throw new IllegalArgumentException("Operation-lease fact is invalid");
    }
    if (updatedAt != null && aggregateVersion <= this.aggregateVersion) return false;
    this.rentalItemId = rentalItemId;
    this.fencingToken = fencingToken;
    this.leaseState = leaseState;
    this.aggregateVersion = aggregateVersion;
    this.updatedAt = MaintenanceTime.now();
    return true;
  }

  public UUID getLeaseId() { return leaseId; }
  public UUID getRentalItemId() { return rentalItemId; }
  public long getFencingToken() { return fencingToken; }
  public String getLeaseState() { return leaseState; }
  public long getAggregateVersion() { return aggregateVersion; }
  public OffsetDateTime getUpdatedAt() { return updatedAt; }
}
