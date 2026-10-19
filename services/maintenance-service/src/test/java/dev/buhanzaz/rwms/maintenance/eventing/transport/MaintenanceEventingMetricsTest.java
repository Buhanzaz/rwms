package dev.buhanzaz.rwms.maintenance.eventing.transport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;

/** Verifies maintenance outbox gauges for populated, empty, and unavailable databases. */
class MaintenanceEventingMetricsTest {
  @Test
  void exposesBacklogAgeAndTerminalCountsWithoutHighCardinalityTags() {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    when(jdbc.queryForObject(anyString(), eq(Long.class))).thenReturn(7L, 2L);
    when(jdbc.queryForObject(anyString(), eq(BigDecimal.class))).thenReturn(BigDecimal.valueOf(11));
    SimpleMeterRegistry registry = new SimpleMeterRegistry();

    new MaintenanceEventingMetrics(registry, jdbc);

    List<Gauge> gauges =
        List.of(
            registry.get("rwms.maintenance.outbox.backlog").gauge(),
            registry.get("rwms.maintenance.outbox.oldest.age.seconds").gauge(),
            registry.get("rwms.maintenance.outbox.terminal").gauge());
    assertThat(gauges).extracting(Gauge::value).containsExactly(7.0, 11.0, 2.0);
    assertThat(gauges).allSatisfy(gauge -> assertThat(gauge.getId().getTags()).isEmpty());
  }

  @Test
  void emptyOutboxProducesZeroGauges() {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    when(jdbc.queryForObject(anyString(), eq(Long.class))).thenReturn(0L);
    when(jdbc.queryForObject(anyString(), eq(BigDecimal.class))).thenReturn(BigDecimal.ZERO);
    SimpleMeterRegistry registry = new SimpleMeterRegistry();

    new MaintenanceEventingMetrics(registry, jdbc);

    assertThat(registry.get("rwms.maintenance.outbox.backlog").gauge().value()).isZero();
    assertThat(registry.get("rwms.maintenance.outbox.oldest.age.seconds").gauge().value())
        .isZero();
    assertThat(registry.get("rwms.maintenance.outbox.terminal").gauge().value()).isZero();
  }

  @Test
  void databaseFailureProducesUnknownRatherThanHealthyZero() {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    DataAccessResourceFailureException failure =
        new DataAccessResourceFailureException("database unavailable");
    when(jdbc.queryForObject(anyString(), eq(Long.class))).thenThrow(failure);
    when(jdbc.queryForObject(anyString(), eq(BigDecimal.class))).thenThrow(failure);
    SimpleMeterRegistry registry = new SimpleMeterRegistry();

    new MaintenanceEventingMetrics(registry, jdbc);

    assertThat(registry.get("rwms.maintenance.outbox.backlog").gauge().value()).isNaN();
    assertThat(registry.get("rwms.maintenance.outbox.oldest.age.seconds").gauge().value())
        .isNaN();
    assertThat(registry.get("rwms.maintenance.outbox.terminal").gauge().value()).isNaN();
  }
}
