package dev.buhanzaz.rwms.inventory.service;

import static dev.buhanzaz.rwms.inventory.api.InventoryApiModels.*;

import dev.buhanzaz.rwms.inventory.domain.FinalPlanReconciliationStrategy;
import dev.buhanzaz.rwms.inventory.domain.FinalPlanScheduleMode;
import dev.buhanzaz.rwms.inventory.domain.FinalPlanState;
import dev.buhanzaz.rwms.inventory.domain.FinalPlanTargetKind;
import dev.buhanzaz.rwms.inventory.domain.FindingPlanSnapshot;
import dev.buhanzaz.rwms.inventory.domain.InspectionState;
import dev.buhanzaz.rwms.inventory.domain.InventoryFinalPlan;
import dev.buhanzaz.rwms.inventory.domain.InventoryFinalPlanEntry;
import dev.buhanzaz.rwms.inventory.domain.InventoryCabinDispositionKind;
import dev.buhanzaz.rwms.inventory.domain.InventoryFinding;
import dev.buhanzaz.rwms.inventory.domain.InventoryPlanningSettings;
import dev.buhanzaz.rwms.inventory.domain.InventorySession;
import dev.buhanzaz.rwms.inventory.domain.SessionLifecycle;
import dev.buhanzaz.rwms.inventory.integration.InventoryDependencyGateway;
import dev.buhanzaz.rwms.inventory.repository.FindingMediaReferenceRepository;
import dev.buhanzaz.rwms.inventory.repository.FindingPlanSnapshotRepository;
import dev.buhanzaz.rwms.inventory.repository.InventoryFinalPlanEntryRepository;
import dev.buhanzaz.rwms.inventory.repository.InventoryFinalPlanRepository;
import dev.buhanzaz.rwms.inventory.repository.InventoryFindingRepository;
import dev.buhanzaz.rwms.inventory.repository.InventoryPlanningSettingsRepository;
import dev.buhanzaz.rwms.inventory.repository.InventorySessionRepository;
import dev.buhanzaz.rwms.inventory.security.InventoryAuthorizer;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Owns warehouse planning settings and immutable, versioned final maintenance plans.
 *
 * <p>Maintenance reconciliation preflight occurs before persistence; completion reuses the
 * persisted plan evidence and rechecks candidate freshness without changing its meaning.
 */
@Service
final class InventoryPlanningService extends InventoryPlanningWorkflowSupport {
  private final InventoryCabinDispositionService cabinDispositions;

  InventoryPlanningService(
      InventorySessionRepository sessions,
      InventoryFindingRepository findings,
      InventoryPlanningSettingsRepository planningSettings,
      InventoryFinalPlanRepository finalPlans,
      InventoryFinalPlanEntryRepository finalPlanEntries,
      FindingMediaReferenceRepository mediaReferences,
      FindingPlanSnapshotRepository planSnapshots,
      InventoryDependencyGateway dependencies,
      InventoryIdempotencyPort idempotency,
      InventoryFrozenPlanFingerprint frozenPlanFingerprint,
      InventoryCabinDispositionService cabinDispositions,
      ObjectMapper mapper,
      InventoryCanonicalJsonPort canonicalJson,
      InventoryAuthorizer authorizer,
      PlatformTransactionManager transactionManager) {
    super(
        sessions,
        findings,
        planningSettings,
        finalPlans,
        finalPlanEntries,
        mediaReferences,
        planSnapshots,
        dependencies,
        idempotency,
        frozenPlanFingerprint,
        mapper,
        canonicalJson,
        authorizer,
        transactionManager);
    this.cabinDispositions = cabinDispositions;
  }

  public PlanningSettingsView planningSettings(Jwt jwt, UUID warehouseId) {
    authorizer.requireManage(jwt, warehouseId);
    return planningSettingsView(warehouseId, planningSpecification(warehouseId));
  }

  public PlanningSettingsView updatePlanningSettings(
      Jwt jwt, UUID warehouseId, PlanningSettingsUpdateRequest request) {
    authorizer.requireManage(jwt, warehouseId);
    PlanningSpecification requested = planningSpecification(request);
    return transactions.execute(
        ignored -> {
          InventoryPlanningSettings existing = planningSettings.findById(warehouseId).orElse(null);
          long revision = existing == null ? 0 : existing.getRevision();
          expectRevision(revision, request.expectedSettingsRevision());
          InventoryPlanningSettings saved;
          if (existing == null) {
            saved =
                planningSettings.saveAndFlush(
                    InventoryPlanningSettings.create(warehouseId, holidaysJson(requested.holidays())));
          } else {
            existing.replaceHolidays(holidaysJson(requested.holidays()));
            saved = planningSettings.saveAndFlush(existing);
          }
          sessions
              .findByWarehouseIdAndLifecycle(warehouseId, SessionLifecycle.ACTIVE)
              .ifPresent(this::invalidateFinalPlan);
          return planningSettingsView(warehouseId, planningSpecification(saved));
        });
  }

  public FinalPlanView prepareFinalPlan(
      Jwt jwt, UUID inventoryId, UUID idempotencyKey, PrepareFinalPlanRequest request) {
    requireScopedSession(inventoryId, authorizer.manageScope(jwt));
    return idempotency.execute(
        authorizer.subjectId(jwt),
        "session.final-plan.prepare",
        idempotencyKey,
        Map.of("inventoryId", inventoryId, "request", request),
        HttpStatus.OK.value(),
        FinalPlanView.class,
        () -> doPrepareFinalPlan(jwt, inventoryId, idempotencyKey, request));
  }

  FinalPlanView doPrepareFinalPlan(
      Jwt jwt, UUID inventoryId, UUID idempotencyKey, PrepareFinalPlanRequest request) {
    InventorySession session = requireActive(inventoryId);
    authorizer.requireManage(jwt, session.getWarehouseId());
    expectRevision(session.getRevision(), request.expectedSessionRevision());
    PlanningSpecification settings = planningSpecification(session.getWarehouseId());
    expectRevision(settings.revision(), request.expectedSettingsRevision());
    InventoryFinalPlan existing = finalPlans.findById(inventoryId).orElse(null);
    long finalPlanVersion = existing == null ? 1 : Math.addExact(existing.getFinalPlanVersion(), 1);
    InventoryPlanningCalendar calendar = calendarFor(session);
    List<FinalPlanDraft> draft =
        scheduleFinalPlan(
            session,
            settings,
            calendar,
            request.movementScheduleMode(),
            request.repairScheduleMode(),
            initialFinalPlanDrafts(session));
    InventoryPlanningCalendar.Evidence calendarEvidence = calendar.evidence();
    String sha256 =
        finalPlanSha256(
            session,
            settings,
            calendarFence(calendarEvidence),
            finalPlanVersion,
            request.movementScheduleMode(),
            request.repairScheduleMode(),
            draft);
    List<FinalPlanDraft> withCandidates =
        attachPreflightCandidates(
            draft,
            dependencies.preflightReconciliation(
                idempotencyKey,
                reconciliationPreflightRequest(
                    session, finalPlanVersion, sha256, draft)),
            inventoryId,
            finalPlanVersion,
            sha256);
    return persistFinalPlanVersion(
        inventoryId,
        request.expectedSessionRevision(),
        settings.revision(),
        existing == null ? 0 : existing.getFinalPlanVersion(),
        finalPlanVersion,
        sha256,
        calendarEvidence,
        request.movementScheduleMode(),
        request.repairScheduleMode(),
        withCandidates);
  }

