package dev.buhanzaz.rwms.taskboard.api;

import static dev.buhanzaz.rwms.taskboard.api.ApiModels.*;

import dev.buhanzaz.rwms.taskboard.security.*;
import dev.buhanzaz.rwms.taskboard.service.TaskBoardService;
import jakarta.validation.Valid;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.context.request.ServletWebRequest;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/warehouses/{warehouseId}/task-board")
public class TaskBoardController {
  private final TaskBoardService service;
  private final WarehouseAccessAuthorizer access;
  private final ObjectMapper objectMapper;

  @GetMapping
  public ResponseEntity<TaskBoardSnapshot> snapshot(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID warehouseId,
      @RequestParam(required = false) LocalDate date,
      @RequestParam(defaultValue = "false") boolean includeShadow,
      ServletWebRequest request) {
    taskAccess(jwt, warehouseId, false);
    TaskBoardSnapshot snapshot = service.snapshot(warehouseId, date, includeShadow);
    String etag = snapshotEtag(date, includeShadow, snapshot);
    if (request.checkNotModified(etag)) {
      return ResponseEntity.status(HttpStatus.NOT_MODIFIED).eTag(etag).build();
    }
    return ResponseEntity.ok().eTag(etag).body(snapshot);
  }

  @GetMapping("/logistics")
  public LogisticsBoardSnapshot logisticsSnapshot(
      @AuthenticationPrincipal Jwt jwt, @PathVariable UUID warehouseId) {
    taskAccess(jwt, warehouseId, false);
    return service.logisticsSnapshot(warehouseId);
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

  @PostMapping("/dates/swap")
  public TaskBoardSnapshot swapDates(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID warehouseId,
      @Valid @RequestBody SwapTaskBoardDatesRequest request) {
    userWrite(jwt, warehouseId);
    return service.swapDates(warehouseId, request);
  }

  @PostMapping("/tasks/{taskId}/pin")
  public TaskBoardSnapshot pin(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID warehouseId,
      @PathVariable UUID taskId,
      @Valid @RequestBody PinTaskRequest request) {
    userWrite(jwt, warehouseId);
    return service.pin(warehouseId, taskId, request);
  }

  private void taskAccess(Jwt jwt, UUID id, boolean write) {
    access.requireTaskScope(jwt, write);
    access.requireWarehouse(jwt, id, write ? AccessLevel.EDIT : AccessLevel.VIEW, true);
  }

  private void userWrite(Jwt jwt, UUID id) {
    access.requireUserScope(jwt, "rwms.write");
    access.requireWarehouse(jwt, id, AccessLevel.EDIT, false);
  }

  /**
   * A weak validator reflects stable public state rather than a byte-for-byte response. Requested
   * parameters are part of the value because unavailable dates may resolve to the same selected
   * date and an empty shadow lane can otherwise have the same response body. Rolling timer values
   * are intentionally excluded: clients derive them from the stable timer state and transition.
   */
  private String snapshotEtag(
      LocalDate requestedDate, boolean includeShadow, TaskBoardSnapshot snapshot) {
    try {
      ObjectNode representation =
          objectMapper.valueToTree(
              new SnapshotRepresentation(requestedDate, includeShadow, snapshot));
      removeVolatileTimerValues(representation);
      String digest =
          HexFormat.of()
              .formatHex(
                  MessageDigest.getInstance("SHA-256")
                      .digest(objectMapper.writeValueAsBytes(representation)));
      return "W/\"task-board-" + digest + "\"";
    } catch (JacksonException | java.security.NoSuchAlgorithmException exception) {
      throw new IllegalStateException("Не удалось вычислить ETag снимка очередей", exception);
    }
  }

  private void removeVolatileTimerValues(ObjectNode representation) {
    JsonNode snapshot = representation.get("snapshot");
    if (!(snapshot instanceof ObjectNode board)) return;
    JsonNode columns = board.get("columns");
    if (columns == null) return;
    for (JsonNode column : columns) {
      JsonNode entries = column.get("entries");
      if (entries == null) continue;
      for (JsonNode entry : entries) {
        JsonNode timer = entry.get("timerSnapshot");
        if (timer instanceof ObjectNode timerSnapshot) {
          timerSnapshot.remove(
              List.of(
                  "countedActiveSeconds", "remainingSeconds", "remainingPercent", "serverTime"));
        }
      }
    }
  }

  private record SnapshotRepresentation(
      LocalDate requestedDate, boolean includeShadow, TaskBoardSnapshot snapshot) {}
}
