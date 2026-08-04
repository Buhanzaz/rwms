package dev.buhanzaz.rwms.logistics.equipment.api;

import dev.buhanzaz.rwms.logistics.equipment.api.EquipmentMovementTaskApiModels.CreateMaintenanceEquipmentMovementTaskRequest;
import dev.buhanzaz.rwms.logistics.equipment.api.EquipmentMovementTaskApiModels.EquipmentMovementTaskResponse;
import dev.buhanzaz.rwms.logistics.equipment.service.EquipmentMovementTaskProcessor;
import dev.buhanzaz.rwms.logistics.equipment.service.EquipmentMovementTaskService;
import dev.buhanzaz.rwms.logistics.security.LogisticsAuthorizer;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Private maintenance boundary for one approved cabin-content disposition movement. */
@RestController
@Validated
@RequiredArgsConstructor
@RequestMapping("/api/internal/logistics/v1/maintenance/equipment-movement-tasks")
public class MaintenanceEquipmentMovementTaskController {
  private final EquipmentMovementTaskService service;
  private final EquipmentMovementTaskProcessor processor;
  private final LogisticsAuthorizer access;

  @PostMapping
  public ResponseEntity<EquipmentMovementTaskResponse> create(
      @AuthenticationPrincipal Jwt jwt,
      @RequestHeader("Idempotency-Key") UUID idempotencyKey,
      @Valid @RequestBody CreateMaintenanceEquipmentMovementTaskRequest request) {
    access.requireMaintenanceEquipmentMovementIntake(jwt);
    EquipmentMovementTaskService.CreateResult result =
        service.createFromMaintenance(idempotencyKey, request);
    processor.processUntilIdle(result.response().id());
    EquipmentMovementTaskResponse response = service.getMaintenance(result.response().id());
    ResponseEntity.BodyBuilder builder =
        ResponseEntity.status(result.replayed() ? HttpStatus.OK : HttpStatus.CREATED)
            .header(HttpHeaders.ETAG, '"' + Long.toString(response.version()) + '"');
    if (result.replayed()) builder.header("Idempotency-Replayed", "true");
    return builder.body(response);
  }

  @GetMapping("/{taskId}")
  public EquipmentMovementTaskResponse get(
      @AuthenticationPrincipal Jwt jwt, @PathVariable UUID taskId) {
    access.requireMaintenanceEquipmentMovementIntake(jwt);
    return service.getMaintenance(taskId);
  }
}