  public FinalPlanView finalPlan(Jwt jwt, UUID inventoryId) {
    InventorySession session = requireScopedSession(inventoryId, authorizer.readScope(jwt));
    InventoryFinalPlan plan =
        finalPlans
            .findById(inventoryId)
            .orElseThrow(() -> InventoryException.notFound("Inventory final plan is not prepared"));
    return finalPlanView(
        session,
        plan,
        finalPlanEntries.findByInventoryIdAndFinalPlanVersionOrderByOrderAscFindingIdAsc(
            inventoryId, plan.getFinalPlanVersion()));
  }

  public FinalPlanView updateFinalPlan(
      Jwt jwt, UUID inventoryId, UUID idempotencyKey, FinalPlanUpdateRequest request) {
    requireScopedSession(inventoryId, authorizer.manageScope(jwt));
    return idempotency.execute(
        authorizer.subjectId(jwt),
        "session.final-plan.update",
        idempotencyKey,
        Map.of("inventoryId", inventoryId, "request", request),
        HttpStatus.OK.value(),
        FinalPlanView.class,
        () -> doUpdateFinalPlan(jwt, inventoryId, idempotencyKey, request));
  }

  FinalPlanView doUpdateFinalPlan(
      Jwt jwt, UUID inventoryId, UUID idempotencyKey, FinalPlanUpdateRequest request) {
    InventorySession session = requireActive(inventoryId);
    authorizer.requireManage(jwt, session.getWarehouseId());
    expectRevision(session.getRevision(), request.expectedSessionRevision());
    InventoryFinalPlan current =
        finalPlans
            .findById(inventoryId)
            .orElseThrow(() -> InventoryException.conflict("Inventory final plan is not prepared"));
    if (current.getState() != FinalPlanState.DRAFT) {
      throw InventoryException.conflict("Inventory final plan must be prepared again");
    }
    expectRevision(current.getFinalPlanVersion(), request.expectedFinalPlanVersion());
    PlanningSpecification settings = planningSpecification(session.getWarehouseId());
    long nextVersion = Math.addExact(current.getFinalPlanVersion(), 1);
    InventoryPlanningCalendar calendar = calendarFor(session);
    List<FinalPlanDraft> draft =
        scheduleFinalPlan(
            session,
            settings,
            calendar,
            request.movementScheduleMode(),
            request.repairScheduleMode(),
            updateFinalPlanDrafts(session, request.entries()));
    InventoryPlanningCalendar.Evidence calendarEvidence = calendar.evidence();
    String sha256 =
        finalPlanSha256(
            session,
            settings,
            calendarFence(calendarEvidence),
            nextVersion,
            request.movementScheduleMode(),
            request.repairScheduleMode(),
            draft);
    List<FinalPlanDraft> withCandidates =
        attachPreflightCandidates(
            draft,
            dependencies.preflightReconciliation(
                idempotencyKey,
                reconciliationPreflightRequest(session, nextVersion, sha256, draft)),
            inventoryId,
            nextVersion,
            sha256);
    return persistFinalPlanVersion(
        inventoryId,
        request.expectedSessionRevision(),
        settings.revision(),
        current.getFinalPlanVersion(),
        nextVersion,
        sha256,
        calendarEvidence,
        request.movementScheduleMode(),
        request.repairScheduleMode(),
        withCandidates);
  }

  PlanningSpecification planningSpecification(UUID warehouseId) {
    return planningSettings
        .findById(warehouseId)
        .map(this::planningSpecification)
        .orElseGet(() -> new PlanningSpecification(0, null, List.of()));
  }

  PlanningSpecification historicalPlanningSpecification(long revision) {
    if (revision < 0) {
      throw new IllegalArgumentException("Planning-settings revision cannot be negative");
    }
    return new PlanningSpecification(revision, null, List.of());
  }

  InventoryPlanningCalendar calendarFor(InventorySession session) {
    return new InventoryPlanningCalendar(
        dependencies, session.getWarehouseId(), planningDate(session));
  }

  PlanningSpecification planningSpecification(InventoryPlanningSettings value) {
    return new PlanningSpecification(
        value.getRevision(),
        value.getUpdatedAt(),
        parseHolidays(read(value.getHolidays())));
  }

  PlanningSpecification planningSpecification(PlanningSettingsUpdateRequest request) {
    if (request == null) throw new IllegalArgumentException("Planning settings are required");
    return new PlanningSpecification(
        request.expectedSettingsRevision(),
        null,
        parseHolidays(request.holidays()));
  }

  PlanningSettingsView planningSettingsView(
      UUID warehouseId, PlanningSpecification specification) {
    return new PlanningSettingsView(
        warehouseId,
        specification.revision(),
        specification.updatedAt(),
        specification.holidays());
  }

  List<LocalDate> parseHolidays(List<LocalDate> values) {
    if (values == null || values.size() > 3660) {
      throw new IllegalArgumentException("Holidays must contain at most 3660 dates");
    }
    Set<LocalDate> unique = new LinkedHashSet<>();
    for (LocalDate value : values) {
      if (value == null || !unique.add(value)) {
        throw new IllegalArgumentException("Holidays must contain unique ISO dates");
      }
    }
    return unique.stream().sorted().toList();
  }

  List<LocalDate> parseHolidays(JsonNode values) {
    if (values == null || !values.isArray()) {
      throw new IllegalStateException("Stored planning holidays are invalid");
    }
    List<LocalDate> dates = new ArrayList<>();
    for (JsonNode value : values) {
      if (!value.isTextual()) {
        throw new IllegalStateException("Stored planning holidays are invalid");
      }
      try {
        dates.add(LocalDate.parse(value.asText()));
      } catch (RuntimeException exception) {
        throw new IllegalStateException("Stored planning holidays are invalid", exception);
      }
    }
    return parseHolidays(dates);
  }

  String holidaysJson(List<LocalDate> values) {
    return write(values.stream().map(LocalDate::toString).toList());
  }

  List<FinalPlanDraft> initialFinalPlanDrafts(InventorySession session) {
    List<InventoryFinding> active =
        findings.findAllByInventoryIdAndMembershipActiveTrueOrderById(session.getId());
    Map<UUID, InventoryCabinDispositionService.DispositionSnapshot> dispositions =
        cabinDispositions.requireCompleted(session, active);
    List<FinalPlanDraft> draft = new ArrayList<>();
    for (InventoryFinding finding : active) {
      draft.add(
          finalPlanDraft(finding, null, null, null, null)
              .withDisposition(requireDisposition(finding, dispositions)));
    }
    draft.sort(
        Comparator.comparing((FinalPlanDraft value) -> value.hasWork() ? 0 : 1)
            .thenComparing(value -> value.priority() == null ? Integer.MAX_VALUE : value.priority())
            .thenComparing(value -> value.finding().getDisplayCanonicalNumber())
            .thenComparing(value -> value.finding().getId()));
    List<FinalPlanDraft> ordered = new ArrayList<>();
    for (int index = 0; index < draft.size(); index++) {
      ordered.add(draft.get(index).withOrder(index));
    }
    return List.copyOf(ordered);
  }

