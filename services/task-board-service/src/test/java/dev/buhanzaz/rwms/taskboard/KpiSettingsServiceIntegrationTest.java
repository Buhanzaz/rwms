package dev.buhanzaz.rwms.taskboard;

import static dev.buhanzaz.rwms.taskboard.api.KpiSettingsApiModels.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.buhanzaz.rwms.taskboard.domain.KpiSettingsStatus;
import dev.buhanzaz.rwms.taskboard.service.KpiPaletteService;
import dev.buhanzaz.rwms.taskboard.service.KpiSettingsService;
import dev.buhanzaz.rwms.taskboard.service.StaleVersionException;
import dev.buhanzaz.rwms.taskboard.service.WarehouseKpiClock;
import dev.buhanzaz.rwms.taskboard.service.WarehouseTimeZoneGateway.TimeZoneDecision;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class KpiSettingsServiceIntegrationTest extends PostgresIntegrationTestSupport {
  private static final UUID W1 =
      UUID.fromString("00000000-0000-0000-0000-000000000201");
  private static final UUID W2 =
      UUID.fromString("00000000-0000-0000-0000-000000000202");
  @Autowired KpiSettingsService service;
  @Autowired KpiPaletteService kpiPalettes;
  @Autowired WarehouseKpiClock clock;
  @Autowired TestWarehouseTimeZoneGateway timeZones;
  @Autowired JdbcTemplate jdbc;

  @BeforeEach
  void clean() {
    cleanTaskBoardFixtures(jdbc);
    timeZones.reset();
    timeZones.setTimeline(
        W1, List.of(new TimeZoneDecision(ZoneId.of("Europe/Moscow"), Instant.EPOCH)));
    timeZones.setTimeline(
        W2, List.of(new TimeZoneDecision(ZoneId.of("Asia/Yekaterinburg"), Instant.EPOCH)));
  }

  @Test
  void scheduleAndPaletteSettingsAreOwnedOnceForTheInstallation() {
    assertThat(service.get())
        .satisfies(
            settings -> {
              assertThat(settings.status()).isEqualTo(KpiSettingsStatus.UNCONFIGURED);
              assertThat(settings.version()).isZero();
              assertThat(settings.minimumEffectiveDate()).isEqualTo(LocalDate.now(ZoneOffset.UTC));
              assertThat(settings.palette()).isNull();
            });

    var first =
        kpiPalettes.replace(
            new SaveKpiPaletteRequest(
                0,
                List.of(
                    new KpiPaletteRangeRequest(0, 35, "#DC2626"),
                    new KpiPaletteRangeRequest(35, 70, "#EAB308"),
                    new KpiPaletteRangeRequest(70, 100, "#16A34A")),
                "#7F1D1D"));

    assertThat(first.palette().ranges())
        .extracting(KpiPaletteRangeDto::fromPercent, KpiPaletteRangeDto::toPercent)
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple(0, 35),
            org.assertj.core.groups.Tuple.tuple(35, 70),
            org.assertj.core.groups.Tuple.tuple(70, 100));
    assertThat(first.palette().overdueColor()).isEqualTo("#7F1D1D");

    assertThat(service.get().palette()).isEqualTo(first.palette());
  }

  @Test
  void oneSavedScheduleIsUsedByWarehousesInTheirOwnAuthoritativeTimezones() {
    LocalDate effectiveFrom = LocalDate.now(ZoneOffset.UTC);
    var saved =
        service.saveWorkSchedule(
            new SaveWorkScheduleRequest(
                0,
                effectiveFrom,
                LocalTime.of(8, 0),
                LocalTime.of(17, 0),
                List.of(),
                List.of()));
    var active =
        service.activate(UUID.randomUUID(), new ActivateKpiSettingsRequest(saved.version()));

    assertThat(active.status()).isEqualTo(KpiSettingsStatus.ACTIVE);
    assertThat(clock.moment(W1, effectiveFrom.atTime(7, 0).atZone(ZoneId.of("Europe/Moscow")).toInstant()).state())
        .isEqualTo(WarehouseKpiClock.ScheduleState.OFF_SHIFT);
    assertThat(clock.moment(W2, effectiveFrom.atTime(8, 30).atZone(ZoneId.of("Asia/Yekaterinburg")).toInstant()).state())
        .isEqualTo(WarehouseKpiClock.ScheduleState.WORKING);
  }

  @Test
  void futureWeeklyScheduleActivatesWhenBreaksAreValid() {
    LocalDate effectiveFrom = LocalDate.now(ZoneOffset.UTC).plusDays(2);

    var draft =
        service.saveWorkSchedule(
            new SaveWorkScheduleRequest(
                0,
                effectiveFrom,
                java.time.LocalTime.of(8, 0),
                java.time.LocalTime.of(17, 0),
                List.of(6, 7),
                List.of(
                    new KpiWorkBreakRequest(
                        java.time.LocalTime.of(10, 0), java.time.LocalTime.of(10, 15)),
                    new KpiWorkBreakRequest(
                        java.time.LocalTime.of(13, 0), java.time.LocalTime.of(14, 0)))));

    assertThat(draft.status()).isEqualTo(KpiSettingsStatus.DRAFT);
    assertThat(draft.activeSchedule()).isNull();
    assertThat(draft.pendingSchedule().effectiveFrom()).isEqualTo(effectiveFrom);
    assertThat(draft.pendingSchedule().daysOff()).containsExactly(6, 7);

    var scheduled =
        service.activate(UUID.randomUUID(), new ActivateKpiSettingsRequest(draft.version()));

    assertThat(scheduled.status()).isEqualTo(KpiSettingsStatus.SCHEDULED);
    assertThat(scheduled.dataAvailableFrom()).isEqualTo(effectiveFrom);
    assertThat(scheduled.activeSchedule()).isNull();
    assertThat(scheduled.pendingSchedule()).isNotNull();
    assertThat(scheduled.pendingSchedule().effectiveFrom()).isEqualTo(effectiveFrom);
  }

  @Test
  void scheduleAcceptsTodayButRejectsPastOvernightAndOverlappingDefinitions() {
    LocalDate today = LocalDate.now(ZoneOffset.UTC);

    var todayDraft =
        service.saveWorkSchedule(
            new SaveWorkScheduleRequest(
                0,
                today,
                LocalTime.of(8, 0),
                LocalTime.of(17, 0),
                List.of(),
                List.of()));

    assertThat(todayDraft.status()).isEqualTo(KpiSettingsStatus.DRAFT);
    assertThat(todayDraft.pendingSchedule().effectiveFrom()).isEqualTo(today);

    assertThatThrownBy(
            () ->
                service.saveWorkSchedule(
                    new SaveWorkScheduleRequest(
                        todayDraft.version(),
                        today.minusDays(1),
                        LocalTime.of(8, 0),
                        LocalTime.of(17, 0),
                        List.of(),
                        List.of())))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("раньше текущего дня");

    assertThatThrownBy(
            () ->
                service.saveWorkSchedule(
                    new SaveWorkScheduleRequest(
                        todayDraft.version(),
                        today.plusDays(1),
                        LocalTime.of(20, 0),
                        LocalTime.of(8, 0),
                        List.of(),
                        List.of(
                            new KpiWorkBreakRequest(
                                LocalTime.of(23, 0), LocalTime.of(23, 30))))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("начинаться раньше");

    assertThatThrownBy(
            () ->
                service.saveWorkSchedule(
                    new SaveWorkScheduleRequest(
                        todayDraft.version(),
                        today.plusDays(2),
                        LocalTime.of(8, 0),
                        LocalTime.of(17, 0),
                        List.of(),
                        List.of(
                            new KpiWorkBreakRequest(
                                LocalTime.of(10, 0), LocalTime.of(11, 0)),
                            new KpiWorkBreakRequest(
                                LocalTime.of(10, 30), LocalTime.of(11, 30))))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("пересек");
  }

  @Test
  void sameDayActivationIsImmediatelyActiveForTheInstallation() {
    LocalDate today = LocalDate.now(ZoneOffset.UTC);
    var draft =
        service.saveWorkSchedule(
            new SaveWorkScheduleRequest(
                0,
                today,
                LocalTime.of(8, 0),
                LocalTime.of(17, 0),
                List.of(),
                List.of()));
    UUID operationId = UUID.randomUUID();

    var active =
        service.activate(operationId, new ActivateKpiSettingsRequest(draft.version()));
    var replay =
        service.activate(operationId, new ActivateKpiSettingsRequest(draft.version()));

    assertThat(active.status()).isEqualTo(KpiSettingsStatus.ACTIVE);
    assertThat(active.activeSchedule()).isNotNull();
    assertThat(active.activeSchedule().effectiveFrom()).isEqualTo(today);
    assertThat(active.pendingSchedule()).isNull();
    assertThat(active.dataAvailableFrom()).isEqualTo(today);
    assertThat(replay.status()).isEqualTo(KpiSettingsStatus.ACTIVE);
    assertThat(replay.version()).isEqualTo(active.version());
    assertThat(replay.activeSchedule().id()).isEqualTo(active.activeSchedule().id());
    assertThat(service.get().status()).isEqualTo(KpiSettingsStatus.ACTIVE);
  }

  @Test
  void sameDayReplacementUnschedulesOldRevisionAndDrivesClockDeterministically() {
    ZoneId zone = ZoneId.of("Europe/Moscow");
    LocalDate today = LocalDate.now(zone);
    var firstDraft =
        service.saveWorkSchedule(
            new SaveWorkScheduleRequest(
                0,
                today,
                LocalTime.of(8, 0),
                LocalTime.of(17, 0),
                List.of(),
                List.of()));
    var firstActive =
        service.activate(UUID.randomUUID(), new ActivateKpiSettingsRequest(firstDraft.version()));
    var replacementDraft =
        service.saveWorkSchedule(
            new SaveWorkScheduleRequest(
                firstActive.version(),
                today,
                LocalTime.of(13, 0),
                LocalTime.of(17, 0),
                List.of(),
                List.of()));

    assertThat(replacementDraft.status()).isEqualTo(KpiSettingsStatus.DRAFT);

    var replacement =
        service.activate(UUID.randomUUID(), new ActivateKpiSettingsRequest(replacementDraft.version()));

    assertThat(replacement.status()).isEqualTo(KpiSettingsStatus.ACTIVE);
    assertThat(replacement.activeSchedule().shiftStart()).isEqualTo(LocalTime.of(13, 0));
    assertThat(replacement.pendingSchedule()).isNull();
    assertThat(
            jdbc.queryForObject(
                "select count(*) from kpi_work_schedule where warehouse_id is null and effective_from=? and scheduled",
                Integer.class,
                today))
        .isOne();
    assertThat(
            clock.moment(W1, today.atTime(12, 0).atZone(zone).toInstant()).state())
        .isEqualTo(WarehouseKpiClock.ScheduleState.OFF_SHIFT);
  }

  @Test
  void everyMutationUsesAggregateExpectedVersion() {
    LocalDate effectiveFrom = LocalDate.now(ZoneOffset.UTC).plusDays(1);
    var saved =
        service.saveWorkSchedule(
            new SaveWorkScheduleRequest(
                0,
                effectiveFrom,
                LocalTime.of(8, 0),
                LocalTime.of(17, 0),
                List.of(),
                List.of()));

    assertThatThrownBy(
            () ->
                service.saveWorkSchedule(
                    new SaveWorkScheduleRequest(
                        saved.version() + 1,
                        effectiveFrom.plusDays(1),
                        LocalTime.of(8, 0),
                        LocalTime.of(17, 0),
                        List.of(),
                        List.of())))
        .isInstanceOf(StaleVersionException.class);
  }

  @Test
  void activationIsIdempotentForTheSameSettingsVersionAndOperationId() {
    var draft =
        service.saveWorkSchedule(
            new SaveWorkScheduleRequest(
                0,
                LocalDate.now(ZoneOffset.UTC).plusDays(2),
                java.time.LocalTime.of(8, 0),
                java.time.LocalTime.of(17, 0),
                List.of(6, 7),
                List.of()));
    UUID operationId = UUID.randomUUID();

    var first =
        service.activate(operationId, new ActivateKpiSettingsRequest(draft.version()));
    var replay =
        service.activate(operationId, new ActivateKpiSettingsRequest(draft.version()));

    assertThat(replay.version()).isEqualTo(first.version());
    assertThat(
            jdbc.queryForObject("select count(*) from kpi_activation_receipt", Integer.class))
        .isOne();
  }

}
