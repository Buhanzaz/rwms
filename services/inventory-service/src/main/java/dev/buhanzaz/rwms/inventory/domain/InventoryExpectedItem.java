package dev.buhanzaz.rwms.inventory.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "inventory_expected_item")
public class InventoryExpectedItem {
  @Id
  @Column(name = "row_id", nullable = false)
  private UUID id;

  @Column(name = "inventory_id", nullable = false)
  private UUID inventoryId;

  @Column(name = "finding_id", nullable = false)
  private UUID findingId;

  @Column(name = "item_order", nullable = false)
  private int itemOrder;

  @Column(name = "asset_id", nullable = false)
  private UUID assetId;

  @Column(name = "asset_version_snapshot", nullable = false)
  private long assetVersion;

  @Column(name = "asset_status_snapshot", nullable = false, length = 48)
  private String assetStatus;

  @Column(name = "display_canonical_number", nullable = false, length = 128)
  private String displayCanonicalNumber;

  @Column(name = "identity_match_key", nullable = false, length = 128)
  private String identityMatchKey;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "safe_passport_snapshot", nullable = false, columnDefinition = "jsonb")
  private String passportSnapshot;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "safe_contents_snapshot", nullable = false, columnDefinition = "jsonb")
  private String contentsSnapshot;

  @Column(name = "captured_at", nullable = false)
  private OffsetDateTime capturedAt;

  protected InventoryExpectedItem() {}

  public InventoryExpectedItem(
      UUID id,
      UUID inventoryId,
      UUID findingId,
      int itemOrder,
      UUID assetId,
      long assetVersion,
      String assetStatus,
      String displayCanonicalNumber,
      String identityMatchKey,
      String passportSnapshot,
      String contentsSnapshot) {
    this.id = id;
    this.inventoryId = inventoryId;
    this.findingId = findingId;
    this.itemOrder = itemOrder;
    this.assetId = assetId;
    this.assetVersion = assetVersion;
    this.assetStatus = assetStatus;
    this.displayCanonicalNumber = displayCanonicalNumber;
    this.identityMatchKey = identityMatchKey;
    this.passportSnapshot = passportSnapshot;
    this.contentsSnapshot = contentsSnapshot;
    capturedAt = OffsetDateTime.now(ZoneOffset.UTC);
  }

  public UUID getFindingId() {
    return findingId;
  }

  public UUID getAssetId() {
    return assetId;
  }

  public long getAssetVersion() {
    return assetVersion;
  }

  public String getAssetStatus() {
    return assetStatus;
  }

  public String getDisplayCanonicalNumber() {
    return displayCanonicalNumber;
  }

  public String getPassportSnapshot() {
    return passportSnapshot;
  }

  public String getContentsSnapshot() {
    return contentsSnapshot;
  }
}
