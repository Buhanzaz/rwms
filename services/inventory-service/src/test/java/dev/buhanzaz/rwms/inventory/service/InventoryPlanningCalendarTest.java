package dev.buhanzaz.rwms.inventory.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.buhanzaz.rwms.inventory.domain.FinalPlanScheduleMode;
import dev.buhanzaz.rwms.inventory.domain.InventoryCabinDispositionKind;
import dev.buhanzaz.rwms.inventory.domain.InventoryFinding;
import dev.buhanzaz.rwms.inventory.domain.InventorySession;
import dev.buhanzaz.rwms.inventory.integration.InventoryDependencyGateway;
import dev.buhanzaz.rwms.inventory.repository.FindingMediaReferenceRepository;
import dev.buhanzaz.rwms.inventory.repository.FindingPlanSnapshotRepository;
import dev.buhanzaz.rwms.inventory.repository.InventoryFinalPlanEntryRepository;
import dev.buhanzaz.rwms.inventory.repository.InventoryFinalPlanRepository;
import dev.buhanzaz.rwms.inventory.repository.InventoryFindingRepository;
import dev.buhanzaz.rwms.inventory.repository.InventoryPlanningSettingsRepository;
import dev.buhanzaz.rwms.inventory.repository.InventorySessionRepository;
import dev.buhanzaz.rwms.inventory.security.InventoryAuthorizer;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.json.JsonMapper;

class InventoryPlanningCalendarTest {

  @Test
  void automaticAndManualSchedulingUseTheSameEarliestCommonWorkingDateWithoutDailyThrottle() {
    UUID warehouseId = UUID.randomUUID();
    LocalDate start = LocalDate.now(ZoneOffset.UTC).plusDays(1);
    InventoryDependencyGateway dependencies = calendarGateway(warehouseId, start, "a".repeat(64));
    InventoryPlanningService service = planningService(dependencies);
    InventorySession session = session(warehouseId, start);
    InventoryPlanningService.PlanningSpecification settings =
        new InventoryPlanningService.PlanningSpecification(4, null, List.of(start.plusDays(1)));
    InventoryPlanningCalendar calendar = service.calendarFor(session);
    List<InventoryPlanningService.FinalPlanDraft> work =
        List.of(workDraft(0, true, null, null), workDraft(1, true, null, null), workDraft(2, false, null, null));

    List<InventoryPlanningService.FinalPlanDraft> automatic =
        service.scheduleFinalPlan(
            session,
            settings,
            calendar,
            FinalPlanScheduleMode.AUTO,
            FinalPlanScheduleMode.AUTO,
            work);

    LocalDate earliest = start.plusDays(2);
    assertThat(automatic)
        .extracting(InventoryPlanningService.FinalPlanDraft::repairScheduledDate)
        .containsOnly(earliest);
    assertThat(automatic)
        .extracting(InventoryPlanningService.FinalPlanDraft::movementScheduledDate)
        .containsExactly(earliest, earliest, null);

    List<InventoryPlanningService.FinalPlanDraft> manual =
        service.scheduleFinalPlan(
            session,
            settings,
            calendar,
            FinalPlanScheduleMode.MANUAL,
            FinalPlanScheduleMode.MANUAL,
            List.of(workDraft(0, true, earliest, earliest), workDraft(1, true, earliest, earliest)));

    assertThat(manual)
        .extracting(InventoryPlanningService.FinalPlanDraft::repairScheduledDate)
        .containsExactly(earliest, earliest);
  }

