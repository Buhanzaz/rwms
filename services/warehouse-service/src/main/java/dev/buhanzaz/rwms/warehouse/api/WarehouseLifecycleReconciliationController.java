package dev.buhanzaz.rwms.warehouse.api;

import dev.buhanzaz.rwms.warehouse.security.WarehouseAuthorizer;
import dev.buhanzaz.rwms.warehouse.service.WarehouseLifecycleReadinessOwner;
import dev.buhanzaz.rwms.warehouse.service.WarehouseService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Durable pull reconciliation for lifecycle owners; it is never gateway-routable.
 *
 * <p>It is the recovery mechanism after a missed lifecycle event or service restart, including for
 * an owner that currently has no live records at the warehouse.
 */
@RestController
@Validated
@RequestMapping("/api/internal/warehouse/v1/lifecycle")
public class WarehouseLifecycleReconciliationController {
  private final WarehouseService service;
  private final WarehouseAuthorizer access;

  /**
   * Creates the private readiness-reconciliation controller.
   *
   * @param service application boundary that provides durable readiness work
   * @param access authorization boundary that derives a lifecycle owner
   */
  public WarehouseLifecycleReconciliationController(
      WarehouseService service, WarehouseAuthorizer access) {
    this.service = service;
    this.access = access;
  }

  /**
   * Returns a keyset page of draining warehouses still awaiting the caller's confirmation.
   *
   * @param jwt authenticated lifecycle-owner credential
   * @param after optional UUID keyset cursor from a previous page
   * @param limit maximum number of work items, from 1 through 500
   * @return owner-scoped readiness work page
   */
  @GetMapping("/readiness-work")
  public WarehouseLifecycleReadinessWorkPageResponse readinessWork(
      @AuthenticationPrincipal Jwt jwt,
      @RequestParam(required = false) UUID after,
      @RequestParam(defaultValue = "100") @Min(1) @Max(500) int limit) {
    WarehouseLifecycleReadinessOwner owner = access.requireInternalLifecycleWorkReader(jwt);
    return service.lifecycleReadinessWork(owner, after, limit);
  }
}
