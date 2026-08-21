package dev.buhanzaz.rwms.maintenance.integration;

import static dev.buhanzaz.rwms.maintenance.integration.MaintenanceDependencyGateway.*;

import dev.buhanzaz.rwms.maintenance.service.MaintenanceDependencyException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;

/**
 * Owns maintenance's private task-board and queue-registry calls, including route and preflight
 * truth validation.
 */
final class MaintenanceTaskBoardHttpClient {
  private static final String TASK_CLIENT = "maintenance-task-board";
  private static final String TASK_SCOPE = "task-board.task-sync";
  private static final String TASK_REGISTRY_CLIENT = "maintenance-task-board-registry";
  private static final String TASK_REGISTRY_SCOPE = "queue-registry.write";

  private final MaintenanceHttpTransport transport;
  private final String taskBase;
  private final String taskCatalogRoutingPreflightUrl;
  private final String taskRoutingPreflightUrl;
  private final String taskWarehouseInternalBase;
  private final String taskRegistryBase;

  MaintenanceTaskBoardHttpClient(
      MaintenanceHttpTransport transport, MaintenanceDependencyProperties.Validated properties) {
    this.transport = transport;
    String taskBoardBase = MaintenanceHttpTransport.strip(properties.taskBoardBaseUrl().toString());
    taskBase = taskBoardBase + "/api/internal/task-board/v1/tasks";
    taskCatalogRoutingPreflightUrl =
        taskBoardBase + "/api/internal/task-board/v1/maintenance/catalog-routing-preflight";
    taskRoutingPreflightUrl =
        taskBoardBase + "/api/internal/task-board/v1/maintenance/routing-preflight";
    taskWarehouseInternalBase = taskBoardBase + "/api/internal/task-board/v1/warehouses";
    taskRegistryBase = taskBoardBase + "/api/internal/queue-definitions";
  }

  TaskSnapshot registerTask(
      UUID key,
      UUID externalTaskId,
      UUID sourceRepairId,
      UUID warehouseId,
      UUID rentalItemId,
      String unitNumber,
      LocalDate scheduledDate,
      int priority,
      List<TaskStage> stages) {
    String canonicalUnitNumber = requireTaskUnitNumber(unitNumber);
    TaskResponse response = transport.post(
        taskBase,
        key,
        new RegisterTaskRequest(
            warehouseId,
            externalTaskId,
            sourceRepairId == null
                ? null
                : new TaskSourceReference("MAINTENANCE_REPAIR", sourceRepairId),
            taskTitle(stages),
            canonicalUnitNumber,
            null,
            duration(stages),
            commonDeadline(stages),
            route(stages),
            scheduledDate,
            priority),
        TaskResponse.class,
        TASK_CLIENT,
        TASK_SCOPE);
    TaskSnapshot snapshot = task(response, externalTaskId, stages.size());
    if (response.scheduledDate() == null
        || response.scheduledDate().isBefore(scheduledDate)
        || priority != response.priority()
        || !canonicalUnitNumber.equals(response.unitNumber())) {
      throw MaintenanceHttpTransport.malformed(
          "Task-board returned another schedule, priority, or rental-item number");
    }
    return snapshot;
  }

  TaskSnapshot updatePreStartTask(
      UUID key,
      UUID externalTaskId,
      long expectedVersion,
      String unitNumber,
      List<TaskStage> stages) {
    String canonicalUnitNumber = requireTaskUnitNumber(unitNumber);
    TaskResponse response = transport.put(
        taskBase + "/" + externalTaskId,
        key,
        new UpdateTaskRequest(
            expectedVersion,
            taskTitle(stages),
            canonicalUnitNumber,
            null,
            duration(stages),
            commonDeadline(stages),
            route(stages)),
        TaskResponse.class,
        TASK_CLIENT,
        TASK_SCOPE);
    TaskSnapshot snapshot = task(response, externalTaskId, stages.size());
    if (!canonicalUnitNumber.equals(response.unitNumber())) {
      throw MaintenanceHttpTransport.malformed("Task-board returned another rental-item number");
    }
    return snapshot;
  }

