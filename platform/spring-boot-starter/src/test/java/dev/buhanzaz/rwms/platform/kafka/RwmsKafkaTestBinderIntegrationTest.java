package dev.buhanzaz.rwms.platform.kafka;

import static org.assertj.core.api.Assertions.assertThat;

import dev.buhanzaz.rwms.platform.contracts.CorrelationContext;
import dev.buhanzaz.rwms.platform.contracts.DomainEventEnvelopeV2;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.stream.binder.test.OutputDestination;
import org.springframework.cloud.stream.binder.test.TestChannelBinderConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.Message;

@SpringBootTest(
        classes = RwmsKafkaTestBinderIntegrationTest.TestApplication.class,
        properties = {
            "rwms.platform.kafka.enabled=true",
            "rwms.platform.kafka.destinations[0]=rwms.task-board.board-task.v1"
        })
@Import(TestChannelBinderConfiguration.class)
class RwmsKafkaTestBinderIntegrationTest {

    private static final String DESTINATION = "rwms.task-board.board-task.v1";

    @Autowired
    private RwmsKafkaOutboundEventPublisher publisher;

    @Autowired
    private OutputDestination output;

    @Test
    void preservesAggregateOrderAndTechnicalHeadersThroughCloudStreamBinder() {
        DomainEventEnvelopeV2<Map<String, String>> first = envelope(1, "50000000-0000-0000-0000-000000000001");
        DomainEventEnvelopeV2<Map<String, String>> second = envelope(2, "50000000-0000-0000-0000-000000000002");

        publisher.publish(DESTINATION, first);
        publisher.publish(DESTINATION, second);

        Message<byte[]> firstReceived = output.receive(2_000, DESTINATION);
        Message<byte[]> secondReceived = output.receive(2_000, DESTINATION);
        assertThat(new String(firstReceived.getPayload(), StandardCharsets.UTF_8)).contains("\"sequence\":\"first\"");
        assertThat(new String(secondReceived.getPayload(), StandardCharsets.UTF_8)).contains("\"sequence\":\"second\"");
        assertThat((byte[]) firstReceived.getHeaders().get(KafkaHeaders.KEY))
                .containsExactly(first.aggregateId().getBytes(StandardCharsets.UTF_8));
        assertThat(secondReceived.getHeaders().get(RwmsKafkaHeaders.AGGREGATE_VERSION)).isEqualTo(2L);
    }

    private static DomainEventEnvelopeV2<Map<String, String>> envelope(long version, String eventId) {
        return new DomainEventEnvelopeV2<>(
                2,
                UUID.fromString(eventId),
                "task-board.board-task.changed.v1",
                1,
                null,
                Instant.parse("2026-07-13T09:00:00Z").plusSeconds(version),
                "task-board-service",
                "BOARD_TASK",
                "20000000-0000-0000-0000-000000000002",
                version,
                new CorrelationContext(UUID.fromString("30000000-0000-0000-0000-000000000003"), null),
                null,
                Map.of("sequence", version == 1 ? "first" : "second"));
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @ImportAutoConfiguration(dev.buhanzaz.rwms.platform.autoconfigure.RwmsKafkaAutoConfiguration.class)
    static class TestApplication {

        @Bean
        RwmsKafkaPayloadSafetyValidator changedEventPayloadValidator() {
            return allowEventType("task-board.board-task.changed.v1");
        }
    }

    private static RwmsKafkaPayloadSafetyValidator allowEventType(String eventType) {
        return new RwmsKafkaPayloadSafetyValidator() {
            @Override
            public String eventType() {
                return eventType;
            }

            @Override
            public void validate(tools.jackson.databind.JsonNode payload) {
                // Explicit test-only schema allow-list.
            }
        };
    }
}
