package dev.buhanzaz.rwms.logistics.api;

import dev.buhanzaz.rwms.logistics.api.WarehouseOperationMarkRecoveryApiModels.WarehouseOperationMarkRecoveryRequest;
import dev.buhanzaz.rwms.logistics.api.WarehouseOperationMarkRecoveryApiModels.WarehouseOperationMarkRecoveryResponse;
import dev.buhanzaz.rwms.logistics.security.LogisticsAuthorizer;
import dev.buhanzaz.rwms.logistics.service.LogisticsWarehouseOperationMarkStore;
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

/** Administrator-reviewed retry boundary for bounded warehouse mark delivery. */
@RestController
@Validated
@RequestMapping("/api/logistics/v1")
@RequiredArgsConstructor
public class WarehouseOperationMarkRecoveryController {
  private final LogisticsWarehouseOperationMarkStore marks;
  private final LogisticsAuthorizer access;

  @PostMapping("/admin/warehouse-operation-marks/{operationId}/recovery")
  public ResponseEntity<WarehouseOperationMarkRecoveryResponse> recover(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID operationId,
      @RequestParam UUID warehouseId,
      @Valid @RequestBody WarehouseOperationMarkRecoveryRequest request) {
    access.requireWarehouseOperationRecoveryAdministrator(jwt);
    LogisticsWarehouseOperationMarkStore.RecoveryResult result =
        marks.recoverQuarantined(
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
