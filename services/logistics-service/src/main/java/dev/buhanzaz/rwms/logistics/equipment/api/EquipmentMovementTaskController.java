package dev.buhanzaz.rwms.logistics.equipment.api;

import dev.buhanzaz.rwms.logistics.equipment.api.EquipmentMovementTaskApiModels.CancelEquipmentMovementTaskRequest;
import dev.buhanzaz.rwms.logistics.equipment.api.EquipmentMovementTaskApiModels.CreateEquipmentMovementTaskRequest;
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

/** Browser-facing schedule/query API; physical balances never change on this command. */
@RestController
@Validated
@RequiredArgsConstructor
@RequestMapping("/api/logistics/v1/equipment-movement-tasks")
public class EquipmentMovementTaskController {
  private final EquipmentMovementTaskService service;
  private final EquipmentMovementTaskProcessor processor;
  private final LogisticsAuthorizer access;

  @PostMapping
  public ResponseEntity<EquipmentMovementTaskResponse> create(
      @AuthenticationPrincipal Jwt jwt,
      @RequestHeader("Idempotency-Key") UUID idempotencyKey,
      @Valid @RequestBody CreateEquipmentMovementTaskRequest request) {
    access.requireEdit(jwt, request.warehouseId());
    EquipmentMovementTaskService.CreateResult result =
        service.create(access.subjectId(jwt), idempotencyKey, request);
    processor.processUntilIdle(result.response().id());
    EquipmentMovementTaskResponse response = service.get(result.response().id());
    return response(response, result.replayed() ? HttpStatus.OK : HttpStatus.CREATED, result.replayed());
  }

  @GetMapping("/{taskId}")
  public EquipmentMovementTaskResponse get(
      @AuthenticationPrincipal Jwt jwt, @PathVariable UUID taskId) {
    EquipmentMovementTaskResponse response = service.get(taskId);
    access.requireRead(jwt, response.warehouseId());
    return response;
  }

  @PostMapping("/{taskId}/cancel")
  public ResponseEntity<EquipmentMovementTaskResponse> cancel(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID taskId,
      @RequestHeader("Idempotency-Key") UUID idempotencyKey,
      @Valid @RequestBody CancelEquipmentMovementTaskRequest request) {
    EquipmentMovementTaskResponse current = service.get(taskId);
    access.requireEdit(jwt, current.warehouseId());
    EquipmentMovementTaskService.MutationResult result =
        service.cancel(access.subjectId(jwt), taskId, idempotencyKey, request);
    processor.processUntilIdle(taskId);
    EquipmentMovementTaskResponse response = service.get(taskId);
    return response(response, HttpStatus.ACCEPTED, result.replayed());
  }

  private static ResponseEntity<EquipmentMovementTaskResponse> response(
      EquipmentMovementTaskResponse body, HttpStatus status, boolean replayed) {
    ResponseEntity.BodyBuilder response =
        ResponseEntity.status(status).header(HttpHeaders.ETAG, '"' + Long.toString(body.version()) + '"');
    if (replayed) response.header("Idempotency-Replayed", "true");
    return response.body(body);
  }
}
