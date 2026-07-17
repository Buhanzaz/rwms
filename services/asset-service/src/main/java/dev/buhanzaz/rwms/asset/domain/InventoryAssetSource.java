package dev.buhanzaz.rwms.asset.domain;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "inventory_asset_source")
public class InventoryAssetSource {
  @EmbeddedId private InventoryAssetSourceId id;

  @Column(name = "request_fingerprint", nullable = false, length = 64)
  private String requestFingerprint;

  @Column(name = "rental_item_id", nullable = false)
  private UUID rentalItemId;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "response_body", nullable = false, columnDefinition = "jsonb")
  private String responseBody;

  @Column(name = "created_at", nullable = false)
  private OffsetDateTime createdAt;

  protected InventoryAssetSource() {}

  public static InventoryAssetSource complete(
      InventoryAssetSourceId id,
      String requestFingerprint,
      UUID rentalItemId,
      String responseBody) {
    InventoryAssetSource value = new InventoryAssetSource();
    value.id = id;
    value.requestFingerprint = requestFingerprint;
    value.rentalItemId = rentalItemId;
    value.responseBody = responseBody;
    value.createdAt = OffsetDateTime.now(ZoneOffset.UTC);
    return value;
  }

  public InventoryAssetSourceId getId() {
    return id;
  }

  public String getRequestFingerprint() {
    return requestFingerprint;
  }

  public UUID getRentalItemId() {
    return rentalItemId;
  }

  public String getResponseBody() {
    return responseBody;
  }

  public OffsetDateTime getCreatedAt() {
    return createdAt;
  }
}
