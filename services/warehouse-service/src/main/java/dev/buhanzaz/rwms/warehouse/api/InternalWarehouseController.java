package dev.buhanzaz.rwms.warehouse.api;

import dev.buhanzaz.rwms.warehouse.security.WarehouseAuthorizer;
import dev.buhanzaz.rwms.warehouse.service.WarehouseService;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Least-privilege internal existence lookups for authentication and asset-service.
 *
 * <p>These routes are private service-to-service boundaries, not gateway routes. Separate methods
 * intentionally prevent one service credential from being reused for another service's contract.
 */
@RestController
@RequestMapping("/api/internal/warehouse/v1/warehouses")
public class InternalWarehouseController {
  private final WarehouseService service;
  private final WarehouseAuthorizer access;

  /**
   * Creates the private existence controller.
   *
   * @param service application boundary that resolves warehouse state
   * @param access authorization boundary for private client identities
   */
  public InternalWarehouseController(WarehouseService service, WarehouseAuthorizer access) {
    this.service = service;
    this.access = access;
  }

  /**
   * Returns only the existence projection required by {@code auth-service}.
   *
   * @param jwt authenticated auth-service credential
   * @param id stable warehouse identity
   * @return minimal existence projection
   */
  @GetMapping("/{id}/existence")
  public InternalWarehouseExistenceResponse existence(
      @AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
    access.requireInternalAuthService(jwt);
    return service.existence(id);
  }

  /**
   * Returns the separate legacy incoming-admission projection required by {@code asset-service}.
   *
   * @param jwt authenticated asset-service credential
   * @param id stable warehouse identity
   * @return minimal existence projection
   */
  @GetMapping("/asset/{id}/existence")
  public InternalWarehouseExistenceResponse assetExistence(
      @AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
    access.requireInternalAssetService(jwt);
    return service.existence(id);
  }
}
