package dev.buhanzaz.rwms.asset.api;

import static dev.buhanzaz.rwms.asset.api.LogisticsFurnitureMovementApiModels.*;

import dev.buhanzaz.rwms.asset.security.AssetAuthorizer;
import dev.buhanzaz.rwms.asset.service.LogisticsFurnitureMovementPlanService;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Narrow private endpoint: logistics receives a plan, never a direct balance mutation. */
@RestController
@Validated
@RequestMapping("/api/internal/asset/v1/logistics/rental-items")
@RequiredArgsConstructor
public class LogisticsFurnitureMovementController {
  private final LogisticsFurnitureMovementPlanService service;
  private final AssetAuthorizer access;

  @PostMapping("/{rentalItemId}/furniture-movement-plan")
  public CabinFurnitureMovementPlan plan(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID rentalItemId,
      @Valid @RequestBody CabinFurnitureMovementPlanRequest request) {
    access.requireLogisticsAssetAccess(jwt);
    return service.plan(rentalItemId, request);
  }
}
