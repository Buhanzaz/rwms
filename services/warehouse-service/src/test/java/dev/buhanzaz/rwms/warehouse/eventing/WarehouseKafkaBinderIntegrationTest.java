package dev.buhanzaz.rwms.warehouse.eventing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.buhanzaz.rwms.platform.autoconfigure.RwmsKafkaAutoConfiguration;
import dev.buhanzaz.rwms.platform.contracts.CorrelationContext;
import dev.buhanzaz.rwms.platform.contracts.DomainEventEnvelopeV2;
import dev.buhanzaz.rwms.platform.kafka.RwmsKafkaOutboundEventPublisher;
import dev.buhanzaz.rwms.platform.kafka.RwmsKafkaHeaders;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.security.oauth2.server.resource.autoconfigure.OAuth2ResourceServerAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.stream.binder.test.OutputDestination;
import org.springframework.cloud.stream.binder.test.TestChannelBinderConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.Message;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(
    classes = WarehouseKafkaBinderIntegrationTest.TestApplication.class,
    properties = {
      "rwms.platform.kafka.enabled=true",
      "rwms.platform.kafka.destinations[0]=rwms.warehouse.warehouse.v1",
      "spring.security.oauth2.resourceserver.jwt.issuer-uri=http://issuer.test"
    })
@Import(TestChannelBinderConfiguration.class)
class WarehouseKafkaBinderIntegrationTest {
  private static final String TOPIC = "rwms.warehouse.warehouse.v1";

  @Autowired RwmsKafkaOutboundEventPublisher publisher;
  @Autowired OutputDestination output;

  @Test
  void emitsTheApprovedEnvelopeWithWarehouseUuidAsKafkaKey() {
    UUID warehouseId = UUID.fromString("00000000-0000-0000-0000-000000000001");
    publisher.publish(TOPIC, envelope(warehouseId, 3, WarehouseEventType.CHANGED, payload(warehouseId)));

    Message<byte[]> received = output.receive(2_000, TOPIC);
    assertThat(received).isNotNull();
    String body = new String(received.getPayload(), StandardCharsets.UTF_8);
    assertThat(body)
        .contains("\"eventType\":\"warehouse.warehouse.changed.v1\"")
        .contains("\"warehouseId\":\"" + warehouseId + "\"")
        .doesNotContain("\"name\"", "\"city\"", "\"address\"");
    assertThat((byte[]) received.getHeaders().get(KafkaHeaders.KEY))
        .containsExactly(warehouseId.toString().getBytes(StandardCharsets.UTF_8));
    assertThat(received.getHeaders().get(RwmsKafkaHeaders.AGGREGATE_VERSION)).isEqualTo(3L);
  }

  @Test
  void rejectsPayloadsThatCouldLeakWarehouseMetadata() {
    UUID warehouseId = UUID.randomUUID();
    Map<String, Object> unsafe = payload(warehouseId);
    unsafe.put("name", "Never publish me");

    assertThatThrownBy(
            () -> publisher.publish(TOPIC, envelope(warehouseId, 0, WarehouseEventType.CREATED, unsafe)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("safety");
  }

  private static DomainEventEnvelopeV2<Map<String, Object>> envelope(
      UUID warehouseId, long version, WarehouseEventType eventType, Map<String, Object> payload) {
    return new DomainEventEnvelopeV2<>(
        2,
        UUID.randomUUID(),
        eventType.value(),
        1,
        null,
        Instant.parse("2026-07-14T10:00:00Z").plusSeconds(version),
        "warehouse-service",
        "WAREHOUSE",
        warehouseId.toString(),
        version,
        new CorrelationContext(UUID.randomUUID(), null),
        null,
        payload);
  }

  private static Map<String, Object> payload(UUID warehouseId) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("warehouseId", warehouseId);
    payload.put("code", "WH_00000000000000000000000000000001");
    payload.put("timeZone", "Europe/Moscow");
    payload.put("active", true);
    payload.put("sortOrder", null);
    return payload;
  }

  @SpringBootConfiguration
  @EnableAutoConfiguration(
      exclude = {
        DataSourceAutoConfiguration.class,
        FlywayAutoConfiguration.class,
        HibernateJpaAutoConfiguration.class,
        OAuth2ResourceServerAutoConfiguration.class
      })
  @ImportAutoConfiguration(RwmsKafkaAutoConfiguration.class)
  @Import(WarehousePayloadSafetyValidatorsConfiguration.class)
  static class TestApplication {
    @Bean
    WarehouseEventPayloadPolicy warehouseEventPayloadPolicy(ObjectMapper objectMapper) {
      return new WarehouseEventPayloadPolicy(objectMapper);
    }
  }
}