  TaskSnapshot getTask(UUID externalTaskId) {
    try {
      TaskResponse response = transport.client()
          .get()
          .uri(taskBase + "/" + externalTaskId)
          .header(HttpHeaders.AUTHORIZATION, transport.bearer(TASK_CLIENT, TASK_SCOPE))
          .retrieve()
          .body(TaskResponse.class);
      return task(response, externalTaskId, -1);
    } catch (RuntimeException exception) {
      throw transport.dependencyFailure(exception);
    }
  }

  TaskSnapshot cancelTask(UUID key, UUID externalTaskId, long expectedVersion) {
    CancelTaskResponse response = transport.post(
        taskBase + "/" + externalTaskId + "/cancel",
        key,
        new CancelTaskRequest(expectedVersion, "maintenance-command"),
        CancelTaskResponse.class,
        TASK_CLIENT,
        TASK_SCOPE);
    if (!externalTaskId.equals(response.externalTaskId())) {
      throw MaintenanceHttpTransport.malformed("Task-board returned another task");
    }
    return new TaskSnapshot(externalTaskId, response.taskVersion(), response.status(), List.of());
  }

  PreStartTaskCancellation cancelTaskIfPreStart(
      UUID key, UUID externalTaskId, long expectedVersion) {
    PreStartCancellationResponse response = transport.post(
        taskBase + "/" + externalTaskId + "/cancel-if-pre-start",
        key,
        new CancelTaskRequest(expectedVersion, "inventory-publication-replacement"),
        PreStartCancellationResponse.class,
        TASK_CLIENT,
        TASK_SCOPE);
    if (response == null
        || response.outcome() == null
        || response.taskId() == null
        || !externalTaskId.equals(response.externalTaskId())
        || response.taskVersion() < 0
        || response.status() == null
        || response.status().isBlank()) {
      throw MaintenanceHttpTransport.malformed(
          "Task-board returned malformed pre-start cancellation truth");
    }
    try {
      return new PreStartTaskCancellation(
          PreStartTaskCancellationOutcome.valueOf(response.outcome()),
          response.taskId(),
          response.externalTaskId(),
          response.taskVersion(),
          response.status(),
          response.cancelledAt());
    } catch (IllegalArgumentException exception) {
      throw MaintenanceHttpTransport.malformed(
          "Task-board returned unknown pre-start cancellation outcome");
    }
  }

  TaskSnapshot relocateTask(
      UUID key, UUID externalTaskId, long expectedVersion, UUID targetWarehouseId) {
    TaskResponse response = transport.post(
        taskBase + "/" + externalTaskId + "/relocate",
        key,
        new RelocateTaskRequest(expectedVersion, targetWarehouseId),
        TaskResponse.class,
        TASK_CLIENT,
        TASK_SCOPE);
    if (!targetWarehouseId.equals(response.warehouseId())) {
      throw MaintenanceHttpTransport.malformed(
          "Task-board returned another warehouse after task relocation");
    }
    return task(response, externalTaskId, -1);
  }

  CatalogRoutingPreflight preflightCatalogRouting(List<CatalogRoutingQueueRequirement> queues) {
    if (queues == null || queues.isEmpty()) {
      throw new IllegalArgumentException("Catalog routing preflight is required");
    }
    Set<UUID> requestedDefinitionIds = queues.stream()
        .map(CatalogRoutingQueueRequirement::queueDefinitionId)
        .collect(java.util.stream.Collectors.toSet());
    if (requestedDefinitionIds.size() != queues.size()) {
      throw new IllegalArgumentException(
          "Catalog routing queue-definition identifiers must be unique");
    }
    try {
      CatalogRoutingPreflight response = transport.client()
          .post()
          .uri(taskCatalogRoutingPreflightUrl)
          .header(HttpHeaders.AUTHORIZATION, transport.bearer(TASK_CLIENT, TASK_SCOPE))
          .body(new CatalogRoutingPreflightRequest(List.copyOf(queues)))
          .retrieve()
          .body(CatalogRoutingPreflight.class);
      validateCatalogRoutingPreflight(response, requestedDefinitionIds);
      return response;
    } catch (RuntimeException exception) {
      throw transport.dependencyFailure(exception);
    }
  }

