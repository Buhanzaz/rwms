package dev.buhanzaz.rwms.taskboard.service;

import static dev.buhanzaz.rwms.taskboard.api.PlanningReplacementApiModels.PlanningReplacementOutcome.APPLIED;
import static dev.buhanzaz.rwms.taskboard.api.PlanningReplacementApiModels.PlanningReplacementOutcome.REPLAYED;

import dev.buhanzaz.rwms.taskboard.api.PlanningReplacementApiModels.PlanningReplacementRequest;
import dev.buhanzaz.rwms.taskboard.api.PlanningReplacementApiModels.PlanningReplacementResponse;
import dev.buhanzaz.rwms.taskboard.api.PlanningReplacementApiModels.PlanningReplacementTaskRequest;
import dev.buhanzaz.rwms.taskboard.domain.PlanningAssignmentReplacementReceipt;
import dev.buhanzaz.rwms.taskboard.domain.TaskSourceType;
import dev.buhanzaz.rwms.taskboard.domain.TaskSyncSource;
import dev.buhanzaz.rwms.taskboard.eventing.TaskBoardEventStore;
import dev.buhanzaz.rwms.taskboard.repository.PlanningAssignmentReplacementReceiptRepository;
import dev.buhanzaz.rwms.taskboard.repository.TaskSyncSourceRepository;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * Owns the single transactional task-board boundary for a complete pre-start planner revision.
 * Receipt replay is checked before and after lineage locking so concurrent retries cannot execute
 * the batch twice or observe a partially changed queue.
 */
@Service
public class PlanningReplacementService {
  private static final String LOGISTICS_SERVICE = "logistics-service";

  private final TaskSyncSourceRepository sources;
  private final PlanningAssignmentReplacementReceiptRepository receipts;
  private final TaskBoardExternalMutationService taskMutations;
  private final DriverShiftService shifts;
  private final ObjectMapper objectMapper;
  private final Clock clock;

  /** Creates the replacement owner with a replaceable UTC clock for deterministic tests. */
  public PlanningReplacementService(
      TaskSyncSourceRepository sources,
      PlanningAssignmentReplacementReceiptRepository receipts,
      TaskBoardExternalMutationService taskMutations,
      DriverShiftService shifts,
      ObjectMapper objectMapper,
      ObjectProvider<Clock> clocks) {
    this.sources = sources;
    this.receipts = receipts;
    this.taskMutations = taskMutations;
    this.shifts = shifts;
    this.objectMapper = objectMapper;
    this.clock = clocks.getIfAvailable(Clock::systemUTC);
  }

  /** Applies every task and shift change or rolls the complete task-board command back. */
  @Transactional
  public PlanningReplacementResponse replace(
      UUID sourcePlanId, UUID idempotencyKey, PlanningReplacementRequest request) {
    requireCommand(sourcePlanId, idempotencyKey, request);
    String fingerprint = fingerprint(sourcePlanId, request);
    PlanningAssignmentReplacementReceipt receipt = receipts.findForUpdate(idempotencyKey).orElse(null);
    if (receipt != null) return replay(receipt, sourcePlanId, request, fingerprint);

    List<TaskSyncSource> membership =
        sources.findAllBySourcePlanIdForUpdate(LOGISTICS_SERVICE, sourcePlanId);
    if (membership.isEmpty()) {
      throw new ConflictException("Planner task lineage is absent");
    }
    receipt = receipts.findForUpdate(idempotencyKey).orElse(null);
    if (receipt != null) return replay(receipt, sourcePlanId, request, fingerprint);
    requireCompleteMembership(sourcePlanId, request, membership);

    var taskResults = taskMutations.replacePlannerTasks(request.date(), request.assignments());
    var shiftResults =
        shifts.replacePlansAtomically(
            sourcePlanId,
            request.expectedSourcePlanVersion(),
            request.replacementPlanVersion(),
            request.warehouseId(),
            request.date(),
            request.driverShiftPlans());
    membership.forEach(
        source ->
            source.advanceSourcePlan(
                request.expectedSourcePlanVersion(), request.replacementPlanVersion()));
    sources.saveAllAndFlush(membership);

    PlanningReplacementResponse response =
        new PlanningReplacementResponse(
            APPLIED,
            sourcePlanId,
            request.replacementPlanVersion(),
            request.warehouseId(),
            request.date(),
            taskResults,
            shiftResults);
    receipts.saveAndFlush(
        new PlanningAssignmentReplacementReceipt(
            idempotencyKey,
            sourcePlanId,
            request.expectedSourcePlanVersion(),
            request.replacementPlanVersion(),
            request.warehouseId(),
            request.date(),
            fingerprint,
            encode(response),
            OffsetDateTime.now(clock)));
    return response;
  }

