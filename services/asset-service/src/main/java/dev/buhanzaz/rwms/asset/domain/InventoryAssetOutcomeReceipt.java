package dev.buhanzaz.rwms.asset.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.proxy.HibernateProxy;

/** Immutable successful receipt for one inventory outcome idempotency key. */
@Entity
@Table(name = "inventory_asset_outcome_receipt")
public class InventoryAssetOutcomeReceipt {
  @Id
  @NotNull
  @Column(name = "idempotency_key", nullable = false)
  private UUID idempotencyKey;

  @NotBlank
  @Pattern(regexp = "^[0-9a-f]{64}$")
  @Column(name = "request_sha256", nullable = false, length = 64)
  private String requestSha256;

  @NotNull
  @Column(name = "inventory_id", nullable = false)
  private UUID inventoryId;

  @NotNull
  @Column(name = "finding_id", nullable = false)
  private UUID findingId;

  @NotNull
  @Column(name = "asset_id", nullable = false)
  private UUID assetId;

  @NotNull
  @Column(name = "warehouse_id", nullable = false)
  private UUID warehouseId;

  @NotNull
  @Column(name = "inventory_completed_at", nullable = false)
  private OffsetDateTime inventoryCompletedAt;

  @Min(1)
  @Column(name = "final_plan_version", nullable = false)
  private long finalPlanVersion;

  @NotBlank
  @Pattern(regexp = "^[0-9a-f]{64}$")
  @Column(name = "final_plan_sha256", nullable = false, length = 64)
  private String finalPlanSha256;

  @Min(1)
  @Column(name = "finding_revision", nullable = false)
  private long findingRevision;

  @NotNull
  @Enumerated(EnumType.STRING)
  @Column(name = "desired_status", nullable = false, length = 32)
  private RentalItemStatus desiredStatus;

  @Min(0)
  @Column(name = "response_asset_version", nullable = false)
  private long responseAssetVersion;

  @NotNull
  @Enumerated(EnumType.STRING)
  @Column(name = "response_status", nullable = false, length = 32)
  private RentalItemStatus responseStatus;

  @NotNull
  @Size(max = 16000)
  @Column(name = "released_operation_lease_ids", nullable = false, length = 16000)
  private String releasedOperationLeaseIds;

  @NotNull
  @Size(max = 16000)
  @Column(name = "released_order_unit_reservation_ids", nullable = false, length = 16000)
  private String releasedOrderUnitReservationIds;

  @NotNull
  @Size(max = 16000)
  @Column(name = "released_presentation_hold_ids", nullable = false, length = 16000)
  private String releasedPresentationHoldIds;

  @Column(name = "transfer_superseded", nullable = false)
  private boolean transferSuperseded;

  @NotNull
  @Column(name = "created_at", nullable = false)
  private OffsetDateTime createdAt;

  protected InventoryAssetOutcomeReceipt() {}

