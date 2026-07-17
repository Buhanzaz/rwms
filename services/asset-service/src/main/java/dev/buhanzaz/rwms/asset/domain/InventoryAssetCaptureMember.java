package dev.buhanzaz.rwms.asset.domain;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "inventory_asset_capture_member")
public class InventoryAssetCaptureMember {
  @EmbeddedId private InventoryAssetCaptureMemberId id;

  @Column(name = "asset_id", nullable = false)
  private UUID assetId;

  @Column(name = "asset_version", nullable = false)
  private long assetVersion;

  @Column(name = "warehouse_id", nullable = false)
  private UUID warehouseId;

  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false, length = 64)
  private RentalItemStatus status;

  @Column(name = "display_canonical_number", nullable = false, length = 128)
  private String displayCanonicalNumber;

  @Column(name = "identity_match_key", nullable = false, length = 128)
  private String identityMatchKey;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "passport_snapshot", nullable = false, columnDefinition = "jsonb")
  private String passportSnapshot;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "contents_snapshot", nullable = false, columnDefinition = "jsonb")
  private String contentsSnapshot;

  protected InventoryAssetCaptureMember() {}

  public static InventoryAssetCaptureMember create(
      UUID captureId,
      long sequence,
      UUID assetId,
      long assetVersion,
      UUID warehouseId,
      RentalItemStatus status,
      String displayCanonicalNumber,
      String identityMatchKey,
      String passportSnapshot,
      String contentsSnapshot) {
    InventoryAssetCaptureMember value = new InventoryAssetCaptureMember();
    value.id = new InventoryAssetCaptureMemberId(captureId, sequence);
    value.assetId = assetId;
    value.assetVersion = assetVersion;
    value.warehouseId = warehouseId;
    value.status = status;
    value.displayCanonicalNumber = displayCanonicalNumber;
    value.identityMatchKey = identityMatchKey;
    value.passportSnapshot = passportSnapshot;
    value.contentsSnapshot = contentsSnapshot;
    return value;
  }

  public InventoryAssetCaptureMemberId getId() {
    return id;
  }

  public UUID getAssetId() {
    return assetId;
  }

  public long getAssetVersion() {
    return assetVersion;
  }

  public UUID getWarehouseId() {
    return warehouseId;
  }

  public RentalItemStatus getStatus() {
    return status;
  }

  public String getDisplayCanonicalNumber() {
    return displayCanonicalNumber;
  }

  public String getIdentityMatchKey() {
    return identityMatchKey;
  }

  public String getPassportSnapshot() {
    return passportSnapshot;
  }

  public String getContentsSnapshot() {
    return contentsSnapshot;
  }
}
