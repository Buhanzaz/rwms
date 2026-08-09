package dev.buhanzaz.rwms.asset.domain;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/**
 * JPA entity that persists inventory asset source operation in the asset-owned database.
 */
@Entity
@Table(name = "inventory_asset_source_operation")
public class InventoryAssetSourceOperation {
  @EmbeddedId private InventoryAssetSourceId id;

  @Version
  @Column(name = "version", nullable = false)
  private long version;

  @Column(name = "request_fingerprint", nullable = false, length = 64)
  private String requestFingerprint;

  @Column(name = "created_at", nullable = false)
  private OffsetDateTime createdAt;

  protected InventoryAssetSourceOperation() {}

  public static InventoryAssetSourceOperation register(
      InventoryAssetSourceId id, String requestFingerprint) {
    if (id == null || requestFingerprint == null || !requestFingerprint.matches("[0-9a-f]{64}")) {
      throw new IllegalArgumentException("Inventory source operation identity is incomplete");
    }
    InventoryAssetSourceOperation value = new InventoryAssetSourceOperation();
    value.id = id;
    value.requestFingerprint = requestFingerprint;
    value.createdAt = OffsetDateTime.now(ZoneOffset.UTC);
    return value;
  }

  public InventoryAssetSourceId getId() {
    return id;
  }

  public long getVersion() {
    return version;
  }

  public String getRequestFingerprint() {
    return requestFingerprint;
  }

  public OffsetDateTime getCreatedAt() {
    return createdAt;
  }
}
