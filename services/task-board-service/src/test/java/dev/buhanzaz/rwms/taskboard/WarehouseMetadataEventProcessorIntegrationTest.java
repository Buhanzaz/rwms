package dev.buhanzaz.rwms.taskboard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.buhanzaz.rwms.taskboard.api.ApiModels.WorkQueueDto;
import dev.buhanzaz.rwms.taskboard.domain.QueueType;
import dev.buhanzaz.rwms.taskboard.eventing.WarehouseMetadataEventProcessor;
import dev.buhanzaz.rwms.taskboard.repository.WarehouseMetadataRepository;
import dev.buhanzaz.rwms.taskboard.service.RegistryService;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class WarehouseMetadataEventProcessorIntegrationTest extends PostgresIntegrationTestSupport {
  private static final UUID WAREHOUSE_ID =
      UUID.fromString("00000000-0000-0000-0000-000000000301");
  private static final UUID EVENT_ID =
      UUID.fromString("00000000-0000-0000-0000-000000000302");

  @Autowired WarehouseMetadataEventProcessor processor;
  @Autowired WarehouseMetadataRepository warehouses;
  @Autowired RegistryService registry;
  @Autowired TestWarehouseTimeZoneGateway timeZones;
  @Autowired JdbcTemplate jdbc;

  @BeforeEach
  void clean() {
    cleanTaskBoardFixtures(jdbc);
    timeZones.reset();
  }

  @Test
  void projectsCanonicalWarehouseTimeZoneAndDeduplicatesAtLeastOnceDelivery() {
    byte[] event = event(EVENT_ID, 3, "Europe/Moscow", true);

    processor.process(event);
    processor.process(event);

    assertThat(warehouses.findById(WAREHOUSE_ID))
        .get()
        .satisfies(
            warehouse -> {
              assertThat(warehouse.getSourceVersion()).isEqualTo(3);
              assertThat(warehouse.getTimeZone()).isEqualTo("Europe/Moscow");
              assertThat(warehouse.isActive()).isTrue();
            });
    assertThat(
            jdbc.queryForObject("select count(*) from warehouse_event_inbox", Integer.class))
        .isOne();
    assertThat(timeZones.invalidatedWarehouses()).containsExactly(WAREHOUSE_ID);
  }

  @Test
  void rejectsEventIdReuseAndDoesNotRegressAFullSnapshot() {
    processor.process(event(EVENT_ID, 5, "Asia/Yekaterinburg", true));
    processor.process(event(UUID.randomUUID(), 4, "Europe/Moscow", true));

    assertThat(warehouses.findById(WAREHOUSE_ID).orElseThrow().getTimeZone())
        .isEqualTo("Asia/Yekaterinburg");
    assertThatThrownBy(
            () -> processor.process(event(EVENT_ID, 6, "Europe/Samara", true)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("eventId");
  }

  @Test
  void activeWarehouseEventMaterializesTheWholeGeneralQueueStandard() {
    var external =
        registry.createQueueDefinition(
            QueueRegistryTestFixtures.globalDefinition(
                0L, "Внешний ремонт", null, QueueType.REPAIR));
    var holding =
        registry.createQueueDefinition(
            QueueRegistryTestFixtures.globalDefinition(
                0L, "Ожидание", null, QueueType.HOLDING));

    processor.process(event(EVENT_ID, 1, "Europe/Moscow", true));

    assertThat(registry.listQueueDefinitions())
        .extracting(definition -> definition.id())
        .containsExactlyInAnyOrder(external.id(), holding.id());
    assertThat(registry.listQueues(WAREHOUSE_ID))
        .extracting(WorkQueueDto::definitionId)
        .containsExactly(external.id(), holding.id());

    processor.process(event(UUID.randomUUID(), 2, "Europe/Moscow", true));
    assertThat(registry.listQueues(WAREHOUSE_ID))
        .extracting(WorkQueueDto::definitionId)
        .containsExactly(external.id(), holding.id());
  }

  private byte[] event(
      UUID eventId, long aggregateVersion, String timeZone, boolean active) {
    String body =
        """
        {
          "envelopeVersion": 2,
          "eventId": "%s",
          "eventType": "warehouse.warehouse.changed.v1",
          "eventVersion": 1,
          "occurredAt": "2026-07-30T09:00:00Z",
          "recordedAt": "2026-07-30T09:00:01Z",
          "producer": "warehouse-service",
          "aggregateType": "WAREHOUSE",
          "aggregateId": "%s",
          "aggregateVersion": %d,
          "correlation": {
            "correlationId": "00000000-0000-0000-0000-000000000303",
            "causationId": null
          },
          "actorRef": null,
          "payload": {
            "warehouseId": "%s",
            "timeZone": "%s",
            "active": %s,
            "sortOrder": 1
          }
        }
        """
            .formatted(
                eventId, WAREHOUSE_ID, aggregateVersion, WAREHOUSE_ID, timeZone, active);
    return body.getBytes(StandardCharsets.UTF_8);
  }
}
