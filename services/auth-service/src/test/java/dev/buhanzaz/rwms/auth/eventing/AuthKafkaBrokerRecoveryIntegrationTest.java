package dev.buhanzaz.rwms.auth.eventing;

import static org.assertj.core.api.Assertions.assertThat;

import com.zaxxer.hikari.HikariDataSource;
import dev.buhanzaz.rwms.auth.domain.AuthSubject;
import dev.buhanzaz.rwms.auth.domain.UserGlobalRole;
import dev.buhanzaz.rwms.auth.repository.AuthSubjectRepository;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.function.Predicate;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.stream.binding.BindingsLifecycleController;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.ToxiproxyContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(properties = {
    "spring.jpa.hibernate.ddl-auto=validate",
    "rwms.platform.kafka.enabled=true",
    "spring.cloud.stream.kafka.binder.auto-create-topics=true",
    "spring.cloud.stream.kafka.binder.configuration.request.timeout.ms=1000",
    "spring.cloud.stream.kafka.binder.configuration.delivery.timeout.ms=3000",
    "spring.cloud.stream.kafka.binder.configuration.max.block.ms=3000",
    "spring.datasource.hikari.connection-timeout=2000",
    "spring.datasource.hikari.validation-timeout=1000",
    "rwms.auth.eventing.outbox.relay-delay=1h",
    "rwms.auth.eventing.outbox.relay-initial-delay=1h"
})
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Import(AuthKafkaConsumerLifecycleRegistry.class)
class AuthKafkaBrokerRecoveryIntegrationTest {

    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine")
            .withNetwork(Network.SHARED)
            .withNetworkAliases("auth-kafka-postgres");

    @Container
    static final ToxiproxyContainer toxiproxy = new ToxiproxyContainer(
                    DockerImageName.parse("ghcr.io/shopify/toxiproxy:2.12.0"))
            .withNetwork(Network.SHARED);

    static ToxiproxyContainer.ContainerProxy databaseProxy;

    @Container
    static final KafkaContainer kafka =
            new KafkaContainer(DockerImageName.parse("apache/kafka:4.3.1"));

    @DynamicPropertySource
    static void kafkaProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.cloud.stream.kafka.binder.brokers", kafka::getBootstrapServers);
        registry.add(
                "spring.datasource.url",
                () -> "jdbc:postgresql://" + toxiproxy.getHost() + ':'
                        + databaseProxy().getProxyPort() + "/test?connectTimeout=2&socketTimeout=2");
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired AuthProjectionWriter projectionWriter;
    @Autowired AuthEventFactFactory facts;
    @Autowired AuthEventStore eventStore;
    @Autowired AuthSubjectRepository subjects;
    @Autowired AuthOutboxRelay outboxRelay;
    @Autowired AuthSanitizedDltPublisher dltPublisher;
    @Autowired AuthSanitizedDltRelay dltRelay;
    @Autowired AuthKafkaConsumerLifecycleRegistry consumerLifecycle;
    @Autowired BindingsLifecycleController bindings;
    @Autowired HikariDataSource dataSource;
    @Autowired ObjectMapper objectMapper;

