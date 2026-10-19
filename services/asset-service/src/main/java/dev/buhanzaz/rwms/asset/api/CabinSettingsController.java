package dev.buhanzaz.rwms.asset.api;

import static dev.buhanzaz.rwms.asset.api.AssetApiModels.*;

import dev.buhanzaz.rwms.asset.domain.CabinCatalogKind;
import dev.buhanzaz.rwms.asset.security.AssetAuthorizer;
import dev.buhanzaz.rwms.asset.service.CabinCompositionService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Global operator settings for types, dimensions, finishing and characteristics. */
@RestController
@Validated
@RequestMapping("/api/asset/v1/cabin-settings")
@RequiredArgsConstructor
public class CabinSettingsController {
  private final CabinCompositionService service;
  private final AssetAuthorizer access;

  @GetMapping
  public CabinSettingsResponse settings(@AuthenticationPrincipal Jwt jwt) {
    access.requireGlobalCatalogRead(jwt);
    return service.settings();
  }

  @PostMapping("/items")
  public ResponseEntity<CabinCatalogItemResponse> createItem(
      @AuthenticationPrincipal Jwt jwt,
      @RequestHeader("Idempotency-Key") UUID idempotencyKey,
      @Valid @RequestBody CreateCabinCatalogItemRequest request) {
    access.requireGlobalCatalogManagement(jwt);
    CabinCompositionService.CreateResult<CabinCatalogItemResponse> result =
        service.createCatalogItem(access.subjectId(jwt), idempotencyKey, request);
    ResponseEntity.BodyBuilder response = ResponseEntity.status(HttpStatus.CREATED);
    if (result.replayed()) response.header("Idempotency-Replayed", "true");
    return response.body(result.response());
  }

  @PutMapping("/items/{id}")
  public CabinCatalogItemResponse updateItem(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID id,
      @Valid @RequestBody UpdateCabinCatalogItemRequest request) {
    access.requireGlobalCatalogManagement(jwt);
    return service.updateCatalogItem(id, request);
  }

  @PutMapping("/{kind}/order")
  public CabinSettingsResponse replaceOrder(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable CabinCatalogKind kind,
      @Valid @RequestBody ReplaceCabinCatalogOrderRequest request) {
    access.requireGlobalCatalogManagement(jwt);
    return service.replaceCatalogOrder(kind, request);
  }

  @DeleteMapping("/items/{id}")
  public ResponseEntity<Void> deleteItem(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID id,
      @RequestParam @Min(0) long expectedVersion) {
    access.requireGlobalCatalogManagement(jwt);
    service.deleteCatalogItem(id, expectedVersion);
    return ResponseEntity.noContent().build();
  }

  @PutMapping("/types/{typeId}/dimensions")
  public CabinSettingsResponse replaceTypeDimensions(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID typeId,
      @Valid @RequestBody ReplaceCabinTypeDimensionsRequest request) {
    access.requireGlobalCatalogManagement(jwt);
    return service.replaceTypeDimensions(typeId, request);
  }
}
