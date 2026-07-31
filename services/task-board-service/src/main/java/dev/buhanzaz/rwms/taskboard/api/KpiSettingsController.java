package dev.buhanzaz.rwms.taskboard.api;

import static dev.buhanzaz.rwms.taskboard.api.KpiSettingsApiModels.*;

import dev.buhanzaz.rwms.taskboard.security.AccessLevel;
import dev.buhanzaz.rwms.taskboard.security.WarehouseAccessAuthorizer;
import dev.buhanzaz.rwms.taskboard.service.KpiSettingsService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@Validated
@RequestMapping("/api/warehouses/{warehouseId}/task-board/kpi-settings")
public class KpiSettingsController {
  private final KpiSettingsService service;
  private final WarehouseAccessAuthorizer access;

  @GetMapping
  public WarehouseKpiSettingsResponse get(
      @AuthenticationPrincipal Jwt jwt, @PathVariable UUID warehouseId) {
    read(jwt, warehouseId);
    return service.get(warehouseId);
  }

  @PutMapping("/palette")
  public WarehouseKpiSettingsResponse savePalette(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID warehouseId,
      @Valid @RequestBody SaveKpiPaletteRequest request) {
    write(jwt, warehouseId);
    return service.savePalette(warehouseId, request);
  }

  @PutMapping("/work-schedule")
  public WarehouseKpiSettingsResponse saveWorkSchedule(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID warehouseId,
      @Valid @RequestBody SaveWorkScheduleRequest request) {
    write(jwt, warehouseId);
    return service.saveWorkSchedule(warehouseId, request);
  }

  @DeleteMapping("/work-schedule/pending")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void deletePendingWorkSchedule(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID warehouseId,
      @RequestParam @Min(0) long expectedVersion) {
    write(jwt, warehouseId);
    service.deletePendingWorkSchedule(warehouseId, expectedVersion);
  }

  @PostMapping("/activate")
  public WarehouseKpiSettingsResponse activate(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID warehouseId,
      @RequestHeader("Idempotency-Key") UUID operationId,
      @Valid @RequestBody ActivateKpiSettingsRequest request) {
    write(jwt, warehouseId);
    return service.activate(warehouseId, operationId, request);
  }

  private void read(Jwt jwt, UUID warehouseId) {
    access.requireUserScope(jwt, "rwms.read");
    access.requireWarehouse(jwt, warehouseId, AccessLevel.VIEW, false);
  }

  private void write(Jwt jwt, UUID warehouseId) {
    access.requireUserScope(jwt, "rwms.write");
    access.requireWarehouse(jwt, warehouseId, AccessLevel.MANAGE, false);
  }
}
