package dev.buhanzaz.rwms.logistics.driver.api;

import dev.buhanzaz.rwms.logistics.driver.api.DriverBoardApiModels.DriverBoardCardResponse;
import dev.buhanzaz.rwms.logistics.driver.api.DriverBoardApiModels.DriverBoardResponse;
import dev.buhanzaz.rwms.logistics.driver.api.DriverBoardApiModels.MoveDriverBoardTaskRequest;
import dev.buhanzaz.rwms.logistics.driver.api.DriverBoardApiModels.PromoteCapitalRepairRequest;
import dev.buhanzaz.rwms.logistics.driver.api.DriverBoardApiModels.ReturnCapitalRepairRequest;
import dev.buhanzaz.rwms.logistics.driver.api.DriverBoardApiModels.ScheduleCapitalRepairRequest;
import dev.buhanzaz.rwms.logistics.driver.api.DriverTaskApiModels.DriverTaskResponse;
import dev.buhanzaz.rwms.logistics.driver.service.DriverBoardService;
import dev.buhanzaz.rwms.logistics.driver.service.DriverQueueScheduler;
import dev.buhanzaz.rwms.logistics.driver.service.DriverTaskProcessor;
import dev.buhanzaz.rwms.logistics.driver.service.DriverTaskService;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Validated
@RequiredArgsConstructor
@RequestMapping("/api/logistics/v1/driver-board")
public class DriverBoardController {
  private final DriverBoardService board;
  private final DriverTaskService tasks;
  private final DriverTaskProcessor processor;
  private final DriverQueueScheduler scheduler;
  private final LogisticsAuthorizer access;

  @GetMapping
  public DriverBoardResponse board(
      @AuthenticationPrincipal Jwt jwt, @RequestParam UUID warehouseId) {
    access.requireRead(jwt, warehouseId);
    return board.board(warehouseId);
  }

  @PostMapping("/tasks/{externalTaskId}/move")
  public DriverBoardCardResponse move(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID externalTaskId,
      @Valid @RequestBody MoveDriverBoardTaskRequest request) {
    access.requireEdit(jwt, request.warehouseId());
    return board.move(externalTaskId, request);
  }

  @PostMapping("/tasks/{externalTaskId}/return-to-capital-repairs")
  public ResponseEntity<Void> returnToCapitalRepairs(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID externalTaskId,
      @Valid @RequestBody ReturnCapitalRepairRequest request) {
    access.requireEdit(jwt, request.warehouseId());
    board.returnToCapitalRepairs(externalTaskId, request);
    return ResponseEntity.noContent().build();
  }

  @PostMapping("/capital-repairs/{repairId}/promote")
  public ResponseEntity<DriverTaskResponse> promoteCapitalRepair(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID repairId,
      @RequestHeader("Idempotency-Key") UUID idempotencyKey,
      @Valid @RequestBody PromoteCapitalRepairRequest request) {
    access.requireEdit(jwt, request.warehouseId());
    DriverTaskService.CreateResult result =
        tasks.createCapitalMovement(
            access.subjectId(jwt), idempotencyKey, request.warehouseId(), repairId);
    processor.processUntilIdle(result.response().id());
    scheduler.promoteRequested(result.response().id());
    DriverTaskResponse response = tasks.get(result.response().id());
    ResponseEntity.BodyBuilder builder =
        ResponseEntity.status(result.replayed() ? HttpStatus.OK : HttpStatus.CREATED)
            .header(HttpHeaders.ETAG, '"' + Long.toString(response.version()) + '"');
    if (result.replayed()) {
      builder.header("Idempotency-Replayed", "true");
    }
    return builder.body(response);
  }

  @PostMapping("/capital-repairs/{repairId}/schedule")
  public ResponseEntity<DriverBoardCardResponse> scheduleCapitalRepair(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID repairId,
      @RequestHeader("Idempotency-Key") UUID idempotencyKey,
      @Valid @RequestBody ScheduleCapitalRepairRequest request) {
    access.requireEdit(jwt, request.warehouseId());
    DriverBoardService.CapitalRepairScheduleResult result =
        board.scheduleCapitalRepair(access.subjectId(jwt), idempotencyKey, repairId, request);
    ResponseEntity.BodyBuilder builder =
        ResponseEntity.status(result.replayed() ? HttpStatus.OK : HttpStatus.CREATED)
            .header(
                HttpHeaders.ETAG,
                '"' + Long.toString(result.card().taskBoardTaskVersion()) + '"');
    if (result.replayed()) {
      builder.header("Idempotency-Replayed", "true");
    }
    return builder.body(result.card());
  }
}
