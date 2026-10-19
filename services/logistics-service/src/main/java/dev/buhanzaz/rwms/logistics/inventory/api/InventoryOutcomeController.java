package dev.buhanzaz.rwms.logistics.inventory.api;

import dev.buhanzaz.rwms.logistics.inventory.api.InventoryOutcomeApiModels.ApplyInventoryOutcomeRequest;
import dev.buhanzaz.rwms.logistics.inventory.api.InventoryOutcomeApiModels.ApplyInventoryOutcomeResponse;
import dev.buhanzaz.rwms.logistics.inventory.service.InventoryOutcomeService;
import dev.buhanzaz.rwms.logistics.security.LogisticsAuthorizer;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Private inventory-service boundary for superseding logistics state by completed inventory. */
@RestController
@RequestMapping("/api/internal/logistics/v1/inventory/outcomes")
@RequiredArgsConstructor
public class InventoryOutcomeController {
  private final LogisticsAuthorizer authorizer;
  private final InventoryOutcomeService outcomes;

  /** Applies or resumes one exact final-plan command under its permanent UUID receipt. */
  @PutMapping("/{inventoryId}")
  public ApplyInventoryOutcomeResponse apply(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID inventoryId,
      @RequestHeader("Idempotency-Key") UUID idempotencyKey,
      @Valid @RequestBody ApplyInventoryOutcomeRequest request) {
    authorizer.requireInventoryOutcome(jwt);
    return outcomes.apply(inventoryId, idempotencyKey, request);
  }
}
