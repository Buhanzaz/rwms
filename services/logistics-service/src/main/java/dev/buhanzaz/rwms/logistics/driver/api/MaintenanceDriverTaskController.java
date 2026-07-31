package dev.buhanzaz.rwms.logistics.driver.api;

import dev.buhanzaz.rwms.logistics.driver.api.DriverTaskApiModels.CreateDriverTaskRequest;
import dev.buhanzaz.rwms.logistics.driver.api.DriverTaskApiModels.DriverTaskResponse;
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
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Single service-to-service intake used by maintenance-owned estimate, repair,
 * and inventory repair flows. The browser never orchestrates a second create.
 */
@RestController
@Validated
@RequiredArgsConstructor
@RequestMapping("/api/internal/logistics/v1/maintenance/driver-tasks")
public class MaintenanceDriverTaskController {
  private final DriverTaskService service;
  private final DriverTaskProcessor processor;
  private final DriverQueueScheduler scheduler;
  private final LogisticsAuthorizer access;

  @PostMapping
  public ResponseEntity<DriverTaskResponse> create(
      @AuthenticationPrincipal Jwt jwt,
      @RequestHeader("Idempotency-Key") UUID idempotencyKey,
      @Valid @RequestBody CreateDriverTaskRequest request) {
    access.requireMaintenanceDriverTaskIntake(jwt);
    DriverTaskService.CreateResult result =
        service.createFromMaintenance(idempotencyKey, request);
    processor.processUntilIdle(result.response().id());
    scheduler.reconcileAndPromote(request.warehouseId());
    DriverTaskResponse response = service.get(result.response().id());
    ResponseEntity.BodyBuilder builder =
        ResponseEntity.status(result.replayed() ? HttpStatus.OK : HttpStatus.CREATED)
            .header(HttpHeaders.ETAG, '"' + Long.toString(response.version()) + '"');
    if (result.replayed()) builder.header("Idempotency-Replayed", "true");
    return builder.body(response);
  }
}
