package dev.buhanzaz.rwms.asset.domain;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

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

  @Column(name = "reserved_rental_item_id")
  private UUID reservedRentalItemId;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "source_plan", columnDefinition = "jsonb")
  private String sourcePlan;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "proposal_response", columnDefinition = "jsonb")
  private String proposalResponse;

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

  public static InventoryAssetSourceOperation register(
      InventoryAssetSourceId id,
      String requestFingerprint,
      UUID reservedRentalItemId,
      String sourcePlan,
      String proposalResponse) {
    InventoryAssetSourceOperation value = register(id, requestFingerprint);
    value.bindProposal(reservedRentalItemId, sourcePlan, proposalResponse);
    return value;
  }

  /** Binds one stable reserved identity to a retained pre-V50 operation that never completed. */
  public void bindProposal(
      UUID nextReservedRentalItemId, String nextSourcePlan, String nextProposalResponse) {
    if (nextReservedRentalItemId == null
        || !jsonObject(nextSourcePlan)
        || !jsonObject(nextProposalResponse)) {
      throw new IllegalArgumentException("Inventory source proposal is incomplete");
    }
    if (reservedRentalItemId != null || sourcePlan != null || proposalResponse != null) {
      if (!nextReservedRentalItemId.equals(reservedRentalItemId)
          || !Objects.equals(nextSourcePlan, sourcePlan)
          || !Objects.equals(nextProposalResponse, proposalResponse)) {
        throw new IllegalStateException("Inventory source proposal is immutable");
      }
      return;
    }
    reservedRentalItemId = nextReservedRentalItemId;
    sourcePlan = nextSourcePlan;
    proposalResponse = nextProposalResponse;
  }

  private static boolean jsonObject(String value) {
    return value != null && value.trim().startsWith("{") && value.trim().endsWith("}");
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

  public boolean hasProposal() {
    return reservedRentalItemId != null && sourcePlan != null && proposalResponse != null;
  }

  public UUID getReservedRentalItemId() {
    return reservedRentalItemId;
  }

  public String getSourcePlan() {
    return sourcePlan;
  }

  public String getProposalResponse() {
    return proposalResponse;
  }

  public OffsetDateTime getCreatedAt() {
    return createdAt;
  }
}