  @Test
  void manualSchedulingRejectsTaskBoardDaysOffAndInventoryHolidays() {
    UUID warehouseId = UUID.randomUUID();
    LocalDate start = LocalDate.now(ZoneOffset.UTC).plusDays(1);
    InventoryPlanningService service =
        planningService(calendarGateway(warehouseId, start, "a".repeat(64)));
    InventorySession session = session(warehouseId, start);
    InventoryPlanningService.PlanningSpecification settings =
        new InventoryPlanningService.PlanningSpecification(4, null, List.of(start.plusDays(1)));
    InventoryPlanningCalendar calendar = service.calendarFor(session);

    assertThatThrownBy(
            () ->
                service.scheduleFinalPlan(
                    session,
                    settings,
                    calendar,
                    FinalPlanScheduleMode.MANUAL,
                    FinalPlanScheduleMode.MANUAL,
                    List.of(workDraft(0, false, null, start))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("working day");
    assertThatThrownBy(
            () ->
                service.scheduleFinalPlan(
                    session,
                    settings,
                    calendar,
                    FinalPlanScheduleMode.MANUAL,
                    FinalPlanScheduleMode.MANUAL,
                    List.of(workDraft(0, false, null, start.plusDays(1)))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("working day");
  }

  @Test
  void completedPlanCorrectionAppendsWorkOnTheSameEarliestCommonWorkingDateWithoutThrottle() {
    UUID warehouseId = UUID.randomUUID();
    LocalDate start = LocalDate.now(ZoneOffset.UTC).plusDays(1);
    InventoryPlanningService service =
        planningService(calendarGateway(warehouseId, start, "a".repeat(64)));
    CompletedInventoryPlanCorrectionService correction =
        new CompletedInventoryPlanCorrectionService(
            mock(InventoryFindingRepository.class),
            mock(InventoryFinalPlanRepository.class),
            mock(InventoryFinalPlanEntryRepository.class),
            service,
            mock(InventoryFindingPersistenceService.class),
            mock(InventoryFindingService.class),
            mock(InventoryStatisticsService.class));
    InventorySession session = session(warehouseId, start);
    InventoryPlanningService.PlanningSpecification settings =
        new InventoryPlanningService.PlanningSpecification(4, null, List.of(start.plusDays(1)));

    List<InventoryPlanningService.FinalPlanDraft> appended =
        correction.scheduleAppended(
            session,
            settings,
            service.calendarFor(session),
            List.of(),
            List.of(workDraft(0, true, null, null), workDraft(1, false, null, null)));

    LocalDate earliest = start.plusDays(2);
    assertThat(appended)
        .extracting(InventoryPlanningService.FinalPlanDraft::repairScheduledDate)
        .containsExactly(earliest, earliest);
    assertThat(appended)
        .extracting(InventoryPlanningService.FinalPlanDraft::movementScheduledDate)
        .containsExactly(earliest, null);
  }

  @Test
  void calendarEvidenceChangesWhenTaskBoardChangesItsEffectiveScheduleVersion() {
    UUID warehouseId = UUID.randomUUID();
    LocalDate start = LocalDate.of(2026, 9, 7);
    AtomicReference<String> fingerprint = new AtomicReference<>("a".repeat(64));
    InventoryDependencyGateway dependencies =
        mock(InventoryDependencyGateway.class);
    when(dependencies.workCalendarSnapshot(eq(warehouseId), any(LocalDate.class), any(LocalDate.class)))
        .thenAnswer(
            invocation ->
                calendarPage(
                    warehouseId,
                    invocation.getArgument(1, LocalDate.class),
                    invocation.getArgument(2, LocalDate.class),
                    fingerprint.get()));

    InventoryPlanningCalendar.Evidence first =
        InventoryPlanningCalendar.currentEvidence(dependencies, warehouseId, start, start.plusDays(30));
    fingerprint.set("b".repeat(64));
    InventoryPlanningCalendar.Evidence changed =
        InventoryPlanningCalendar.currentEvidence(dependencies, warehouseId, start, start.plusDays(30));

    assertThat(changed.fingerprint()).isNotEqualTo(first.fingerprint());
    assertThat(changed.snapshot().pages())
        .singleElement()
        .satisfies(page -> assertThat(page.dates().getFirst().scheduleVersion()).isEqualTo(2L));
  }

  private static InventoryDependencyGateway calendarGateway(
      UUID warehouseId, LocalDate firstDayOff, String fingerprint) {
    InventoryDependencyGateway dependencies = mock(InventoryDependencyGateway.class);
    when(dependencies.workCalendarSnapshot(eq(warehouseId), any(LocalDate.class), any(LocalDate.class)))
        .thenAnswer(
            invocation ->
                calendarPage(
                    warehouseId,
                    invocation.getArgument(1, LocalDate.class),
                    invocation.getArgument(2, LocalDate.class),
                    fingerprint,
                    firstDayOff));
    return dependencies;
  }

  private static InventoryDependencyGateway.WorkCalendarSnapshot calendarPage(
      UUID warehouseId, LocalDate from, LocalDate through, String fingerprint) {
    return calendarPage(warehouseId, from, through, fingerprint, null);
  }

  private static InventoryDependencyGateway.WorkCalendarSnapshot calendarPage(
      UUID warehouseId,
      LocalDate from,
      LocalDate through,
      String fingerprint,
      LocalDate firstDayOff) {
    UUID scheduleId = UUID.fromString("00000000-0000-0000-0000-000000000701");
    long scheduleVersion = fingerprint.charAt(0) == 'b' ? 2 : 1;
    List<InventoryDependencyGateway.WorkCalendarDate> dates =
        from.datesUntil(through.plusDays(1))
            .map(
                date ->
                    new InventoryDependencyGateway.WorkCalendarDate(
                        date,
                        !date.equals(firstDayOff),
                        "UTC",
                        OffsetDateTime.parse("2026-01-01T00:00:00Z"),
                        scheduleId,
                        scheduleVersion,
                        LocalDate.of(2026, 1, 1)))
            .toList();
    return new InventoryDependencyGateway.WorkCalendarSnapshot(
        warehouseId, from, through, fingerprint, dates);
  }

  private static InventorySession session(UUID warehouseId, LocalDate businessDate) {
    InventorySession session = mock(InventorySession.class);
    when(session.getWarehouseId()).thenReturn(warehouseId);
    when(session.getWarehouseTimeZone()).thenReturn("UTC");
    when(session.getBusinessDate()).thenReturn(businessDate);
    return session;
  }

  private static InventoryPlanningService.FinalPlanDraft workDraft(
      int order,
      boolean movementToRepair,
      LocalDate movementDate,
      LocalDate repairDate) {
    return new InventoryPlanningService.FinalPlanDraft(
        mock(InventoryFinding.class),
        null,
        true,
        dev.buhanzaz.rwms.inventory.domain.FinalPlanTargetKind.REPAIR,
        order,
        1,
        movementToRepair,
        movementDate,
        repairDate,
        "[]",
        null,
        InventoryCabinDispositionKind.LOCAL,
        "{\"formerRental\":null}");
  }

  private static InventoryPlanningService planningService(InventoryDependencyGateway dependencies) {
    JsonMapper mapper = JsonMapper.builder().findAndAddModules().build();
    return new InventoryPlanningService(
        mock(InventorySessionRepository.class),
        mock(InventoryFindingRepository.class),
        mock(InventoryPlanningSettingsRepository.class),
        mock(InventoryFinalPlanRepository.class),
        mock(InventoryFinalPlanEntryRepository.class),
        mock(FindingMediaReferenceRepository.class),
        mock(FindingPlanSnapshotRepository.class),
        dependencies,
        mock(InventoryIdempotencyPort.class),
        mock(InventoryFrozenPlanFingerprint.class),
        mock(InventoryCabinDispositionService.class),
        mapper,
        new InventoryCanonicalJsonService(mapper),
        mock(InventoryAuthorizer.class),
        mock(PlatformTransactionManager.class));
  }
}
