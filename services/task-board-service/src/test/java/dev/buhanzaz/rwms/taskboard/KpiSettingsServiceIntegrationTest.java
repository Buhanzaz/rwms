package dev.buhanzaz.rwms.taskboard;

import static dev.buhanzaz.rwms.taskboard.api.KpiSettingsApiModels.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.buhanzaz.rwms.taskboard.domain.WarehouseMetadata;
import dev.buhanzaz.rwms.taskboard.domain.KpiSettingsStatus;
import dev.buhanzaz.rwms.taskboard.repository.WarehouseMetadataRepository;
import dev.buhanzaz.rwms.taskboard.service.KpiSettingsService;
import dev.buhanzaz.rwms.taskboard.service.StaleVersionException;
import java.time.LocalDate;
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
  @Autowired WarehouseMetadataRepository warehouses;
  @Autowired JdbcTemplate jdbc;

  @BeforeEach
  void clean() {
    cleanTaskBoardFixtures(jdbc);
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
    LocalDate effectiveFrom = LocalDate.now().plusDays(2);

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
    assertThat(scheduled.pendingSchedule()).isNotNull();
  }

  @Test
  void scheduleRejectsOvernightOverlappingAndNonFutureDefinitions() {
    var settings =
        service.savePalette(
            W1,
            new SaveKpiPaletteRequest(
                0,
                List.of(new KpiPaletteRangeRequest(0, 100, "#16A34A")),
                "#7F1D1D"));

    assertThatThrownBy(
            () ->
                service.saveWorkSchedule(
                    W1,
                    new SaveWorkScheduleRequest(
                        settings.version(),
                        LocalDate.now(),
                        java.time.LocalTime.of(20, 0),
                        java.time.LocalTime.of(8, 0),
                        List.of(),
                        List.of(
                            new KpiWorkBreakRequest(
                                java.time.LocalTime.of(23, 0),
                                java.time.LocalTime.of(23, 30))))))
        .isInstanceOf(IllegalArgumentException.class);

    assertThatThrownBy(
            () ->
                service.saveWorkSchedule(
                    W1,
                    new SaveWorkScheduleRequest(
                        settings.version(),
                        LocalDate.now().plusDays(2),
                        java.time.LocalTime.of(8, 0),
                        java.time.LocalTime.of(17, 0),
                        List.of(),
                        List.of(
                            new KpiWorkBreakRequest(
                                java.time.LocalTime.of(10, 0),
                                java.time.LocalTime.of(11, 0)),
                            new KpiWorkBreakRequest(
                                java.time.LocalTime.of(10, 30),
                                java.time.LocalTime.of(11, 30))))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("пересек");
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
                LocalDate.now().plusDays(2),
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