  /** Persists a complete response so exact retries do not repeat operational side effects. */
  public static InventoryAssetOutcomeReceipt record(
      UUID idempotencyKey,
      String requestSha256,
      UUID inventoryId,
      UUID findingId,
      UUID assetId,
      UUID warehouseId,
      OffsetDateTime inventoryCompletedAt,
      long finalPlanVersion,
      String finalPlanSha256,
      long findingRevision,
      RentalItemStatus desiredStatus,
      long responseAssetVersion,
      RentalItemStatus responseStatus,
      List<UUID> releasedOperationLeaseIds,
      List<UUID> releasedOrderUnitReservationIds,
      List<UUID> releasedPresentationHoldIds,
      boolean transferSuperseded) {
    if (requestSha256 == null || !requestSha256.matches("[0-9a-f]{64}")) {
      throw new IllegalArgumentException("Inventory outcome request hash is invalid");
    }
    InventoryAssetOutcomeReceipt value = new InventoryAssetOutcomeReceipt();
    value.idempotencyKey = Objects.requireNonNull(idempotencyKey, "idempotencyKey");
    value.requestSha256 = requestSha256;
    value.inventoryId = Objects.requireNonNull(inventoryId, "inventoryId");
    value.findingId = Objects.requireNonNull(findingId, "findingId");
    value.assetId = Objects.requireNonNull(assetId, "assetId");
    value.warehouseId = Objects.requireNonNull(warehouseId, "warehouseId");
    value.inventoryCompletedAt =
        Objects.requireNonNull(inventoryCompletedAt, "inventoryCompletedAt");
    value.finalPlanVersion = finalPlanVersion;
    value.finalPlanSha256 = Objects.requireNonNull(finalPlanSha256, "finalPlanSha256");
    value.findingRevision = findingRevision;
    value.desiredStatus = Objects.requireNonNull(desiredStatus, "desiredStatus");
    value.responseAssetVersion = responseAssetVersion;
    value.responseStatus = Objects.requireNonNull(responseStatus, "responseStatus");
    value.releasedOperationLeaseIds = encode(releasedOperationLeaseIds);
    value.releasedOrderUnitReservationIds = encode(releasedOrderUnitReservationIds);
    value.releasedPresentationHoldIds = encode(releasedPresentationHoldIds);
    value.transferSuperseded = transferSuperseded;
    value.createdAt = now();
    return value;
  }

  public UUID getIdempotencyKey() { return idempotencyKey; }
  public String getRequestSha256() { return requestSha256; }
  public UUID getInventoryId() { return inventoryId; }
  public UUID getFindingId() { return findingId; }
  public UUID getAssetId() { return assetId; }
  public UUID getWarehouseId() { return warehouseId; }
  public OffsetDateTime getInventoryCompletedAt() { return inventoryCompletedAt; }
  public long getFinalPlanVersion() { return finalPlanVersion; }
  public String getFinalPlanSha256() { return finalPlanSha256; }
  public long getFindingRevision() { return findingRevision; }
  public RentalItemStatus getDesiredStatus() { return desiredStatus; }
  public long getResponseAssetVersion() { return responseAssetVersion; }
  public RentalItemStatus getResponseStatus() { return responseStatus; }
  public List<UUID> getReleasedOperationLeaseIds() { return decode(releasedOperationLeaseIds); }
  public List<UUID> getReleasedOrderUnitReservationIds() {
    return decode(releasedOrderUnitReservationIds);
  }
  public List<UUID> getReleasedPresentationHoldIds() { return decode(releasedPresentationHoldIds); }
  public boolean isTransferSuperseded() { return transferSuperseded; }
  public OffsetDateTime getCreatedAt() { return createdAt; }

  @Override
  public final boolean equals(Object other) {
    if (this == other) return true;
    if (other == null) return false;
    Class<?> otherClass = other instanceof HibernateProxy proxy
        ? proxy.getHibernateLazyInitializer().getPersistentClass() : other.getClass();
    Class<?> thisClass = this instanceof HibernateProxy proxy
        ? proxy.getHibernateLazyInitializer().getPersistentClass() : getClass();
    return thisClass == otherClass && idempotencyKey != null
        && Objects.equals(idempotencyKey, ((InventoryAssetOutcomeReceipt) other).idempotencyKey);
  }

  @Override
  public final int hashCode() {
    return this instanceof HibernateProxy proxy
        ? proxy.getHibernateLazyInitializer().getPersistentClass().hashCode()
        : getClass().hashCode();
  }

  private static String encode(List<UUID> values) {
    if (values == null) {
      throw new IllegalArgumentException("Inventory outcome receipt IDs are required");
    }
    return values.stream().map(UUID::toString).sorted()
        .reduce((left, right) -> left + "," + right).orElse("");
  }

  private static List<UUID> decode(String value) {
    if (value == null || value.isBlank()) return List.of();
    return Arrays.stream(value.split(",")).map(UUID::fromString).toList();
  }

  private static OffsetDateTime now() {
    return OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
  }
}
