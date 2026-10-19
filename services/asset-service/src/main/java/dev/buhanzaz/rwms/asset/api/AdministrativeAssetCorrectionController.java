package dev.buhanzaz.rwms.asset.api;

import static dev.buhanzaz.rwms.asset.api.AdministrativeAssetCorrectionApiModels.*;

import dev.buhanzaz.rwms.asset.administrative.AdministrativeAssetCorrectionService;
import dev.buhanzaz.rwms.asset.security.AssetAuthorizer;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Public administrator boundary for correcting erroneous recorded warehouse custody. */
@RestController
@Validated
@RequestMapping("/api/asset/v1/administrative-corrections")
public class AdministrativeAssetCorrectionController {
  private final AdministrativeAssetCorrectionService service;
  private final AssetAuthorizer access;

  public AdministrativeAssetCorrectionController(
      AdministrativeAssetCorrectionService service, AssetAuthorizer access) {
    this.service = service;
    this.access = access;
  }

  @PostMapping
  public ResponseEntity<AdministrativeAssetCorrectionResponse> create(
      @AuthenticationPrincipal Jwt jwt,
      @RequestHeader("Idempotency-Key") UUID idempotencyKey,
      @Valid @RequestBody CreateAdministrativeAssetCorrectionRequest request) {
    access.requireAdministrativeCorrection(
        jwt, request.sourceWarehouseId(), request.targetWarehouseId());
    CommandResult<AdministrativeAssetCorrectionResponse> result =
        service.create(access.subjectId(jwt), idempotencyKey, request);
    ResponseEntity.BodyBuilder response =
        ResponseEntity.status(result.replayed() ? HttpStatus.OK : HttpStatus.CREATED);
    if (result.replayed()) {
      response.header("Idempotency-Replayed", "true");
    }
    return response.body(result.response());
  }
}