  List<FinalPlanDraft> updateFinalPlanDrafts(
      InventorySession session, List<FinalPlanEntryUpdate> submitted) {
    if (submitted == null) throw new IllegalArgumentException("Final-plan entries are required");
    List<InventoryFinding> active =
        findings.findAllByInventoryIdAndMembershipActiveTrueOrderById(session.getId());
    Map<UUID, InventoryCabinDispositionService.DispositionSnapshot> dispositions =
        cabinDispositions.requireCompleted(session, active);
    if (submitted.size() != active.size()) {
      throw InventoryException.conflict("Final plan must contain every active finding exactly once");
    }
    Map<UUID, InventoryFinding> byId = new LinkedHashMap<>();
    active.forEach(value -> byId.put(value.getId(), value));
    List<FinalPlanEntryUpdate> ordered = new ArrayList<>(submitted);
    ordered.sort(Comparator.comparingInt(FinalPlanEntryUpdate::order));
    Set<UUID> ids = new HashSet<>();
    List<FinalPlanDraft> result = new ArrayList<>();
    for (int index = 0; index < ordered.size(); index++) {
      FinalPlanEntryUpdate input = ordered.get(index);
      if (input == null
          || input.order() != index
          || !ids.add(input.findingId())) {
        throw new IllegalArgumentException("Final-plan order must be contiguous without duplicates");
      }
      InventoryFinding finding = byId.get(input.findingId());
      if (finding == null || finding.getRevision() != input.expectedFindingRevision()) {
        throw InventoryException.conflict("Final-plan finding revision is stale");
      }
      if (input.movementToRepair() == null) {
        throw new IllegalArgumentException("Final-plan movement choice is required");
      }
      FinalPlanDraft draft =
          finalPlanDraft(
              finding,
              input.priority(),
              input.movementToRepair(),
              input.movementScheduledDate(),
              input.repairScheduledDate())
              .withDisposition(requireDisposition(finding, dispositions));
      if (!draft.hasWork()) {
        if (input.priority() != null
            || input.movementToRepair()
            || input.movementScheduledDate() != null
            || input.repairScheduledDate() != null
            || input.reconciliationDecision() != null) {
          throw new IllegalArgumentException("No-work final-plan entry has operational data");
        }
      } else {
        validateFinalPlanPriority(input.priority());
        draft = draft.withDecision(decisionJson(input.reconciliationDecision()));
      }
      result.add(draft.withOrder(index));
    }
    if (ids.size() != byId.size()) {
      throw InventoryException.conflict("Final plan does not match the active finding set");
    }
    return List.copyOf(result);
  }

  FinalPlanDraft finalPlanDraft(
      InventoryFinding finding,
      Integer requestedPriority,
      Boolean requestedMovementToRepair,
      LocalDate requestedMovementDate,
      LocalDate requestedRepairDate) {
    if (finding.getInspection() != InspectionState.WORK_STAGED) {
      return FinalPlanDraft.noWork(finding);
    }
    FindingPlanSnapshot snapshot =
        activePlanSnapshot(finding)
            .orElseThrow(() -> InventoryException.conflict("Frozen maintenance plan is missing"));
    if (!snapshot.getFingerprint().equals(finding.getMaintenancePlanFingerprintSha256())
        || !snapshot.getFingerprint().equals(frozenPlanFingerprint.sha256(read(snapshot.getSourceSnapshot())))) {
      throw InventoryException.conflict("Frozen maintenance plan is stale");
    }
    Integer defaultPriority = read(snapshot.getSourceSnapshot()).path("priority").asInt(-1);
    validateFinalPlanPriority(defaultPriority);
    int priority = requestedPriority == null ? defaultPriority : requestedPriority;
    boolean movement =
        requestedMovementToRepair == null ? snapshot.isMovementToRepair() : requestedMovementToRepair;
    return new FinalPlanDraft(
        finding,
        snapshot,
        true,
        FinalPlanTargetKind.REPAIR,
        0,
        priority,
        movement,
        requestedMovementDate,
        requestedRepairDate,
        "[]",
        null,
        InventoryCabinDispositionKind.LOCAL,
        "{\"formerRental\":null}");
  }

  private static InventoryCabinDispositionService.DispositionSnapshot requireDisposition(
      InventoryFinding finding,
      Map<UUID, InventoryCabinDispositionService.DispositionSnapshot> dispositions) {
    InventoryCabinDispositionService.DispositionSnapshot disposition =
        dispositions.get(finding.getId());
    if (disposition == null
        || disposition.findingRevision() != finding.getRevision()
        || !java.util.Objects.equals(disposition.assetId(), finding.getAssetId())
        || !java.util.Objects.equals(disposition.assetVersion(), finding.getAssetVersion())) {
      throw InventoryException.conflict("Inventory cabin disposition evidence is stale");
    }
    return disposition;
  }

  void validateFinalPlanPriority(Integer value) {
    if (value == null || value < 1 || value > 5) {
      throw new IllegalArgumentException("Final-plan priority must be between 1 and 5");
    }
  }

  List<FinalPlanDraft> scheduleFinalPlan(
      InventorySession session,
      PlanningSpecification settings,
      InventoryPlanningCalendar calendar,
      FinalPlanScheduleMode movementMode,
      FinalPlanScheduleMode repairMode,
      List<FinalPlanDraft> input) {
    if (movementMode == null || repairMode == null) {
      throw new IllegalArgumentException("Final-plan schedule modes are required");
    }
    LocalDate planningDate = planningDate(session);
    List<FinalPlanDraft> result = new ArrayList<>(input);
    for (int index = 0; index < result.size(); index++) {
      FinalPlanDraft entry = result.get(index);
      if (!entry.hasWork()) continue;
      if (!entry.movementToRepair()) {
        if (entry.movementScheduledDate() != null) {
          throw new IllegalArgumentException("Movement date requires movement to repair");
        }
        continue;
      }
      LocalDate date;
      if (movementMode == FinalPlanScheduleMode.AUTO) {
        if (entry.movementScheduledDate() != null) {
          throw new IllegalArgumentException("AUTO movement schedule cannot include dates");
        }
        date = reserveAutomaticDate(planningDate, settings, calendar);
      } else {
        date = entry.movementScheduledDate();
        reserveManualDate(date, planningDate, settings, calendar, "movement");
      }
      result.set(index, entry.withDates(date, entry.repairScheduledDate()));
    }
    for (int index = 0; index < result.size(); index++) {
      FinalPlanDraft entry = result.get(index);
      if (!entry.hasWork()) continue;
      LocalDate earliest =
          entry.movementToRepair() && entry.movementScheduledDate().isAfter(planningDate)
              ? entry.movementScheduledDate()
              : planningDate;
      LocalDate date;
      if (repairMode == FinalPlanScheduleMode.AUTO) {
        if (entry.repairScheduledDate() != null) {
          throw new IllegalArgumentException("AUTO repair schedule cannot include dates");
        }
        date = reserveAutomaticDate(earliest, settings, calendar);
      } else {
        date = entry.repairScheduledDate();
        reserveManualDate(date, planningDate, settings, calendar, "repair");
        if (entry.movementToRepair() && date.isBefore(entry.movementScheduledDate())) {
          throw new IllegalArgumentException("Repair date cannot precede movement date");
        }
      }
      result.set(index, entry.withDates(entry.movementScheduledDate(), date));
    }
    return List.copyOf(result);
  }

  LocalDate planningDate(InventorySession session) {
    LocalDate warehouseToday = LocalDate.now(ZoneId.of(session.getWarehouseTimeZone()));
    return session.getBusinessDate().isAfter(warehouseToday)
        ? session.getBusinessDate()
        : warehouseToday;
  }

  LocalDate reserveAutomaticDate(
      LocalDate earliest,
      PlanningSpecification settings,
      InventoryPlanningCalendar calendar) {
    LocalDate date = earliest;
    for (int offset = 0; offset < 20_000; offset++, date = date.plusDays(1)) {
      if (!workingDay(date, settings, calendar)) continue;
      return date;
    }
    throw new IllegalStateException("Planning calendar has no available date");
  }

