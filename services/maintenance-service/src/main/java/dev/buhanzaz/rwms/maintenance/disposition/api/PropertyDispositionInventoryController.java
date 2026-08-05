package dev.buhanzaz.rwms.maintenance.disposition.api;

import dev.buhanzaz.rwms.maintenance.disposition.application.PropertyDispositionApplicationService;
import dev.buhanzaz.rwms.maintenance.disposition.api.PropertyDispositionApiModels.CreateInventoryLossDispositionRequest;
import dev.buhanzaz.rwms.maintenance.disposition.api.PropertyDispositionApiModels.CreateResult;
import dev.buhanzaz.rwms.maintenance.disposition.api.PropertyDispositionApiModels.PropertyDispositionDecisionResponse;
import dev.buhanzaz.rwms.maintenance.security.MaintenanceAuthorizer;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
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
import java.util.UUID;

/** Exact inventory-service boundary for shortages; it records a proposal and never applies loss. */
@RestController
@Validated
@RequestMapping("/api/internal/maintenance/v1/inventory")
@RequiredArgsConstructor
public class PropertyDispositionInventoryController {
  private final PropertyDispositionApplicationService dispositions;
  private final MaintenanceAuthorizer access;

  @PostMapping("/dispositions")
  public ResponseEntity<PropertyDispositionDecisionResponse> createLoss(
      @AuthenticationPrincipal Jwt jwt,
      @RequestHeader("Idempotency-Key") UUID idempotencyKey,
      @Valid @RequestBody CreateInventoryLossDispositionRequest request) {
    access.requireInventoryService(jwt);
    CreateResult result = dispositions.createInventoryLoss(idempotencyKey, request);
    ResponseEntity.BodyBuilder response = ResponseEntity.status(
        result.replayed() ? HttpStatus.OK : HttpStatus.CREATED);
    if (result.replayed()) response.header("Idempotency-Replayed", "true");
    return response.body(result.response());
  }
}
