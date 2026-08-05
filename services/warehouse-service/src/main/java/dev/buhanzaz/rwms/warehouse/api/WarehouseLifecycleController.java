package dev.buhanzaz.rwms.warehouse.api;

import dev.buhanzaz.rwms.warehouse.security.WarehouseAuthorizer;
import dev.buhanzaz.rwms.warehouse.service.WarehouseLifecycleReadinessOwner;
import dev.buhanzaz.rwms.warehouse.service.WarehouseOperationDirection;
import dev.buhanzaz.rwms.warehouse.service.WarehouseService;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Private lifecycle decisions for resource owners; this controller is never gateway-routable. */
@RestController
@RequestMapping("/api/internal/warehouse/v1/warehouses")
public class WarehouseLifecycleController {
  private final WarehouseService service;
  private final WarehouseAuthorizer access;

  public WarehouseLifecycleController(WarehouseService service, WarehouseAuthorizer access) {
    this.service = service;
    this.access = access;
  }

  @GetMapping("/{id}/admission")
  public WarehouseOperationAdmissionResponse admission(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID id,
      @RequestParam WarehouseOperationDirection direction) {
    access.requireInternalLifecycleAdmissionReader(jwt);
    return service.admission(id, direction);
  }

  @PostMapping("/{id}/lifecycle-readiness")
  public WarehouseLifecycleReadinessConfirmationResponse confirmReadiness(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID id,
      @Valid @RequestBody WarehouseLifecycleReadinessRequest request) {
    WarehouseLifecycleReadinessOwner owner =
        access.requireInternalLifecycleReadinessConfirmer(jwt);
    return service.confirmLifecycleReadiness(id, owner, request);
  }
}