  private void requireCompleteMembership(
      UUID sourcePlanId,
      PlanningReplacementRequest request,
      List<TaskSyncSource> membership) {
    Map<UUID, PlanningReplacementTaskRequest> requested =
        request.assignments().stream()
            .collect(
                java.util.stream.Collectors.toMap(
                    PlanningReplacementTaskRequest::externalTaskId,
                    java.util.function.Function.identity(),
                    (left, right) -> {
                      throw new IllegalArgumentException(
                          "Planner replacement contains duplicate external tasks");
                    }));
    Set<UUID> persistedIds =
        membership.stream()
            .map(TaskSyncSource::getExternalTaskId)
            .collect(java.util.stream.Collectors.toUnmodifiableSet());
    if (!persistedIds.equals(requested.keySet())) {
      throw new ConflictException("Planner task membership is incomplete or divergent");
    }
    for (TaskSyncSource source : membership) {
      PlanningReplacementTaskRequest item = requested.get(source.getExternalTaskId());
      if (!sourcePlanId.equals(source.getSourcePlanId())
          || source.getSourcePlanVersion() == null
          || source.getSourcePlanVersion() != request.expectedSourcePlanVersion()
          || !request.warehouseId().equals(source.getSourcePlanWarehouseId())
          || !request.date().equals(source.getSourcePlanDate())
          || source.getSourceType() != TaskSourceType.LOGISTICS_DRIVER_TASK
          || !item.sourceTaskId().equals(source.getSourceId())) {
        throw new ConflictException("Planner task lineage fence changed");
      }
    }
  }

  private static void requireCommand(
      UUID sourcePlanId, UUID idempotencyKey, PlanningReplacementRequest request) {
    if (sourcePlanId == null || idempotencyKey == null || request == null) {
      throw new IllegalArgumentException("Planner replacement identity is required");
    }
    if (request.expectedSourcePlanVersion() == null
        || request.replacementPlanVersion() == null
        || request.replacementPlanVersion() <= request.expectedSourcePlanVersion()) {
      throw new IllegalArgumentException("Replacement plan version must be strictly newer");
    }
  }

  private PlanningReplacementResponse replay(
      PlanningAssignmentReplacementReceipt receipt,
      UUID sourcePlanId,
      PlanningReplacementRequest request,
      String fingerprint) {
    if (!sourcePlanId.equals(receipt.getSourcePlanId())
        || receipt.getExpectedSourcePlanVersion() != request.expectedSourcePlanVersion()
        || receipt.getReplacementPlanVersion() != request.replacementPlanVersion()
        || !request.warehouseId().equals(receipt.getSourcePlanWarehouseId())
        || !request.date().equals(receipt.getSourcePlanDate())
        || !fingerprint.equals(receipt.getRequestSha256())) {
      throw new ConflictException("Idempotency-Key was reused for another planner replacement");
    }
    PlanningReplacementResponse applied = decode(receipt.getResponseJson());
    return new PlanningReplacementResponse(
        REPLAYED,
        applied.sourcePlanId(),
        applied.sourcePlanVersion(),
        applied.warehouseId(),
        applied.date(),
        applied.assignments(),
        applied.driverShiftPlans());
  }

  private String fingerprint(UUID sourcePlanId, PlanningReplacementRequest request) {
    try {
      byte[] body = objectMapper.writeValueAsBytes(Map.of("sourcePlanId", sourcePlanId, "request", request));
      return TaskBoardEventStore.sha256(body);
    } catch (JacksonException exception) {
      throw new IllegalArgumentException("Planner replacement cannot be serialized", exception);
    }
  }

  private String encode(PlanningReplacementResponse response) {
    try {
      return objectMapper.writeValueAsString(response);
    } catch (JacksonException exception) {
      throw new IllegalArgumentException("Planner replacement response cannot be serialized", exception);
    }
  }

  private PlanningReplacementResponse decode(String responseJson) {
    try {
      return objectMapper.readValue(responseJson, PlanningReplacementResponse.class);
    } catch (JacksonException exception) {
      throw new IllegalStateException("Stored planner replacement receipt is invalid", exception);
    }
  }
}