  void reserveManualDate(
      LocalDate date,
      LocalDate planningDate,
      PlanningSpecification settings,
      InventoryPlanningCalendar calendar,
      String stream) {
    if (date != null && date.isBefore(planningDate)) {
      throw new IllegalArgumentException(
          "Manual " + stream + " date cannot precede the current warehouse planning date");
    }
    if (date == null || !workingDay(date, settings, calendar)) {
      throw new IllegalArgumentException("Manual " + stream + " date must be a working day");
    }
  }

  boolean workingDay(
      LocalDate date, PlanningSpecification settings, InventoryPlanningCalendar calendar) {
    return calendar.taskBoardWorking(date) && !settings.holidays().contains(date);
  }

  TaskBoardCalendarFence calendarFence(InventoryPlanningCalendar.Evidence evidence) {
    if (evidence == null) {
      throw new IllegalArgumentException("Task-board calendar evidence is required");
    }
    return new TaskBoardCalendarFence(evidence.from(), evidence.through(), evidence.fingerprint());
  }

  TaskBoardCalendarFence calendarFence(InventoryFinalPlan plan) {
    if (plan == null) {
      throw InventoryException.conflict("Inventory final plan calendar evidence is missing");
    }
    try {
      String snapshot = plan.getTaskBoardCalendarSnapshot();
      if (snapshot == null
          || snapshot.isBlank()
          || snapshot.length() > 8_000_000
          || !read(snapshot).isObject()) {
        throw new IllegalStateException("Final-plan task-board calendar snapshot is invalid");
      }
      return new TaskBoardCalendarFence(
          plan.getTaskBoardCalendarFrom(),
          plan.getTaskBoardCalendarThrough(),
          plan.getTaskBoardCalendarFingerprint());
    } catch (IllegalArgumentException | IllegalStateException exception) {
      throw InventoryException.conflict("Inventory final plan calendar evidence is missing");
    }
  }

  TaskBoardCalendarFence calendarFenceOrNull(InventoryFinalPlan plan) {
    if (plan.getTaskBoardCalendarFrom() == null
        && plan.getTaskBoardCalendarThrough() == null
        && plan.getTaskBoardCalendarFingerprint() == null
        && plan.getTaskBoardCalendarSnapshot() == null) {
      return null;
    }
    return calendarFence(plan);
  }

  String calendarSnapshot(InventoryPlanningCalendar.Evidence evidence) {
    if (evidence == null) {
      throw new IllegalArgumentException("Task-board calendar evidence is required");
    }
    return write(evidence.snapshot());
  }

  TaskBoardCalendarFence requireCurrentCalendarFence(
      InventorySession session, InventoryFinalPlan plan) {
    TaskBoardCalendarFence persisted = calendarFence(plan);
    InventoryPlanningCalendar.Evidence current =
        InventoryPlanningCalendar.currentEvidence(
            dependencies,
            session.getWarehouseId(),
            persisted.from(),
            persisted.through());
    if (!persisted.equals(calendarFence(current))) {
      throw InventoryException.conflict("Inventory final plan calendar is stale");
    }
    return persisted;
  }

  String finalPlanSha256(
      InventorySession session,
      PlanningSpecification settings,
      TaskBoardCalendarFence calendarFence,
      long finalPlanVersion,
      FinalPlanScheduleMode movementMode,
      FinalPlanScheduleMode repairMode,
      List<FinalPlanDraft> entries) {
    List<Map<String, Object>> canonicalEntries = new ArrayList<>();
    for (FinalPlanDraft entry : entries) {
      Map<String, Object> value = new LinkedHashMap<>();
      value.put("findingId", entry.finding().getId());
      value.put("findingRevision", entry.finding().getRevision());
      value.put("assetId", entry.finding().getAssetId());
      value.put("assetVersion", entry.finding().getAssetVersion());
      value.put("planFingerprintSha256", entry.planFingerprintSha256());
      value.put("hasWork", entry.hasWork());
      value.put("targetKind", entry.targetKind());
      value.put("order", entry.order());
      value.put("priority", entry.priority());
      value.put("movementToRepair", entry.movementToRepair());
      value.put("forceCapitalRepair", entry.forceCapitalRepair());
      value.put("movementScheduledDate", entry.movementScheduledDate());
      value.put("repairScheduledDate", entry.repairScheduledDate());
      value.put("dispositionKind", entry.dispositionKind());
      value.put("dispositionDetails", convert(read(entry.dispositionDetails()), Object.class));
      value.put(
          "reconciliationDecision",
          entry.reconciliationDecision() == null
              ? null
              : convert(read(entry.reconciliationDecision()), Object.class));
      canonicalEntries.add(value);
    }
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("inventoryId", session.getId());
    payload.put("sessionRevision", session.getRevision());
    payload.put("planningSettingsRevision", settings.revision());
    if (calendarFence != null) {
      payload.put("taskBoardCalendarFrom", calendarFence.from());
      payload.put("taskBoardCalendarThrough", calendarFence.through());
      payload.put("taskBoardCalendarFingerprint", calendarFence.fingerprint());
    }
    payload.put("finalPlanVersion", finalPlanVersion);
    payload.put("movementScheduleMode", movementMode);
    payload.put("repairScheduleMode", repairMode);
    payload.put("entries", canonicalEntries);
    return canonicalHash(payload);
  }

  ObjectNode reconciliationPreflightRequest(
      InventorySession session,
      long finalPlanVersion,
      String finalPlanSha256,
      List<FinalPlanDraft> entries) {
    ObjectNode request = mapper.createObjectNode();
    request.put("inventoryId", session.getId().toString());
    request.put("warehouseId", session.getWarehouseId().toString());
    request.put("finalPlanVersion", finalPlanVersion);
    request.put("finalPlanSha256", finalPlanSha256);
    ArrayNode findingsBody = request.putArray("findings");
    for (FinalPlanDraft entry : entries) {
      if (!entry.hasWork()) continue;
      InventoryFinding finding = entry.finding();
      FindingPlanSnapshot snapshot = entry.snapshot();
      if (finding.getAssetId() == null || finding.getAssetVersion() == null || snapshot == null) {
        throw InventoryException.conflict("Work final-plan finding has incomplete asset evidence");
      }
      ObjectNode body = findingsBody.addObject();
      body.put("findingId", finding.getId().toString());
      body.put("findingRevision", finding.getRevision());
      body.put("assetId", finding.getAssetId().toString());
      body.put("assetVersion", finding.getAssetVersion());
      body.put("planFingerprintSha256", entry.planFingerprintSha256());
      body.put("snapshotSchemaVersion", snapshot.getSnapshotSchemaVersion());
      body.put("priority", entry.priority());
      body.put("movementToRepair", entry.movementToRepair());
      body.put("forceCapitalRepair", entry.forceCapitalRepair());
      if (entry.movementScheduledDate() == null) body.putNull("movementScheduledDate");
      else body.put("movementScheduledDate", entry.movementScheduledDate().toString());
      body.put("repairScheduledDate", entry.repairScheduledDate().toString());
      body.set("snapshot", read(snapshot.getSourceSnapshot()));
      body.set("media", frozenPlanMedia(snapshot));
    }
    return request;
  }

