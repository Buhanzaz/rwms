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

@RestController
@RequestMapping("/api/internal/warehouse/v1/warehouses/inventory")
public class InventoryWarehouseController {
  private final WarehouseService service;
  private final WarehouseAuthorizer access;

  public InventoryWarehouseController(WarehouseService service, WarehouseAuthorizer access) {
    this.service = service;
    this.access = access;
  }

  @GetMapping("/{id}/metadata")
  public InventoryWarehouseMetadataResponse metadata(
      @AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
    access.requireInternalInventoryService(jwt);
    return service.inventoryMetadata(id);
  }
}
