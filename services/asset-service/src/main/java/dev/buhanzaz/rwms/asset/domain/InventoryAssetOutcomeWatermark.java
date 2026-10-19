package dev.buhanzaz.rwms.asset.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.proxy.HibernateProxy;

/**
 * Per-cabin high-water mark that prevents an older completed inventory or final-plan revision from
 * replacing newer physical truth.
 */
@Entity
@Table(name = "inventory_asset_outcome_watermark")
public class InventoryAssetOutcomeWatermark {
  @Id
  @NotNull
  @Column(name = "asset_id", nullable = false)
  private UUID assetId;

  @Version
  @Min(0)
  @Column(name = "version", nullable = false)
  private long version;

  @NotNull
  @Column(name = "inventory_id", nullable = false)
  private UUID inventoryId;

  @NotNull
  @Column(name = "finding_id", nullable = false)
  private UUID findingId;

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

  @Enumerated(EnumType.STRING)
  @Column(name = "desired_status", length = 32)
  private RentalItemStatus desiredStatus;

  @Column(name = "preserve_operational_state", nullable = false)
  private boolean preserveOperationalState;

  @Pattern(regexp = "^[0-9a-f]{64}$")
  @Column(name = "passport_observation_sha256", length = 64)
  private String passportObservationSha256;

  @Pattern(regexp = "^[0-9a-f]{64}$")
  @Column(name = "shipment_contents_sha256", length = 64)
  private String shipmentContentsSha256;

  @NotNull
  @Column(name = "created_at", nullable = false)
  private OffsetDateTime createdAt;

  @NotNull
  @Column(name = "updated_at", nullable = false)
  private OffsetDateTime updatedAt;

  protected InventoryAssetOutcomeWatermark() {}

  /** Creates the first accepted completed-inventory source for one cabin. */
  public static InventoryAssetOutcomeWatermark register(
      UUID assetId,
      UUID inventoryId,
      UUID findingId,
      UUID warehouseId,
      OffsetDateTime inventoryCompletedAt,
      long finalPlanVersion,
      String finalPlanSha256,
      long findingRevision,
      RentalItemStatus desiredStatus,
      boolean preserveOperationalState,
      String passportObservationSha256,
      String shipmentContentsSha256) {
    InventoryAssetOutcomeWatermark value = new InventoryAssetOutcomeWatermark();
    value.assetId = Objects.requireNonNull(assetId, "assetId");
    value.replace(
        inventoryId,
        findingId,
        warehouseId,
        inventoryCompletedAt,
        finalPlanVersion,
        finalPlanSha256,
        findingRevision,
        desiredStatus,
        preserveOperationalState,
        passportObservationSha256,
        shipmentContentsSha256);
    value.createdAt = now();
    value.updatedAt = value.createdAt;
    return value;
  }

  /**
   * Advances or reasserts the accepted source after the caller has compared inventory completion
   * time and immutable final-plan identity.
   */
  public void replace(
      UUID inventoryId,
      UUID findingId,
      UUID warehouseId,
      OffsetDateTime inventoryCompletedAt,
      long finalPlanVersion,
      String finalPlanSha256,
      long findingRevision,
      RentalItemStatus desiredStatus,
      boolean preserveOperationalState,
      String passportObservationSha256,
      String shipmentContentsSha256) {
    if (finalPlanVersion < 1 || findingRevision < 1) {
      throw new IllegalArgumentException("Inventory outcome versions must be positive");
    }
    if (finalPlanSha256 == null || !finalPlanSha256.matches("[0-9a-f]{64}")) {
      throw new IllegalArgumentException("Inventory final plan hash is invalid");
    }
    if (preserveOperationalState != (desiredStatus == null)
        || (!preserveOperationalState && !isOutcomeStatus(desiredStatus))) {
      throw new IllegalArgumentException("Inventory outcome status is invalid");
    }
    if (passportObservationSha256 == null
        || !passportObservationSha256.matches("[0-9a-f]{64}")) {
      throw new IllegalArgumentException("Inventory passport observation hash is invalid");
    }
    if ((!preserveOperationalState && desiredStatus == RentalItemStatus.RENTED)
        != (shipmentContentsSha256 != null
            && shipmentContentsSha256.matches("[0-9a-f]{64}"))) {
      throw new IllegalArgumentException("Inventory shipment contents hash is invalid");
    }
    this.inventoryId = Objects.requireNonNull(inventoryId, "inventoryId");
    this.findingId = Objects.requireNonNull(findingId, "findingId");
    this.warehouseId = Objects.requireNonNull(warehouseId, "warehouseId");
    this.inventoryCompletedAt =
        Objects.requireNonNull(inventoryCompletedAt, "inventoryCompletedAt");
    this.finalPlanVersion = finalPlanVersion;
    this.finalPlanSha256 = finalPlanSha256;
    this.findingRevision = findingRevision;
    this.desiredStatus = desiredStatus;
    this.preserveOperationalState = preserveOperationalState;
    this.passportObservationSha256 = passportObservationSha256;
    this.shipmentContentsSha256 = shipmentContentsSha256;
    this.updatedAt = now();
  }

