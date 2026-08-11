package dev.buhanzaz.rwms.asset.api;

import dev.buhanzaz.rwms.asset.api.AssetApiModels.EquipmentContentResponse;
import dev.buhanzaz.rwms.asset.domain.RentalItemStatus;
import dev.buhanzaz.rwms.asset.api.OrderAssetApiModels.OrderUnitEquipmentRequirements;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * HTTP transport model container for presentation hold.
 * Its records are boundary representations, not persistence entities.
 */
public final class PresentationHoldApiModels {
  private PresentationHoldApiModels() {}

  /** Authenticated logistics actor metadata used by hold release commands. */
  public record ActorInput(
      @NotNull UUID actorSubjectId,
      @NotBlank
          @Size(max = 32)
          @Pattern(
              regexp =
                  "^(SYSTEM_ADMIN|WMS_ADMIN|WAREHOUSE_MANAGER|RENTAL_MANAGER|VIEWER)$")
          String actorRole) {}

  /** Exact presentation selection replacement with expiry, actor and optional source scope. */
  public record ReplacePresentationHoldsRequest(
      @NotNull UUID warehouseId,
      @NotNull @Size(min = 1, max = 100) List<@NotNull UUID> rentalItemIds,
      @NotNull OffsetDateTime expiresAt,
      @NotNull UUID actorSubjectId,
      @NotBlank
          @Size(max = 32)
          @Pattern(
              regexp =
                  "^(SYSTEM_ADMIN|WMS_ADMIN|WAREHOUSE_MANAGER|RENTAL_MANAGER|VIEWER)$")
          String actorRole,
      UUID sourceHoldScopeId) {}

  /**
   * Converts selected presentation holds into one logistics order reservation. Non-null units are
   * the authoritative all-order furniture composition and commit in the same transaction.
   */
  public record ConvertPresentationHoldsRequest(
      @NotNull UUID orderId,
      @NotNull UUID warehouseId,
      @NotNull @Size(min = 1, max = 100) List<@NotNull UUID> selectedRentalItemIds,
      @NotNull UUID clientId,
      @NotBlank @Size(max = 512) String tenantSnapshot,
      @NotNull UUID actorSubjectId,
      @NotBlank
          @Size(max = 32)
          @Pattern(
              regexp =
                  "^(SYSTEM_ADMIN|WMS_ADMIN|WAREHOUSE_MANAGER|RENTAL_MANAGER|VIEWER)$")
          String actorRole,
      @Size(max = 100)
          List<@NotNull @Valid OrderUnitEquipmentRequirements> units) {
    public ConvertPresentationHoldsRequest(
        UUID orderId,
        UUID warehouseId,
        List<UUID> selectedRentalItemIds,
        UUID clientId,
        String tenantSnapshot,
        UUID actorSubjectId,
        String actorRole) {
      this(
          orderId,
          warehouseId,
          selectedRentalItemIds,
          clientId,
          tenantSnapshot,
          actorSubjectId,
          actorRole,
          null);
    }
  }

  /** Current or historical asset-owned presentation hold projection. */
  public record PresentationHoldView(
      UUID holdId,
      long version,
      UUID presentationId,
      UUID rentalItemId,
      UUID warehouseId,
      String state,
      OffsetDateTime expiresAt,
      UUID orderId,
      OffsetDateTime createdAt,
      OffsetDateTime endedAt) {}

  /**
   * Authoritative presentation hold set and its atomically captured cabin snapshots. Replacement
   * responses preserve the request rental-item order; release responses contain no cabins.
   */
  public record ReplacePresentationHoldsResponse(
      UUID presentationId,
      OffsetDateTime expiresAt,
      List<PresentationHoldView> holds,
      List<AvailableCabin> cabins) {}

  /** Conversion result with created reservations and released unselected items. */
  public record ConvertPresentationHoldsResponse(
      UUID presentationId,
      UUID orderId,
      List<OrderAssetApiModels.OrderUnitReservationView> reservations,
      List<UUID> releasedRentalItemIds,
      List<OrderAssetApiModels.OrderEquipmentReservationView> equipmentReservations) {
    public ConvertPresentationHoldsResponse(
        UUID presentationId,
        UUID orderId,
        List<OrderAssetApiModels.OrderUnitReservationView> reservations,
        List<UUID> releasedRentalItemIds) {
      this(presentationId, orderId, reservations, releasedRentalItemIds, List.of());
    }
  }

