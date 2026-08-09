package dev.buhanzaz.rwms.taskboard.api;

import static dev.buhanzaz.rwms.taskboard.api.ApiModels.LogisticsBoardSnapshot;

import dev.buhanzaz.rwms.taskboard.api.ApiModels.BoardTaskRegistrationDto;
import dev.buhanzaz.rwms.taskboard.api.ApiModels.MoveExternalLogisticsTaskRequest;
import dev.buhanzaz.rwms.taskboard.security.TaskSyncAuthorizer;
import dev.buhanzaz.rwms.taskboard.service.TaskBoardService;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Private logistics-service read and move surface for the dedicated driver board.
 *
 * <p>Only logistics may call it, and it sees neither the general task board nor maintenance-owned
 * routing administration. This preserves the driver queue as a bounded operational projection.
 */
@RestController
@RequestMapping("/api/internal/task-board/v1/logistics")
@RequiredArgsConstructor
public class InternalDriverLogisticsController {
  private final TaskBoardService service;
  private final TaskSyncAuthorizer access;

  /** Returns the logistics-only board for one warehouse. */
  @GetMapping("/warehouses/{warehouseId}/board")
  public LogisticsBoardSnapshot board(
      @AuthenticationPrincipal Jwt jwt, @PathVariable UUID warehouseId) {
    access.requireLogisticsTaskAccess(jwt);
    return service.logisticsSnapshot(warehouseId);
  }

  /** Moves a logistics-owned task in the driver lane under task and entry version fences. */
  @PostMapping("/tasks/{externalTaskId}/move")
  public BoardTaskRegistrationDto move(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID externalTaskId,
      @Valid @RequestBody MoveExternalLogisticsTaskRequest request) {
    access.requireLogisticsTaskAccess(jwt);
    return service.moveExternalLogisticsTask(externalTaskId, request);
  }
}