  RoutingPreflight preflightMaintenanceRouting(
      UUID warehouseId, List<RoutingQueueRequirement> queues) {
    if (warehouseId == null || queues == null || queues.isEmpty()) {
      throw new IllegalArgumentException("Maintenance routing preflight is required");
    }
    Set<UUID> requestedQueueIds = queues.stream()
        .map(RoutingQueueRequirement::queueDefinitionId)
        .collect(java.util.stream.Collectors.toSet());
    if (requestedQueueIds.size() != queues.size()) {
      throw new IllegalArgumentException("Maintenance routing queue identifiers must be unique");
    }
    try {
      RoutingPreflight response = transport.client()
          .post()
          .uri(taskRoutingPreflightUrl)
          .header(HttpHeaders.AUTHORIZATION, transport.bearer(TASK_CLIENT, TASK_SCOPE))
          .body(new RoutingPreflightRequest(warehouseId, List.copyOf(queues)))
          .retrieve()
          .body(RoutingPreflight.class);
      validateRoutingPreflight(response, warehouseId, requestedQueueIds);
      return response;
    } catch (RuntimeException exception) {
      throw transport.dependencyFailure(exception);
    }
  }

  QueueCapabilities queueCapabilities(UUID warehouseId) {
    try {
      QueueCapabilities response = transport.client()
          .get()
          .uri(taskWarehouseInternalBase + "/" + warehouseId + "/queue-capabilities")
          .header(HttpHeaders.AUTHORIZATION, transport.bearer(TASK_CLIENT, TASK_SCOPE))
          .retrieve()
          .body(QueueCapabilities.class);
      if (response == null || !warehouseId.equals(response.warehouseId())) {
        throw MaintenanceHttpTransport.malformed(
            "Task-board returned another warehouse queue capability truth");
      }
      return response;
    } catch (RuntimeException exception) {
      throw transport.dependencyFailure(exception);
    }
  }

  CatalogPositionReference registerCatalogPosition(UUID queueId, String externalReferenceId) {
    if (queueId == null
        || externalReferenceId == null
        || externalReferenceId.isBlank()
        || externalReferenceId.length() > 128) {
      throw new IllegalArgumentException("Catalog-position reference is required");
    }
    try {
      CatalogPositionReference response = transport.client().post()
          .uri(taskRegistryBase + "/" + queueId + "/references")
          .header(
              HttpHeaders.AUTHORIZATION,
              transport.bearer(TASK_REGISTRY_CLIENT, TASK_REGISTRY_SCOPE))
          .body(new QueueReferenceRequest("CATALOG_POSITION", externalReferenceId))
          .retrieve()
          .body(CatalogPositionReference.class);
      if (response == null
          || !queueId.equals(response.queueDefinitionId())
          || !"CATALOG_POSITION".equals(response.type())
          || !externalReferenceId.equals(response.externalReferenceId())) {
        throw MaintenanceHttpTransport.malformed(
            "Task-board returned mismatched catalog-position reference truth");
      }
      return response;
    } catch (RuntimeException exception) {
      throw transport.dependencyFailure(exception);
    }
  }

  void deleteCatalogPosition(String externalReferenceId, long expectedVersion) {
    if (externalReferenceId == null
        || externalReferenceId.isBlank()
        || externalReferenceId.length() > 128
        || expectedVersion < 0) {
      throw new IllegalArgumentException("Catalog-position deletion is required");
    }
    try {
      transport.client().delete()
          .uri(
              taskRegistryBase
                  + "/references/CATALOG_POSITION/"
                  + externalReferenceId
                  + "?expectedVersion="
                  + expectedVersion)
          .header(
              HttpHeaders.AUTHORIZATION,
              transport.bearer(TASK_REGISTRY_CLIENT, TASK_REGISTRY_SCOPE))
          .retrieve()
          .toBodilessEntity();
    } catch (HttpClientErrorException.NotFound replayed) {
      // At-least-once delivery can replay after task-board committed but maintenance did not.
    } catch (RuntimeException exception) {
      throw transport.dependencyFailure(exception);
    }
  }

