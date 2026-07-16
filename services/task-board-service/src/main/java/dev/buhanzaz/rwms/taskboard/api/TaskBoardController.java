package dev.buhanzaz.rwms.taskboard.api;

import static dev.buhanzaz.rwms.taskboard.api.ApiModels.*;

import dev.buhanzaz.rwms.taskboard.security.*;
import dev.buhanzaz.rwms.taskboard.service.TaskBoardService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/warehouses/{warehouseId}/task-board")
public class TaskBoardController {
  private final TaskBoardService service;
  private final WarehouseAccessAuthorizer access;

  @GetMapping
  public TaskBoardSnapshot snapshot(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID warehouseId,
      @RequestParam(defaultValue = "false") boolean includeShadow) {
    taskAccess(jwt, warehouseId, false);
    return service.snapshot(warehouseId, includeShadow);
  }

  @GetMapping("/queues/{queueId}/eligible-groups")
  public List<WorkerGroupDto> eligible(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID warehouseId,
      @PathVariable UUID queueId) {
    taskAccess(jwt, warehouseId, false);
    return service.eligibleGroups(warehouseId, queueId);
  }

  @GetMapping("/entries/{entryId}/history")
  public List<TimeEventDto> history(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID warehouseId,
      @PathVariable UUID entryId) {
    taskAccess(jwt, warehouseId, false);
    return service.history(warehouseId, entryId);
  }

  @PostMapping("/tasks")
  @ResponseStatus(HttpStatus.CREATED)
  public TaskBoardSnapshot create(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID warehouseId,
      @Valid @RequestBody CreateBoardTaskRequest request) {
    userWrite(jwt, warehouseId);
    return service.createTask(warehouseId, request);
  }

  @PostMapping("/tasks/by-external-id/{externalTaskId}/cancel")
  public CancelledTaskDto cancel(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID warehouseId,
      @PathVariable UUID externalTaskId,
      @Valid @RequestBody CancelTaskRequest request) {
    userWrite(jwt, warehouseId);
    return service.cancelTask(warehouseId, externalTaskId, request);
  }

  @GetMapping("/tasks/by-external-id/{externalTaskId}")
  public BoardTaskRegistrationDto registration(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID warehouseId,
      @PathVariable UUID externalTaskId) {
    taskAccess(jwt, warehouseId, false);
    return service.registration(warehouseId, externalTaskId);
  }

  @PostMapping("/entries/{entryId}/take")
  public BoardEntryDto take(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID warehouseId,
      @PathVariable UUID entryId,
      @Valid @RequestBody TakeEntryRequest request) {
    taskAccess(jwt, warehouseId, true);
    return service.take(warehouseId, entryId, request, access.workerId(jwt));
  }

  @PostMapping("/entries/{entryId}/pause")
  public BoardEntryDto pause(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID warehouseId,
      @PathVariable UUID entryId,
      @Valid @RequestBody PauseEntryRequest request) {
    taskAccess(jwt, warehouseId, true);
    return service.pause(warehouseId, entryId, request, access.workerId(jwt));
  }

  @PostMapping("/entries/{entryId}/resume")
  public BoardEntryDto resume(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID warehouseId,
      @PathVariable UUID entryId,
      @Valid @RequestBody VersionCommand request) {
    taskAccess(jwt, warehouseId, true);
    return service.resume(warehouseId, entryId, request, access.workerId(jwt));
  }

  @PostMapping("/entries/{entryId}/complete")
  public BoardEntryDto complete(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID warehouseId,
      @PathVariable UUID entryId,
      @Valid @RequestBody VersionCommand request) {
    taskAccess(jwt, warehouseId, true);
    return service.complete(warehouseId, entryId, request, access.workerId(jwt));
  }

  @PostMapping("/entries/{entryId}/move")
  public TaskBoardSnapshot move(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID warehouseId,
      @PathVariable UUID entryId,
      @Valid @RequestBody MoveEntryRequest request) {
    userWrite(jwt, warehouseId);
    return service.move(warehouseId, entryId, request);
  }

  private void taskAccess(Jwt jwt, UUID id, boolean write) {
    access.requireTaskScope(jwt, write);
    access.requireWarehouse(jwt, id, write ? AccessLevel.EDIT : AccessLevel.VIEW, true);
  }

  private void userWrite(Jwt jwt, UUID id) {
    access.requireUserScope(jwt, "rwms.write");
    access.requireWarehouse(jwt, id, AccessLevel.EDIT, false);
  }
}