  ArrayNode frozenPlanMedia(FindingPlanSnapshot snapshot) {
    JsonNode source = read(snapshot.getSourceSnapshot());
    ArrayNode output = mapper.createArrayNode();
    Set<String> seen = new LinkedHashSet<>();
    appendFrozenPlanMedia(output, seen, source.path("mediaReferences"));
    JsonNode lines = source.path("lines");
    if (!lines.isArray()) {
      throw InventoryException.conflict("Frozen maintenance plan lines are invalid");
    }
    for (JsonNode line : lines) {
      if (!line.isObject()) {
        throw InventoryException.conflict("Frozen maintenance plan lines are invalid");
      }
      appendFrozenPlanMedia(output, seen, line.path("mediaReferences"));
    }
    return output;
  }

  void appendFrozenPlanMedia(ArrayNode output, Set<String> seen, JsonNode references) {
    if (references.isMissingNode() || references.isNull()) return;
    if (!references.isArray()) {
      throw InventoryException.conflict("Frozen maintenance plan media is invalid");
    }
    for (JsonNode reference : references) {
      UUID mediaId = requiredUuid(reference, "mediaId", "frozen plan media id");
      long generation = reference.path("generation").asLong(-1);
      if (generation < 0) {
        throw InventoryException.conflict("Frozen maintenance plan media generation is invalid");
      }
      String key = mediaId + ":" + generation;
      if (!seen.add(key)) continue;
      ObjectNode normalized = output.addObject();
      normalized.put("mediaId", mediaId.toString());
      normalized.put("generation", generation);
    }
  }

  List<FinalPlanDraft> attachPreflightCandidates(
      List<FinalPlanDraft> entries,
      JsonNode response,
      UUID inventoryId,
      long finalPlanVersion,
      String finalPlanSha256) {
    if (!inventoryId.equals(requiredUuid(response, "inventoryId", "reconciliation inventory id"))
        || response.path("finalPlanVersion").asLong(-1) != finalPlanVersion
        || !finalPlanSha256.equals(response.path("finalPlanSha256").asText())
        || !response.path("findings").isArray()) {
      throw InventoryException.dependency("Maintenance reconciliation preflight is malformed");
    }
    Map<UUID, String> candidatesByFinding = new LinkedHashMap<>();
    for (JsonNode finding : response.path("findings")) {
      UUID findingId = requiredUuid(finding, "findingId", "reconciliation finding id");
      JsonNode candidates = finding.path("candidates");
      if (!candidates.isArray() || candidatesByFinding.put(findingId, canonicalWrite(candidates)) != null) {
        throw InventoryException.dependency("Maintenance reconciliation candidates are malformed");
      }
      for (JsonNode candidate : candidates) validateFinalPlanCandidate(candidate);
    }
    Set<UUID> workIds = new LinkedHashSet<>();
    for (FinalPlanDraft entry : entries) {
      if (entry.hasWork()) workIds.add(entry.finding().getId());
    }
    if (!candidatesByFinding.keySet().equals(workIds)) {
      throw InventoryException.dependency("Maintenance reconciliation candidate set is incomplete");
    }
    List<FinalPlanDraft> result = new ArrayList<>();
    for (FinalPlanDraft entry : entries) {
      FinalPlanDraft withCandidates =
          entry.hasWork()
              ? entry.withCandidates(candidatesByFinding.get(entry.finding().getId()))
              : entry;
      validateFinalPlanDecision(withCandidates);
      result.add(withCandidates);
    }
    return List.copyOf(result);
  }

  void validateFinalPlanCandidate(JsonNode candidate) {
    try {
      if (!candidate.isObject()) throw new IllegalArgumentException();
      FinalPlanTargetKind kind =
          FinalPlanTargetKind.valueOf(candidate.path("targetKind").asText());
      UUID targetId = requiredUuid(candidate, "targetId", "reconciliation target id");
      UUID estimateId = nullableUuid(candidate, "estimateId", "reconciliation estimate id");
      UUID repairId = nullableUuid(candidate, "repairId", "reconciliation repair id");
      if ((kind == FinalPlanTargetKind.ESTIMATE && (!targetId.equals(estimateId) || repairId != null))
          || (kind == FinalPlanTargetKind.REPAIR && (!targetId.equals(repairId) || estimateId != null))
          || candidate.path("version").asLong(-1) < 0
          || candidate.path("state").asText().isBlank()
          || !candidate.path("started").isBoolean()
          || !candidate.path("active").isBoolean()
          || !candidate.path("forceCapitalRepair").isBoolean()) {
        throw new IllegalArgumentException();
      }
      if (candidate.hasNonNull("priority")) validateFinalPlanPriority(candidate.path("priority").asInt(-1));
      if (candidate.hasNonNull("planFingerprintSha256")
          && !candidate.path("planFingerprintSha256").asText().matches("^[0-9a-f]{64}$")) {
        throw new IllegalArgumentException();
      }
      JsonNode summary = candidate.path("planSummary");
      if (!summary.isObject()
          || summary.path("workLineCount").asInt(-1) < 0
          || summary.path("materialLineCount").asInt(-1) < 0
          || summary.path("grandTotalMinor").asLong(-1) < 0) {
        throw new IllegalArgumentException();
      }
    } catch (RuntimeException exception) {
      throw InventoryException.dependency("Maintenance reconciliation candidate is malformed");
    }
  }

  String decisionJson(FinalPlanReconciliationDecision decision) {
    if (decision == null) return null;
    if (decision.strategy() == null) {
      throw new IllegalArgumentException("Final-plan reconciliation strategy is required");
    }
    if (decision.strategy() == FinalPlanReconciliationStrategy.CREATE) {
      if (decision.selectedTargetKind() != null || decision.selectedTargetId() != null) {
        throw new IllegalArgumentException("CREATE reconciliation cannot select an existing target");
      }
    } else if (decision.selectedTargetKind() == null || decision.selectedTargetId() == null) {
      throw new IllegalArgumentException("MERGE and REPLACE reconciliation require a target");
    }
    ObjectNode result = mapper.createObjectNode();
    result.put("strategy", decision.strategy().name());
    if (decision.selectedTargetKind() == null) result.putNull("selectedTargetKind");
    else result.put("selectedTargetKind", decision.selectedTargetKind().name());
    if (decision.selectedTargetId() == null) result.putNull("selectedTargetId");
    else result.put("selectedTargetId", decision.selectedTargetId().toString());
    return canonicalWrite(result);
  }

  void validateFinalPlanDecision(FinalPlanDraft entry) {
    if (!entry.hasWork()) return;
    validateFinalPlanDecision(entry, finalPlanCandidateSet(entry));
  }

  void validateFinalPlanDecision(FinalPlanDraft entry, FinalPlanCandidateSet candidates) {
    if (entry.reconciliationDecision() == null) return;
    FinalPlanReconciliationDecision decision = finalPlanDecision(read(entry.reconciliationDecision()));
    if (candidates.active().size() > 1) {
      throw InventoryException.conflict(
          "Maintenance reconciliation has multiple active candidates");
    }
    if (decision.strategy() == FinalPlanReconciliationStrategy.CREATE) {
      if (!candidates.active().isEmpty()) {
        throw InventoryException.conflict(
            "CREATE reconciliation is only valid with no active maintenance candidate");
      }
      return;
    }
    FinalPlanCandidateView selected =
        candidates.all().stream()
            .filter(
                candidate ->
                    candidate.targetKind() == decision.selectedTargetKind()
                        && candidate.targetId().equals(decision.selectedTargetId()))
            .findFirst()
            .orElse(null);
    if (selected == null) {
      throw InventoryException.conflict("Reconciliation selection is no longer a maintenance candidate");
    }
    if (!selected.active()) {
      throw InventoryException.conflict(
          "Reconciliation selection must be an active maintenance candidate");
    }
    if (selected.started() && decision.strategy() != FinalPlanReconciliationStrategy.MERGE) {
      throw InventoryException.conflict("Started maintenance candidate requires MERGE reconciliation");
    }
  }