  /** Returns whether an equal-timestamp retry names the same immutable final-plan finding. */
  public boolean isSameSource(
      UUID inventoryId,
      UUID findingId,
      UUID warehouseId,
      OffsetDateTime inventoryCompletedAt,
      long finalPlanVersion,
      String finalPlanSha256,
      long findingRevision,
      RentalItemStatus desiredStatus,
      boolean preserveOperationalState,
      String passportObservationSha256,
      String shipmentContentsSha256) {
    return isSameBaseSource(
            inventoryId,
            findingId,
            warehouseId,
            inventoryCompletedAt,
            finalPlanVersion,
            finalPlanSha256,
            findingRevision,
            desiredStatus,
            preserveOperationalState)
        && Objects.equals(this.passportObservationSha256, passportObservationSha256)
        && Objects.equals(this.shipmentContentsSha256, shipmentContentsSha256);
  }

  /**
   * Adopts one pre-V39 status-only watermark exactly once without treating the new passport hash
   * as a conflicting equal-time inventory. The next successful replacement stores the hash and
   * removes this legacy eligibility permanently.
   */
  public boolean isLegacySameSource(
      UUID inventoryId,
      UUID findingId,
      UUID warehouseId,
      OffsetDateTime inventoryCompletedAt,
      long finalPlanVersion,
      String finalPlanSha256,
      long findingRevision,
      RentalItemStatus desiredStatus) {
    return passportObservationSha256 == null
        && shipmentContentsSha256 == null
        && !preserveOperationalState
        && desiredStatus != RentalItemStatus.RENTED
        && isSameBaseSource(
            inventoryId,
            findingId,
            warehouseId,
            inventoryCompletedAt,
            finalPlanVersion,
            finalPlanSha256,
            findingRevision,
            desiredStatus,
            false);
  }

  /**
   * Returns whether an equal-time source is a strictly newer final-plan revision of the same
   * completed inventory finding.
   *
   * <p>The final-plan hash and finding payload may change in that revision. Equal and lower plan
   * versions remain fenced by the caller unless the complete immutable source matches exactly.
   */
  public boolean isNewerPlanRevisionOfSameCompletedFinding(
      UUID inventoryId,
      UUID findingId,
      OffsetDateTime inventoryCompletedAt,
      long finalPlanVersion) {
    return Objects.equals(this.inventoryId, inventoryId)
        && Objects.equals(this.findingId, findingId)
        && Objects.equals(this.inventoryCompletedAt, inventoryCompletedAt)
        && finalPlanVersion > this.finalPlanVersion;
  }

  private boolean isSameBaseSource(
      UUID inventoryId,
      UUID findingId,
      UUID warehouseId,
      OffsetDateTime inventoryCompletedAt,
      long finalPlanVersion,
      String finalPlanSha256,
      long findingRevision,
      RentalItemStatus desiredStatus,
      boolean preserveOperationalState) {
    return Objects.equals(this.inventoryId, inventoryId)
        && Objects.equals(this.findingId, findingId)
        && Objects.equals(this.warehouseId, warehouseId)
        && Objects.equals(this.inventoryCompletedAt, inventoryCompletedAt)
        && this.finalPlanVersion == finalPlanVersion
        && Objects.equals(this.finalPlanSha256, finalPlanSha256)
        && this.findingRevision == findingRevision
        && this.desiredStatus == desiredStatus
        && this.preserveOperationalState == preserveOperationalState;
  }

  @PrePersist
  void prePersist() {
    OffsetDateTime timestamp = now();
    if (createdAt == null) createdAt = timestamp;
    if (updatedAt == null) updatedAt = timestamp;
  }

  @PreUpdate
  void preUpdate() {
    updatedAt = now();
  }

  public UUID getAssetId() { return assetId; }
  public long getVersion() { return version; }
  public UUID getInventoryId() { return inventoryId; }
  public UUID getFindingId() { return findingId; }
  public UUID getWarehouseId() { return warehouseId; }
  public OffsetDateTime getInventoryCompletedAt() { return inventoryCompletedAt; }
  public long getFinalPlanVersion() { return finalPlanVersion; }
  public String getFinalPlanSha256() { return finalPlanSha256; }
  public long getFindingRevision() { return findingRevision; }
  public RentalItemStatus getDesiredStatus() { return desiredStatus; }
  public boolean isPreserveOperationalState() { return preserveOperationalState; }
  public String getPassportObservationSha256() { return passportObservationSha256; }
  public String getShipmentContentsSha256() { return shipmentContentsSha256; }
  public OffsetDateTime getCreatedAt() { return createdAt; }
  public OffsetDateTime getUpdatedAt() { return updatedAt; }

  @Override
  public final boolean equals(Object other) {
    if (this == other) return true;
    if (other == null) return false;
    Class<?> otherClass = other instanceof HibernateProxy proxy
        ? proxy.getHibernateLazyInitializer().getPersistentClass() : other.getClass();
    Class<?> thisClass = this instanceof HibernateProxy proxy
        ? proxy.getHibernateLazyInitializer().getPersistentClass() : getClass();
    return thisClass == otherClass && assetId != null
        && Objects.equals(assetId, ((InventoryAssetOutcomeWatermark) other).assetId);
  }

  @Override
  public final int hashCode() {
    return this instanceof HibernateProxy proxy
        ? proxy.getHibernateLazyInitializer().getPersistentClass().hashCode()
        : getClass().hashCode();
  }

  private static boolean isOutcomeStatus(RentalItemStatus status) {
    return status == RentalItemStatus.FREE
        || status == RentalItemStatus.REPAIR
        || status == RentalItemStatus.CAPITAL_REPAIR
        || status == RentalItemStatus.RENTED;
  }

  private static OffsetDateTime now() {
    return OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
  }
}
