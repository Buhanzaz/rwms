package dev.buhanzaz.rwms.warehouse.api;

import dev.buhanzaz.rwms.warehouse.security.WarehouseAuthorizer;
import dev.buhanzaz.rwms.warehouse.service.WarehouseService;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/internal/warehouse/v1/warehouses/logistics")
public class LogisticsWarehouseController {
  private final WarehouseService service;
  private final WarehouseAuthorizer access;

  public LogisticsWarehouseController(WarehouseService service, WarehouseAuthorizer access) {
    this.service = service;
    this.access = access;
  }

  @GetMapping("/{id}/identity")
  public LogisticsWarehouseIdentityResponse identity(
      @AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
    access.requireInternalLogisticsService(jwt);
    return service.logisticsIdentity(id);
  }

  @GetMapping
  public List<LogisticsWarehouseIdentityResponse> list(
      @AuthenticationPrincipal Jwt jwt) {
    access.requireInternalLogisticsService(jwt);
    return service.logisticsIdentities();
  }
}
