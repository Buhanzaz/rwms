package dev.buhanzaz.rwms.asset.api;

import dev.buhanzaz.rwms.asset.domain.CabinCatalogKind;
import dev.buhanzaz.rwms.asset.domain.RentalItemHtmlImportRowAction;
import dev.buhanzaz.rwms.asset.domain.RentalItemHtmlImportState;
import dev.buhanzaz.rwms.asset.domain.RentalItemStatus;
import dev.buhanzaz.rwms.asset.service.RentalItemHtmlParser.FurnitureLine;
import dev.buhanzaz.rwms.asset.service.RentalItemHtmlParser.ParserDiagnostic;
import dev.buhanzaz.rwms.asset.service.RentalItemHtmlParser.SourceValue;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * HTTP transport model container for rental item html import.
 * Its records are boundary representations, not persistence entities.
 */
public final class RentalItemHtmlImportApiModels {
  private RentalItemHtmlImportApiModels() {}

  public record HtmlImportSummaryResponse(
      UUID id,
      long version,
      UUID warehouseId,
      RentalItemHtmlImportState state,
      int rowCount,
      int selectedCount,
      int invalidCount,
      int unresolvedCount,
      int mediaLinkCount,
      int warningCount,
      UUID mediaJobId,
      String failureCode,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt) {}

  public record HtmlImportDetailResponse(
      UUID id,
      long version,
      UUID warehouseId,
      RentalItemHtmlImportState state,
      int rowCount,
      int selectedCount,
      int invalidCount,
      int unresolvedCount,
      int mediaLinkCount,
      int warningCount,
      UUID mediaJobId,
      String failureCode,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt,
      List<HtmlImportCatalogTarget> catalogTargets,
      List<HtmlImportEquipmentTarget> equipmentTargets,
      List<HtmlImportSourceCandidate> sourceCandidates,
      HtmlImportPlan plan) {}

  public record HtmlImportCatalogTarget(
      UUID id, CabinCatalogKind kind, String name, boolean active) {}

  public record HtmlImportEquipmentTarget(
      UUID id, String name, boolean active) {}

  public enum HtmlImportCandidateKind {
    TYPE,
    DIMENSION,
    FINISHING,
    CATEGORY,
    CHARACTERISTIC,
    STATUS,
    EQUIPMENT
  }

  public record HtmlImportSourceCandidate(
      HtmlImportCandidateKind kind,
      String sourceValue,
      String suggestedValue,
      long occurrences,
      UUID suggestedTargetId,
      boolean required) {}

  public record ExistingRentalItemPreview(
      UUID id,
      long version,
      String number,
      RentalItemStatus status,
      UUID rentalTypeId,
      String rentalType,
      UUID dimensionId,
      String dimension,
      UUID finishingId,
      String finishing,
      UUID categoryId,
      String category,
      Boolean linoleum,
      String generalComment,
      Map<String, Object> passport) {}

  public record HtmlImportRowResponse(
      UUID id,
      String sourceRowId,
      int sourcePosition,
      String sourceNumber,
      String proposedNumber,
      RentalItemHtmlImportRowAction action,
      UUID targetRentalItemId,
      SourceValue rentalType,
      SourceValue dimension,
      SourceValue finishing,
      SourceValue category,
      List<SourceValue> characteristics,
      Boolean linoleum,
      String storageState,
      SourceValue status,
      RentalItemStatus proposedStatus,
      String comment,
      boolean hasPhotoLink,
      List<FurnitureLine> furniture,
      LocalDate shipmentDate,
      String tenant,
      BigDecimal price,
      List<ParserDiagnostic> diagnostics,
      HtmlImportRowDecision decision,
      ExistingRentalItemPreview existingRentalItem) {}

  public record HtmlImportRowPage(
      List<HtmlImportRowResponse> content,
      long page,
      long size,
      long totalElements,
      long totalPages) {}

  public enum HtmlImportMappingAction {
    MAP,
    CREATE,
    IGNORE
  }

  public record HtmlImportCatalogMapping(
      @NotNull CabinCatalogKind kind,
      @NotBlank @Size(max = 255) String sourceValue,
      @NotNull HtmlImportMappingAction action,
      UUID targetId,
      @Size(max = 255) String stagedName) {}

  public record HtmlImportEquipmentMapping(
      @NotBlank @Size(max = 255) String sourceValue,
      @NotNull HtmlImportMappingAction action,
      UUID targetId,
      @Size(max = 255) String stagedName) {}

  public record HtmlImportStatusMapping(
      @NotBlank @Size(max = 255) String sourceValue,
      @NotNull RentalItemStatus targetStatus) {}

  public enum MergeWinner {
    SOURCE,
    TARGET
  }

  public record HtmlImportRowDecision(
      @NotBlank
          @Size(max = 64)
          @Pattern(regexp = "^[A-Za-z0-9_-]+$")
          String sourceRowId,
      @NotNull RentalItemHtmlImportRowAction action,
      @Size(max = 128) String proposedNumber,
      UUID targetRentalItemId,
      @Min(0) Long targetExpectedVersion,
      UUID rentalTypeId,
      UUID dimensionId,
      UUID finishingId,
      UUID categoryId,
      @Size(max = 100) List<@NotNull UUID> characteristicIds,
      RentalItemStatus status,
      Boolean linoleum,
      @Size(max = 4000) String comment,
      @Size(max = 32) Map<@NotBlank @Size(max = 64) String, @NotNull MergeWinner>
          mergeChoices) {}

  public record HtmlImportPlan(
      List<HtmlImportCatalogMapping> catalogMappings,
      List<HtmlImportEquipmentMapping> equipmentMappings,
      List<HtmlImportStatusMapping> statusMappings,
      List<HtmlImportRowDecision> rows) {
    public HtmlImportPlan {
      catalogMappings = catalogMappings == null ? List.of() : List.copyOf(catalogMappings);
      equipmentMappings =
          equipmentMappings == null ? List.of() : List.copyOf(equipmentMappings);
      statusMappings = statusMappings == null ? List.of() : List.copyOf(statusMappings);
      rows = rows == null ? List.of() : List.copyOf(rows);
    }
  }

  public record UpdateHtmlImportPlanRequest(
      @NotNull @Min(0) Long expectedVersion,
      @NotNull @Valid @Size(max = 1000) List<@Valid HtmlImportCatalogMapping> catalogMappings,
      @NotNull @Valid @Size(max = 100) List<@Valid HtmlImportEquipmentMapping> equipmentMappings,
      @NotNull @Valid @Size(max = 100) List<@Valid HtmlImportStatusMapping> statusMappings,
      @NotNull @Valid @Size(max = 5000) List<@Valid HtmlImportRowDecision> rows) {
    public HtmlImportPlan plan() {
      return new HtmlImportPlan(catalogMappings, equipmentMappings, statusMappings, rows);
    }
  }

  public record CommitHtmlImportRequest(@NotNull @Min(0) Long expectedVersion) {}

  public record RetryHtmlImportMediaRequest(@NotNull @Min(0) Long expectedVersion) {}

  public record SkipHtmlImportMediaRequest(@NotNull @Min(0) Long expectedVersion) {}

  public record HtmlImportMediaReplacement(
      @NotNull UUID rowId,
      @NotBlank
          @Pattern(regexp = "^https://disk\\.yandex\\.ru/d/[A-Za-z0-9_-]{14}$")
          String publicUrl) {}

  public record ReplaceHtmlImportMediaRequest(
      @NotNull @Min(0) Long expectedVersion,
      @NotNull @Valid @Size(min = 1, max = 500) List<@Valid HtmlImportMediaReplacement> replacements) {}
}
