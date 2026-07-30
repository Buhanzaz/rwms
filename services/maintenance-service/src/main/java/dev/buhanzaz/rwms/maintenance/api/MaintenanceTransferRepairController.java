package dev.buhanzaz.rwms.maintenance.api;

import static dev.buhanzaz.rwms.maintenance.api.MaintenanceApiModels.CompleteTransferRepairRequest;
import static dev.buhanzaz.rwms.maintenance.api.MaintenanceApiModels.CompleteTransferRepairResponse;
import static dev.buhanzaz.rwms.maintenance.api.MaintenanceApiModels.PrepareTransferRepairResponse;
import static dev.buhanzaz.rwms.maintenance.api.MaintenanceApiModels.TransferRepairArrivalPreflightResponse;
import static dev.buhanzaz.rwms.maintenance.api.MaintenanceApiModels.TransferRepairRequest;

import dev.buhanzaz.rwms.maintenance.security.MaintenanceAuthorizer;
import dev.buhanzaz.rwms.maintenance.service.MaintenanceApplicationService;
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

/** Private logistics saga boundary for moving the existing active repair chain. */
@RestController
@Validated
@RequestMapping(
    "/api/internal/maintenance/v1/logistics/transfers/{transferId}/lines/{lineId}")
@RequiredArgsConstructor
public class MaintenanceTransferRepairController {
  private final MaintenanceApplicationService transfers;
  private final MaintenanceAuthorizer access;

  @PostMapping("/prepare-departure")
  public ResponseEntity<PrepareTransferRepairResponse> prepareDeparture(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID transferId,
      @PathVariable UUID lineId,
      @RequestHeader("Idempotency-Key") UUID idempotencyKey,
      @Valid @RequestBody TransferRepairRequest request) {
    access.requireLogisticsService(jwt);
    MaintenanceApplicationService.CreateResult<PrepareTransferRepairResponse> result =
        transfers.prepareTransferDeparture(transferId, lineId, idempotencyKey, request);
    return response(result);
  }

  @PostMapping("/arrival-preflight")
  public TransferRepairArrivalPreflightResponse arrivalPreflight(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID transferId,
      @PathVariable UUID lineId,
      @Valid @RequestBody TransferRepairRequest request) {
    access.requireLogisticsService(jwt);
    return transfers.transferArrivalPreflight(transferId, lineId, request);
  }

  @PostMapping("/complete-arrival")
  public ResponseEntity<CompleteTransferRepairResponse> completeArrival(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID transferId,
      @PathVariable UUID lineId,
      @RequestHeader("Idempotency-Key") UUID idempotencyKey,
      @Valid @RequestBody CompleteTransferRepairRequest request) {
    access.requireLogisticsService(jwt);
    MaintenanceApplicationService.CreateResult<CompleteTransferRepairResponse> result =
        transfers.completeTransferArrival(transferId, lineId, idempotencyKey, request);
    return response(result);
  }

  private static <T> ResponseEntity<T> response(
      MaintenanceApplicationService.CreateResult<T> result) {
    ResponseEntity.BodyBuilder response = ResponseEntity.ok();
    if (result.replayed()) {
      response.header("Idempotency-Replayed", "true");
    }
    return response.body(result.response());
  }
}
