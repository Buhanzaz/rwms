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
 * Narrow internal metadata boundary for {@code inventory-service}.
 *
 * <p>It exposes only active warehouse identity, version and timezone; draining and inactive
 * warehouses are intentionally hidden to prevent new inventory work.
 */
@RestController
@RequestMapping("/api/internal/warehouse/v1/warehouses/inventory")
public class InventoryWarehouseController {
  private final WarehouseService service;
  private final WarehouseAuthorizer access;

  /**
   * Creates the inventory-specific private controller.
   *
   * @param service application boundary that resolves active inventory metadata
   * @param access authorization boundary for the inventory-service credential
   */
  public InventoryWarehouseController(WarehouseService service, WarehouseAuthorizer access) {
    this.service = service;
    this.access = access;
  }

  /**
   * Returns the active warehouse projection needed to fence inventory commands.
   *
   * @param jwt authenticated inventory-service credential
   * @param id stable warehouse identity
   * @return active-only inventory metadata
   */
  @GetMapping("/{id}/metadata")
  public InventoryWarehouseMetadataResponse metadata(
      @AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
    access.requireInternalInventoryService(jwt);
    return service.inventoryMetadata(id);
  }
}