  FinalPlanCandidateSet finalPlanCandidateSet(FinalPlanDraft entry) {
    List<FinalPlanCandidateView> all = finalPlanCandidates(read(entry.collisionCandidates()));
    return new FinalPlanCandidateSet(all, all.stream().filter(FinalPlanCandidateView::active).toList());
  }

  FinalPlanView persistFinalPlanVersion(
      UUID inventoryId,
      long expectedSessionRevision,
      long expectedSettingsRevision,
      long expectedPriorVersion,
      long finalPlanVersion,
      String sha256,
      InventoryPlanningCalendar.Evidence calendarEvidence,
      FinalPlanScheduleMode movementMode,
      FinalPlanScheduleMode repairMode,
      List<FinalPlanDraft> entries) {
    return transactions.execute(
        ignored -> {
          InventorySession locked =
              sessions
                  .findByIdAndLifecycleForUpdate(inventoryId, SessionLifecycle.ACTIVE)
                  .orElseThrow(() -> InventoryException.conflict("Inventory session is not active"));
          expectRevision(locked.getRevision(), expectedSessionRevision);
          PlanningSpecification lockedSettings = planningSpecification(locked.getWarehouseId());
          expectRevision(lockedSettings.revision(), expectedSettingsRevision);
          InventoryFinalPlan head = finalPlans.findByInventoryIdForUpdate(inventoryId).orElse(null);
          long prior = head == null ? 0 : head.getFinalPlanVersion();
          expectRevision(prior, expectedPriorVersion);
          if (finalPlanVersion != Math.addExact(prior, 1)) {
            throw InventoryException.conflict("Inventory final-plan version changed");
          }
          InventoryFinalPlan saved;
          if (head == null) {
            saved =
                InventoryFinalPlan.create(
                    inventoryId,
                    locked.getRevision(),
                    lockedSettings.revision(),
                    calendarEvidence.from(),
                    calendarEvidence.through(),
                    calendarEvidence.fingerprint(),
                    write(calendarEvidence.snapshot()),
                    sha256,
                    movementMode,
                    repairMode);
          } else {
            head.nextVersion(
                locked.getRevision(),
                lockedSettings.revision(),
                calendarEvidence.from(),
                calendarEvidence.through(),
                calendarEvidence.fingerprint(),
                write(calendarEvidence.snapshot()),
                sha256,
                movementMode,
                repairMode);
            saved = head;
          }
          saved = finalPlans.saveAndFlush(saved);
          List<InventoryFinalPlanEntry> persisted =
              entries.stream()
                  .map(entry -> finalPlanEntry(inventoryId, finalPlanVersion, entry))
                  .toList();
          finalPlanEntries.saveAllAndFlush(persisted);
          return finalPlanView(locked, saved, persisted);
        });
  }

  InventoryFinalPlanEntry finalPlanEntry(
      UUID inventoryId, long finalPlanVersion, FinalPlanDraft entry) {
    return new InventoryFinalPlanEntry(
        inventoryId,
        finalPlanVersion,
        entry.finding().getId(),
        entry.finding().getRevision(),
        entry.finding().getAssetId(),
        entry.finding().getAssetVersion(),
        entry.planFingerprintSha256(),
        entry.hasWork(),
        entry.targetKind(),
        entry.order(),
        entry.priority(),
        entry.movementToRepair(),
        entry.forceCapitalRepair(),
        entry.movementScheduledDate(),
        entry.repairScheduledDate(),
        entry.collisionCandidates(),
        entry.reconciliationDecision(),
        entry.dispositionKind(),
        entry.dispositionDetails());
  }

  FinalPlanView finalPlanView(
      InventorySession session, InventoryFinalPlan plan, List<InventoryFinalPlanEntry> entries) {
    return new FinalPlanView(
        session.getId(),
        session.getRevision(),
        plan.getFinalPlanVersion(),
        plan.getFinalPlanSha256(),
        plan.getPlanningSettingsRevision(),
        plan.getState(),
        plan.getMovementScheduleMode(),
        plan.getRepairScheduleMode(),
        entries.stream().map(this::finalPlanEntryView).toList());
  }

  FinalPlanEntryView finalPlanEntryView(InventoryFinalPlanEntry entry) {
    return new FinalPlanEntryView(
        entry.getFindingId(),
        entry.getFindingRevision(),
        entry.getPlanFingerprintSha256(),
        entry.isHasWork(),
        entry.getTargetKind(),
        entry.getOrder(),
        entry.getPriority(),
        entry.isMovementToRepair(),
        entry.getMovementScheduledDate(),
        entry.getRepairScheduledDate(),
        finalPlanCandidates(read(entry.getCollisionCandidates())),
        entry.getReconciliationDecision() == null
            ? null
            : finalPlanDecision(read(entry.getReconciliationDecision())),
        entry.isForceCapitalRepair(),
        entry.getDispositionKind(),
        read(entry.getDispositionDetails()));
  }

  List<FinalPlanCandidateView> finalPlanCandidates(JsonNode candidates) {
    if (!candidates.isArray()) {
      throw new IllegalStateException("Stored final-plan candidates are invalid");
    }
    List<FinalPlanCandidateView> result = new ArrayList<>();
    for (JsonNode candidate : candidates) {
      validateFinalPlanCandidate(candidate);
      JsonNode summary = candidate.path("planSummary");
      result.add(
          new FinalPlanCandidateView(
              FinalPlanTargetKind.valueOf(candidate.path("targetKind").asText()),
              requiredUuid(candidate, "targetId", "stored reconciliation target id"),
              nullableUuid(candidate, "estimateId", "stored reconciliation estimate id"),
              nullableUuid(candidate, "repairId", "stored reconciliation repair id"),
              candidate.path("version").asLong(),
              candidate.path("state").asText(),
              candidate.path("started").asBoolean(),
              candidate.path("active").asBoolean(),
              candidate.hasNonNull("priority") ? candidate.path("priority").asInt() : null,
              candidate.hasNonNull("sourceParty") ? candidate.path("sourceParty").asText() : null,
              candidate.hasNonNull("planFingerprintSha256")
                  ? candidate.path("planFingerprintSha256").asText()
                  : null,
              new FinalPlanSummaryView(
                  summary.path("workLineCount").asInt(),
                  summary.path("materialLineCount").asInt(),
                  summary.path("grandTotalMinor").asLong()),
              candidate.path("forceCapitalRepair").asBoolean()));
    }
    return List.copyOf(result);
  }

  FinalPlanReconciliationDecision finalPlanDecision(JsonNode decision) {
    try {
      if (!decision.isObject()) throw new IllegalArgumentException();
      FinalPlanReconciliationStrategy strategy =
          FinalPlanReconciliationStrategy.valueOf(decision.path("strategy").asText());
      FinalPlanTargetKind targetKind =
          decision.hasNonNull("selectedTargetKind")
              ? FinalPlanTargetKind.valueOf(decision.path("selectedTargetKind").asText())
              : null;
      UUID targetId = nullableUuid(decision, "selectedTargetId", "stored reconciliation target id");
      return new FinalPlanReconciliationDecision(strategy, targetKind, targetId);
    } catch (RuntimeException exception) {
      throw new IllegalStateException("Stored final-plan reconciliation decision is invalid", exception);
    }
  }

