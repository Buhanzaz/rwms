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

@RestController
@RequestMapping("/api/internal/task-board/v1/logistics")
@RequiredArgsConstructor
public class InternalDriverLogisticsController {
  private final TaskBoardService service;
  private final TaskSyncAuthorizer access;

  @GetMapping("/warehouses/{warehouseId}/board")
  public LogisticsBoardSnapshot board(
      @AuthenticationPrincipal Jwt jwt, @PathVariable UUID warehouseId) {
    access.requireLogisticsTaskAccess(jwt);
    return service.logisticsSnapshot(warehouseId);
  }

  @PostMapping("/tasks/{externalTaskId}/move")
  public BoardTaskRegistrationDto move(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID externalTaskId,
      @Valid @RequestBody MoveExternalLogisticsTaskRequest request) {
    access.requireLogisticsTaskAccess(jwt);
    return service.moveExternalLogisticsTask(externalTaskId, request);
  }
}
