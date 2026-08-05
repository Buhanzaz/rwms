package dev.buhanzaz.rwms.asset.eventing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class AssetEventingMetricsTest {
  @Test
  void exposesEvidenceGrowthBacklogAgeAndPerTableStorage() {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    when(jdbc.queryForObject(anyString(), eq(Long.class))).thenReturn(7L);
    when(jdbc.queryForObject(anyString(), eq(BigDecimal.class))).thenReturn(BigDecimal.valueOf(11));
    SimpleMeterRegistry registry = new SimpleMeterRegistry();

    new AssetEventingMetrics(registry, jdbc);

    assertThat(registry.get("rwms.asset.event_store.rows").gauge().value()).isEqualTo(7);
    assertThat(registry.get("rwms.asset.outbox.oldest.age.seconds").gauge().value()).isEqualTo(11);
    assertThat(registry.find("rwms.asset.storage.bytes").gauges()).hasSize(5);
  }
}
