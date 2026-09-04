package dev.buhanzaz.rwms.logistics.contractor.share;

import static dev.buhanzaz.rwms.logistics.contractor.share.ContractorRouteShareApiModels.*;

import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Authenticated dispatcher boundary for explicit creation and revocation of contractor links. */
@RestController
@Validated
@RequiredArgsConstructor
@RequestMapping("/api/logistics/v1/warehouses/{warehouseId}/contractor-route-shares")
public class ContractorRouteShareController {
  private final ContractorRouteShareService routeShares;

  /** Creates one expiring link or returns the byte-equivalent subject-scoped replay. */
  @PostMapping
  public ResponseEntity<ContractorRouteShareResponse> create(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID warehouseId,
      @RequestHeader("Idempotency-Key") UUID idempotencyKey,
      @Valid @RequestBody CreateContractorRouteShareRequest request) {
    ContractorRouteShareService.CreationResult result =
        routeShares.create(jwt, warehouseId, idempotencyKey, request);
    ResponseEntity.BodyBuilder response =
        result.replayed() ? ResponseEntity.ok() : ResponseEntity.status(201);
    if (result.replayed()) response.header("Idempotency-Replayed", "true");
    return response.body(result.response());
  }

  /** Revokes the capability under its current aggregate version. */
  @PostMapping("/{shareId}/revoke")
  public ContractorRouteShareResponse revoke(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID warehouseId,
      @PathVariable UUID shareId,
      @Valid @RequestBody RevokeContractorRouteShareRequest request) {
    return routeShares.revoke(jwt, warehouseId, shareId, request);
  }
}
