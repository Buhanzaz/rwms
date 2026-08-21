package dev.buhanzaz.rwms.maintenance.disposition.api;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.annotation.JsonTypeName;
import dev.buhanzaz.rwms.maintenance.api.MaintenanceApiModels.ActorSnapshot;
import dev.buhanzaz.rwms.maintenance.disposition.domain.PropertyDispositionAssetEffectState;
import dev.buhanzaz.rwms.maintenance.disposition.domain.PropertyDispositionAssetKind;
import dev.buhanzaz.rwms.maintenance.disposition.domain.PropertyDispositionContentsMode;
import dev.buhanzaz.rwms.maintenance.disposition.domain.PropertyDispositionKind;
import dev.buhanzaz.rwms.maintenance.disposition.domain.PropertyDispositionSource;
import dev.buhanzaz.rwms.maintenance.disposition.domain.PropertyDispositionState;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** HTTP inputs and views for the maintenance-owned property disposition aggregate. */
public final class PropertyDispositionApiModels {
  private PropertyDispositionApiModels() {}

  public record CabinContentsDispositionLineInput(
      @NotNull UUID equipmentId,
      @NotNull @Min(0) Long expectedBalanceVersion,
      @NotNull @Min(0) Long moveToStockQuantity) {}

  public record CabinContentsDispositionPlanInput(
      @NotNull PropertyDispositionContentsMode mode,
      @NotEmpty @Size(max = 1000) List<@Valid CabinContentsDispositionLineInput> lines) {}

  @JsonTypeInfo(
      use = JsonTypeInfo.Id.NAME,
      include = JsonTypeInfo.As.EXISTING_PROPERTY,
      property = "assetKind",
      visible = true)
  @JsonSubTypes({
      @JsonSubTypes.Type(value = CreateCabinPropertyDispositionRequest.class, name = "CABIN"),
      @JsonSubTypes.Type(value = CreateEquipmentPropertyDispositionRequest.class, name = "EQUIPMENT")
  })
  public sealed interface CreatePropertyDispositionRequest
      permits CreateCabinPropertyDispositionRequest, CreateEquipmentPropertyDispositionRequest {
    UUID warehouseId();

    PropertyDispositionAssetKind assetKind();

    UUID assetId();

    Long expectedAssetVersion();

    PropertyDispositionKind disposition();

    String reason();

    String evidenceLink();
  }

  @JsonTypeName("CABIN")
  public record CreateCabinPropertyDispositionRequest(
      @NotNull UUID warehouseId,
      @NotNull PropertyDispositionAssetKind assetKind,
      @NotNull UUID assetId,
      @NotNull @Min(0) Long expectedAssetVersion,
      @NotNull PropertyDispositionKind disposition,
      @NotBlank @Size(max = 2000) String reason,
      @Size(max = 2000) String evidenceLink,
      @JsonProperty(required = true) @Valid CabinContentsDispositionPlanInput contentsPlan)
      implements CreatePropertyDispositionRequest {
    @AssertTrue(message = "assetKind must be CABIN")
    @JsonIgnore
    public boolean isCabin() {
      return assetKind == PropertyDispositionAssetKind.CABIN;
    }
  }

  @JsonTypeName("EQUIPMENT")
  public record CreateEquipmentPropertyDispositionRequest(
      @NotNull UUID warehouseId,
      @NotNull PropertyDispositionAssetKind assetKind,
      @NotNull UUID assetId,
      @NotNull @Min(0) Long expectedAssetVersion,
      @NotNull @Min(0) Long expectedSourceBalanceVersion,
      @NotNull @Min(1) Long quantity,
      @NotNull PropertyDispositionKind disposition,
      @NotBlank @Size(max = 2000) String reason,
      @Size(max = 2000) String evidenceLink)
      implements CreatePropertyDispositionRequest {
    @AssertTrue(message = "assetKind must be EQUIPMENT")
    @JsonIgnore
    public boolean isEquipment() {
      return assetKind == PropertyDispositionAssetKind.EQUIPMENT;
    }
  }

