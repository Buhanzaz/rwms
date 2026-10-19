package dev.buhanzaz.rwms.asset.api;

import static dev.buhanzaz.rwms.asset.api.RentalItemCreationIntentApiModels.CreateRentalItemWithPhotoIntentRequest;
import static dev.buhanzaz.rwms.asset.api.RentalItemCreationIntentApiModels.CreateRentalItemWithPhotoIntentResponse;
import static dev.buhanzaz.rwms.asset.api.RentalItemCreationIntentApiModels.RentalItemCreationIntentCommand;
import static dev.buhanzaz.rwms.asset.api.RentalItemCreationIntentApiModels.RentalItemCreationIntentPage;
import static dev.buhanzaz.rwms.asset.api.RentalItemCreationIntentApiModels.RentalItemCreationIntentResponse;

import dev.buhanzaz.rwms.asset.security.AssetAuthorizer;
import dev.buhanzaz.rwms.asset.service.AssetService;
import dev.buhanzaz.rwms.asset.service.RentalItemCreationIntentService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** HTTP adapter for the asset-owned resumable mandatory-photo creation workflow. */
@RestController
@Validated
@RequestMapping("/api/asset/v1/rental-item-creation-intents")
@RequiredArgsConstructor
public class RentalItemCreationIntentController {
  private final RentalItemCreationIntentService intents;
  private final AssetAuthorizer access;

  @GetMapping
  public RentalItemCreationIntentPage listPending(
      @AuthenticationPrincipal Jwt jwt,
      @RequestParam UUID warehouseId,
      @RequestParam(defaultValue = "0") @Min(0) int page,
      @RequestParam(defaultValue = "50") @Min(1) @Max(200) int size) {
    access.requireRead(jwt, warehouseId);
    return intents.listPending(warehouseId, page, size);
  }

  @PostMapping
  public ResponseEntity<CreateRentalItemWithPhotoIntentResponse> create(
      @AuthenticationPrincipal Jwt jwt,
      @RequestHeader("Idempotency-Key") UUID idempotencyKey,
      @Valid @RequestBody CreateRentalItemWithPhotoIntentRequest request) {
    access.requireEdit(jwt, request.rentalItem().warehouseId());
    AssetService.CreateResult<CreateRentalItemWithPhotoIntentResponse> result =
        intents.create(access.subjectId(jwt), idempotencyKey, request);
    ResponseEntity.BodyBuilder response = ResponseEntity.status(HttpStatus.CREATED);
    if (result.replayed()) response.header("Idempotency-Replayed", "true");
    return response.body(result.response());
  }

  @GetMapping("/{intentId}")
  public RentalItemCreationIntentResponse get(
      @AuthenticationPrincipal Jwt jwt, @PathVariable UUID intentId) {
    RentalItemCreationIntentResponse response = intents.get(intentId);
    access.requireRead(jwt, response.warehouseId());
    return response;
  }

  @PostMapping("/{intentId}/complete")
  public ResponseEntity<RentalItemCreationIntentResponse> complete(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID intentId,
      @RequestHeader("Idempotency-Key") UUID idempotencyKey,
      @Valid @RequestBody RentalItemCreationIntentCommand request) {
    RentalItemCreationIntentResponse current = intents.get(intentId);
    access.requireEdit(jwt, current.warehouseId());
    AssetService.CreateResult<RentalItemCreationIntentResponse> result =
        intents.complete(access.subjectId(jwt), idempotencyKey, intentId, request);
    ResponseEntity.BodyBuilder response = ResponseEntity.ok();
    if (result.replayed()) response.header("Idempotency-Replayed", "true");
    return response.body(result.response());
  }

  @PostMapping("/{intentId}/abandon")
  public ResponseEntity<RentalItemCreationIntentResponse> abandon(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID intentId,
      @RequestHeader("Idempotency-Key") UUID idempotencyKey,
      @Valid @RequestBody RentalItemCreationIntentCommand request) {
    RentalItemCreationIntentResponse current = intents.get(intentId);
    access.requireEdit(jwt, current.warehouseId());
    AssetService.CreateResult<RentalItemCreationIntentResponse> result =
        intents.abandon(access.subjectId(jwt), idempotencyKey, intentId, request);
    ResponseEntity.BodyBuilder response = ResponseEntity.ok();
    if (result.replayed()) response.header("Idempotency-Replayed", "true");
    return response.body(result.response());
  }
}
