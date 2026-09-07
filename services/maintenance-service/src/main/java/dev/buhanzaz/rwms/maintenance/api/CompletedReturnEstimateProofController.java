package dev.buhanzaz.rwms.maintenance.api;

import dev.buhanzaz.rwms.maintenance.security.MaintenanceAuthorizer;
import dev.buhanzaz.rwms.maintenance.service.CompletedReturnEstimateProofReader;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Private inventory boundary exposing verified completed-return estimate provenance. */
@RestController
@RequestMapping("/api/internal/maintenance/v1/inventory/return-estimates")
@RequiredArgsConstructor
public class CompletedReturnEstimateProofController {
  private final CompletedReturnEstimateProofReader proofs;
  private final MaintenanceAuthorizer access;

  @GetMapping("/{estimateId}")
  public CompletedReturnEstimateProofResponse get(
      @AuthenticationPrincipal Jwt jwt, @PathVariable UUID estimateId) {
    access.requireInventoryService(jwt);
    return proofs.get(estimateId);
  }
}
