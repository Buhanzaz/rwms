package dev.buhanzaz.rwms.taskboard.api;

import static dev.buhanzaz.rwms.taskboard.api.WorkerApiModels.EvidenceReservationRequest;
import static dev.buhanzaz.rwms.taskboard.api.WorkerApiModels.TaskEvidence;
import static dev.buhanzaz.rwms.taskboard.api.WorkerApiModels.WorkerActionAppliedResult;
import static dev.buhanzaz.rwms.taskboard.api.WorkerApiModels.WorkerActionRequest;
import static dev.buhanzaz.rwms.taskboard.api.WorkerApiModels.WorkerContext;
import static dev.buhanzaz.rwms.taskboard.api.WorkerApiModels.WorkerDeviceRegistration;
import static dev.buhanzaz.rwms.taskboard.api.WorkerApiModels.WorkerDeviceRegistrationRequest;
import static dev.buhanzaz.rwms.taskboard.api.WorkerApiModels.WorkerFeed;
import static dev.buhanzaz.rwms.taskboard.api.WorkerApiModels.WorkerTaskDetail;

import dev.buhanzaz.rwms.taskboard.security.AccessLevel;
import dev.buhanzaz.rwms.taskboard.security.WarehouseAccessAuthorizer;
import dev.buhanzaz.rwms.taskboard.service.MobileTaskSurface;
import dev.buhanzaz.rwms.taskboard.service.WorkerInvalidationHub;
import dev.buhanzaz.rwms.taskboard.service.WorkerTaskBoardService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Public {@code driver.tasks} API for the dedicated DriverApp.
 *
 * <p>The route fixes the DRIVER surface server-side. JWT claims supply worker and warehouse
 * identity, while {@link WorkerTaskBoardService} restricts categories and commands to primary
 * bindings so a crafted request cannot enter the WorkerApp slinger workflow.
 */
@RestController
@Validated
@RequestMapping("/api/driver/v1")
public class DriverTaskBoardController {
  private final WorkerTaskBoardService service;
  private final WorkerInvalidationHub invalidations;
  private final WarehouseAccessAuthorizer access;

  public DriverTaskBoardController(
      WorkerTaskBoardService service,
      WorkerInvalidationHub invalidations,
      WarehouseAccessAuthorizer access) {
    this.service = service;
    this.invalidations = invalidations;
    this.access = access;
  }

  /** Returns the authenticated driver's primary task context and offline lease. */
  @GetMapping("/context")
  public WorkerContext context(@AuthenticationPrincipal Jwt jwt) {
    DriverPrincipal principal = principal(jwt, false);
    return service.context(
        MobileTaskSurface.DRIVER, principal.workerId(), principal.warehouseId());
  }

