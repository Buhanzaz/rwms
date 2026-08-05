package dev.buhanzaz.rwms.asset.api;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.annotation.JsonTypeName;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Deliberately narrow, administrator-only correction inputs. A correction
 * repairs recorded custody; it is never a substitute for a logistics
 * document representing a physical move.
 */
public final class AdministrativeAssetCorrectionApiModels {
  private AdministrativeAssetCorrectionApiModels() {}

  public enum AdministrativeCorrectionAssetKind {
    CABIN,
    EQUIPMENT
  }

  @JsonTypeInfo(
      use = JsonTypeInfo.Id.NAME,
      include = JsonTypeInfo.As.EXISTING_PROPERTY,
      property = "assetKind",
      visible = true)
  @JsonSubTypes({
      @JsonSubTypes.Type(value = CreateCabinAdministrativeCorrectionRequest.class, name = "CABIN"),
      @JsonSubTypes.Type(value = CreateEquipmentAdministrativeCorrectionRequest.class, name = "EQUIPMENT")
  })
  public sealed interface CreateAdministrativeAssetCorrectionRequest
      permits CreateCabinAdministrativeCorrectionRequest,
          CreateEquipmentAdministrativeCorrectionRequest {
    AdministrativeCorrectionAssetKind assetKind();

    UUID assetId();

    String reason();

    String evidenceLink();

    UUID sourceWarehouseId();

    UUID targetWarehouseId();
  }

  @JsonTypeName("CABIN")
  public record CreateCabinAdministrativeCorrectionRequest(
      @NotNull AdministrativeCorrectionAssetKind assetKind,
      @NotNull UUID assetId,
      @NotNull @Min(0) Long expectedVersion,
      @NotNull UUID recordedWarehouseId,
      @NotNull UUID correctedWarehouseId,
      @NotBlank @Size(max = 2000) String reason,
      @NotBlank @Size(max = 2000) String evidenceLink)
      implements CreateAdministrativeAssetCorrectionRequest {
    @Override
    public UUID sourceWarehouseId() {
      return recordedWarehouseId;
    }

    @Override
    public UUID targetWarehouseId() {
      return correctedWarehouseId;
    }

    @AssertTrue(message = "assetKind must be CABIN and warehouses must differ")
    @JsonIgnore
    public boolean isCabinCorrection() {
      return assetKind == AdministrativeCorrectionAssetKind.CABIN
          && recordedWarehouseId != null
          && !recordedWarehouseId.equals(correctedWarehouseId);
    }
  }

  @JsonTypeName("EQUIPMENT")
  public record CreateEquipmentAdministrativeCorrectionRequest(
      @NotNull AdministrativeCorrectionAssetKind assetKind,
      @NotNull UUID assetId,
      @NotNull UUID sourceWarehouseId,
      @NotNull @Min(0) Long sourceExpectedVersion,
      @NotNull UUID targetWarehouseId,
      @NotNull @Min(0) Long targetExpectedVersion,
      @NotNull @Min(1) Long quantity,
      @NotBlank @Size(max = 2000) String reason,
      @NotBlank @Size(max = 2000) String evidenceLink)
      implements CreateAdministrativeAssetCorrectionRequest {
    @AssertTrue(message = "assetKind must be EQUIPMENT and warehouses must differ")
    @JsonIgnore
    public boolean isEquipmentCorrection() {
      return assetKind == AdministrativeCorrectionAssetKind.EQUIPMENT
          && sourceWarehouseId != null
          && !sourceWarehouseId.equals(targetWarehouseId);
    }
  }

  public record AdministrativeAssetCorrectionResponse(
      UUID id,
      AdministrativeCorrectionAssetKind assetKind,
      UUID assetId,
      UUID sourceWarehouseId,
      UUID targetWarehouseId,
      Long quantity,
      String reason,
      String evidenceLink,
      String requestSha256,
      UUID appliedBy,
      OffsetDateTime appliedAt) {}

  /** Controller maps a permanent idempotent replay to 200 and a new correction to 201. */
  public record CommandResult<T>(T response, boolean replayed) {}
}
