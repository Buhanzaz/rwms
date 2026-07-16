package dev.buhanzaz.rwms.taskboard.api;

import static dev.buhanzaz.rwms.taskboard.api.ApiModels.*;

import dev.buhanzaz.rwms.taskboard.security.*;
import dev.buhanzaz.rwms.taskboard.service.WorkforceService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import java.util.List;
import java.util.UUID;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/warehouses/{warehouseId}")
@Validated
public class WorkforceController {
  private final WorkforceService service;
  private final WarehouseAccessAuthorizer access;

  @GetMapping("/workers")
  public List<WorkerDto> workers(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID warehouseId) {
    read(jwt, warehouseId);
    return service.listWorkers(warehouseId);
  }

  @PostMapping("/workers")
  @ResponseStatus(HttpStatus.CREATED)
  public WorkerDto createWorker(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID warehouseId,
      @Valid @RequestBody WorkerRequest request) {
    write(jwt, warehouseId);
    return service.createWorker(warehouseId, request);
  }

  @PutMapping("/workers/{id}")
  public WorkerDto updateWorker(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID warehouseId,
      @PathVariable UUID id,
      @Valid @RequestBody WorkerRequest request) {
    write(jwt, warehouseId);
    return service.updateWorker(warehouseId, id, request);
  }

  @PostMapping("/workers/{id}/credentials/reset")
  public WorkerDto reset(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID warehouseId,
      @PathVariable UUID id,
      @Valid @RequestBody CredentialPasswordRequest request) {
    write(jwt, warehouseId);
    return service.resetPassword(warehouseId, id, request.expectedVersion(), request.password());
  }

  @PostMapping("/workers/{id}/credentials/disable")
  public WorkerDto disable(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID warehouseId,
      @PathVariable UUID id,
      @Valid @RequestBody VersionCommand request) {
    write(jwt, warehouseId);
    return service.disableCredentials(warehouseId, id, request.expectedVersion());
  }

  @PostMapping("/workers/{id}/credentials/reconcile-disable")
  public WorkerDto reconcileDisable(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID warehouseId,
      @PathVariable UUID id,
      @Valid @RequestBody VersionCommand request) {
    write(jwt, warehouseId);
    return service.reconcileDisableCredentials(warehouseId, id, request.expectedVersion());
  }

  @DeleteMapping("/workers/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void deleteWorker(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID warehouseId,
      @PathVariable UUID id,
      @RequestParam @Min(0) long expectedVersion) {
    write(jwt, warehouseId);
    service.deleteWorker(warehouseId, id, expectedVersion);
  }

  @PostMapping("/workers/{id}/deletion/reconcile")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void reconcileWorkerDeletion(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID warehouseId,
      @PathVariable UUID id,
      @Valid @RequestBody VersionCommand request) {
    write(jwt, warehouseId);
    service.deleteWorker(warehouseId, id, request.expectedVersion());
  }

  @GetMapping("/worker-groups")
  public List<WorkerGroupDto> groups(
      @AuthenticationPrincipal Jwt jwt, @PathVariable UUID warehouseId) {
    read(jwt, warehouseId);
    return service.listGroups(warehouseId);
  }

  @PostMapping("/worker-groups")
  @ResponseStatus(HttpStatus.CREATED)
  public WorkerGroupDto createGroup(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID warehouseId,
      @Valid @RequestBody WorkerGroupRequest request) {
    write(jwt, warehouseId);
    return service.createGroup(warehouseId, request);
  }

  @PutMapping("/worker-groups/{id}")
  public WorkerGroupDto updateGroup(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID warehouseId,
      @PathVariable UUID id,
      @Valid @RequestBody WorkerGroupRequest request) {
    write(jwt, warehouseId);
    return service.updateGroup(warehouseId, id, request);
  }

  @DeleteMapping("/worker-groups/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void deleteGroup(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID warehouseId,
      @PathVariable UUID id,
      @RequestParam @Min(0) long expectedVersion) {
    write(jwt, warehouseId);
    service.deleteGroup(warehouseId, id, expectedVersion);
  }

  private void read(Jwt jwt, UUID id) {
    access.requireUserScope(jwt, "rwms.read");
    access.requireWarehouse(jwt, id, AccessLevel.VIEW, false);
  }

  private void write(Jwt jwt, UUID id) {
    access.requireUserScope(jwt, "rwms.write");
    access.requireWarehouse(jwt, id, AccessLevel.MANAGE, false);
  }
}
