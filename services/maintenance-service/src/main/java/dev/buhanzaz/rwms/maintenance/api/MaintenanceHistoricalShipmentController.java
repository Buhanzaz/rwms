package dev.buhanzaz.rwms.maintenance.api;

import dev.buhanzaz.rwms.maintenance.api.MaintenanceApiModels.HistoricalShipmentRepairClosureRequest;
import dev.buhanzaz.rwms.maintenance.api.MaintenanceApiModels.HistoricalShipmentRepairClosureResponse;
import dev.buhanzaz.rwms.maintenance.security.MaintenanceAuthorizer;
import dev.buhanzaz.rwms.maintenance.service.HistoricalShipmentRepairClosureService;
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

/**
 * Private logistics boundary that closes active maintenance work before one imported rental
 * shipment. It accepts only the exact logistics-service machine principal.
 */
@RestController
@Validated
@RequestMapping("/api/internal/maintenance/v1/logistics/historical-shipments/{shipmentId}")
@RequiredArgsConstructor
public class MaintenanceHistoricalShipmentController {
  private final HistoricalShipmentRepairClosureService closures;
  private final MaintenanceAuthorizer access;

  @PostMapping("/close")
  public ResponseEntity<HistoricalShipmentRepairClosureResponse> close(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID shipmentId,
      @RequestHeader("Idempotency-Key") UUID idempotencyKey,
      @Valid @RequestBody HistoricalShipmentRepairClosureRequest request) {
    access.requireLogisticsService(jwt);
    HistoricalShipmentRepairClosureService.CloseResult result =
        closures.close(shipmentId, idempotencyKey, request);
    ResponseEntity.BodyBuilder response = ResponseEntity.ok();
    if (result.replayed()) response.header("Idempotency-Replayed", "true");
    return response.body(result.response());
  }
}
