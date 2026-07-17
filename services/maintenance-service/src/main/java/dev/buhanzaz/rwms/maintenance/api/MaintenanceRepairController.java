package dev.buhanzaz.rwms.maintenance.api;

import static dev.buhanzaz.rwms.maintenance.api.MaintenanceApiModels.*;

import dev.buhanzaz.rwms.maintenance.security.MaintenanceAuthorizer;
import dev.buhanzaz.rwms.maintenance.service.MaintenanceApplicationService;
import dev.buhanzaz.rwms.maintenance.domain.RepairAcceptanceState;
import dev.buhanzaz.rwms.maintenance.domain.RepairExecutionState;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Validated
@RequestMapping("/api/maintenance/v1")
@RequiredArgsConstructor
public class MaintenanceRepairController {
  private final MaintenanceApplicationService service;
  private final MaintenanceAuthorizer access;

  @GetMapping("/repairs")
  public PageResponse<RepairResponse> list(
      @AuthenticationPrincipal Jwt jwt,
      @RequestParam UUID warehouseId,
      @RequestParam(defaultValue = "0") @Min(0) int page,
      @RequestParam(defaultValue = "50") @Min(1) @Max(200) int size,
      @RequestParam(required = false) RepairExecutionState executionState,
      @RequestParam(required = false) RepairAcceptanceState acceptanceState,
      @RequestParam(required = false) UUID rentalItemId) {
    access.requireRead(jwt, warehouseId);
    List<RepairResponse> values = service.repairs(warehouseId).stream()
        .filter(value -> executionState == null || value.executionState() == executionState)
        .filter(value -> acceptanceState == null || value.acceptanceState() == acceptanceState)
        .filter(value -> rentalItemId == null || value.rentalItemId().equals(rentalItemId))
        .toList();
    return page(values, page, size);
  }

  @PostMapping("/repairs/direct")
  public ResponseEntity<RepairResponse> direct(
      @AuthenticationPrincipal Jwt jwt,
      @RequestHeader("Idempotency-Key") UUID idempotencyKey,
      @Valid @RequestBody CreateDirectRepairRequest request) {
    access.requireEdit(jwt, request.warehouseId());
    MaintenanceApplicationService.CreateResult<RepairResponse> result = service.createDirectRepair(
        access.subjectId(jwt), idempotencyKey, request);
    ResponseEntity.BodyBuilder response = ResponseEntity.status(HttpStatus.CREATED);
    if (result.replayed()) response.header("Idempotency-Replayed", "true");
    return response.body(result.response());
  }

  @GetMapping("/repairs/{id}")
  public RepairResponse get(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID id,
      @RequestParam UUID warehouseId) {
    access.requireRead(jwt, warehouseId);
    return requireWarehouse(id, warehouseId);
  }

  @GetMapping("/repairs/{id}/plan")
  public RepairPlanResponse plan(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID id,
      @RequestParam UUID warehouseId) {
    access.requireRead(jwt, warehouseId);
    requireWarehouse(id, warehouseId);
    return service.repairPlan(id);
  }

  @PutMapping("/repairs/{id}/plan")
  public RepairResponse replacePlan(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID id,
      @RequestParam UUID warehouseId,
      @Valid @RequestBody UpdateRepairPlanRequest request) {
    access.requireEdit(jwt, warehouseId);
    requireWarehouse(id, warehouseId);
    return service.updateRepairPlan(id, request);
  }

  @PostMapping("/repairs/{id}/plan")
  public ResponseEntity<RepairCommandResult> queuePlan(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID id,
      @RequestParam UUID warehouseId,
      @RequestHeader("Idempotency-Key") UUID idempotencyKey,
      @Valid @RequestBody VersionCommand request) {
    access.requireEdit(jwt, warehouseId);
    requireWarehouse(id, warehouseId);
    return idempotentOk(service.queueRepair(access.subjectId(jwt), idempotencyKey, id, request));
  }

  @PostMapping("/repairs/{id}/reworks")
  public ResponseEntity<RepairResponse> rework(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID id,
      @RequestParam UUID warehouseId,
      @RequestHeader("Idempotency-Key") UUID idempotencyKey,
      @Valid @RequestBody CreateReworkRequest request) {
    access.requireEdit(jwt, warehouseId);
    requireWarehouse(id, warehouseId);
    MaintenanceApplicationService.CreateResult<RepairResponse> result = service.createRework(
        access.subjectId(jwt), idempotencyKey, id, request);
    ResponseEntity.BodyBuilder response = ResponseEntity.status(HttpStatus.CREATED);
    if (result.replayed()) response.header("Idempotency-Replayed", "true");
    return response.body(result.response());
  }

  @PostMapping("/repairs/{id}/accept")
  public ResponseEntity<RepairCommandResult> accept(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID id,
      @RequestParam UUID warehouseId,
      @RequestHeader("Idempotency-Key") UUID idempotencyKey,
      @Valid @RequestBody RepairDecisionRequest request) {
    access.requireEdit(jwt, warehouseId);
    requireWarehouse(id, warehouseId);
    return idempotentOk(service.accept(
        access.subjectId(jwt), idempotencyKey, id, request));
  }

  @PostMapping("/repairs/{id}/write-off")
  public ResponseEntity<RepairCommandResult> writeOff(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID id,
      @RequestParam UUID warehouseId,
      @RequestHeader("Idempotency-Key") UUID idempotencyKey,
      @Valid @RequestBody WriteOffRepairRequest request) {
    access.requireManage(jwt, warehouseId);
    requireWarehouse(id, warehouseId);
    return idempotentOk(service.writeOff(
        access.subjectId(jwt), idempotencyKey, id, request));
  }

  @GetMapping("/acceptance")
  public PageResponse<AcceptanceProjection> acceptance(
      @AuthenticationPrincipal Jwt jwt,
      @RequestParam UUID warehouseId,
      @RequestParam(defaultValue = "0") @Min(0) int page,
      @RequestParam(defaultValue = "50") @Min(1) @Max(200) int size,
      @RequestParam(required = false) RepairAcceptanceState state) {
    access.requireRead(jwt, warehouseId);
    List<AcceptanceProjection> values = service.acceptance(warehouseId).stream()
        .filter(value -> state == null || value.acceptanceState() == state)
        .toList();
    return page(values, page, size);
  }

  @GetMapping("/write-offs")
  public PageResponse<WriteOffProjection> writeOffs(
      @AuthenticationPrincipal Jwt jwt,
      @RequestParam UUID warehouseId,
      @RequestParam(defaultValue = "0") @Min(0) int page,
      @RequestParam(defaultValue = "50") @Min(1) @Max(200) int size) {
    access.requireRead(jwt, warehouseId);
    return page(service.writeOffs(warehouseId), page, size);
  }

  private RepairResponse requireWarehouse(UUID id, UUID warehouseId) {
    return service.repair(id, warehouseId);
  }

  private static <T> PageResponse<T> page(List<T> values, int page, int size) {
    int from = Math.min(Math.multiplyExact(page, size), values.size());
    int to = Math.min(from + size, values.size());
    return new PageResponse<>(values.subList(from, to), page, size, values.size());
  }

  private static <T> ResponseEntity<T> idempotentOk(
      MaintenanceApplicationService.CreateResult<T> result) {
    ResponseEntity.BodyBuilder response = ResponseEntity.ok();
    if (result.replayed()) response.header("Idempotency-Replayed", "true");
    return response.body(result.response());
  }
}