  UUID nullableUuid(JsonNode node, String field, String label) {
    if (!node.hasNonNull(field)) return null;
    return requiredUuid(node, field, label);
  }

  boolean explicitNull(JsonNode node, String field) {
    return node.has(field) && node.get(field).isNull();
  }

  void invalidateFinalPlan(InventorySession session) {
    finalPlans
        .findById(session.getId())
        .ifPresent(
            plan -> {
              plan.markStale();
              finalPlans.saveAndFlush(plan);
            });
  }

  /**
   * Completion is bound to the exact server-owned plan version, not merely to a client-side list
   * of finding revisions. Re-running maintenance preflight makes a changed candidate set a
   * conflict before any terminal session mutation is possible.
   */
  CompletionFinalPlan requireCompletionFinalPlan(
      InventorySession session,
      long expectedVersion,
      String expectedSha256,
      boolean refreshMaintenanceCandidates) {
    InventoryFinalPlan plan =
        finalPlans
            .findById(session.getId())
            .orElseThrow(() -> InventoryException.conflict("Inventory final plan is not prepared"));
    if (plan.getState() != FinalPlanState.DRAFT
        || plan.getFinalPlanVersion() != expectedVersion
        || !plan.getFinalPlanSha256().equals(expectedSha256)
        || plan.getBasisSessionRevision() != session.getRevision()) {
      throw InventoryException.conflict("Inventory final plan is stale");
    }
    PlanningSpecification settings = planningSpecification(session.getWarehouseId());
    if (settings.revision() != plan.getPlanningSettingsRevision()) {
      throw InventoryException.conflict("Inventory final plan calendar is stale");
    }
    TaskBoardCalendarFence calendarFence = requireCurrentCalendarFence(session, plan);
    List<InventoryFinalPlanEntry> entries =
        finalPlanEntries.findByInventoryIdAndFinalPlanVersionOrderByOrderAscFindingIdAsc(
            session.getId(), plan.getFinalPlanVersion());
    requireCurrentFinalPlanDates(session, entries);
    List<FinalPlanDraft> draft = completionFinalPlanDrafts(session, plan, entries);
    String calculated =
        finalPlanSha256(
            session,
            settings,
            calendarFence,
            plan.getFinalPlanVersion(),
            plan.getMovementScheduleMode(),
            plan.getRepairScheduleMode(),
            draft);
    if (!plan.getFinalPlanSha256().equals(calculated)) {
      throw InventoryException.conflict("Inventory final plan evidence is stale");
    }
    if (refreshMaintenanceCandidates) {
      UUID key =
          UUID.nameUUIDFromBytes(
              ("rwms:inventory:preflight:"
                      + session.getId()
                      + ":"
                      + plan.getFinalPlanVersion()
                      + ":"
                      + plan.getFinalPlanSha256())
                  .getBytes(StandardCharsets.UTF_8));
      List<FinalPlanDraft> fresh =
          attachPreflightCandidates(
              draft,
              dependencies.preflightReconciliation(
                  key,
                  reconciliationPreflightRequest(
                      session, plan.getFinalPlanVersion(), plan.getFinalPlanSha256(), draft)),
              session.getId(),
              plan.getFinalPlanVersion(),
              plan.getFinalPlanSha256());
      for (int index = 0; index < draft.size(); index++) {
        if (!canonicalJsonTreeHash(read(draft.get(index).collisionCandidates()))
            .equals(canonicalJsonTreeHash(read(fresh.get(index).collisionCandidates())))) {
          throw InventoryException.conflict("Maintenance reconciliation candidates changed");
        }
      }
    }
    for (FinalPlanDraft entry : draft) {
      FinalPlanCandidateSet candidates = finalPlanCandidateSet(entry);
      if (candidates.active().size() > 1) {
        throw new InventoryException(
            HttpStatus.UNPROCESSABLE_ENTITY,
            "INVENTORY_FINAL_PLAN_AMBIGUOUS_ACTIVE_CANDIDATES",
            "Resolve multiple active maintenance candidates before completion");
      }
      if (entry.hasWork()
          && !candidates.active().isEmpty()
          && entry.reconciliationDecision() == null) {
        throw new InventoryException(
            HttpStatus.UNPROCESSABLE_ENTITY,
            "INVENTORY_FINAL_PLAN_INCOMPLETE",
            "Choose a reconciliation strategy for every active maintenance candidate");
      }
      validateFinalPlanDecision(entry, candidates);
    }
    return new CompletionFinalPlan(plan, entries, draft);
  }

  void requireCurrentFinalPlanDates(
      InventorySession session, List<InventoryFinalPlanEntry> entries) {
    LocalDate planningDate = planningDate(session);
    boolean hasPastOperationalDate =
        entries.stream()
            .filter(InventoryFinalPlanEntry::isHasWork)
            .anyMatch(
                entry ->
                    (entry.getMovementScheduledDate() != null
                            && entry.getMovementScheduledDate().isBefore(planningDate))
                        || (entry.getRepairScheduledDate() != null
                            && entry.getRepairScheduledDate().isBefore(planningDate)));
    if (hasPastOperationalDate) {
      throw InventoryException.conflict(
          "Inventory final plan has operational dates before the current warehouse planning date; prepare it again");
    }
  }

  List<FinalPlanDraft> completionFinalPlanDrafts(
      InventorySession session, InventoryFinalPlan plan, List<InventoryFinalPlanEntry> entries) {
    List<InventoryFinding> active =
        findings.findAllByInventoryIdAndMembershipActiveTrueOrderById(session.getId());
    Map<UUID, InventoryCabinDispositionService.DispositionSnapshot> dispositions =
        session.getLifecycle() == SessionLifecycle.COMPLETED
            ? Map.of()
            : cabinDispositions.requireCompleted(session, active);
    if (active.size() != entries.size()) {
      throw InventoryException.conflict("Inventory final plan no longer covers the active population");
    }
    Map<UUID, InventoryFinalPlanEntry> byId = new LinkedHashMap<>();
    for (int index = 0; index < entries.size(); index++) {
      InventoryFinalPlanEntry entry = entries.get(index);
      if (entry.getOrder() != index || byId.put(entry.getFindingId(), entry) != null) {
        throw InventoryException.conflict("Inventory final-plan ordering is invalid");
      }
    }
    List<FinalPlanDraft> result = new ArrayList<>();
    for (InventoryFinding finding : active) {
      InventoryFinalPlanEntry entry = byId.get(finding.getId());
      if (entry == null || entry.getFindingRevision() != finding.getRevision()) {
        throw InventoryException.conflict("Inventory final plan finding evidence is stale");
      }
      InventoryCabinDispositionService.DispositionSnapshot disposition =
          session.getLifecycle() == SessionLifecycle.COMPLETED
              ? frozenDisposition(finding, entry)
              : requireDisposition(finding, dispositions);
      if (entry.getDispositionKind() != disposition.kind()
          || !canonicalJsonTreeHash(read(entry.getDispositionDetails()))
              .equals(canonicalJsonTreeHash(read(disposition.details())))) {
        throw InventoryException.conflict("Inventory final plan disposition evidence is stale");
      }
      if (!entry.isHasWork()) {
        if (finding.getInspection() == InspectionState.WORK_STAGED
            && entry.getDispositionKind() == InventoryCabinDispositionKind.LOCAL) {
          throw InventoryException.conflict("Inventory final plan omitted staged maintenance work");
        }
        result.add(
            FinalPlanDraft.noWork(finding)
                .withDisposition(disposition)
                .withOrder(entry.getOrder()));
        continue;
      }
      if (finding.getInspection() != InspectionState.WORK_STAGED
          || finding.getAssetId() == null
          || finding.getAssetVersion() == null
          || !finding.getAssetId().equals(entry.getAssetId())
          || !finding.getAssetVersion().equals(entry.getAssetVersion())) {
        throw InventoryException.conflict("Inventory final-plan work evidence is stale");
      }
      FindingPlanSnapshot snapshot =
          activePlanSnapshot(finding)
              .orElseThrow(() -> InventoryException.conflict("Frozen maintenance plan is missing"));
      FinalPlanTargetKind expectedTarget = FinalPlanTargetKind.REPAIR;
      if (!snapshot.getFingerprint().equals(entry.getPlanFingerprintSha256())) {
        throw InventoryException.conflict("Inventory final-plan work fingerprint is stale");
      }
      if (entry.getTargetKind() != expectedTarget || entry.getPriority() == null) {
        throw InventoryException.conflict("Inventory final-plan work routing is invalid");
      }
      if (entry.getRepairScheduledDate() == null
          || entry.isMovementToRepair() != (entry.getMovementScheduledDate() != null)) {
        throw InventoryException.conflict("Inventory final-plan work schedule is invalid");
      }
      if (entry.isForceCapitalRepair() != snapshot.isForceCapitalRepair()) {
        throw InventoryException.conflict("Inventory final-plan capital choice is stale");
      }
      result.add(
          new FinalPlanDraft(
              finding,
              snapshot,
              true,
              entry.getTargetKind(),
              entry.getOrder(),
              entry.getPriority(),
              entry.isMovementToRepair(),
              entry.getMovementScheduledDate(),
              entry.getRepairScheduledDate(),
              entry.getCollisionCandidates(),
              entry.getReconciliationDecision(),
              disposition.kind(),
              disposition.details()));
    }
    result.sort(Comparator.comparingInt(FinalPlanDraft::order));
    return List.copyOf(result);
  }