  private static TaskSnapshot task(
      TaskResponse response, UUID externalTaskId, int expectedRouteSize) {
    if (response == null
        || !externalTaskId.equals(response.externalTaskId())
        || response.taskVersion() < 0
        || response.route() == null
        || (expectedRouteSize >= 0 && response.route().size() != expectedRouteSize)) {
      throw MaintenanceHttpTransport.malformed("Task-board returned malformed registration truth");
    }
    List<TaskStageSnapshot> route = response.route().stream()
        .map(
            stage ->
                new TaskStageSnapshot(stage.routeIndex(), stage.entryId(), stage.entryVersion()))
        .toList();
    for (int index = 0; index < route.size(); index++) {
      if (route.get(index).routeIndex() != index) {
        throw MaintenanceHttpTransport.malformed("Task-board route indices are not canonical");
      }
    }
    return new TaskSnapshot(externalTaskId, response.taskVersion(), response.status(), route);
  }

  private static void validateRoutingPreflight(
      RoutingPreflight response, UUID warehouseId, Set<UUID> requestedQueueIds) {
    if (response == null
        || !warehouseId.equals(response.warehouseId())
        || response.missingQueueDefinitionIds() == null
        || response.missingWarehouseBindingDefinitionIds() == null
        || response.mismatches() == null
        || response.queues() == null
        || response.ready()
            != (response.missingQueueDefinitionIds().isEmpty()
                && response.missingWarehouseBindingDefinitionIds().isEmpty()
                && response.mismatches().isEmpty())) {
      throw MaintenanceHttpTransport.malformed(
          "Task-board returned malformed maintenance routing truth");
    }
    Set<UUID> missingDefinitions = Set.copyOf(response.missingQueueDefinitionIds());
    Set<UUID> missingBindings = Set.copyOf(response.missingWarehouseBindingDefinitionIds());
    Set<UUID> mismatched = response.mismatches().stream()
        .map(RoutingMismatch::queueDefinitionId)
        .collect(java.util.stream.Collectors.toSet());
    Set<UUID> returned = response.queues().stream()
        .map(RoutingQueueSnapshot::queueDefinitionId)
        .collect(java.util.stream.Collectors.toSet());
    if (missingDefinitions.size() != response.missingQueueDefinitionIds().size()
        || missingBindings.size() != response.missingWarehouseBindingDefinitionIds().size()
        || mismatched.size() != response.mismatches().size()
        || returned.size() != response.queues().size()
        || !requestedQueueIds.containsAll(missingDefinitions)
        || !requestedQueueIds.containsAll(missingBindings)
        || !requestedQueueIds.containsAll(mismatched)
        || !requestedQueueIds.containsAll(returned)
        || missingDefinitions.stream().anyMatch(mismatched::contains)
        || missingBindings.stream().anyMatch(mismatched::contains)
        || java.util.stream.Stream.of(missingDefinitions, missingBindings, mismatched, returned)
                .flatMap(Set::stream)
                .collect(java.util.stream.Collectors.toSet())
                .size()
            != requestedQueueIds.size()) {
      throw MaintenanceHttpTransport.malformed(
          "Task-board returned unrelated maintenance routing truth");
    }
  }

