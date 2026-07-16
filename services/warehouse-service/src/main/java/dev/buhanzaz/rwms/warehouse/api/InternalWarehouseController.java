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
@RequestMapping("/api/internal/warehouse/v1/warehouses")
public class InternalWarehouseController {
  private final WarehouseService service;
  private final WarehouseAuthorizer access;

  public InternalWarehouseController(WarehouseService service, WarehouseAuthorizer access) {
    this.service = service;
    this.access = access;
  }

  @GetMapping("/{id}/existence")
  public InternalWarehouseExistenceResponse existence(
      @AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
    access.requireInternalAuthService(jwt);
    return service.existence(id);
  }

  @GetMapping("/asset/{id}/existence")
  public InternalWarehouseExistenceResponse assetExistence(
      @AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
    access.requireInternalAssetService(jwt);
    return service.existence(id);
  }
}