  /** Returns a revision-fenced page of primary driver work. */
  @GetMapping("/feed")
  public ResponseEntity<WorkerFeed> feed(
      @AuthenticationPrincipal Jwt jwt,
      @RequestParam(required = false) String cursor,
      @RequestParam(defaultValue = "50") @Min(1) @Max(50) int limit,
      @RequestHeader(name = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch) {
    DriverPrincipal principal = principal(jwt, false);
    WorkerTaskBoardService.FeedPage page =
        service.feed(
            MobileTaskSurface.DRIVER,
            principal.workerId(),
            principal.warehouseId(),
            cursor,
            limit);
    if (cursor == null && page.etag().equals(ifNoneMatch)) {
      return ResponseEntity.status(HttpStatus.NOT_MODIFIED).eTag(page.etag()).build();
    }
    return ResponseEntity.ok().eTag(page.etag()).body(page.feed());
  }

  /** Returns one driver-authorized task detail. */
  @GetMapping("/entries/{entryId}")
  public WorkerTaskDetail detail(
      @AuthenticationPrincipal Jwt jwt, @PathVariable UUID entryId) {
    DriverPrincipal principal = principal(jwt, false);
    return service.detail(
        MobileTaskSurface.DRIVER, principal.workerId(), principal.warehouseId(), entryId);
  }

  /** Opens the driver's invalidation-only SSE stream. */
  @GetMapping(path = "/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  public SseEmitter events(
      @AuthenticationPrincipal Jwt jwt,
      @RequestHeader(name = "Last-Event-ID", required = false) String lastEventId) {
    DriverPrincipal principal = principal(jwt, false);
    return invalidations.subscribe(
        principal.warehouseId(),
        MobileTaskSurface.DRIVER,
        principal.workerId(),
        service.revision(principal.warehouseId()));
  }

  /** Applies an idempotent primary-driver action under version and offline-lease fencing. */
  @PostMapping("/entries/{entryId}/actions")
  public WorkerActionAppliedResult action(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID entryId,
      @RequestHeader("Idempotency-Key") String idempotencyKey,
      @Valid @RequestBody WorkerActionRequest request) {
    DriverPrincipal principal = principal(jwt, true);
    return service.applyAction(
        MobileTaskSurface.DRIVER,
        principal.workerId(),
        principal.warehouseId(),
        entryId,
        idempotencyKey,
        request);
  }

  /** Reserves one durable result-photo upload for an assigned driver task. */
  @PostMapping("/entries/{entryId}/evidence-reservations")
  public ResponseEntity<TaskEvidence> reserveEvidence(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID entryId,
      @RequestHeader("Idempotency-Key") String idempotencyKey,
      @Valid @RequestBody EvidenceReservationRequest request) {
    DriverPrincipal principal = principal(jwt, true);
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(
            service.reserveEvidence(
                MobileTaskSurface.DRIVER,
                principal.workerId(),
                principal.warehouseId(),
                entryId,
                idempotencyKey,
                request));
  }

  /** Registers or replaces this driver's DriverApp installation. */
  @PutMapping("/devices/{installationId}")
  public ResponseEntity<WorkerDeviceRegistration> registerDevice(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable String installationId,
      @Valid @RequestBody WorkerDeviceRegistrationRequest request) {
    DriverPrincipal principal = principal(jwt, true);
    WorkerTaskBoardService.DeviceRegistrationResult result =
        service.registerDevice(
            MobileTaskSurface.DRIVER,
            principal.workerId(),
            principal.warehouseId(),
            installationId,
            request);
    return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK)
        .body(result.registration());
  }

  /** Removes only this worker's DriverApp installation. */
  @DeleteMapping("/devices/{installationId}")
  public ResponseEntity<Void> unregisterDevice(
      @AuthenticationPrincipal Jwt jwt, @PathVariable String installationId) {
    DriverPrincipal principal = principal(jwt, true);
    service.unregisterDevice(
        MobileTaskSurface.DRIVER,
        principal.workerId(),
        principal.warehouseId(),
        installationId);
    return ResponseEntity.noContent().build();
  }

  private DriverPrincipal principal(Jwt jwt, boolean write) {
    access.requireMobileTaskScope(jwt, MobileTaskSurface.DRIVER.scope());
    UUID workerId = requiredUuidClaim(jwt, "worker_id");
    UUID warehouseId = requiredUuidClaim(jwt, "warehouse_id");
    access.requireWarehouse(jwt, warehouseId, write ? AccessLevel.EDIT : AccessLevel.VIEW, true);
    return new DriverPrincipal(workerId, warehouseId);
  }

  private UUID requiredUuidClaim(Jwt jwt, String name) {
    String value = jwt.getClaimAsString(name);
    if (value == null) throw new AccessDeniedException(name + " claim is required");
    try {
      return UUID.fromString(value);
    } catch (IllegalArgumentException exception) {
      throw new AccessDeniedException("Invalid " + name + " claim");
    }
  }

  /** Authenticated driver and warehouse identities derived only from validated JWT claims. */
  private record DriverPrincipal(UUID workerId, UUID warehouseId) {}
}
