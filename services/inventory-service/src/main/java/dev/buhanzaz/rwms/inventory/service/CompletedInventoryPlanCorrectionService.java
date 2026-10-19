package dev.buhanzaz.rwms.inventory.service;

import dev.buhanzaz.rwms.inventory.domain.FinalPlanState;
import dev.buhanzaz.rwms.inventory.domain.InspectionState;
import dev.buhanzaz.rwms.inventory.domain.InventoryFinalPlan;
import dev.buhanzaz.rwms.inventory.domain.InventoryFinalPlanEntry;
import dev.buhanzaz.rwms.inventory.domain.InventoryFinding;
import dev.buhanzaz.rwms.inventory.domain.InventorySession;
import dev.buhanzaz.rwms.inventory.domain.SessionLifecycle;
import dev.buhanzaz.rwms.inventory.repository.InventoryFinalPlanEntryRepository;
import dev.buhanzaz.rwms.inventory.repository.InventoryFinalPlanRepository;
import dev.buhanzaz.rwms.inventory.repository.InventoryFindingRepository;
import dev.buhanzaz.rwms.platform.contracts.OpaqueActorReference;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Appends explicit observations lost by the obsolete automatic-membership rule to a new immutable
 * version of an already completed inventory plan.
 *
 * <p>Every prior entry, manager routing choice and operational date is copied unchanged. Only the
 * restored findings are appended on the same common object calendar as future plans. Calendar
 * evidence is fetched before this service receives database locks, then fenced again locally.
 */
@Service
final class CompletedInventoryPlanCorrectionService {
  private final InventoryFindingRepository findings;
  private final InventoryFinalPlanRepository finalPlans;
  private final InventoryFinalPlanEntryRepository finalPlanEntries;
  private final InventoryPlanningService planning;
  private final InventoryFindingPersistenceService findingPersistence;
  private final InventoryFindingService findingService;
  private final InventoryStatisticsService statistics;

  CompletedInventoryPlanCorrectionService(
      InventoryFindingRepository findings,
      InventoryFinalPlanRepository finalPlans,
      InventoryFinalPlanEntryRepository finalPlanEntries,
      InventoryPlanningService planning,
      InventoryFindingPersistenceService findingPersistence,
      InventoryFindingService findingService,
      InventoryStatisticsService statistics) {
    this.findings = findings;
    this.finalPlans = finalPlans;
    this.finalPlanEntries = finalPlanEntries;
    this.planning = planning;
    this.findingPersistence = findingPersistence;
    this.findingService = findingService;
    this.statistics = statistics;
  }

  /**
   * Fetches calendar evidence only when a completed head appears to need restored observations.
   * The locked correction repeats the omitted-observation check and verifies the local settings
   * revision before it can use this preloaded evidence.
   */
  Optional<CorrectionCalendar> prepareCalendarIfCorrectionLikely(InventorySession session) {
    InventoryFinalPlan plan = finalPlans.findById(session.getId()).orElse(null);
    if (plan == null || plan.getState() != FinalPlanState.COMPLETED) {
      return Optional.empty();
    }
    Set<UUID> entryIds = new LinkedHashSet<>();
    finalPlanEntries
        .findByInventoryIdAndFinalPlanVersionOrderByOrderAscFindingIdAsc(
            session.getId(), plan.getFinalPlanVersion())
        .forEach(entry -> entryIds.add(entry.getFindingId()));
    boolean omitted =
        findings.findAllByInventoryIdOrderById(session.getId()).stream()
            .anyMatch(
                finding ->
                    !finding.isMembershipActive()
                        && finding.isExplicitObservation()
                        && !entryIds.contains(finding.getId()));
    if (!omitted) {
      return Optional.empty();
    }
    InventoryPlanningService.PlanningSpecification settings =
        planning.planningSpecification(session.getWarehouseId());
    InventoryPlanningCalendar calendar = planning.calendarFor(session);
    // With no daily throttle, every appended automatic item uses this first valid common date.
    planning.reserveAutomaticDate(planning.planningDate(session), settings, calendar);
    return Optional.of(new CorrectionCalendar(settings, calendar));
  }

