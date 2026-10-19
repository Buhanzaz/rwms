package dev.buhanzaz.rwms.logistics.inventory.api;

import dev.buhanzaz.rwms.logistics.inventory.api.NormalReturnInspectionApiModels.NormalReturnInspectionResponse;
import dev.buhanzaz.rwms.logistics.inventory.service.NormalReturnInspectionService;
import dev.buhanzaz.rwms.logistics.security.LogisticsAuthorizer;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Exact inventory-service reader for completed normal return inspection proof. */
@RestController
@RequestMapping("/api/internal/logistics/v1/inventory/returns")
@RequiredArgsConstructor
public class NormalReturnInspectionController {
  private final LogisticsAuthorizer authorizer;
  private final NormalReturnInspectionService inspections;

  @GetMapping("/{returnId}/inspection")
  public NormalReturnInspectionResponse get(
      @AuthenticationPrincipal Jwt jwt, @PathVariable UUID returnId) {
    authorizer.requireInventoryOutcome(jwt);
    return inspections.get(returnId);
  }
}
