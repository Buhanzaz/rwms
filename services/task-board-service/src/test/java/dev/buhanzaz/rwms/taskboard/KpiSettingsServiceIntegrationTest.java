package dev.buhanzaz.rwms.taskboard;

import static dev.buhanzaz.rwms.taskboard.api.KpiSettingsApiModels.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.buhanzaz.rwms.taskboard.domain.WarehouseMetadata;
import dev.buhanzaz.rwms.taskboard.domain.KpiSettingsStatus;
import dev.buhanzaz.rwms.taskboard.repository.WarehouseMetadataRepository;
import dev.buhanzaz.rwms.taskboard.service.KpiSettingsService;
import dev.buhanzaz.rwms.taskboard.service.StaleVersionException;
import dev.buhanzaz.rwms.taskboard.service.WarehouseKpiClock;
import dev.buhanzaz.rwms.taskboard.service.WarehouseTimeZoneGateway.TimeZoneDecision;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
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
  @Autowired WarehouseKpiClock clock;
  @Autowired WarehouseMetadataRepository warehouses;
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
    warehouses.save(WarehouseMetadata.fromFact(W1, 4, "Europe/Moscow", true));
    warehouses.save(WarehouseMetadata.fromFact(W2, 2, "Asia/Yekaterinburg", true));
  }

  @Test
  void settingsAndPaletteAreIsolatedByCurrentWarehouse() {
    assertThat(service.get(W1))
        .satisfies(
            settings -> {
              assertThat(settings.warehouseId()).isEqualTo(W1);
              assertThat(settings.timeZone()).isEqualTo("Europe/Moscow");
              assertThat(settings.status()).isEqualTo(KpiSettingsStatus.UNCONFIGURED);
              assertThat(settings.version()).isZero();
              assertThat(settings.palette()).isNull();
            });

    var first =
        service.savePalette(
            W1,
            new SaveKpiPaletteRequest(
                0,
                List.of(
                    new KpiPaletteRangeRequest(0, 35, "#DC2626"),
                    new KpiPaletteRangeRequest(35, 70, "#EAB308"),
                    new KpiPaletteRangeRequest(70, 100, "#16A34A")),
                "#7F1D1D"));

    assertThat(first.status()).isEqualTo(KpiSettingsStatus.DRAFT);
    assertThat(first.palette().ranges())
        .extracting(KpiPaletteRangeDto::fromPercent, KpiPaletteRangeDto::toPercent)
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple(0, 35),
            org.assertj.core.groups.Tuple.tuple(35, 70),
            org.assertj.core.groups.Tuple.tuple(70, 100));
    assertThat(first.palette().overdueColor()).isEqualTo("#7F1D1D");

    assertThat(service.get(W2).palette()).isNull();
    assertThat(service.get(W2).timeZone()).isEqualTo("Asia/Yekaterinburg");
  }

  @Test
  void currentKpiSettingsZoneComesFromAuthoritativeAsOfBoundaryNotMetadataSnapshot() {
    timeZones.setTimeline(
        W1, List.of(new TimeZoneDecision(ZoneId.of("Europe/Samara"), Instant.EPOCH)));

    assertThat(service.get(W1).timeZone()).isEqualTo("Europe/Samara");

    var saved =
        service.savePalette(
            W1,
            new SaveKpiPaletteRequest(
                0,
                List.of(new KpiPaletteRangeRequest(0, 100, "#16A34A")),
                "#7F1D1D"));

    assertThat(saved.timeZone()).isEqualTo("Europe/Samara");
  }

  @Test
  void paletteMustCoverWholeIntegerScaleWithoutGapsAndUsesSeparateOverdueColor() {
    assertThatThrownBy(
            () ->
                service.savePalette(
                    W1,
                    new SaveKpiPaletteRequest(
                        0,
                        List.of(
                            new KpiPaletteRangeRequest(0, 35, "#DC2626"),
                            new KpiPaletteRangeRequest(36, 100, "#16A34A")),
                        "#7F1D1D")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("непрерыв");

    assertThatThrownBy(
            () ->
                service.savePalette(
                    W1,
                    new SaveKpiPaletteRequest(
                        0,
                        List.of(new KpiPaletteRangeRequest(0, 100, "#GGGGGG")),
                        "#7F1D1D")))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void futureWeeklyScheduleActivatesOnlyWhenPaletteAndBreaksAreValid() {
    var palette =
        service.savePalette(
            W1,
            new SaveKpiPaletteRequest(
                0,
                List.of(new KpiPaletteRangeRequest(0, 100, "#16A34A")),
                "#7F1D1D"));
    LocalDate effectiveFrom = LocalDate.now(ZoneId.of("Europe/Moscow")).plusDays(2);

    var draft =
        service.saveWorkSchedule(
            W1,
            new SaveWorkScheduleRequest(
                palette.version(),
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
        service.activate(
            W1, UUID.randomUUID(), new ActivateKpiSettingsRequest(draft.version()));

    assertThat(scheduled.status()).isEqualTo(KpiSettingsStatus.SCHEDULED);
    assertThat(scheduled.dataAvailableFrom()).isEqualTo(effectiveFrom);
    assertThat(scheduled.activeSchedule()).isNull();
    assertThat(scheduled.pendingSchedule()).isNotNull();
    assertThat(scheduled.pendingSchedule().effectiveFrom()).isEqualTo(effectiveFrom);
  }

  @Test
  void scheduleAcceptsTodayButRejectsPastOvernightAndOverlappingDefinitions() {
    var settings =
        service.savePalette(
            W1,
            new SaveKpiPaletteRequest(
                0,
                List.of(new KpiPaletteRangeRequest(0, 100, "#16A34A")),
                "#7F1D1D"));
    LocalDate today = LocalDate.now(ZoneId.of("Europe/Moscow"));

    var todayDraft =
        service.saveWorkSchedule(
            W1,
            new SaveWorkScheduleRequest(
                settings.version(),
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
                    W1,
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
                    W1,
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
                    W1,
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
  void sameDayActivationIsImmediatelyActiveForTheWholeWarehouseLocalDate() {
    LocalDate today = LocalDate.now(ZoneId.of("Europe/Moscow"));
    var palette =
        service.savePalette(
            W1,
            new SaveKpiPaletteRequest(
                0,
                List.of(new KpiPaletteRangeRequest(0, 100, "#16A34A")),
                "#7F1D1D"));
    var draft =
        service.saveWorkSchedule(
            W1,
            new SaveWorkScheduleRequest(
                palette.version(),
                today,
                LocalTime.of(8, 0),
                LocalTime.of(17, 0),
                List.of(),
                List.of()));
    UUID operationId = UUID.randomUUID();

    var active =
        service.activate(
            W1, operationId, new ActivateKpiSettingsRequest(draft.version()));
    var replay =
        service.activate(W1, operationId, new ActivateKpiSettingsRequest(draft.version()));

    assertThat(active.status()).isEqualTo(KpiSettingsStatus.ACTIVE);
    assertThat(active.activeSchedule()).isNotNull();
    assertThat(active.activeSchedule().effectiveFrom()).isEqualTo(today);
    assertThat(active.pendingSchedule()).isNull();
    assertThat(active.dataAvailableFrom()).isEqualTo(today);
    assertThat(replay.status()).isEqualTo(KpiSettingsStatus.ACTIVE);
    assertThat(replay.version()).isEqualTo(active.version());
    assertThat(replay.activeSchedule().id()).isEqualTo(active.activeSchedule().id());
    assertThat(service.get(W1).status()).isEqualTo(KpiSettingsStatus.ACTIVE);
  }

  @Test
  void sameDayReplacementUnschedulesOldRevisionAndDrivesClockDeterministically() {
    ZoneId zone = ZoneId.of("Europe/Moscow");
    LocalDate today = LocalDate.now(zone);
    var palette =
        service.savePalette(
            W1,
            new SaveKpiPaletteRequest(
                0,
                List.of(new KpiPaletteRangeRequest(0, 100, "#16A34A")),
                "#7F1D1D"));
    var firstDraft =
        service.saveWorkSchedule(
            W1,
            new SaveWorkScheduleRequest(
                palette.version(),
                today,
                LocalTime.of(8, 0),
                LocalTime.of(17, 0),
                List.of(),
                List.of()));
    var firstActive =
        service.activate(
            W1, UUID.randomUUID(), new ActivateKpiSettingsRequest(firstDraft.version()));
    var replacementDraft =
        service.saveWorkSchedule(
            W1,
            new SaveWorkScheduleRequest(
                firstActive.version(),
                today,
                LocalTime.of(13, 0),
                LocalTime.of(17, 0),
                List.of(),
                List.of()));

    var replacement =
        service.activate(
            W1,
            UUID.randomUUID(),
            new ActivateKpiSettingsRequest(replacementDraft.version()));

    assertThat(replacement.status()).isEqualTo(KpiSettingsStatus.ACTIVE);
    assertThat(replacement.activeSchedule().shiftStart()).isEqualTo(LocalTime.of(13, 0));
    assertThat(replacement.pendingSchedule()).isNull();
    assertThat(
            jdbc.queryForObject(
                "select count(*) from kpi_work_schedule where warehouse_id=? and effective_from=? and scheduled",
                Integer.class,
                W1,
                today))
        .isOne();
    assertThat(
            clock.moment(W1, today.atTime(12, 0).atZone(zone).toInstant()).state())
        .isEqualTo(WarehouseKpiClock.ScheduleState.OFF_SHIFT);
  }

  @Test
  void everyMutationUsesAggregateExpectedVersion() {
    var saved =
        service.savePalette(
            W1,
            new SaveKpiPaletteRequest(
                0,
                List.of(new KpiPaletteRangeRequest(0, 100, "#16A34A")),
                "#7F1D1D"));

    assertThatThrownBy(
            () ->
                service.savePalette(
                    W1,
                    new SaveKpiPaletteRequest(
                        saved.version() + 1,
                        List.of(new KpiPaletteRangeRequest(0, 100, "#2563EB")),
                        "#111827")))
        .isInstanceOf(StaleVersionException.class);
  }

  @Test
  void activationIsIdempotentForTheSameWarehouseVersionAndOperationId() {
    var palette =
        service.savePalette(
            W1,
            new SaveKpiPaletteRequest(
                0,
                List.of(new KpiPaletteRangeRequest(0, 100, "#16A34A")),
                "#7F1D1D"));
    var draft =
        service.saveWorkSchedule(
            W1,
            new SaveWorkScheduleRequest(
                palette.version(),
                LocalDate.now(ZoneId.of("Europe/Moscow")).plusDays(2),
                java.time.LocalTime.of(8, 0),
                java.time.LocalTime.of(17, 0),
                List.of(6, 7),
                List.of()));
    UUID operationId = UUID.randomUUID();

    var first =
        service.activate(
            W1, operationId, new ActivateKpiSettingsRequest(draft.version()));
    var replay =
        service.activate(
            W1, operationId, new ActivateKpiSettingsRequest(draft.version()));

    assertThat(replay.version()).isEqualTo(first.version());
    assertThat(
            jdbc.queryForObject("select count(*) from kpi_activation_receipt", Integer.class))
        .isOne();
  }
}
