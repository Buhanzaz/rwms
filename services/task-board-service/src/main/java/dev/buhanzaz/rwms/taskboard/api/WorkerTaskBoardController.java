package dev.buhanzaz.rwms.taskboard.api;

import static dev.buhanzaz.rwms.taskboard.api.WorkerApiModels.*;

import dev.buhanzaz.rwms.taskboard.security.AccessLevel;
import dev.buhanzaz.rwms.taskboard.security.WarehouseAccessAuthorizer;
import dev.buhanzaz.rwms.taskboard.service.MobileTaskSurface;
import dev.buhanzaz.rwms.taskboard.service.TaskProblemReportService;
import dev.buhanzaz.rwms.taskboard.service.WorkerInvalidationHub;
import dev.buhanzaz.rwms.taskboard.service.WorkerProfileMediaService;
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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
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
 * Public worker-token API for the worker Android application.
 *
 * <p>The authenticated JWT fixes both worker and warehouse identity. The app never chooses those
 * identifiers in a request, which prevents a worker from reading or acting on another worker's
 * task feed.
 */
@RestController
@Validated
@RequestMapping("/api/worker/v1")
public class WorkerTaskBoardController {
  private final WorkerTaskBoardService service;
  private final WorkerProfileMediaService profileMedia;
  private final WorkerInvalidationHub invalidations;
  private final WarehouseAccessAuthorizer access;
  private final TaskProblemReportService problemReports;

  public WorkerTaskBoardController(
      WorkerTaskBoardService service,
      WorkerProfileMediaService profileMedia,
      WorkerInvalidationHub invalidations,
      WarehouseAccessAuthorizer access,
      TaskProblemReportService problemReports) {
    this.service = service;
    this.profileMedia = profileMedia;
    this.invalidations = invalidations;
    this.access = access;
    this.problemReports = problemReports;
  }

  /** Returns identity, qualifications, queue categories, clock data and a short-lived offline lease. */
  @GetMapping("/context")
  public WorkerContext context(@AuthenticationPrincipal Jwt jwt) {
    WorkerPrincipal principal = principal(jwt, false);
    return service.context(
        MobileTaskSurface.WORKER, principal.workerId(), principal.warehouseId());
  }

  /** Establishes and returns this worker's canonical profile-avatar media scope. */
  @PostMapping("/profile/avatar-scope")
  public WorkerProfileAvatarScope avatarScope(@AuthenticationPrincipal Jwt jwt) {
    WorkerPrincipal principal = principal(jwt, true);
    return profileMedia.prepare(principal.workerId(), principal.warehouseId());
  }

  /**
   * Returns one cursor page of the authorized worker feed.
   *
   * <p>Pages are tied to a feed revision. A changed revision produces a conflict instead of mixing
   * results from two snapshots; an unchanged first page can return {@code 304} via its ETag.
   */
  @GetMapping("/feed")
  public ResponseEntity<WorkerFeed> feed(
      @AuthenticationPrincipal Jwt jwt,
      @RequestParam(required = false) String cursor,
      @RequestParam(defaultValue = "50") @Min(1) @Max(50) int limit,
      @RequestHeader(name = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch) {
    WorkerPrincipal principal = principal(jwt, false);
    WorkerTaskBoardService.FeedPage page =
        service.feed(
            MobileTaskSurface.WORKER,
            principal.workerId(),
            principal.warehouseId(),
            cursor,
            limit);
    if (cursor == null && page.etag().equals(ifNoneMatch)) {
      return ResponseEntity.status(HttpStatus.NOT_MODIFIED).eTag(page.etag()).build();
    }
    return ResponseEntity.ok().eTag(page.etag()).body(page.feed());
  }

  /** Returns task detail only when the worker is authorized to see the entry. */
  @GetMapping("/entries/{entryId}")
  public WorkerTaskDetail detail(
      @AuthenticationPrincipal Jwt jwt, @PathVariable UUID entryId) {
    WorkerPrincipal principal = principal(jwt, false);
    return service.detail(
        MobileTaskSurface.WORKER, principal.workerId(), principal.warehouseId(), entryId);
  }

  /**
   * Opens a worker-scoped SSE invalidation stream.
   *
   * <p>Events are signals to refresh the authorized feed, not a substitute for task data. Each
   * reconnect starts a fresh subscription; this operation has no replay cursor contract.
   */
  @GetMapping(path = "/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  public SseEmitter events(@AuthenticationPrincipal Jwt jwt) {
    WorkerPrincipal principal = principal(jwt, false);
    return invalidations.subscribe(
        principal.warehouseId(),
        MobileTaskSurface.WORKER,
        principal.workerId(),
        service.revision(principal.warehouseId()));
  }

  /** Applies an idempotent worker action using the issued offline lease and observed entry version. */
  @PostMapping("/entries/{entryId}/actions")
  public WorkerActionAppliedResult action(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID entryId,
      @RequestHeader("Idempotency-Key") String idempotencyKey,
      @Valid @RequestBody WorkerActionRequest request) {
    WorkerPrincipal principal = principal(jwt, true);
    return service.applyAction(
        MobileTaskSurface.WORKER,
        principal.workerId(),
        principal.warehouseId(),
        entryId,
        idempotencyKey,
        request);
  }

  /** Reserves an idempotent evidence upload slot before media-service receives the bytes. */
  @PostMapping("/entries/{entryId}/evidence-reservations")
  public ResponseEntity<TaskEvidence> reserveEvidence(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID entryId,
      @RequestHeader("Idempotency-Key") String idempotencyKey,
      @Valid @RequestBody EvidenceReservationRequest request) {
    WorkerPrincipal principal = principal(jwt, true);
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(
            service.reserveEvidence(
                MobileTaskSurface.WORKER,
                principal.workerId(),
                principal.warehouseId(),
                entryId,
                idempotencyKey,
                request));
  }

  /** Atomically records an immutable problem report and every prepared photo reservation. */
  @PostMapping("/entries/{entryId}/problem-reports")
  public ResponseEntity<WorkerProblemReport> reportProblem(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID entryId,
      @RequestHeader("Idempotency-Key") String idempotencyKey,
      @Valid @RequestBody WorkerProblemReportRequest request) {
    WorkerPrincipal principal = principal(jwt, true);
    TaskProblemReportService.CreatedWorkerProblemReport result =
        problemReports.create(
            principal.workerId(),
            principal.warehouseId(),
            entryId,
            idempotencyKey,
            request);
    return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK)
        .body(result.report());
  }

