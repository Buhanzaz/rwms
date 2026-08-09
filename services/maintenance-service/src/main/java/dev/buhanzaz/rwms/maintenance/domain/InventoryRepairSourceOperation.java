package dev.buhanzaz.rwms.maintenance.domain;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.OffsetDateTime;

/** JPA reconciliation fact linking an inventory source operation to a maintenance repair decision. */
@Entity
@Table(name = "inventory_repair_source_operation")
public class InventoryRepairSourceOperation {
  @EmbeddedId private InventoryRepairSourceOperationId id;

  @Version
  @Column(name = "version", nullable = false)
  private long version;

  @Column(name = "request_sha256", nullable = false, length = 64)
  private String requestSha256;

  @Column(name = "created_at", nullable = false)
  private OffsetDateTime createdAt;

  protected InventoryRepairSourceOperation() {}

  public static InventoryRepairSourceOperation register(
      InventoryRepairSourceOperationId id, String requestSha256) {
    if (id == null || requestSha256 == null || !requestSha256.matches("[0-9a-f]{64}")) {
      throw new IllegalArgumentException("Inventory repair source operation is incomplete");
    }
    InventoryRepairSourceOperation value = new InventoryRepairSourceOperation();
    value.id = id;
    value.requestSha256 = requestSha256;
    value.createdAt = MaintenanceTime.now();
    return value;
  }

  public InventoryRepairSourceOperationId getId() {
    return id;
  }

  public long getVersion() {
    return version;
  }

  public String getRequestSha256() {
    return requestSha256;
  }

  public OffsetDateTime getCreatedAt() {
    return createdAt;
  }
}
