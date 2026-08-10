package dev.buhanzaz.rwms.logistics.driver.settings.api;

import dev.buhanzaz.rwms.logistics.driver.settings.api.ShipmentTaskSettingsApiModels.ShipmentTaskSettingsResponse;
import dev.buhanzaz.rwms.logistics.driver.settings.api.ShipmentTaskSettingsApiModels.UpdateShipmentTaskSettingsRequest;
import dev.buhanzaz.rwms.logistics.driver.settings.service.ShipmentTaskSettingsService;
import dev.buhanzaz.rwms.logistics.security.LogisticsAuthorizer;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Authenticated HTTP boundary for the warehouse policy that limits cabins in one shipment task.
 */
@RestController
@Validated
@RequiredArgsConstructor
@RequestMapping("/api/logistics/v1/warehouses/{warehouseId}/shipment-task-settings")
public class ShipmentTaskSettingsController {
  private final ShipmentTaskSettingsService settings;
  private final LogisticsAuthorizer access;

  /** Reads the selected warehouse's effective grouped-shipment cap. */
  @GetMapping
  public ShipmentTaskSettingsResponse get(
      @AuthenticationPrincipal Jwt jwt, @PathVariable UUID warehouseId) {
    access.requireRead(jwt, warehouseId);
    return settings.get(warehouseId, access.subjectId(jwt));
  }

  /** Changes the selected warehouse's cap through a MANAGE-scoped optimistic update. */
  @PutMapping
  public ShipmentTaskSettingsResponse update(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID warehouseId,
      @Valid @RequestBody UpdateShipmentTaskSettingsRequest request) {
    access.requireManage(jwt, warehouseId);
    return settings.update(warehouseId, access.subjectId(jwt), request);
  }
}