  /**
   * Returns the existing completed head when it already covers every explicit observation, or
   * persists and returns its strictly newer corrected successor.
   */
  CorrectionResult correct(
      InventorySession session,
      InventoryFinalPlan plan,
      List<InventoryFinalPlanEntry> currentEntries,
      CorrectionCalendar preparedCalendar,
      OpaqueActorReference actor) {
    if (session.getLifecycle() != SessionLifecycle.COMPLETED
        || plan.getState() != FinalPlanState.COMPLETED
        || currentEntries.isEmpty()) {
      throw InventoryException.conflict("Completed inventory plan correction is unavailable");
    }
    List<InventoryFinding> all = findings.findAllByInventoryIdForUpdateOrderById(session.getId());
    Set<UUID> currentEntryIds = new LinkedHashSet<>();
    currentEntries.forEach(entry -> currentEntryIds.add(entry.getFindingId()));
    List<InventoryFinding> omitted =
        all.stream()
            .filter(value -> !value.isMembershipActive())
            .filter(InventoryFinding::isExplicitObservation)
            .filter(value -> !currentEntryIds.contains(value.getId()))
            .sorted(
                Comparator.comparing(
                        (InventoryFinding value) ->
                            value.getInspection() == InspectionState.WORK_STAGED ? 0 : 1)
                    .thenComparing(InventoryFinding::getDisplayCanonicalNumber)
                    .thenComparing(InventoryFinding::getId))
            .toList();
    if (omitted.isEmpty()) {
      return new CorrectionResult(plan, currentEntries, 0);
    }
    boolean coveredInactiveExplicit =
        all.stream()
            .anyMatch(
                value ->
                    !value.isMembershipActive()
                        && value.isExplicitObservation()
                        && currentEntryIds.contains(value.getId()));
    if (coveredInactiveExplicit) {
      throw InventoryException.conflict(
          "Completed inventory contains an inactive explicit observation already present in its"
              + " plan");
    }

    List<InventoryPlanningService.FinalPlanDraft> retained =
        planning.completionFinalPlanDrafts(session, plan, currentEntries);
    if (preparedCalendar == null) {
      throw InventoryException.conflict(
          "Completed inventory calendar evidence must be prepared again before observation recovery");
    }
    InventoryPlanningService.PlanningSpecification settings = preparedCalendar.settings();
    if (planning.planningSpecification(session.getWarehouseId()).revision() != settings.revision()) {
      throw InventoryException.conflict(
          "Completed inventory planning settings changed before observation recovery");
    }

    for (InventoryFinding finding : omitted) {
      long sourceRevision = finding.getRevision();
      try {
        if (!finding.restoreCompletedExplicitObservation()) {
          throw new IllegalStateException("Explicit observation is already active");
        }
      } catch (IllegalStateException exception) {
        throw InventoryException.conflict(
            "Completed explicit observation is incomplete and cannot be restored");
      }
      InventoryFinding saved = findings.saveAndFlush(finding);
      findingPersistence.carryForwardMediaReferences(
          saved.getId(), sourceRevision, saved.getRevision());
      findingService.appendCompletedObservationRestored(saved, session, actor);
    }

    List<InventoryPlanningService.FinalPlanDraft> appended =
        scheduleAppended(
            session,
            settings,
            preparedCalendar.calendar(),
            retained,
            omitted.stream()
                .map(value -> planning.finalPlanDraft(value, null, null, null, null))
                .toList());
    long nextVersion = Math.addExact(plan.getFinalPlanVersion(), 1);
    List<InventoryPlanningService.FinalPlanDraft> combined = combine(retained, appended);
    InventoryPlanningCalendar.Evidence calendarEvidence = preparedCalendar.calendar().evidence();
    String finalSha =
        planning.finalPlanSha256(
            session,
            settings,
            planning.calendarFence(calendarEvidence),
            nextVersion,
            plan.getMovementScheduleMode(),
            plan.getRepairScheduleMode(),
            combined);

    plan.nextVersion(
        session.getRevision(),
        settings.revision(),
        calendarEvidence.from(),
        calendarEvidence.through(),
        calendarEvidence.fingerprint(),
        planning.calendarSnapshot(calendarEvidence),
        finalSha,
        plan.getMovementScheduleMode(),
        plan.getRepairScheduleMode());
    plan.complete();
    InventoryFinalPlan correctedPlan = finalPlans.saveAndFlush(plan);
    List<InventoryFinalPlanEntry> correctedEntries =
        combined.stream()
            .map(value -> planning.finalPlanEntry(session.getId(), nextVersion, value))
            .toList();
    finalPlanEntries.saveAllAndFlush(correctedEntries);
    List<InventoryFinding> active =
        findings.findAllByInventoryIdAndMembershipActiveTrueOrderById(session.getId());
    statistics.replacePersistedStatistics(session, active);
    return new CorrectionResult(correctedPlan, correctedEntries, omitted.size());
  }