  /** One exact cabin filter and requested quantity within a grouped hold search. */
  public record CabinSearchGroup(
      @Size(max = 255) String cabinType,
      @Size(max = 255) String finish,
      @Size(max = 255) String dimensions,
      @Size(max = 255) String category,
      @Size(max = 2000) String characteristics,
      Boolean linoleum,
      @NotNull @Min(1) @Max(30) Integer quantity) {}

  /** Exact grouped availability command with explicit append-or-replace hold semantics. */
  public record CabinSearchRequest(
      @NotNull UUID warehouseId,
      @NotNull UUID holdScopeId,
      @NotNull OffsetDateTime expiresAt,
      @NotNull UUID actorSubjectId,
      @NotBlank
          @Size(max = 32)
          @Pattern(
              regexp =
                  "^(SYSTEM_ADMIN|WMS_ADMIN|WAREHOUSE_MANAGER|RENTAL_MANAGER|VIEWER)$")
          String actorRole,
      @NotNull @Size(min = 1, max = 20) List<@NotNull @Valid CabinSearchGroup> groups,
      @Pattern(regexp = "^(APPEND|REPLACE)$") String resultMode) {
    /** Returns the transaction mode used by the asset owner for this exact hold mutation. */
    public String normalizedResultMode() {
      return "APPEND".equals(resultMode) ? "APPEND" : "REPLACE";
    }
  }

  /** Warehouse-scoped rental item IDs to check or snapshot. */
  public record CabinAvailabilityRequest(
      @NotNull UUID warehouseId,
      @NotNull @Size(min = 1, max = 100) List<@NotNull UUID> rentalItemIds) {}

  /** Exact dimensions currently related to one available cabin type. */
  public record CabinTypeDimensions(String cabinType, List<String> dimensions) {}

  /** Availability-backed facets and type relations used for safe interactive clarification. */
  public record CabinFacetResponse(
      UUID warehouseId,
      List<String> cabinTypes,
      List<String> finishes,
      List<String> dimensions,
      List<String> categories,
      List<String> characteristics,
      List<CabinTypeDimensions> typeDimensions) {}

  /**
   * Cabin snapshot returned by availability search, reference lookup and internal reads. Once an
   * active presentation hold is created, its contents remain fenced until release or conversion.
   */
  public record AvailableCabin(
      UUID id,
      long version,
      UUID warehouseId,
      String number,
      RentalItemStatus status,
      String rentalType,
      String dimensions,
      String finishing,
      String category,
      String characteristics,
      Boolean linoleum,
      Map<String, Object> passport,
      List<String> tags,
      List<EquipmentContentResponse> contents,
      OffsetDateTime updatedAt) {}

  /** One exact requested group paired with the cabins held for it. */
  public record CabinSearchGroupResult(
      CabinSearchGroup group, List<AvailableCabin> cabins) {}

  /** Grouped search result whose expiry matches the created or renewed holds. */
  public record CabinSearchResponse(
      UUID warehouseId, OffsetDateTime expiresAt, List<CabinSearchGroupResult> groups) {}

  /** Facts-only warehouse cabin page used for number, type and characteristic reference lookup. */
  public record CabinCatalogPage(
      UUID warehouseId,
      List<AvailableCabin> content,
      long page,
      long size,
      long totalElements,
      long totalPages) {}

  /** Current availability decision and optional safe reason for one rental item. */
  public record CabinAvailability(
      UUID rentalItemId, boolean available, String reason) {}

  /** Warehouse-scoped batch availability decisions. */
  public record CabinAvailabilityResponse(
      UUID warehouseId, List<CabinAvailability> items) {}

  /** Warehouse-scoped cabin snapshots without creating presentation holds. */
  public record CabinSnapshotsResponse(
      UUID warehouseId, List<AvailableCabin> items) {}
}
