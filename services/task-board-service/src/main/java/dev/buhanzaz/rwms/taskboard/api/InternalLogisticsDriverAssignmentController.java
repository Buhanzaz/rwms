package dev.buhanzaz.rwms.taskboard.api;

import static dev.buhanzaz.rwms.taskboard.api.LogisticsDriverAssignmentApiModels.ContractorDriverResponse;
import static dev.buhanzaz.rwms.taskboard.api.LogisticsDriverAssignmentApiModels.CreateContractorDriverRequest;
import static dev.buhanzaz.rwms.taskboard.api.LogisticsDriverAssignmentApiModels.CreateWorkerOperationalAssignmentRequest;
import static dev.buhanzaz.rwms.taskboard.api.LogisticsDriverAssignmentApiModels.TransitionWorkerOperationalAssignmentRequest;
import static dev.buhanzaz.rwms.taskboard.api.LogisticsDriverAssignmentApiModels.WorkerOperationalAssignmentResponse;

import dev.buhanzaz.rwms.taskboard.security.TaskSyncAuthorizer;
import dev.buhanzaz.rwms.taskboard.service.ContractorDriverService;
import dev.buhanzaz.rwms.taskboard.service.WorkerOperationalAssignmentService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Private logistics-service commands for contractor drivers and transfer-backed warehouse
 * assignments.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/internal/task-board/v1/logistics")
public class InternalLogisticsDriverAssignmentController {
  private final ContractorDriverService contractors;
  private final WorkerOperationalAssignmentService assignments;
  private final TaskSyncAuthorizer access;

  /** Creates an on-demand contractor profile without provisioning authentication credentials. */
  @PostMapping("/warehouses/{warehouseId}/contractors")
  @ResponseStatus(HttpStatus.CREATED)
  public ContractorDriverResponse createContractor(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID warehouseId,
      @Valid @RequestBody CreateContractorDriverRequest request) {
    access.requireLogisticsTaskAccess(jwt);
    return contractors.create(warehouseId, request);
  }

  /** Creates a planned assignment or returns its identical transfer-and-worker replay. */
  @PostMapping("/operational-assignments")
  @ResponseStatus(HttpStatus.CREATED)
  public WorkerOperationalAssignmentResponse createAssignment(
      @AuthenticationPrincipal Jwt jwt,
      @Valid @RequestBody CreateWorkerOperationalAssignmentRequest request) {
    access.requireLogisticsTaskAccess(jwt);
    return assignments.create(request, jwt.getSubject());
  }

  /** Returns one assignment snapshot. */
  @GetMapping("/operational-assignments/{assignmentId}")
  public WorkerOperationalAssignmentResponse getAssignment(
      @AuthenticationPrincipal Jwt jwt, @PathVariable UUID assignmentId) {
    access.requireLogisticsTaskAccess(jwt);
    return assignments.get(assignmentId);
  }

  /** Lists assignment history by exactly one transfer or worker identity. */
  @GetMapping("/operational-assignments")
  public List<WorkerOperationalAssignmentResponse> listAssignments(
      @AuthenticationPrincipal Jwt jwt,
      @RequestParam(required = false) UUID transferId,
      @RequestParam(required = false) UUID workerId) {
    access.requireLogisticsTaskAccess(jwt);
    return assignments.list(transferId, workerId);
  }

  /** Applies one version-fenced lifecycle transition with safe same-target replay. */
  @PostMapping("/operational-assignments/{assignmentId}/transition")
  public WorkerOperationalAssignmentResponse transitionAssignment(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID assignmentId,
      @Valid @RequestBody TransitionWorkerOperationalAssignmentRequest request) {
    access.requireLogisticsTaskAccess(jwt);
    return assignments.transition(
        assignmentId, request.expectedVersion(), request.targetStatus(), jwt.getSubject());
  }
}
