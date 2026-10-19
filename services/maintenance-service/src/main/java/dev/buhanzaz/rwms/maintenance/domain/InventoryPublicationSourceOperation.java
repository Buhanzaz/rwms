package dev.buhanzaz.rwms.maintenance.domain;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.OffsetDateTime;

/** Serializes competing applies for one immutable completed-inventory source key. */
@Entity
@Table(name = "inventory_publication_source_operation")
public class InventoryPublicationSourceOperation {
  @EmbeddedId private InventoryPublicationSourceId id;

  @Version
  @Column(name = "version", nullable = false)
  private long version;

  @Column(name = "request_sha256", nullable = false, length = 64)
  private String requestSha256;

  @Column(name = "created_at", nullable = false)
  private OffsetDateTime createdAt;

  protected InventoryPublicationSourceOperation() {}

  public static InventoryPublicationSourceOperation register(
      InventoryPublicationSourceId id, String requestSha256) {
    if (id == null || !sha256(requestSha256)) {
      throw new IllegalArgumentException("Inventory publication source operation is incomplete");
    }
    InventoryPublicationSourceOperation value = new InventoryPublicationSourceOperation();
    value.id = id;
    value.requestSha256 = requestSha256;
    value.createdAt = MaintenanceTime.now();
    return value;
  }

  private static boolean sha256(String value) {
    return value != null && value.matches("[0-9a-f]{64}");
  }

  public InventoryPublicationSourceId getId() {
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