    @Test
    void brokerOutageLeavesOutboxRecoverableThenPublishesOrderedFactsAndSanitizedDlt() {
        markExistingOutboxPublished();
        UUID subjectId = new TransactionTemplate(transactionManager).execute(status -> {
            AuthSubject subject = projectionWriter.insertUser(
                    "broker-test-" + UUID.randomUUID(),
                    "{noop}test-password",
                    null,
                    null,
                    null,
                    "Europe/Moscow",
                    UserGlobalRole.VIEWER,
                    true);
            eventStore.initialize(
                    AuthAggregateType.USER_AUTHORIZATION,
                    subject.getId(),
                    subject.getVersion(),
                    AuthEventTypes.USER_CREATED,
                    facts.userAuthorization(subject),
                    null);
            return subject.getId();
        });

        kafka.getDockerClient().pauseContainerCmd(kafka.getContainerId()).exec();
        try {
            assertThat(outboxRelay.relayOne()).isFalse();
            OutboxState failedState = outboxState(subjectId, 0);
            assertThat(failedState.status()).isEqualTo("PENDING");
            assertThat(failedState.attemptCount()).isOne();
            assertThat(failedState.leaseToken()).isNull();
            assertThat(failedState.leaseUntil()).isNull();
            assertThat(failedState.lastErrorCode()).isEqualTo("PUBLISH_FAILED");
        } finally {
            kafka.getDockerClient().unpauseContainerCmd(kafka.getContainerId()).exec();
        }
        awaitKafkaRecovery();
        awaitOutboxPublishWithinRemainingBudget(subjectId, 0);
        OutboxState publishedState = outboxState(subjectId, 0);
        assertThat(publishedState.status()).isEqualTo("PUBLISHED");
        assertThat(publishedState.attemptCount()).isBetween(1, 3);

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            AuthSubject subject = subjects.findById(subjectId).orElseThrow();
            long version = eventStore.lockCurrentVersion(
                    AuthAggregateType.USER_AUTHORIZATION, subjectId);
            AuthSubject updated = projectionWriter.updateUser(
                    subject,
                    subject.getUsername(),
                    subject.getFirstName(),
                    subject.getLastName(),
                    subject.getEmail(),
                    subject.getTimeZoneId(),
                    UserGlobalRole.WMS_ADMIN,
                    true,
                    false,
                    false);
            eventStore.append(
                    AuthAggregateType.USER_AUTHORIZATION,
                    subjectId,
                    version,
                    AuthEventTypes.USER_CHANGED,
                    facts.userAuthorization(updated),
                    null);
        });
        assertThat(outboxRelay.relayOne()).isTrue();

        List<ConsumerRecord<String, byte[]>> deliveredFacts = consumeUniqueFacts(
                AuthAggregateType.USER_AUTHORIZATION.topic(),
                2,
                record -> subjectId.toString().equals(record.key()));
        Map<String, ConsumerRecord<String, byte[]>> uniqueFactsByEventId = new LinkedHashMap<>();
        deliveredFacts.forEach(record -> uniqueFactsByEventId.putIfAbsent(
                eventJson(record).path("eventId").asText(), record));
        List<ConsumerRecord<String, byte[]>> facts = List.copyOf(uniqueFactsByEventId.values());
        assertThat(deliveredFacts).hasSizeBetween(2, 5);
        assertThat(facts).hasSize(2);
        assertThat(facts).extracting(ConsumerRecord::key).containsOnly(subjectId.toString());
        assertThat(facts.get(1).offset()).isGreaterThan(facts.get(0).offset());
        assertThat(facts)
                .extracting(record -> eventJson(record).path("aggregateVersion").asLong())
                .containsExactly(0L, 1L);
        assertThat(deliveredFacts)
                .extracting(record -> new String(record.value(), StandardCharsets.UTF_8).toLowerCase())
                .allSatisfy(body -> assertThat(body)
                        .doesNotContain("test-password", "broker-test-", "passwordhash", "bearer "));

        byte[] rejected = "password=secret bearer aaa.bbb.ccc canary@example.test"
                .getBytes(StandardCharsets.UTF_8);
        String rejectedSha256 = AuthEventStore.sha256(rejected);
        dltPublisher.publish(
                AuthAggregateType.USER_AUTHORIZATION, rejected, "VALIDATION_REJECTED");
        assertThat(dltRelay.relayOne()).isTrue();
        String dltBody = new String(
                consume(
                                AuthAggregateType.USER_AUTHORIZATION.sanitizedDltTopic(),
                                1,
                                record -> new String(record.value(), StandardCharsets.UTF_8)
                                        .contains(rejectedSha256))
                        .getFirst()
                        .value(),
                StandardCharsets.UTF_8)
                .toLowerCase();
        assertThat(dltBody).doesNotContain(
                "secret", "bearer", "aaa.bbb.ccc", "canary@example.test", "password=");

        awaitInbox(subjectId, 2);
        assertThat(jdbc.queryForObject(
                        """
                        select count(*) from inbox_message
                         where consumer_group='auth-shadow-v1' and aggregate_id=? and status='PROCESSED'
                        """,
                        Integer.class,
                        subjectId.toString()))
                .isEqualTo(2);
    }

    @Test
    void brokerOutageLeavesSanitizedDltRecoverableOnItsRemainingBoundedAttempts() {
        byte[] rejected = ("password=secret bearer aaa.bbb.ccc dlt-outage-" + UUID.randomUUID()
                        + "@example.test")
                .getBytes(StandardCharsets.UTF_8);
        String messageSha256 = AuthEventStore.sha256(rejected);
        dltPublisher.publish(
                AuthAggregateType.USER_AUTHORIZATION, rejected, "VALIDATION_REJECTED");

        kafka.getDockerClient().pauseContainerCmd(kafka.getContainerId()).exec();
        try {
            assertThat(dltRelay.relayOne()).isFalse();
            SanitizedDltState failed = sanitizedDltState(messageSha256);
            assertThat(failed.status()).isEqualTo("PENDING");
            assertThat(failed.attemptCount()).isOne();
        } finally {
            kafka.getDockerClient().unpauseContainerCmd(kafka.getContainerId()).exec();
        }

        awaitKafkaRecovery();
        awaitSanitizedDltPublishWithinRemainingBudget(messageSha256);
        SanitizedDltState published = sanitizedDltState(messageSha256);
        assertThat(published.status()).isEqualTo("PUBLISHED");
        assertThat(published.attemptCount()).isBetween(1, 3);

        List<ConsumerRecord<String, byte[]>> records = consume(
                AuthAggregateType.USER_AUTHORIZATION.sanitizedDltTopic(),
                1,
                record -> new String(record.value(), StandardCharsets.UTF_8)
                        .contains(messageSha256));
        assertThat(records).isNotEmpty();
        assertThat(new String(records.getFirst().value(), StandardCharsets.UTF_8).toLowerCase())
                .doesNotContain(
                        "secret", "bearer", "aaa.bbb.ccc", "dlt-outage-", "example.test", "password=");
    }

    @Test
    void realBinderDoesNotCommitMalformedRecordWhenDltDatabaseIsDownThenReprocessesAfterRestart()
            throws Exception {
        String bindingName = "authUserAuthorizationEvents-in-0";
        awaitBindingRunning(bindingName);
        awaitConsumerState(AuthAggregateType.USER_AUTHORIZATION, true);
        byte[] malformed = "password=secret bearer aaa.bbb.ccc binder-canary@example.test"
                .getBytes(StandardCharsets.UTF_8);

        databaseProxy().setConnectionCut(true);
        dataSource.getHikariPoolMXBean().softEvictConnections();
        RecordMetadata metadata;
        try {
            metadata = publishRaw(
                    AuthAggregateType.USER_AUTHORIZATION.topic(),
                    "malformed-" + UUID.randomUUID(),
                    malformed);
            awaitConsumerState(AuthAggregateType.USER_AUTHORIZATION, false);
            assertThat(committedOffset(metadata))
                    .as("Fail-closed binder must not commit the malformed source record")
                    .isLessThanOrEqualTo(metadata.offset());
        } finally {
            databaseProxy().setConnectionCut(false);
            dataSource.getHikariPoolMXBean().softEvictConnections();
        }

        awaitDatabaseRecovery();
        bindings.stop(bindingName);
        bindings.start(bindingName);
        awaitBindingRunning(bindingName);
        awaitConsumerState(AuthAggregateType.USER_AUTHORIZATION, true);
        awaitCommittedPast(metadata);

        String messageSha256 = AuthEventStore.sha256(malformed);
        assertThat(jdbc.queryForObject(
                        """
                        select count(*) from sanitized_dead_letter
                         where message_sha256=? and failure_code='VALIDATION_REJECTED'
                        """,
                        Integer.class,
                        messageSha256))
                .isOne();
        assertThat(jdbc.queryForObject(
                        """
                        select safe_body::text from sanitized_dead_letter
                         where message_sha256=? and failure_code='VALIDATION_REJECTED'
                        """,
                        String.class,
                        messageSha256)
                        .toLowerCase())
                .doesNotContain(
                        "secret", "bearer", "aaa.bbb.ccc", "binder-canary@example.test", "password=");
        assertThat(dltRelay.relayOne()).isTrue();
    }

    private void markExistingOutboxPublished() {
        jdbc.update(
                """
                update outbox_event
                   set status='PUBLISHED', published_at=coalesce(published_at, clock_timestamp()),
                       lease_owner=null, lease_token=null, lease_until=null,
                       dlt_at=null, last_error_code=null
                 where status <> 'PUBLISHED'
                """);
    }

    private void awaitOutboxPublishWithinRemainingBudget(
            UUID aggregateId, long aggregateVersion) {
        Instant deadline = Instant.now().plusSeconds(20);
        while (Instant.now().isBefore(deadline)) {
            OutboxState state = outboxState(aggregateId, aggregateVersion);
            if ("PUBLISHED".equals(state.status())) {
                return;
            }
            if ("DLT".equals(state.status())
                    || "QUARANTINED".equals(state.status())
                    || state.attemptCount() >= 4) {
                throw new AssertionError("Auth outbox exhausted the bounded retry budget: " + state);
            }
            if (!"PENDING".equals(state.status())) {
                throw new AssertionError("Auth outbox entered an unexpected retry state: " + state);
            }
            Boolean due = jdbc.queryForObject(
                    """
                    select status='PENDING' and next_attempt_at <= clock_timestamp()
                      from outbox_event where aggregate_id=? and aggregate_version=?
                    """,
                    Boolean.class,
                    aggregateId.toString(),
                    aggregateVersion);
            if (Boolean.TRUE.equals(due)) {
                outboxRelay.relayOne();
                OutboxState afterAttempt = outboxState(aggregateId, aggregateVersion);
                if ("PUBLISHED".equals(afterAttempt.status())) {
                    return;
                }
                if ("DLT".equals(afterAttempt.status())
                        || "QUARANTINED".equals(afterAttempt.status())
                        || afterAttempt.attemptCount() >= 4) {
                    throw new AssertionError(
                            "Auth outbox exhausted the bounded retry budget: " + afterAttempt);
                }
            }
            try {
                Thread.sleep(250);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while waiting for Kafka recovery", exception);
            }
        }
        throw new AssertionError("Auth outbox did not publish within the bounded retry window: "
                + outboxState(aggregateId, aggregateVersion));
    }

    private void awaitSanitizedDltPublishWithinRemainingBudget(String messageSha256) {
        Instant deadline = Instant.now().plusSeconds(20);
        while (Instant.now().isBefore(deadline)) {
            SanitizedDltState state = sanitizedDltState(messageSha256);
            if ("PUBLISHED".equals(state.status())) {
                return;
            }
            if ("FAILED".equals(state.status()) || state.attemptCount() >= 4) {
                throw new AssertionError(
                        "Sanitized auth DLT exhausted the bounded retry budget: " + state);
            }
            if (!"PENDING".equals(state.status())) {
                throw new AssertionError(
                        "Sanitized auth DLT entered an unexpected retry state: " + state);
            }
            if (!state.nextAttemptAt().isAfter(Instant.now())) {
                dltRelay.relayOne();
                SanitizedDltState afterAttempt = sanitizedDltState(messageSha256);
                if ("PUBLISHED".equals(afterAttempt.status())) {
                    return;
                }
                if ("FAILED".equals(afterAttempt.status()) || afterAttempt.attemptCount() >= 4) {
                    throw new AssertionError(
                            "Sanitized auth DLT exhausted the bounded retry budget: " + afterAttempt);
                }
            }
            try {
                Thread.sleep(250);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(
                        "Interrupted while waiting for sanitized DLT recovery", exception);
            }
        }
        throw new AssertionError("Sanitized auth DLT did not publish within the bounded retry window: "
                + sanitizedDltState(messageSha256));
    }

    private OutboxState outboxState(UUID aggregateId, long aggregateVersion) {
        return jdbc.queryForObject(
                """
                select status, attempt_count, next_attempt_at, lease_token,
                       lease_until, last_error_code
                  from outbox_event where aggregate_id=? and aggregate_version=?
                """,
                (result, row) -> new OutboxState(
                        result.getString("status"),
                        result.getInt("attempt_count"),
                        result.getTimestamp("next_attempt_at").toInstant(),
                        result.getObject("lease_token", UUID.class),
                        result.getTimestamp("lease_until") == null
                                ? null
                                : result.getTimestamp("lease_until").toInstant(),
                        result.getString("last_error_code")),
                aggregateId.toString(),
                aggregateVersion);
    }

    private SanitizedDltState sanitizedDltState(String messageSha256) {
        return jdbc.queryForObject(
                """
                select status, attempt_count, next_attempt_at
                  from sanitized_dead_letter where message_sha256=?
                """,
                (result, row) -> new SanitizedDltState(
                        result.getString("status"),
                        result.getInt("attempt_count"),
                        result.getTimestamp("next_attempt_at").toInstant()),
                messageSha256);
    }

    private void awaitKafkaRecovery() {
        Instant deadline = Instant.now().plusSeconds(20);
        while (Instant.now().isBefore(deadline)) {
            try (AdminClient admin = AdminClient.create(Map.of(
                    AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers(),
                    AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, 1_000,
                    AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, 1_000))) {
                if (admin.describeCluster().clusterId().get() != null) {
                    return;
                }
            } catch (Exception ignored) {
                // The broker is still rebuilding network state after container resume.
            }
            try {
                Thread.sleep(250);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while waiting for Kafka recovery", exception);
            }
        }
        throw new AssertionError("Auth Kafka broker did not recover after container resume");
    }

    private void awaitInbox(UUID subjectId, int expected) {
        Instant deadline = Instant.now().plusSeconds(20);
        while (Instant.now().isBefore(deadline)) {
            Integer count = jdbc.queryForObject(
                    """
                    select count(*) from inbox_message
                     where consumer_group='auth-shadow-v1' and aggregate_id=? and status='PROCESSED'
                    """,
                    Integer.class,
                    subjectId.toString());
            if (count != null && count == expected) {
                return;
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while waiting for auth inbox", exception);
            }
        }
        throw new AssertionError("Auth inbox did not process the expected broker records");
    }

    private void awaitBindingRunning(String bindingName) {
        awaitBindingState(bindingName, true);
    }

    private void awaitBindingState(String bindingName, boolean running) {
        Instant deadline = Instant.now().plusSeconds(20);
        while (Instant.now().isBefore(deadline)) {
            var state = bindings.queryState(bindingName);
            if (!state.isEmpty() && state.stream().allMatch(binding -> binding.isRunning() == running)) {
                return;
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while waiting for Kafka binding state", exception);
            }
        }
        throw new AssertionError("Auth Kafka binding did not reach running=" + running);
    }

    private void awaitConsumerState(AuthAggregateType aggregateType, boolean running) {
        Instant deadline = Instant.now().plusSeconds(20);
        while (Instant.now().isBefore(deadline)) {
            if (consumerLifecycle.isRegistered(aggregateType)
                    && consumerLifecycle.isRunning(aggregateType) == running) {
                return;
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while waiting for Kafka consumer state", exception);
            }
        }
        throw new AssertionError("Auth Kafka consumer did not reach running=" + running);
    }

    private void awaitDatabaseRecovery() {
        Instant deadline = Instant.now().plusSeconds(20);
        while (Instant.now().isBefore(deadline)) {
            try {
                if (Integer.valueOf(1).equals(jdbc.queryForObject("select 1", Integer.class))) {
                    return;
                }
            } catch (RuntimeException ignored) {
                // Hikari needs a short interval to discard the broken connection after container resume.
            }
            try {
                Thread.sleep(250);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while waiting for PostgreSQL recovery", exception);
            }
        }
        throw new AssertionError("Auth PostgreSQL did not recover after container resume");
    }

    private void awaitCommittedPast(RecordMetadata metadata) throws Exception {
        Instant deadline = Instant.now().plusSeconds(20);
        while (Instant.now().isBefore(deadline)) {
            if (committedOffset(metadata) > metadata.offset()) {
                return;
            }
            Thread.sleep(200);
        }
        throw new AssertionError("Auth Kafka consumer did not commit the retried malformed record");
    }

    private long committedOffset(RecordMetadata metadata) throws Exception {
        try (AdminClient admin = AdminClient.create(Map.of(
                AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers()))) {
            var offsets = admin.listConsumerGroupOffsets("auth-shadow-v1")
                    .partitionsToOffsetAndMetadata()
                    .get();
            var committed = offsets.get(new TopicPartition(metadata.topic(), metadata.partition()));
            return committed == null ? -1 : committed.offset();
        }
    }

    private static RecordMetadata publishRaw(String topic, String key, byte[] value) throws Exception {
        Properties configuration = new Properties();
        configuration.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        configuration.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        configuration.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class);
        try (KafkaProducer<String, byte[]> producer = new KafkaProducer<>(configuration)) {
            return producer.send(new ProducerRecord<>(topic, key, value)).get();
        }
    }

    private static synchronized ToxiproxyContainer.ContainerProxy databaseProxy() {
        if (databaseProxy == null) {
            databaseProxy = toxiproxy.getProxy(postgres, PostgreSQLContainer.POSTGRESQL_PORT);
        }
        return databaseProxy;
    }

    private JsonNode eventJson(ConsumerRecord<String, byte[]> record) {
        try {
            return objectMapper.readTree(record.value());
        } catch (tools.jackson.core.JacksonException exception) {
            throw new AssertionError("Kafka fact is not valid JSON", exception);
        }
    }

    private List<ConsumerRecord<String, byte[]>> consumeUniqueFacts(
            String topic,
            int expectedUniqueEvents,
            Predicate<ConsumerRecord<String, byte[]>> filter) {
        Map<String, Object> configuration = Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,
                kafka.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG,
                "auth-broker-test-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG,
                "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,
                StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,
                ByteArrayDeserializer.class);
        List<ConsumerRecord<String, byte[]>> records = new ArrayList<>();
        Map<String, Boolean> eventIds = new LinkedHashMap<>();
        try (KafkaConsumer<String, byte[]> consumer = new KafkaConsumer<>(configuration)) {
            consumer.subscribe(List.of(topic));
            Instant deadline = Instant.now().plusSeconds(20);
            while (eventIds.size() < expectedUniqueEvents && Instant.now().isBefore(deadline)) {
                consumer.poll(Duration.ofMillis(500)).forEach(record -> {
                    if (filter.test(record)) {
                        records.add(record);
                        eventIds.put(eventJson(record).path("eventId").asText(), true);
                    }
                });
            }
        }
        return records;
    }

    private record OutboxState(
            String status,
            int attemptCount,
            Instant nextAttemptAt,
            UUID leaseToken,
            Instant leaseUntil,
            String lastErrorCode) {}

    private record SanitizedDltState(String status, int attemptCount, Instant nextAttemptAt) {}

    private static List<ConsumerRecord<String, byte[]>> consume(
            String topic,
            int expected,
            Predicate<ConsumerRecord<String, byte[]>> filter) {
        Map<String, Object> configuration = Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,
                kafka.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG,
                "auth-broker-test-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG,
                "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,
                StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,
                ByteArrayDeserializer.class);
        List<ConsumerRecord<String, byte[]>> records = new ArrayList<>();
        try (KafkaConsumer<String, byte[]> consumer = new KafkaConsumer<>(configuration)) {
            consumer.subscribe(List.of(topic));
            Instant deadline = Instant.now().plusSeconds(20);
            while (records.size() < expected && Instant.now().isBefore(deadline)) {
                consumer.poll(Duration.ofMillis(500)).forEach(record -> {
                    if (filter.test(record)) {
                        records.add(record);
                    }
                });
            }
        }
        return records;
    }
}
