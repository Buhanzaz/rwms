package dev.buhanzaz.rwms.inventory.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceException;
import jakarta.persistence.Query;
import org.junit.jupiter.api.Test;

class InventoryOperationalMetricsTest {

  @Test
  void registersWithoutDatabaseAccessAndReturnsNanWhenTheDatabaseIsUnavailable() {
    EntityManager entityManager = mock(EntityManager.class);
    Query query = mock(Query.class);
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    InventoryOperationalMetrics metrics = new InventoryOperationalMetrics(registry, entityManager);

    metrics.register();

    verifyNoInteractions(entityManager);
    when(entityManager.createNativeQuery(anyString())).thenReturn(query);
    when(query.getSingleResult()).thenThrow(new PersistenceException("database offline"));

    assertThat(gauge(registry, "rwms.inventory.outbox.backlog").value()).isNaN();
    assertThat(gauge(registry, "rwms.inventory.outbox.oldest.age.seconds").value()).isNaN();
  }

  private static Gauge gauge(SimpleMeterRegistry registry, String name) {
    Gauge gauge = registry.find(name).gauge();
    assertThat(gauge).as(name).isNotNull();
    return gauge;
  }
}
