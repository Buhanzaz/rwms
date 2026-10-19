package dev.buhanzaz.rwms.inventory.eventing;

import dev.buhanzaz.rwms.inventory.security.InventoryAuthorizer;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** HTTP adapter for administrator-reviewed inventory event delivery recovery. */
@RestController
@Validated
@RequestMapping("/api/inventory/v1/operations")
public class InventoryEventingRecoveryController {
  private final InventoryEventingRecoveryService recovery;
  private final InventoryAuthorizer access;

  public InventoryEventingRecoveryController(
      InventoryEventingRecoveryService recovery, InventoryAuthorizer access) {
    this.recovery = recovery;
    this.access = access;
  }

  @PostMapping("/outbox/{eventId}/requeue")
  public InventoryEventingRecoveryResponse requeueOutbox(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID eventId,
      @Valid @RequestBody InventoryEventingRecoveryRequest request) {
    access.requireEventingRecovery(jwt);
    return recovery.requeueOutbox(
        eventId, request.expectedReviewVersion(), access.subjectId(jwt), request.reason());
  }

  @PostMapping("/dead-letters/{dltId}/requeue")
  public InventoryEventingRecoveryResponse requeueDeadLetter(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID dltId,
      @Valid @RequestBody InventoryEventingRecoveryRequest request) {
    access.requireEventingRecovery(jwt);
    return recovery.requeueDeadLetter(
        dltId, request.expectedReviewVersion(), access.subjectId(jwt), request.reason());
  }
}
