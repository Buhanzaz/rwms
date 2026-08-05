package dev.buhanzaz.rwms.maintenance.api;

import static dev.buhanzaz.rwms.maintenance.api.WarehouseOperationMarkRecoveryApiModels.*;

import dev.buhanzaz.rwms.maintenance.security.MaintenanceAuthorizer;
import dev.buhanzaz.rwms.maintenance.service.WarehouseOperationMarkRecoveryService;
import dev.buhanzaz.rwms.maintenance.service.WarehouseOperationMarkStore;
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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Public, administrator-only recovery for a bounded warehouse operation-mark delivery. */
@RestController
@Validated
@RequestMapping("/api/maintenance/v1")
@RequiredArgsConstructor
public class WarehouseOperationMarkRecoveryController {
  private final WarehouseOperationMarkRecoveryService recovery;
  private final MaintenanceAuthorizer access;

  @PostMapping("/warehouse-operation-marks/{operationId}/recovery")
  public ResponseEntity<WarehouseOperationMarkRecoveryResponse> recover(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID operationId,
      @RequestParam UUID warehouseId,
      @Valid @RequestBody WarehouseOperationMarkRecoveryRequest request) {
    access.requireWarehouseOperationRecoveryAdministrator(jwt, warehouseId);
    WarehouseOperationMarkStore.RecoveryResult result = recovery.recover(
        warehouseId,
        operationId,
        request.expectedRecoveryVersion(),
        access.subjectId(jwt),
        request.reason());
    ResponseEntity.BodyBuilder response = ResponseEntity.ok();
    if (result.replayed()) response.header("Idempotency-Replayed", "true");
    return response.body(
        new WarehouseOperationMarkRecoveryResponse(
            result.warehouseId(),
            result.operationId(),
            result.state(),
            result.attemptCount(),
            result.recoveryVersion(),
            result.lastErrorCode(),
            result.recoveredBySubjectId(),
            result.recoveryReason(),
            result.recoveredAt()));
  }
}
