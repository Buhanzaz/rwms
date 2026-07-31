package dev.buhanzaz.rwms.maintenance.api;

import dev.buhanzaz.rwms.maintenance.security.MaintenanceAuthorizer;
import dev.buhanzaz.rwms.maintenance.service.RepairPlaceService;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/maintenance/v1/repair-places")
public class RepairPlaceController {
  private final RepairPlaceService service;
  private final MaintenanceAuthorizer access;

  public RepairPlaceController(RepairPlaceService service, MaintenanceAuthorizer access) {
    this.service = service;
    this.access = access;
  }

  @GetMapping("/{warehouseId}")
  public RepairPlaceProjectionResponse projection(
      @AuthenticationPrincipal Jwt jwt, @PathVariable UUID warehouseId) {
    access.requireRead(jwt, warehouseId);
    return service.projection(warehouseId);
  }
}
