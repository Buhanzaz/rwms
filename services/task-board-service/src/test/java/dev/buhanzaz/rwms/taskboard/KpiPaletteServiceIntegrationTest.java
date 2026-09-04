package dev.buhanzaz.rwms.taskboard;

import static dev.buhanzaz.rwms.taskboard.api.KpiSettingsApiModels.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.buhanzaz.rwms.taskboard.service.KpiPaletteService;
import dev.buhanzaz.rwms.taskboard.service.StaleVersionException;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class KpiPaletteServiceIntegrationTest extends PostgresIntegrationTestSupport {
  @Autowired KpiPaletteService service;
  @Autowired JdbcTemplate jdbc;

  @BeforeEach
  void clean() {
    cleanTaskBoardFixtures(jdbc);
  }

  @Test
  void paletteHeadIsGlobalAndStartsUnconfigured() {
    assertThat(service.get().version()).isZero();
    assertThat(service.get().palette()).isNull();

    var first = service.replace(palette(0, "#7F1D1D"));

    assertThat(service.get()).isEqualTo(first);
    assertThat(first.palette().overdueColor()).isEqualTo("#7F1D1D");
    assertThat(jdbc.queryForObject("select count(*) from kpi_settings", Integer.class)).isOne();
  }

  @Test
  void replacementUsesTheGlobalPaletteVersionFence() {
    var first = service.replace(palette(0, "#7F1D1D"));
    var replacement = service.replace(palette(first.version(), "#111827"));

    assertThat(replacement.version()).isGreaterThan(first.version());
    assertThat(replacement.palette().overdueColor()).isEqualTo("#111827");
    assertThatThrownBy(() -> service.replace(palette(first.version(), "#2563EB")))
        .isInstanceOf(StaleVersionException.class);
  }

  @Test
  void paletteMustCoverTheWholeIntegerScaleWithValidColors() {
    assertThatThrownBy(
            () ->
                service.replace(
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
                service.replace(
                    new SaveKpiPaletteRequest(
                        0,
                        List.of(new KpiPaletteRangeRequest(0, 100, "#GGGGGG")),
                        "#7F1D1D")))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static SaveKpiPaletteRequest palette(long expectedVersion, String overdueColor) {
    return new SaveKpiPaletteRequest(
        expectedVersion,
        List.of(
            new KpiPaletteRangeRequest(0, 35, "#DC2626"),
            new KpiPaletteRangeRequest(35, 70, "#EAB308"),
            new KpiPaletteRangeRequest(70, 100, "#16A34A")),
        overdueColor);
  }
}
