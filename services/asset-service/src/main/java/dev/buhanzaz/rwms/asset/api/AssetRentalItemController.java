package dev.buhanzaz.rwms.asset.api;

import static dev.buhanzaz.rwms.asset.api.AssetApiModels.*;

import dev.buhanzaz.rwms.asset.domain.RentalItemStatus;
import dev.buhanzaz.rwms.asset.security.AssetAuthorizer;
import dev.buhanzaz.rwms.asset.service.AssetService;
import dev.buhanzaz.rwms.asset.service.CabinCompositionService;
import dev.buhanzaz.rwms.asset.service.PresentationHoldService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Validated
@RequestMapping("/api/asset/v1/rental-items")
@RequiredArgsConstructor
public class AssetRentalItemController {
  private final AssetService service;
  private final CabinCompositionService composition;
  private final PresentationHoldService presentationHolds;
  private final AssetAuthorizer access;

  @GetMapping
  public RentalItemPage list(
      @AuthenticationPrincipal Jwt jwt,
      @RequestParam UUID warehouseId,
      @RequestParam(defaultValue = "0") @Min(0) int page,
      @RequestParam(defaultValue = "50") @Min(1) @Max(200) int size,
      @RequestParam(required = false) String search,
      @RequestParam(name = "excludeStatus", required = false)
          Set<RentalItemStatus> excludedStatuses) {
    access.requireRead(jwt, warehouseId);
    return service.listRentalItems(
        warehouseId, page, size, search, excludedStatuses);
  }

  @GetMapping("/available")
  public RentalItemPage available(
      @AuthenticationPrincipal Jwt jwt,
      @RequestParam UUID warehouseId,
      @RequestParam(defaultValue = "0") @Min(0) int page,
      @RequestParam(defaultValue = "50") @Min(1) @Max(200) int size,
      @RequestParam(required = false) @Size(max = 128) String search) {
    access.requireRead(jwt, warehouseId);
    return presentationHolds.availableRentalItems(warehouseId, page, size, search);
  }

  @PostMapping("/availability")
  public PresentationHoldApiModels.CabinAvailabilityResponse availability(
      @AuthenticationPrincipal Jwt jwt,
      @Valid @RequestBody PresentationHoldApiModels.CabinAvailabilityRequest request) {
    access.requireRead(jwt, request.warehouseId());
    return presentationHolds.availability(request);
  }

  @GetMapping("/creation-options")
  public RentalItemCreationOptionsResponse creationOptions(
      @AuthenticationPrincipal Jwt jwt, @RequestParam UUID warehouseId) {
    access.requireRead(jwt, warehouseId);
    return composition.creationOptions();
  }

  @GetMapping("/{id}")
  public RentalItemResponse get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
    RentalItemResponse response = service.rentalItem(id);
    access.requireRead(jwt, response.warehouseId());
    return response;
  }

  @PostMapping
  public ResponseEntity<RentalItemResponse> create(
      @AuthenticationPrincipal Jwt jwt,
      @RequestHeader("Idempotency-Key") UUID idempotencyKey,
      @Valid @RequestBody CreateRentalItemRequest request) {
    access.requireEdit(jwt, request.warehouseId());
    AssetService.CreateResult<RentalItemResponse> result = service.createRentalItem(access.subjectId(jwt), idempotencyKey, request);
    ResponseEntity.BodyBuilder response = ResponseEntity.status(HttpStatus.CREATED);
    if (result.replayed()) response.header("Idempotency-Replayed", "true");
    return response.body(result.response());
  }

  @PutMapping("/{id}/passport")
  public RentalItemResponse updatePassport(
      @AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @Valid @RequestBody UpdatePassportRequest request) {
    access.requireEdit(jwt, service.rentalItem(id).warehouseId());
    return service.updatePassport(id, request);
  }

  @PutMapping("/{id}/status")
  public RentalItemResponse updateStatus(
      @AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @Valid @RequestBody UpdateStatusRequest request) {
    access.requireEdit(jwt, service.rentalItem(id).warehouseId());
    return service.updateStatus(id, request);
  }

  @PutMapping("/{id}/warehouse")
  public RentalItemResponse updateWarehouse(
      @AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @Valid @RequestBody UpdateWarehouseRequest request) {
    RentalItemResponse current = service.rentalItem(id);
    access.requireEdit(jwt, current.warehouseId());
    access.requireEdit(jwt, request.warehouseId());
    return service.updateWarehouse(id, request);
  }

  @PutMapping("/{id}/general-comment")
  public RentalItemResponse updateGeneralComment(
      @AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @Valid @RequestBody UpdateGeneralCommentRequest request) {
    access.requireEdit(jwt, service.rentalItem(id).warehouseId());
    return service.updateGeneralComment(id, request);
  }

  @GetMapping("/{id}/manual-notes")
  public List<ManualNoteResponse> notes(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
    access.requireRead(jwt, service.rentalItem(id).warehouseId());
    return service.manualNotes(id);
  }

  @PostMapping("/{id}/manual-notes")
  public ResponseEntity<ManualNoteResponse> addNote(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID id,
      @RequestHeader("Idempotency-Key") UUID idempotencyKey,
      @Valid @RequestBody AddManualNoteRequest request) {
    access.requireEdit(jwt, service.rentalItem(id).warehouseId());
    AssetService.CreateResult<ManualNoteResponse> result = service.addManualNote(access.subjectId(jwt), idempotencyKey, id, request);
    ResponseEntity.BodyBuilder response = ResponseEntity.status(HttpStatus.CREATED);
    if (result.replayed()) response.header("Idempotency-Replayed", "true");
    return response.body(result.response());
  }
}
