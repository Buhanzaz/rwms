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

/**
 * Private lifecycle decisions for resource owners; this controller is never gateway-routable.
 *
 * <p>The authenticated service client determines the owner. A request body can therefore neither
 * choose another owner's readiness record nor broaden its lifecycle authority.
 */
@RestController
@RequestMapping("/api/internal/warehouse/v1/warehouses")
public class WarehouseLifecycleController {
  private final WarehouseService service;
  private final WarehouseAuthorizer access;

  /**
   * Creates the private lifecycle controller.
   *
   * @param service application boundary that owns lifecycle decisions
   * @param access authorization boundary that derives the lifecycle owner
   */
  public WarehouseLifecycleController(WarehouseService service, WarehouseAuthorizer access) {
    this.service = service;
    this.access = access;
  }

  /**
   * Returns the exact admission decision for one direction of work.
   *
   * <p>Clients must use this instead of deriving admission from the compatibility {@code active}
   * field, which cannot express outbound draining work.
   *
   * @param jwt authenticated lifecycle-owner credential
   * @param id stable warehouse identity
   * @param direction proposed work direction
   * @return exact lifecycle admission decision
   */
  @GetMapping("/{id}/admission")
  public WarehouseOperationAdmissionResponse admission(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID id,
      @RequestParam WarehouseOperationDirection direction) {
    access.requireInternalLifecycleAdmissionReader(jwt);
    return service.admission(id, direction);
  }

  /**
   * Records one immutable readiness confirmation for the authenticated resource owner.
   *
   * <p>An exact lost-response retry returns the stored confirmation rather than requiring a newer
   * aggregate version.
   *
   * @param jwt authenticated lifecycle-owner credential
   * @param id stable warehouse identity
   * @param request version observed before the first confirmation attempt
   * @return immutable confirmation acknowledgement
   */
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
