package dev.buhanzaz.rwms.warehouse.eventing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/** Verifies the read-only warehouse outbox gauges required before backlog draining. */
class WarehouseEventingMetricsTest {
  @Test
  void exposesBacklogOldestPendingAgeAndTerminalRecoveryWork() {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    when(jdbc.queryForObject(anyString(), eq(Long.class))).thenReturn(7L);
    when(jdbc.queryForObject(anyString(), eq(BigDecimal.class))).thenReturn(BigDecimal.valueOf(11));
    SimpleMeterRegistry registry = new SimpleMeterRegistry();

    new WarehouseEventingMetrics(registry, jdbc);

    assertThat(registry.get("rwms.warehouse.outbox.backlog").gauge().value()).isEqualTo(7);
    assertThat(registry.get("rwms.warehouse.outbox.oldest.age.seconds").gauge().value())
        .isEqualTo(11);
    assertThat(registry.get("rwms.warehouse.outbox.terminal").gauge().value()).isEqualTo(7);
  }
}