  private static void validateCatalogRoutingPreflight(
      CatalogRoutingPreflight response, Set<UUID> requestedDefinitionIds) {
    if (response == null
        || response.missingQueueDefinitionIds() == null
        || response.mismatches() == null
        || response.resolvedDefinitions() == null
        || response.ready()
            != (response.missingQueueDefinitionIds().isEmpty()
                && response.mismatches().isEmpty())) {
      throw MaintenanceHttpTransport.malformed(
          "Task-board returned malformed catalog routing truth");
    }
    Set<UUID> missing = Set.copyOf(response.missingQueueDefinitionIds());
    Set<UUID> mismatched = response.mismatches().stream()
        .map(CatalogRoutingMismatch::queueDefinitionId)
        .collect(java.util.stream.Collectors.toSet());
    Set<UUID> returned = response.resolvedDefinitions().stream()
        .map(QueueDefinitionSnapshot::queueDefinitionId)
        .collect(java.util.stream.Collectors.toSet());
    if (missing.size() != response.missingQueueDefinitionIds().size()
        || mismatched.size() != response.mismatches().size()
        || returned.size() != response.resolvedDefinitions().size()
        || !requestedDefinitionIds.containsAll(missing)
        || !requestedDefinitionIds.containsAll(mismatched)
        || !requestedDefinitionIds.containsAll(returned)
        || java.util.stream.Stream.of(missing, mismatched, returned)
                .flatMap(Set::stream)
                .collect(java.util.stream.Collectors.toSet())
                .size()
            != requestedDefinitionIds.size()) {
      throw MaintenanceHttpTransport.malformed(
          "Task-board returned unrelated catalog routing truth");
    }
  }

  private static List<RouteStep> route(List<TaskStage> stages) {
    return stages.stream()
        .map(
            stage -> {
              UUID queueId = stage.queueId();
              if (queueId == null) {
                throw new MaintenanceDependencyException(
                    HttpStatus.UNPROCESSABLE_ENTITY, "Repair stage queue identity is required");
              }
              return new RouteStep(
                  queueId,
                  stage.title(),
                  stage.plannedDurationMinutes(),
                  stage.works(),
                  stage.materials(),
                  stage.comments(),
                  stage.sourceMedia());
            })
        .toList();
  }

  /** Requires one source-owned repair title across every route stage in a task package. */
  private static String taskTitle(List<TaskStage> stages) {
    if (stages == null || stages.isEmpty()) {
      throw new MaintenanceDependencyException(
          HttpStatus.UNPROCESSABLE_ENTITY, "Repair task requires at least one route stage");
    }
    List<String> titles = stages.stream().map(TaskStage::taskTitle).distinct().toList();
    if (titles.size() != 1 || titles.getFirst() == null || titles.getFirst().isBlank()) {
      throw new MaintenanceDependencyException(
          HttpStatus.UNPROCESSABLE_ENTITY,
          "Repair route stages must carry one common worker task title");
    }
    return titles.getFirst();
  }

  private static Integer duration(List<TaskStage> stages) {
    if (stages == null || stages.isEmpty()) {
      throw new MaintenanceDependencyException(
          HttpStatus.UNPROCESSABLE_ENTITY, "Repair task requires at least one route stage");
    }
    int total = 0;
    boolean present = false;
    try {
      for (TaskStage stage : stages) {
        if (stage == null) {
          throw new MaintenanceDependencyException(
              HttpStatus.UNPROCESSABLE_ENTITY, "Repair route stage is required");
        }
        if (stage.plannedDurationMinutes() == null) {
          continue;
        }
        if (stage.plannedDurationMinutes() < 1) {
          throw new MaintenanceDependencyException(
              HttpStatus.UNPROCESSABLE_ENTITY,
              "Repair route stage planned duration must be positive when present");
        }
        present = true;
        total = Math.addExact(total, stage.plannedDurationMinutes());
      }
    } catch (ArithmeticException exception) {
      throw new MaintenanceDependencyException(
          HttpStatus.UNPROCESSABLE_ENTITY,
          "Repair task planned duration exceeds the supported range",
          exception);
    }
    return present ? total : null;
  }

  private static OffsetDateTime commonDeadline(List<TaskStage> stages) {
    List<OffsetDateTime> deadlines = stages.stream()
        .map(TaskStage::taskDeadline)
        .filter(java.util.Objects::nonNull)
        .distinct()
        .toList();
    if (deadlines.size() > 1) {
      throw new MaintenanceDependencyException(
          HttpStatus.UNPROCESSABLE_ENTITY,
          "Repair plan stage deadlines must be absent or one identical timestamp");
    }
    return deadlines.isEmpty() ? null : deadlines.getFirst();
  }

