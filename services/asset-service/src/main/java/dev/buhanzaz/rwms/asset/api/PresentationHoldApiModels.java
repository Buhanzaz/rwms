package dev.buhanzaz.rwms.asset.api;

import dev.buhanzaz.rwms.asset.domain.RentalItemStatus;
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

public final class PresentationHoldApiModels {
  private PresentationHoldApiModels() {}

  public record ActorInput(
      @NotNull UUID actorSubjectId,
      @NotBlank
          @Size(max = 32)
          @Pattern(
              regexp =
                  "^(SYSTEM_ADMIN|WMS_ADMIN|WAREHOUSE_MANAGER|RENTAL_MANAGER|VIEWER)$")
          String actorRole) {}

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
          String actorRole) {}

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
          String actorRole) {}

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

  public record ReplacePresentationHoldsResponse(
      UUID presentationId, OffsetDateTime expiresAt, List<PresentationHoldView> holds) {}

  public record ConvertPresentationHoldsResponse(
      UUID presentationId,
      UUID orderId,
      List<OrderAssetApiModels.OrderUnitReservationView> reservations,
      List<UUID> releasedRentalItemIds) {}

  public record CabinSearchGroup(
      @Size(max = 255) String cabinType,
      @Size(max = 255) String finish,
      @Size(max = 255) String dimensions,
      @Size(max = 255) String category,
      @Size(max = 2000) String characteristics,
      Boolean linoleum,
      @NotNull @Min(1) @Max(30) Integer quantity) {}

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
      @NotNull @Size(min = 1, max = 20) List<@NotNull @Valid CabinSearchGroup> groups) {}

  public record CabinAvailabilityRequest(
      @NotNull UUID warehouseId,
      @NotNull @Size(min = 1, max = 100) List<@NotNull UUID> rentalItemIds) {}

  public record CabinFacetResponse(
      UUID warehouseId,
      List<String> cabinTypes,
      List<String> finishes,
      List<String> dimensions,
      List<String> categories) {}

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
      OffsetDateTime updatedAt) {}

  public record CabinSearchGroupResult(
      CabinSearchGroup group, List<AvailableCabin> cabins) {}

  public record CabinSearchResponse(
      UUID warehouseId, OffsetDateTime expiresAt, List<CabinSearchGroupResult> groups) {}

  public record CabinAvailability(
      UUID rentalItemId, boolean available, String reason) {}

  public record CabinAvailabilityResponse(
      UUID warehouseId, List<CabinAvailability> items) {}

  public record CabinSnapshotsResponse(
      UUID warehouseId, List<AvailableCabin> items) {}
}
