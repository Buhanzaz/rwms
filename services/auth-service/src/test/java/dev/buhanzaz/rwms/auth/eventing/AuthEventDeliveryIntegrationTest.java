package dev.buhanzaz.rwms.auth.eventing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import dev.buhanzaz.rwms.auth.domain.AuthSubject;
import dev.buhanzaz.rwms.auth.domain.UserGlobalRole;
import dev.buhanzaz.rwms.auth.repository.AuthSubjectRepository;
import dev.buhanzaz.rwms.platform.kafka.RwmsKafkaOutboundEventPublisher;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.cloud.stream.function.StreamBridge;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.messaging.Message;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.databind.ObjectMapper;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest(properties = {
    "spring.jpa.hibernate.ddl-auto=validate",
    "rwms.platform.kafka.enabled=false"
})
@ActiveProfiles("test")
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class AuthEventDeliveryIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired AuthEventStore eventStore;
    @Autowired AuthInboxProcessor inbox;
    @Autowired AuthOutboxStore outbox;
    @Autowired AuthSanitizedDltStore sanitizedDlt;
    @Autowired AuthSanitizedDltPublisher sanitizedDltPublisher;
    @Autowired AuthProjectionWriter projectionWriter;
    @Autowired AuthEventFactFactory facts;
    @Autowired AuthSubjectRepository subjects;
    @Autowired AuthSubjectProfileStore profiles;
    @Autowired AuthShadowReconciler reconciler;
    @Autowired AuthOutboxProperties outboxProperties;
    @Autowired AuthEventingMetrics metrics;
    @Autowired ObjectMapper objectMapper;

    @Test
    void inboxRejectsForgedOrMutatedEnvelopeAndProcessesOnlyAuthoritativeFacts() throws Exception {
        UUID subjectId = createUserAggregate();
        byte[] authoritative = envelope(subjectId, 0);
        ObjectNode forged = (ObjectNode) objectMapper.readTree(authoritative);
        forged.put("eventId", UUID.randomUUID().toString());

        assertThatThrownBy(() -> inbox.process(
                        objectMapper.writeValueAsBytes(forged),
                        AuthAggregateType.USER_AUTHORIZATION))
                .isInstanceOf(AuthEventValidationException.class);

        ObjectNode mutated = (ObjectNode) objectMapper.readTree(authoritative);
        ((ObjectNode) mutated.get("payload")).put("active", false);
        assertThatThrownBy(() -> inbox.process(
                        objectMapper.writeValueAsBytes(mutated),
                        AuthAggregateType.USER_AUTHORIZATION))
                .isInstanceOf(AuthEventValidationException.class);

        inbox.process(authoritative, AuthAggregateType.USER_AUTHORIZATION);
        inbox.process(authoritative, AuthAggregateType.USER_AUTHORIZATION);
        assertThat(jdbc.queryForObject(
                        """
                        select count(*) from inbox_message
                         where consumer_group='auth-shadow-v1' and status='PROCESSED'
                           and aggregate_id=?
                        """,
                        Integer.class,
                        subjectId.toString()))
                .isEqualTo(1);
    }

    @Test
    void versionGapBlocksUntilGuardedAuthoritativeReplayThenAcceptsNextFact() {
        UUID subjectId = createUserAggregate();
        appendAuthorization(subjectId, UserGlobalRole.WMS_ADMIN);
        appendAuthorization(subjectId, UserGlobalRole.VIEWER);

        inbox.process(envelope(subjectId, 0), AuthAggregateType.USER_AUTHORIZATION);
        inbox.process(envelope(subjectId, 2), AuthAggregateType.USER_AUTHORIZATION);
        assertThat(checkpoint(subjectId)).isEqualTo(new Checkpoint(0, true));

        assertThatThrownBy(() -> reconciler.reconcile(
                        AuthAggregateType.USER_AUTHORIZATION,
                        subjectId,
                        1,
                        "stale operator command",
                        subjectId))
                .isInstanceOfSatisfying(
                        AuthShadowRecoveryException.class,
                        exception -> assertThat(exception.kind())
                                .isEqualTo(AuthShadowRecoveryException.Kind.STALE_OR_INELIGIBLE));

        AuthShadowReconciler.Result result = reconciler.reconcile(
                AuthAggregateType.USER_AUTHORIZATION,
                subjectId,
                0,
                "Verified authoritative local stream after broker gap",
                subjectId);
        assertThat(result.version()).isEqualTo(2);
        assertThat(checkpoint(subjectId)).isEqualTo(new Checkpoint(2, false));
        assertThat(jdbc.queryForObject(
                        """
                        select resolution_reason from version_gap_quarantine
                         where consumer_group='auth-shadow-v1' and aggregate_id=? and status='RESOLVED'
                        """,
                        String.class,
                        subjectId.toString()))
                .isEqualTo("Verified authoritative local stream after broker gap");

        appendAuthorization(subjectId, UserGlobalRole.RENTAL_MANAGER);
        inbox.process(envelope(subjectId, 3), AuthAggregateType.USER_AUTHORIZATION);
        assertThat(checkpoint(subjectId)).isEqualTo(new Checkpoint(3, false));

        inbox.process(envelope(subjectId, 1), AuthAggregateType.USER_AUTHORIZATION);
        assertThat(checkpoint(subjectId)).isEqualTo(new Checkpoint(3, false));
    }

    @Test
    void outboxRelayPreservesAggregateOrderRecoversLeaseAndRequiresBrokerAck() {
        markAllOutboxPublished();
        UUID subjectId = createUserAggregate();
        appendAuthorization(subjectId, UserGlobalRole.WMS_ADMIN);
        RwmsKafkaOutboundEventPublisher publisher = mock(RwmsKafkaOutboundEventPublisher.class);
        AuthOutboxRelay relay = new AuthOutboxRelay(outbox, outboxProperties, publisher, metrics);

        assertThat(relay.relayOne()).isTrue();
        assertThat(jdbc.queryForObject(
                        "select status from outbox_event where aggregate_id=? and aggregate_version=0",
                        String.class,
                        subjectId.toString()))
                .isEqualTo("PUBLISHED");
        assertThat(relay.relayOne()).isTrue();
        verify(publisher).publishSerializedV2(
                AuthAggregateType.USER_AUTHORIZATION.topic(), envelope(subjectId, 0));

        createUserAggregate();
        AuthOutboxStore.Claim abandoned = outbox.claim("crashed-instance", Duration.ofMillis(1)).orElseThrow();
        jdbc.update("update outbox_event set lease_until=clock_timestamp()-interval '1 second' where event_id=?",
                abandoned.eventId());
        assertThat(outbox.claim("restarted-instance", Duration.ofSeconds(30))).isPresent();
    }

    @Test
    void corruptedOutboxWithCanonicalIdentityButWrongPayloadIsNeitherPublishedNorProjected() {
        markAllOutboxPublished();
        UUID subjectId = createUserAggregate();
        UUID eventId = jdbc.queryForObject(
                "select event_id from outbox_event where aggregate_id=?",
                UUID.class,
                subjectId.toString());
        jdbc.execute("alter table outbox_event disable trigger trg_outbox_event_immutable_metadata");
        jdbc.execute("alter table outbox_event disable trigger trg_outbox_event_domain_parity");
        try {
            jdbc.update(
                    """
                    with changed as (
                        select jsonb_set(envelope_body, '{payload,active}', 'false'::jsonb) body
                          from outbox_event where event_id=?
                    )
                    update outbox_event outbox
                       set envelope_body=changed.body,
                           envelope_sha256=encode(
                               sha256(convert_to(changed.body::text, 'UTF8')), 'hex')
                      from changed where outbox.event_id=?
                    """,
                    eventId,
                    eventId);
        } finally {
            jdbc.execute("alter table outbox_event enable trigger trg_outbox_event_domain_parity");
            jdbc.execute("alter table outbox_event enable trigger trg_outbox_event_immutable_metadata");
        }
        byte[] wrongEnvelope = envelope(subjectId, 0);
        RwmsKafkaOutboundEventPublisher publisher = mock(RwmsKafkaOutboundEventPublisher.class);
        AuthOutboxRelay relay = new AuthOutboxRelay(outbox, outboxProperties, publisher, metrics);

        assertThat(relay.relayOne()).isFalse();
        verifyNoInteractions(publisher);
        assertThat(jdbc.queryForObject(
                        "select status from outbox_event where event_id=?", String.class, eventId))
                .isEqualTo("QUARANTINED");
        assertThatThrownBy(() -> inbox.process(
                        wrongEnvelope, AuthAggregateType.USER_AUTHORIZATION))
                .isInstanceOf(AuthEventValidationException.class);
        assertThat(jdbc.queryForObject(
                        """
                        select count(*) from projection_checkpoint
                         where projection_name='auth-kafka-shadow-v1' and aggregate_id=?
                        """,
                        Integer.class,
                        subjectId.toString()))
                .isZero();
        assertThat(jdbc.queryForObject(
                        """
                        select count(*) from inbox_message
                         where consumer_group='auth-shadow-v1' and aggregate_id=?
                        """,
                        Integer.class,
                        subjectId.toString()))
                .isZero();
    }

    @Test
    void outboxFailureIsBoundedThenGuardedRequeueRestoresPendingState() {
        markAllOutboxPublished();
        UUID subjectId = createUserAggregate();
        RwmsKafkaOutboundEventPublisher publisher = mock(RwmsKafkaOutboundEventPublisher.class);
        doThrow(new IllegalStateException("broker unavailable"))
                .when(publisher)
                .publishSerializedV2(anyString(), any(byte[].class));
        AuthOutboxRelay relay = new AuthOutboxRelay(outbox, outboxProperties, publisher, metrics);

        for (int attempt = 0; attempt < 4; attempt++) {
            jdbc.update(
                    "update outbox_event set next_attempt_at=clock_timestamp() where aggregate_id=?",
                    subjectId.toString());
            assertThat(relay.relayOne()).isFalse();
        }
        UUID eventId = jdbc.queryForObject(
                "select event_id from outbox_event where aggregate_id=?", UUID.class, subjectId.toString());
        assertThat(jdbc.queryForObject(
                        "select status from outbox_event where event_id=?", String.class, eventId))
                .isEqualTo("DLT");
        assertThat(outbox.requeue(eventId, 4)).isTrue();
        assertThat(outbox.requeue(eventId, 4)).isFalse();
    }

    @Test
    void sanitizedDltIsIdempotentContainsNoRejectedPayloadAndRelayRequiresAck() {
        byte[] rejected = "password=topsecret bearer aaa.bbb.ccc canary@example.test"
                .getBytes(StandardCharsets.UTF_8);
        sanitizedDltPublisher.publish(
                AuthAggregateType.USER_AUTHORIZATION, rejected, "VALIDATION_REJECTED");
        sanitizedDltPublisher.publish(
                AuthAggregateType.USER_AUTHORIZATION, rejected, "VALIDATION_REJECTED");
        String safeBody = jdbc.queryForObject(
                "select safe_body::text from sanitized_dead_letter where message_sha256=?",
                String.class,
                AuthEventStore.sha256(rejected));
        assertThat(jdbc.queryForObject(
                        "select count(*) from sanitized_dead_letter where message_sha256=?",
                        Integer.class,
                        AuthEventStore.sha256(rejected)))
                .isEqualTo(1);
        assertThat(safeBody.toLowerCase())
                .doesNotContain("topsecret", "bearer", "canary@example.test", "aaa.bbb.ccc");

        StreamBridge bridge = mock(StreamBridge.class);
        when(bridge.send(anyString(), any(Message.class))).thenReturn(true);
        AuthSanitizedDltRelay relay = new AuthSanitizedDltRelay(
                sanitizedDlt, outboxProperties, bridge, metrics);
        assertThat(relay.relayOne()).isTrue();
        assertThat(jdbc.queryForObject(
                        "select status from sanitized_dead_letter", String.class))
                .isEqualTo("PUBLISHED");
    }

    private UUID createUserAggregate() {
        return new TransactionTemplate(transactionManager).execute(status -> {
            String username = "event-user-" + UUID.randomUUID();
            AuthSubject subject = projectionWriter.insertUser(
                    username,
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
    }

    private void markAllOutboxPublished() {
        jdbc.update(
                """
                update outbox_event
                   set status='PUBLISHED', published_at=coalesce(published_at, clock_timestamp()),
                       lease_owner=null, lease_token=null, lease_until=null,
                       dlt_at=null, last_error_code=null
                 where status <> 'PUBLISHED'
                """);
    }

    private void appendAuthorization(UUID subjectId, UserGlobalRole role) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            AuthSubject subject = subjects.findById(subjectId).orElseThrow();
            long version = eventStore.lockCurrentVersion(
                    AuthAggregateType.USER_AUTHORIZATION, subjectId);
            var profile = profiles.require(subjectId);
            AuthSubject updated = projectionWriter.updateUser(
                    subject,
                    profile.username(),
                    profile.firstName(),
                    profile.lastName(),
                    profile.email(),
                    profile.timeZoneId(),
                    role,
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
    }

    private byte[] envelope(UUID aggregateId, long version) {
        String body = jdbc.queryForObject(
                """
                select envelope_body::text from outbox_event
                 where aggregate_id=? and aggregate_version=?
                """,
                String.class,
                aggregateId.toString(),
                version);
        return body.getBytes(StandardCharsets.UTF_8);
    }

    private Checkpoint checkpoint(UUID aggregateId) {
        return jdbc.queryForObject(
                """
                select last_aggregate_version, blocked from consumer_aggregate_checkpoint
                 where consumer_group='auth-shadow-v1' and aggregate_id=?
                """,
                (result, row) -> new Checkpoint(result.getLong(1), result.getBoolean(2)),
                aggregateId.toString());
    }

    private record Checkpoint(long version, boolean blocked) {}
}