  /** Refreshes an author-owned report's asynchronously finalized photo states after task closure. */
  @GetMapping("/problem-reports/{reportId}")
  public WorkerProblemReport problemReport(
      @AuthenticationPrincipal Jwt jwt, @PathVariable UUID reportId) {
    WorkerPrincipal principal = principal(jwt, false);
    return problemReports.own(principal.workerId(), principal.warehouseId(), reportId);
  }

  /** Registers or replaces this worker's push-notification device installation. */
  @PutMapping("/devices/{installationId}")
  public ResponseEntity<WorkerDeviceRegistration> registerDevice(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable String installationId,
      @Valid @RequestBody WorkerDeviceRegistrationRequest request) {
    WorkerPrincipal principal = principal(jwt, true);
    WorkerTaskBoardService.DeviceRegistrationResult result =
        service.registerDevice(
            MobileTaskSurface.WORKER,
            principal.workerId(),
            principal.warehouseId(),
            installationId,
            request);
    return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK)
        .body(result.registration());
  }

  /** Removes only the authenticated worker's device installation binding. */
  @DeleteMapping("/devices/{installationId}")
  public ResponseEntity<Void> unregisterDevice(
      @AuthenticationPrincipal Jwt jwt, @PathVariable String installationId) {
    WorkerPrincipal principal = principal(jwt, true);
    service.unregisterDevice(
        MobileTaskSurface.WORKER,
        principal.workerId(),
        principal.warehouseId(),
        installationId);
    return ResponseEntity.noContent().build();
  }

  private WorkerPrincipal principal(Jwt jwt, boolean write) {
    access.requireMobileTaskScope(jwt, MobileTaskSurface.WORKER.scope());
    if (!"WORKER".equals(jwt.getClaimAsString("principal_type"))) {
      throw new AccessDeniedException("WORKER principal is required");
    }
    UUID workerId = requiredUuidClaim(jwt, "worker_id");
    UUID warehouseId = requiredUuidClaim(jwt, "warehouse_id");
    access.requireWarehouse(jwt, warehouseId, write ? AccessLevel.EDIT : AccessLevel.VIEW, true);
    return new WorkerPrincipal(workerId, warehouseId);
  }

  private UUID requiredUuidClaim(Jwt jwt, String name) {
    String value = jwt.getClaimAsString(name);
    if (value == null) {
      throw new AccessDeniedException(name + " claim is required");
    }
    try {
      return UUID.fromString(value);
    } catch (IllegalArgumentException exception) {
      throw new AccessDeniedException("Invalid " + name + " claim");
    }
  }

  private record WorkerPrincipal(UUID workerId, UUID warehouseId) {}
}