  /** Reconstructs a completed plan's immutable disposition without consulting mutable review rows. */
  private static InventoryCabinDispositionService.DispositionSnapshot frozenDisposition(
      InventoryFinding finding, InventoryFinalPlanEntry entry) {
    if (entry.getDispositionKind() == null
        || entry.getDispositionDetails() == null
        || !entry.getFindingId().equals(finding.getId())
        || entry.getFindingRevision() != finding.getRevision()
        || !java.util.Objects.equals(entry.getAssetId(), finding.getAssetId())
        || !java.util.Objects.equals(entry.getAssetVersion(), finding.getAssetVersion())) {
      throw InventoryException.conflict("Inventory final plan disposition evidence is stale");
    }
    return new InventoryCabinDispositionService.DispositionSnapshot(
        entry.getFindingId(),
        entry.getFindingRevision(),
        entry.getAssetId(),
        entry.getAssetVersion(),
        entry.getDispositionKind(),
        entry.getDispositionDetails());
  }

  /** Inventory-owned holiday exceptions and their optimistic revision. */
  record PlanningSpecification(
      long revision,
      OffsetDateTime updatedAt,
      List<LocalDate> holidays) {}

  /** Immutable calendar range and digest fenced into one final-plan generation. */
  record TaskBoardCalendarFence(LocalDate from, LocalDate through, String fingerprint) {
    TaskBoardCalendarFence {
      if (from == null
          || through == null
          || through.isBefore(from)
          || fingerprint == null
          || !fingerprint.matches("^[0-9a-f]{64}$")) {
        throw new IllegalArgumentException("Task-board calendar fence is invalid");
      }
    }
  }

  /** A transient, validated proposal before one immutable final-plan version is persisted. */
  record FinalPlanDraft(
      InventoryFinding finding,
      FindingPlanSnapshot snapshot,
      boolean hasWork,
      FinalPlanTargetKind targetKind,
      int order,
      Integer priority,
      boolean movementToRepair,
      LocalDate movementScheduledDate,
      LocalDate repairScheduledDate,
      String collisionCandidates,
      String reconciliationDecision,
      InventoryCabinDispositionKind dispositionKind,
      String dispositionDetails) {
    static FinalPlanDraft noWork(InventoryFinding finding) {
      return new FinalPlanDraft(
          finding,
          null,
          false,
          null,
          0,
          null,
          false,
          null,
          null,
          "[]",
          null,
          InventoryCabinDispositionKind.LOCAL,
          "{\"formerRental\":null}");
    }

    String planFingerprintSha256() {
      return snapshot == null ? null : snapshot.getFingerprint();
    }

    /** Returns the immutable manager choice carried by this draft's frozen plan. */
    boolean forceCapitalRepair() {
      return snapshot != null && snapshot.isForceCapitalRepair();
    }

    FinalPlanDraft withOrder(int value) {
      return new FinalPlanDraft(
          finding,
          snapshot,
          hasWork,
          targetKind,
          value,
          priority,
          movementToRepair,
          movementScheduledDate,
          repairScheduledDate,
          collisionCandidates,
          reconciliationDecision,
          dispositionKind,
          dispositionDetails);
    }

    FinalPlanDraft withDates(LocalDate movement, LocalDate repair) {
      return new FinalPlanDraft(
          finding,
          snapshot,
          hasWork,
          targetKind,
          order,
          priority,
          movementToRepair,
          movement,
          repair,
          collisionCandidates,
          reconciliationDecision,
          dispositionKind,
          dispositionDetails);
    }

    FinalPlanDraft withCandidates(String candidates) {
      return new FinalPlanDraft(
          finding,
          snapshot,
          hasWork,
          targetKind,
          order,
          priority,
          movementToRepair,
          movementScheduledDate,
          repairScheduledDate,
          candidates,
          reconciliationDecision,
          dispositionKind,
          dispositionDetails);
    }

    FinalPlanDraft withDecision(String decision) {
      return new FinalPlanDraft(
          finding,
          snapshot,
          hasWork,
          targetKind,
          order,
          priority,
          movementToRepair,
          movementScheduledDate,
          repairScheduledDate,
          collisionCandidates,
          decision,
          dispositionKind,
          dispositionDetails);
    }

    FinalPlanDraft withDisposition(
        InventoryCabinDispositionService.DispositionSnapshot disposition) {
      if (disposition.kind() != InventoryCabinDispositionKind.LOCAL) {
        return new FinalPlanDraft(
            finding,
            null,
            false,
            null,
            order,
            null,
            false,
            null,
            null,
            "[]",
            null,
            disposition.kind(),
            disposition.details());
      }
      return new FinalPlanDraft(
          finding,
          snapshot,
          hasWork,
          targetKind,
          order,
          priority,
          movementToRepair,
          movementScheduledDate,
          repairScheduledDate,
          collisionCandidates,
          reconciliationDecision,
          disposition.kind(),
          disposition.details());
    }
  }

  /** Separates every discovered target candidate from the subset eligible for active planning. */
  record FinalPlanCandidateSet(
      List<FinalPlanCandidateView> all, List<FinalPlanCandidateView> active) {}

  /**
   * Holds the locked final-plan aggregate, its persisted entries and reconstructed drafts during
   * completion validation.
   */
  record CompletionFinalPlan(
      InventoryFinalPlan plan,
      List<InventoryFinalPlanEntry> entries,
      List<FinalPlanDraft> drafts) {}
}
