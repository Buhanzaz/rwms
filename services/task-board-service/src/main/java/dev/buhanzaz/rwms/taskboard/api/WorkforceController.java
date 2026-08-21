package dev.buhanzaz.rwms.taskboard.api;

import static dev.buhanzaz.rwms.taskboard.api.ApiModels.*;

import dev.buhanzaz.rwms.taskboard.security.*;
import dev.buhanzaz.rwms.taskboard.service.WorkforceService;
import dev.buhanzaz.rwms.taskboard.service.WorkerGroupAvailabilityService;
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

/**
 * Public warehouse-scoped administration API for workers and worker groups.
 *
 * <p>Workers, memberships and their operational availability are task-board-owned. Credential
 * provisioning crosses the explicit auth boundary, but its eventual outcome is reflected in the
 * worker response instead of being assumed successful by the caller.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/warehouses/{warehouseId}")
@Validated
public class WorkforceController {
  private final WorkforceService service;
  private final WorkerGroupAvailabilityService groupAvailability;
  private final WarehouseAccessAuthorizer access;

  /** Lists workers registered in the selected warehouse. */
  @GetMapping("/workers")
  public List<WorkerDto> workers(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID warehouseId) {
    read(jwt, warehouseId);
    return service.listWorkers(warehouseId);
  }

  /** Creates a worker profile and starts requested credential provisioning. */
  @PostMapping("/workers")
  @ResponseStatus(HttpStatus.CREATED)
  public WorkerDto createWorker(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID warehouseId,
      @Valid @RequestBody WorkerRequest request) {
    write(jwt, warehouseId);
    return service.createWorker(warehouseId, request);
  }

  /** Replaces a version-fenced worker profile, qualifications and optional credentials. */
  @PutMapping("/workers/{id}")
  public WorkerDto updateWorker(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID warehouseId,
      @PathVariable UUID id,
      @Valid @RequestBody WorkerRequest request) {
    write(jwt, warehouseId);
    return service.updateWorker(warehouseId, id, request);
  }

  /** Changes a worker's current group under the worker version fence. */
  @PutMapping("/workers/{id}/current-group")
  public WorkerDto setCurrentGroup(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID warehouseId,
      @PathVariable UUID id,
      @Valid @RequestBody SetCurrentGroupRequest request) {
    write(jwt, warehouseId);
    return service.setCurrentGroup(warehouseId, id, request);
  }

  /** Replaces a worker credential through the credential-operation boundary. */
  @PostMapping("/workers/{id}/credentials/reset")
  public WorkerDto reset(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID warehouseId,
      @PathVariable UUID id,
      @Valid @RequestBody CredentialPasswordRequest request) {
    write(jwt, warehouseId);
    return service.resetPassword(warehouseId, id, request.expectedVersion(), request.password());
  }

  /** Starts version-fenced credential disabling for a worker. */
  @PostMapping("/workers/{id}/credentials/disable")
  public WorkerDto disable(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID warehouseId,
      @PathVariable UUID id,
      @Valid @RequestBody VersionCommand request) {
    write(jwt, warehouseId);
    return service.disableCredentials(warehouseId, id, request.expectedVersion());
  }

  /** Starts version-fenced credential enabling for a worker. */
  @PostMapping("/workers/{id}/credentials/enable")
  public WorkerDto enable(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID warehouseId,
      @PathVariable UUID id,
      @Valid @RequestBody VersionCommand request) {
    write(jwt, warehouseId);
    return service.enableCredentials(warehouseId, id, request.expectedVersion());
  }

  /** Reconciles a previously requested credential-disable operation after an external failure. */
  @PostMapping("/workers/{id}/credentials/reconcile-disable")
  public WorkerDto reconcileDisable(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID warehouseId,
      @PathVariable UUID id,
      @Valid @RequestBody VersionCommand request) {
    write(jwt, warehouseId);
    return service.reconcileDisableCredentials(warehouseId, id, request.expectedVersion());
  }

  /** Deletes an unreferenced worker under the supplied version fence. */
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

  /** Replays worker-deletion reconciliation after the cross-service credential step is settled. */
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

  /** Lists worker groups in the selected warehouse. */
  @GetMapping("/worker-groups")
  public List<WorkerGroupDto> groups(
      @AuthenticationPrincipal Jwt jwt, @PathVariable UUID warehouseId) {
    read(jwt, warehouseId);
    return service.listGroups(warehouseId);
  }

  /** Creates a group and atomically applies its requested current-worker changes. */
  @PostMapping("/worker-groups")
  @ResponseStatus(HttpStatus.CREATED)
  public WorkerGroupDto createGroup(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID warehouseId,
      @Valid @RequestBody WorkerGroupRequest request) {
    write(jwt, warehouseId);
    return service.createGroup(warehouseId, request);
  }

  /** Replaces a group, memberships, and requested current-worker changes atomically. */
  @PutMapping("/worker-groups/{id}")
  public WorkerGroupDto updateGroup(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID warehouseId,
      @PathVariable UUID id,
      @Valid @RequestBody WorkerGroupRequest request) {
    write(jwt, warehouseId);
    return service.updateGroup(warehouseId, id, request);
  }

  /** Marks a group operationally unavailable without deleting its historic membership. */
  @PostMapping("/worker-groups/{id}/disable")
  public WorkerGroupDto disableGroup(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID warehouseId,
      @PathVariable UUID id,
      @Valid @RequestBody GroupAvailabilityRequest request) {
    write(jwt, warehouseId);
    return groupAvailability.disable(warehouseId, id, request);
  }

  /** Restores an operationally unavailable group under its observed version. */
  @PostMapping("/worker-groups/{id}/enable")
  public WorkerGroupDto enableGroup(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID warehouseId,
      @PathVariable UUID id,
      @Valid @RequestBody GroupAvailabilityRequest request) {
    write(jwt, warehouseId);
    return groupAvailability.enable(warehouseId, id, request);
  }

  /** Deletes an unused worker group under the supplied version fence. */
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
