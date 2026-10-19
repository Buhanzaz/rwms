package dev.buhanzaz.rwms.inventory.api;

import dev.buhanzaz.rwms.inventory.security.InventoryAuthorizer;
import dev.buhanzaz.rwms.inventory.service.ReturnEstimateInspectionReader;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Public warehouse-scoped read boundary for return-estimate inventory inspection status. */
@RestController
@RequestMapping("/api/inventory/v1/return-estimates")
@RequiredArgsConstructor
public class ReturnEstimateInspectionController {
  private final ReturnEstimateInspectionReader inspections;
  private final InventoryAuthorizer access;

  @GetMapping("/{estimateId}/inspection")
  public ReturnEstimateInspectionResponse get(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID estimateId,
      @RequestParam UUID warehouseId) {
    access.requireRead(jwt, warehouseId);
    return inspections.get(estimateId, warehouseId);
  }
}