  /**
   * Copies an unchanged completed plan into a strictly newer immutable generation.
   *
   * <p>This is the recovery fence: downstream owners must never interpret a manual reapplication
   * as another delivery of the older immutable source after that source already produced effects.
   */
  CorrectionResult advanceExactGeneration(
      InventorySession session,
      InventoryFinalPlan plan,
      List<InventoryFinalPlanEntry> currentEntries) {
    if (session.getLifecycle() != SessionLifecycle.COMPLETED
        || plan.getState() != FinalPlanState.COMPLETED
        || currentEntries.isEmpty()) {
      throw InventoryException.conflict("Completed inventory plan advance is unavailable");
    }
    InventoryPlanningService.PlanningSpecification settings =
        planning.historicalPlanningSpecification(plan.getPlanningSettingsRevision());
    List<InventoryPlanningService.FinalPlanDraft> exact =
        planning.completionFinalPlanDrafts(session, plan, currentEntries);
    long nextVersion = Math.addExact(plan.getFinalPlanVersion(), 1);
    String nextSha =
        planning.finalPlanSha256(
            session,
            settings,
            planning.calendarFenceOrNull(plan),
            nextVersion,
            plan.getMovementScheduleMode(),
            plan.getRepairScheduleMode(),
            exact);
    plan.nextVersion(
        session.getRevision(),
        settings.revision(),
        plan.getTaskBoardCalendarFrom(),
        plan.getTaskBoardCalendarThrough(),
        plan.getTaskBoardCalendarFingerprint(),
        plan.getTaskBoardCalendarSnapshot(),
        nextSha,
        plan.getMovementScheduleMode(),
        plan.getRepairScheduleMode());
    plan.complete();
    InventoryFinalPlan advanced = finalPlans.saveAndFlush(plan);
    List<InventoryFinalPlanEntry> copied =
        exact.stream()
            .map(value -> planning.finalPlanEntry(session.getId(), nextVersion, value))
            .toList();
    finalPlanEntries.saveAllAndFlush(copied);
    return new CorrectionResult(advanced, copied, 0);
  }

  List<InventoryPlanningService.FinalPlanDraft> scheduleAppended(
      InventorySession session,
      InventoryPlanningService.PlanningSpecification settings,
      InventoryPlanningCalendar calendar,
      List<InventoryPlanningService.FinalPlanDraft> retained,
      List<InventoryPlanningService.FinalPlanDraft> omitted) {
    LocalDate planningDate = planning.planningDate(session);
    List<InventoryPlanningService.FinalPlanDraft> result = new ArrayList<>();
    for (int index = 0; index < omitted.size(); index++) {
      InventoryPlanningService.FinalPlanDraft entry = omitted.get(index);
      if (entry.hasWork()) {
        LocalDate movement = null;
        if (entry.movementToRepair()) {
          movement =
              planning.reserveAutomaticDate(planningDate, settings, calendar);
        }
        LocalDate earliest =
            movement != null && movement.isAfter(planningDate) ? movement : planningDate;
        LocalDate repair =
            planning.reserveAutomaticDate(earliest, settings, calendar);
        entry = entry.withDates(movement, repair);
      }
      result.add(entry.withOrder(retained.size() + index));
    }
    return List.copyOf(result);
  }

  private static List<InventoryPlanningService.FinalPlanDraft> combine(
      List<InventoryPlanningService.FinalPlanDraft> retained,
      List<InventoryPlanningService.FinalPlanDraft> appended) {
    List<InventoryPlanningService.FinalPlanDraft> combined = new ArrayList<>(retained);
    combined.addAll(appended);
    return List.copyOf(combined);
  }

  /** Corrected completed-plan head, its exact entries and the restored observation count. */
  record CorrectionResult(
      InventoryFinalPlan plan, List<InventoryFinalPlanEntry> entries, int restoredCount) {}

  /** Calendar and settings fetched before the recovery transaction acquires row locks. */
  record CorrectionCalendar(
      InventoryPlanningService.PlanningSpecification settings, InventoryPlanningCalendar calendar) {}
}
