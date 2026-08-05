package dev.buhanzaz.rwms.warehouse.api;

import dev.buhanzaz.rwms.warehouse.api.WarehouseOutboxRecoveryApiModels.WarehouseOutboxRecoveryRequest;
import dev.buhanzaz.rwms.warehouse.api.WarehouseOutboxRecoveryApiModels.WarehouseOutboxRecoveryResponse;
import dev.buhanzaz.rwms.warehouse.eventing.WarehouseOutboxRecoveryAccessAuthorizer;
import dev.buhanzaz.rwms.warehouse.eventing.WarehouseOutboxRecoveryService;
import dev.buhanzaz.rwms.warehouse.eventing.WarehouseOutboxRecoveryStore;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Public, reviewed recovery boundary. It never permits editing the stored event envelope. */
@RestController
@Validated
@RequestMapping("/api/warehouse/v1/admin/outbox-events")
public class WarehouseOutboxRecoveryController {
  private final WarehouseOutboxRecoveryService recovery;
  private final WarehouseOutboxRecoveryAccessAuthorizer access;

  public WarehouseOutboxRecoveryController(
      WarehouseOutboxRecoveryService recovery, WarehouseOutboxRecoveryAccessAuthorizer access) {
    this.recovery = recovery;
    this.access = access;
  }

  @PostMapping("/{eventId}/recovery")
  public ResponseEntity<WarehouseOutboxRecoveryResponse> recover(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID eventId,
      @Valid @RequestBody WarehouseOutboxRecoveryRequest request) {
    UUID reviewedBySubjectId = access.requireRecoveryAdministrator(jwt);
    WarehouseOutboxRecoveryStore.RecoveryResult result =
        recovery.recover(
            eventId, request.expectedReviewVersion(), reviewedBySubjectId, request.reason());
    ResponseEntity.BodyBuilder response = ResponseEntity.ok();
    if (result.replayed()) response.header("Idempotency-Replayed", "true");
    return response.body(
        new WarehouseOutboxRecoveryResponse(
            result.eventId(),
            result.aggregateType(),
            result.aggregateId(),
            result.aggregateVersion(),
            result.status(),
            result.attemptCount(),
            result.reviewVersion(),
            result.lastErrorCode(),
            result.reviewedBySubjectId(),
            result.recoveryReason(),
            result.recoveredAt()));
  }
}
