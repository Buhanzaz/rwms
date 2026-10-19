package dev.buhanzaz.rwms.platform.kafka;

import static org.assertj.core.api.Assertions.assertThat;

import dev.buhanzaz.rwms.platform.contracts.CorrelationContext;
import dev.buhanzaz.rwms.platform.contracts.DomainEventEnvelopeV2;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(
        classes = RwmsKafkaBrokerIntegrationTest.TestApplication.class,
        properties = {
            "rwms.platform.kafka.enabled=true",
            "rwms.platform.kafka.destinations[0]=rwms.test.aggregate.v1",
            "spring.cloud.stream.kafka.binder.auto-create-topics=true"
        })
class RwmsKafkaBrokerIntegrationTest {

    private static final String DESTINATION = "rwms.test.aggregate.v1";

    @Container
    private static final KafkaContainer KAFKA =
            new KafkaContainer(DockerImageName.parse("apache/kafka:4.3.1"));

    @Autowired
    private RwmsKafkaOutboundEventPublisher publisher;

    @DynamicPropertySource
    static void kafkaProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.cloud.stream.kafka.binder.brokers", KAFKA::getBootstrapServers);
    }

    @Test
    void receivesBrokerAcknowledgementAndPreservesAggregateKeyOrder() {
        publisher.publish(DESTINATION, envelope(1));
        publisher.publish(DESTINATION, envelope(2));

        List<ConsumerRecord<String, byte[]>> records = consumeTwoRecords();
        assertThat(records).extracting(ConsumerRecord::key).containsOnly("aggregate-1");
        assertThat(records)
                .extracting(record -> new String(record.value(), StandardCharsets.UTF_8))
                .allSatisfy(value -> assertThat(value).contains("\"envelopeVersion\":2"));
        assertThat(records.get(0).partition()).isEqualTo(records.get(1).partition());
        assertThat(records.get(1).offset()).isEqualTo(records.get(0).offset() + 1);
    }

    private static List<ConsumerRecord<String, byte[]>> consumeTwoRecords() {
        Map<String, Object> configuration = Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,
                KAFKA.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG,
                "rwms-starter-broker-test-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG,
                "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,
                StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,
                ByteArrayDeserializer.class);
        List<ConsumerRecord<String, byte[]>> records = new ArrayList<>();
        try (KafkaConsumer<String, byte[]> consumer = new KafkaConsumer<>(configuration)) {
            consumer.subscribe(List.of(DESTINATION));
            Instant deadline = Instant.now().plusSeconds(20);
            while (records.size() < 2 && Instant.now().isBefore(deadline)) {
                consumer.poll(Duration.ofMillis(500)).forEach(records::add);
            }
        }
        assertThat(records).hasSize(2);
        return records;
    }

    private static DomainEventEnvelopeV2<Map<String, String>> envelope(long version) {
        return new DomainEventEnvelopeV2<>(
                2,
                UUID.randomUUID(),
                "test.aggregate.changed.v1",
                1,
                null,
                Instant.parse("2026-07-13T09:00:00Z").plusSeconds(version),
                "starter-test",
                "AGGREGATE",
                "aggregate-1",
                version,
                new CorrelationContext(UUID.fromString("30000000-0000-0000-0000-000000000003"), null),
                null,
                Map.of("sequence", Long.toString(version)));
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @ImportAutoConfiguration({
        dev.buhanzaz.rwms.platform.autoconfigure.RwmsKafkaAutoConfiguration.class,
        dev.buhanzaz.rwms.platform.autoconfigure.RwmsKafkaBinderAutoConfiguration.class
    })
    static class TestApplication {

        @Bean
        RwmsKafkaPayloadSafetyValidator changedEventPayloadValidator() {
            return new RwmsKafkaPayloadSafetyValidator() {
                @Override
                public String eventType() {
                    return "test.aggregate.changed.v1";
                }

                @Override
                public void validate(tools.jackson.databind.JsonNode payload) {
                    // Explicit test-only schema allow-list.
                }
            };
        }
    }
}