  /** The repair route is also a cabin disposition, but binds its root repair/lease proof. */
  public record WriteOffRepairRequest(
      @NotNull @Min(0) Long expectedVersion,
      @NotBlank @Size(max = 2000) String reason,
      @Size(max = 2000) String comment,
      @JsonProperty(required = true) @Valid CabinContentsDispositionPlanInput contentsPlan) {}

  /** Private inventory service input; this always records an equipment LOSS proposal. */
  public record CreateInventoryLossDispositionRequest(
      @NotNull UUID inventorySessionId,
      @NotNull UUID findingId,
      @NotNull UUID warehouseId,
      @NotNull UUID equipmentId,
      @NotBlank @Size(max = 255) String equipmentName,
      @NotNull @Min(0) Long expectedAssetVersion,
      @NotNull @Min(1) Long quantity,
      @NotNull @Min(0) Long expectedSourceBalanceVersion,
      @NotBlank @Size(max = 2000) String reason,
      @Size(max = 2000) String evidenceLink) {}

  /**
   * Private inventory-service input for one cabin that remained missing after the shipment review.
   * Maintenance resolves the current cabin contents itself and freezes them as dispose-with-cabin
   * evidence; inventory must not fabricate balance versions.
   */
  public record CreateInventoryCabinWriteOffRequest(
      @NotNull UUID inventorySessionId,
      @NotNull UUID findingId,
      @NotNull UUID warehouseId,
      @NotNull UUID cabinId,
      @NotNull @Min(0) Long expectedAssetVersion,
      @NotBlank @Size(max = 2000) String reason,
      @Size(max = 2000) String evidenceLink) {}

  public record ApprovePropertyDispositionRequest(
      @NotNull @Min(0) Long expectedVersion,
      @Size(max = 2000) String comment) {}

  public record RejectPropertyDispositionRequest(
      @NotNull @Min(0) Long expectedVersion,
      @NotBlank @Size(max = 2000) String reason) {}

  public record RecoverPropertyDispositionRequest(
      @NotNull @Min(0) Long expectedVersion,
      @NotNull @Min(0) Long expectedRecoveryVersion,
      @NotBlank @Size(max = 2000) String reason) {}

  public record CabinContentsDispositionLine(
      UUID equipmentId,
      String equipmentName,
      String equipmentFormat,
      long currentQuantity,
      long moveToStockQuantity,
      long disposeQuantity,
      long expectedBalanceVersion) {}

  public record CabinContentsDispositionPlan(
      PropertyDispositionContentsMode mode,
      List<CabinContentsDispositionLine> lines) {}

  public record PropertyDispositionRepairChainEntry(UUID repairId, long repairVersion) {}

  public record PropertyDispositionDecisionResponse(
      UUID id,
      long version,
      long recoveryVersion,
      UUID warehouseId,
      PropertyDispositionAssetKind assetKind,
      UUID assetId,
      String assetDisplayName,
      PropertyDispositionKind disposition,
      PropertyDispositionSource source,
      PropertyDispositionState state,
      String reason,
      String evidenceLink,
      Long quantity,
      Long expectedAssetVersion,
      Long expectedSourceBalanceVersion,
      UUID maintenanceCustodyClaimId,
      Long maintenanceCustodyVersion,
      CabinContentsDispositionPlan contentsPlan,
      UUID rootRepairId,
      List<PropertyDispositionRepairChainEntry> repairChain,
      UUID inventorySessionId,
      UUID findingId,
      PropertyDispositionAssetEffectState assetEffectState,
      UUID movementTaskId,
      String failureCode,
      String failureDetail,
      ActorSnapshot requestedBy,
      Instant requestedAt,
      ActorSnapshot reviewedBy,
      Instant reviewedAt,
      String reviewComment,
      Instant effectiveAt,
      Instant updatedAt) {}

  public record PropertyDispositionPage(
      List<PropertyDispositionDecisionResponse> items,
      int page,
      int size,
      long totalElements) {}

  /** Result metadata is kept internal so controllers can set honest HTTP idempotency headers. */
  public record CreateResult(PropertyDispositionDecisionResponse response, boolean replayed) {}
}