  private static String requireTaskUnitNumber(String value) {
    if (value == null || value.isBlank() || value.length() > 64) {
      throw new IllegalArgumentException("Rental-item number is invalid for task synchronization");
    }
    return value;
  }

  /**
   * Transport representation of one immutable maintenance stage and all of its task-board work,
   * material, comment and source-media evidence.
   */
  private record RouteStep(
      UUID queueDefinitionId,
      String taskText,
      Integer plannedDurationMinutes,
      List<TaskWork> works,
      List<TaskMaterial> materials,
      List<TaskComment> comments,
      List<TaskSourceMedia> sourceMedia) {}

  /** Requests warehouse-scoped readiness for the exact queues required by a maintenance route. */
  private record RoutingPreflightRequest(UUID warehouseId, List<RoutingQueueRequirement> queues) {}

  /** Requests catalog-wide readiness for the queue definitions selected by maintenance. */
  private record CatalogRoutingPreflightRequest(List<CatalogRoutingQueueRequirement> queues) {}

  /** Identifies a queue-registry reference by its authoritative type and external identity. */
  private record QueueReferenceRequest(String type, String externalReferenceId) {}

  /** Binds a task-board registration to the maintenance aggregate that owns the source command. */
  private record TaskSourceReference(String type, UUID sourceId) {}

  /**
   * Complete idempotent task registration payload derived from a frozen maintenance route and its
   * scheduling policy.
   */
  private record RegisterTaskRequest(
      UUID warehouseId,
      UUID externalTaskId,
      TaskSourceReference source,
      String title,
      String unitNumber,
      String description,
      Integer plannedDurationMinutes,
      OffsetDateTime deadlineAt,
      List<RouteStep> route,
      LocalDate scheduledDate,
      int priority) {}

  /**
   * Replaces a not-yet-started task route under the caller's observed task version; task-board
   * remains responsible for the compare-and-set transition.
   */
  private record UpdateTaskRequest(
      long expectedTaskVersion,
      String title,
      String unitNumber,
      String description,
      Integer plannedDurationMinutes,
      OffsetDateTime deadlineAt,
      List<RouteStep> route) {}

  /** Identifies one persisted task-board route entry and the queue/version assigned to it. */
  private record RouteResponse(
      UUID entryId, long entryVersion, UUID queueDefinitionId, UUID workQueueId, int routeIndex) {}

  /**
   * Task-board-owned task snapshot validated against the requested external identity and route
   * cardinality before it enters maintenance reconciliation.
   */
  private record TaskResponse(
      UUID taskId,
      long taskVersion,
      UUID warehouseId,
      UUID externalTaskId,
      String title,
      String unitNumber,
      String description,
      String status,
      Integer plannedDurationMinutes,
      OffsetDateTime deadlineAt,
      LocalDate scheduledDate,
      int priority,
      boolean pinned,
      OffsetDateTime doneAt,
      List<RouteResponse> route) {}

  /** Fences a cancellation with the observed task version and maintenance-owned reason code. */
  private record CancelTaskRequest(long expectedTaskVersion, String reason) {}

  /**
   * Explicit pre-start cancellation outcome used to distinguish a terminal replay from work that
   * has already begun and must not be cancelled as compensation.
   */
  private record PreStartCancellationResponse(
      String outcome,
      UUID taskId,
      UUID externalTaskId,
      long taskVersion,
      String status,
      OffsetDateTime cancelledAt) {}

  /** Requests warehouse relocation under the observed task version and target identity. */
  private record RelocateTaskRequest(long expectedTaskVersion, UUID targetWarehouseId) {}

  /** Terminal cancellation snapshot used to reconcile repeated maintenance cancellation calls. */
  private record CancelTaskResponse(
      UUID taskId,
      UUID externalTaskId,
      long taskVersion,
      String status,
      OffsetDateTime cancelledAt) {}
}
