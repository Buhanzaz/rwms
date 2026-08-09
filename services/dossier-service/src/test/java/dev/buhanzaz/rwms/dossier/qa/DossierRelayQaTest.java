package dev.buhanzaz.rwms.dossier.qa;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.buhanzaz.rwms.dossier.domain.DossierDltFailureCode;
import dev.buhanzaz.rwms.dossier.domain.DossierOutboxEvent;
import dev.buhanzaz.rwms.dossier.domain.DossierOutboxState;
import dev.buhanzaz.rwms.dossier.domain.DossierSanitizedDeadLetter;
import dev.buhanzaz.rwms.dossier.eventing.DossierDeadLetterService;
import dev.buhanzaz.rwms.dossier.eventing.DossierDeadLetterRelay;
import dev.buhanzaz.rwms.dossier.eventing.DossierEventHash;
import dev.buhanzaz.rwms.dossier.eventing.DossierOutboundSchemaValidator;
import dev.buhanzaz.rwms.dossier.eventing.DossierOutboxRelay;
import dev.buhanzaz.rwms.dossier.eventing.DossierRelayTransactions;
import dev.buhanzaz.rwms.dossier.repository.DossierOutboxEventRepository;
import dev.buhanzaz.rwms.dossier.repository.DossierSanitizedDeadLetterRepository;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.stream.function.StreamBridge;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.Message;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;

/** Relay unit checks plus PostgreSQL proof that DLT transport and coverage mutations serialize. */
@SpringBootTest(
    properties = {
      "rwms.platform.kafka.enabled=false",
      "spring.cloud.function.definition=",
      "AUTH_ISSUER=http://issuer.invalid",
      "AUTH_AUDIENCE=rwms-services",
      "PANEL_ORIGIN=http://localhost:5173"
    })
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class DossierRelayQaTest {
  private static final OffsetDateTime NOW =
      OffsetDateTime.of(2026, 7, 18, 12, 0, 0, 0, ZoneOffset.UTC);
  private static final UUID CABIN_ID =
      UUID.fromString("10000000-0000-0000-0000-000000000001");
  private static final UUID ACTIVE_GENERATION_ID =
      UUID.fromString("00000000-0000-0000-0000-000000000901");

  @Container
  static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer("postgres:17-alpine").withDatabaseName("dossier_relay_qa");

  @DynamicPropertySource
  static void database(DynamicPropertyRegistry registry) {
    registry.add("DOSSIER_DB_URL", POSTGRES::getJdbcUrl);
    registry.add("DOSSIER_DB_USERNAME", POSTGRES::getUsername);
    registry.add("DOSSIER_DB_PASSWORD", POSTGRES::getPassword);
    registry.add(
        "DOSSIER_CURSOR_SECRET", () -> "dossier-relay-concurrency-secret-at-least-32-bytes");
  }

  @Autowired DossierDeadLetterService persistedDeadLetterService;
  @Autowired DossierRelayTransactions persistedRelayTransactions;
  @Autowired DossierSanitizedDeadLetterRepository persistedDeadLetters;
  @Autowired JdbcTemplate jdbc;
  @Autowired PlatformTransactionManager transactionManager;

  @Test
  void outboxIsMarkedPublishedOnlyAfterPositiveBrokerAcknowledgement() {
    DossierRelayTransactions transactions = mock(DossierRelayTransactions.class);
    StreamBridge bridge = mock(StreamBridge.class);
    DossierOutboxEvent event = outbox(0);
    when(transactions.nextOutbox()).thenReturn(Optional.of(event));
    when(bridge.send(anyString(), any(Message.class))).thenReturn(false);

    new DossierOutboxRelay(
            transactions,
            bridge,
            new tools.jackson.databind.ObjectMapper(),
            mock(DossierOutboundSchemaValidator.class))
        .relay();

    verify(transactions).outboxFailed(event.getEventId());
    verify(transactions, never()).outboxPublished(any());

    DossierRelayTransactions acknowledgedTransactions = mock(DossierRelayTransactions.class);
    StreamBridge acknowledgedBridge = mock(StreamBridge.class);
    when(acknowledgedTransactions.nextOutbox()).thenReturn(Optional.of(event));
    when(acknowledgedBridge.send(anyString(), any(Message.class))).thenReturn(true);

    new DossierOutboxRelay(
            acknowledgedTransactions,
            acknowledgedBridge,
            new tools.jackson.databind.ObjectMapper(),
            mock(DossierOutboundSchemaValidator.class))
        .relay();

    verify(acknowledgedTransactions).outboxPublished(event.getEventId());
    verify(acknowledgedTransactions, never()).outboxFailed(any());
  }

  @Test
  void dueSelectionDoesNotLetALaterCabinVersionOvertakeItsPredecessor() {
    DossierOutboxEventRepository outbox = mock(DossierOutboxEventRepository.class);
    DossierSanitizedDeadLetterRepository deadLetters =
        mock(DossierSanitizedDeadLetterRepository.class);
    DossierOutboxEvent first = outbox(0);
    when(outbox.findPublishableHeads(
            any(), org.mockito.ArgumentMatchers.eq(DossierOutboxState.PUBLISHED), any(), any()))
        .thenReturn(List.of(first));

    Optional<DossierOutboxEvent> selected =
        new DossierRelayTransactions(outbox, deadLetters).nextOutbox();

    assertThat(selected).contains(first);
  }

  @Test
  void outboxAndSanitizedDltUseExactlyThreeRetriesThenBecomeTerminal() {
    DossierOutboxEventRepository outbox = mock(DossierOutboxEventRepository.class);
    DossierSanitizedDeadLetterRepository deadLetters =
        mock(DossierSanitizedDeadLetterRepository.class);
    DossierRelayTransactions transactions = new DossierRelayTransactions(outbox, deadLetters);
    DossierOutboxEvent event = outbox(0);
    when(outbox.findById(event.getEventId())).thenReturn(Optional.of(event));
    DossierSanitizedDeadLetter failure = deadLetter();
    when(deadLetters.findForUpdateById(failure.getId())).thenReturn(Optional.of(failure));

    for (int attempt = 0; attempt < 4; attempt++) {
      transactions.outboxFailed(event.getEventId());
      transactions.deadLetterFailed(failure.getId());
    }

    assertThat(event.getAttemptCount()).isEqualTo(3);
    assertThat(event.getStatus()).isEqualTo(DossierOutboxState.DLT);
    assertThat(failure.getAttemptCount()).isEqualTo(3);
    assertThat(failure.getStatus()).isEqualTo(DossierOutboxState.DLT);
  }

  @Test
  void explicitRecoveryPreservesTheCabinPredecessorFenceAndResetsBoundedAttempts() {
    DossierOutboxEventRepository outbox = mock(DossierOutboxEventRepository.class);
    DossierSanitizedDeadLetterRepository deadLetters =
        mock(DossierSanitizedDeadLetterRepository.class);
    DossierRelayTransactions transactions = new DossierRelayTransactions(outbox, deadLetters);
    DossierOutboxEvent later = outbox(1);
    when(outbox.findById(later.getEventId())).thenReturn(Optional.of(later));
    for (int attempt = 0; attempt < 4; attempt++) transactions.outboxFailed(later.getEventId());
    when(outbox.existsByCabinIdAndAggregateVersionLessThanAndStatusNot(
            CABIN_ID, 1, DossierOutboxState.PUBLISHED))
        .thenReturn(true);

    assertThatThrownBy(() -> transactions.recoverOutbox(later.getEventId()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("DOSSIER_OUTBOX_PREDECESSOR_UNPUBLISHED");
    assertThat(later.getStatus()).isEqualTo(DossierOutboxState.DLT);

    when(outbox.existsByCabinIdAndAggregateVersionLessThanAndStatusNot(
            CABIN_ID, 1, DossierOutboxState.PUBLISHED))
        .thenReturn(false);
    transactions.recoverOutbox(later.getEventId());
    assertThat(later.getStatus()).isEqualTo(DossierOutboxState.RETRY);
    assertThat(later.getAttemptCount()).isZero();

    DossierSanitizedDeadLetter failure = deadLetter();
    when(deadLetters.findForUpdateById(failure.getId())).thenReturn(Optional.of(failure));
    for (int attempt = 0; attempt < 4; attempt++) {
      transactions.deadLetterFailed(failure.getId());
    }
    transactions.recoverDeadLetter(failure.getId());
    assertThat(failure.getStatus()).isEqualTo(DossierOutboxState.RETRY);
    assertThat(failure.getAttemptCount()).isZero();
  }

  @Test
  void relayAndCoverageMutationsSerializeWithoutLosingEitherState() throws Exception {
    UUID sourceEventId = UUID.randomUUID();
    OffsetDateTime resolvedAt = NOW.plusHours(1);
    DossierSanitizedDeadLetter failure =
        DossierSanitizedDeadLetter.pending(
            sourceEventId,
            CABIN_ID,
            "rwms.asset.rental-item.v1",
            0,
            91,
            DossierEventHash.sha256(sourceEventId.toString()),
            DossierEventHash.sha256("concurrent-coverage-" + sourceEventId),
            DossierDltFailureCode.PROCESSING_FAILED,
            ACTIVE_GENERATION_ID,
            CABIN_ID,
            NOW);
    persistedDeadLetters.saveAndFlush(failure);
    CountDownLatch rowLocked = new CountDownLatch(1);
    CountDownLatch releaseRow = new CountDownLatch(1);

    try {
      try (var workers = Executors.newFixedThreadPool(3)) {
        Future<?> blocker =
            workers.submit(
                () ->
                    new TransactionTemplate(transactionManager)
                        .executeWithoutResult(
                            ignored -> {
                              persistedDeadLetters
                                  .findForUpdateById(failure.getId())
                                  .orElseThrow();
                              rowLocked.countDown();
                              awaitLatch(releaseRow, "release sanitized DLT row");
                            }));
        Future<?> coverage;
        Future<?> relay;
        try {
          assertThat(rowLocked.await(10, TimeUnit.SECONDS)).isTrue();
          coverage =
              workers.submit(
                  () ->
                      persistedDeadLetterService.resolveProcessingCoverage(
                          sourceEventId, ACTIVE_GENERATION_ID, resolvedAt));
          relay =
              workers.submit(
                  () -> persistedRelayTransactions.deadLetterPublished(failure.getId()));
          await(
              "coverage and relay transactions wait for the same row",
              this::twoDltWaitersPresent);
        } finally {
          releaseRow.countDown();
        }

        blocker.get(10, TimeUnit.SECONDS);
        coverage.get(10, TimeUnit.SECONDS);
        relay.get(10, TimeUnit.SECONDS);
      }

      assertThat(persistedDeadLetters.findById(failure.getId()))
          .get()
          .satisfies(
              persisted -> {
                assertThat(persisted.getStatus()).isEqualTo(DossierOutboxState.PUBLISHED);
                assertThat(persisted.getPublishedAt()).isNotNull();
                assertThat(persisted.getCoverageGenerationId()).isEqualTo(ACTIVE_GENERATION_ID);
                assertThat(persisted.getCoverageSubjectCabinId()).isEqualTo(CABIN_ID);
                assertThat(persisted.getCoverageResolvedAt()).isEqualTo(resolvedAt);
                assertThat(persisted.getRowVersion()).isGreaterThanOrEqualTo(2);
              });
    } finally {
      releaseRow.countDown();
      persistedDeadLetters.deleteById(failure.getId());
    }
  }

  @Test
  void deadLetterPayloadIsSanitizedAndUsesStableAggregateKey() {
    DossierRelayTransactions transactions = mock(DossierRelayTransactions.class);
    StreamBridge bridge = mock(StreamBridge.class);
    DossierSanitizedDeadLetter failure = deadLetter();
    when(transactions.nextDeadLetter()).thenReturn(Optional.of(failure));
    when(bridge.send(anyString(), any(Message.class))).thenReturn(true);
    DossierDeadLetterRelay relay =
        new DossierDeadLetterRelay(
            transactions, bridge, new ObjectMapper(), new DossierOutboundSchemaValidator());

    relay.relay();

    @SuppressWarnings("rawtypes")
    ArgumentCaptor<Message> message = ArgumentCaptor.forClass(Message.class);
    verify(bridge).send(anyString(), message.capture());
    byte[] payload = (byte[]) message.getValue().getPayload();
    String json = new String(payload, StandardCharsets.UTF_8);
    assertThat(json)
        .contains("\"failureCode\":\"INVALID_PAYLOAD\"")
        .contains("\"messageSha256\"")
        .doesNotContain(
            "raw",
            "email",
            "authorization",
            "objectKey",
            "coverageGenerationId",
            "coverageSubjectCabinId",
            "coverageResolvedAt");
    assertThat((byte[]) message.getValue().getHeaders().get(KafkaHeaders.KEY))
        .isEqualTo(failure.getSourceAggregateId().toString().getBytes(StandardCharsets.UTF_8));
    verify(transactions).deadLetterPublished(failure.getId());
  }

  @Test
  void outboxRecanonicalizesJsonbAndGuardsTheStoredHashBeforeSend() {
    DossierRelayTransactions transactions = mock(DossierRelayTransactions.class);
    StreamBridge bridge = mock(StreamBridge.class);
    DossierOutboundSchemaValidator schemas = mock(DossierOutboundSchemaValidator.class);
    String canonical = "{\"a\":2,\"z\":1}";
    DossierOutboxEvent event =
        DossierOutboxEvent.pending(
            UUID.randomUUID(),
            CABIN_ID,
            0,
            UUID.randomUUID(),
            DossierEventHash.sha256(canonical),
            "{\"z\":1,\"a\":2}",
            NOW);
    when(transactions.nextOutbox()).thenReturn(Optional.of(event));
    when(bridge.send(anyString(), any(Message.class))).thenReturn(true);

    new DossierOutboxRelay(
            transactions, bridge, new tools.jackson.databind.ObjectMapper(), schemas)
        .relay();

    @SuppressWarnings("rawtypes")
    ArgumentCaptor<Message> message = ArgumentCaptor.forClass(Message.class);
    verify(bridge).send(anyString(), message.capture());
    assertThat((byte[]) message.getValue().getPayload())
        .isEqualTo(canonical.getBytes(StandardCharsets.UTF_8));
    verify(schemas).activity(canonical);
    verify(transactions).outboxPublished(event.getEventId());
  }

  @Test
  void outboxHashMismatchNeverReachesTheBroker() {
    DossierRelayTransactions transactions = mock(DossierRelayTransactions.class);
    StreamBridge bridge = mock(StreamBridge.class);
    DossierOutboxEvent event =
        DossierOutboxEvent.pending(
            UUID.randomUUID(),
            CABIN_ID,
            0,
            UUID.randomUUID(),
            "a".repeat(64),
            "{}",
            NOW);
    when(transactions.nextOutbox()).thenReturn(Optional.of(event));

    new DossierOutboxRelay(
            transactions,
            bridge,
            new tools.jackson.databind.ObjectMapper(),
            mock(DossierOutboundSchemaValidator.class))
        .relay();

    verify(bridge, never()).send(anyString(), any(Message.class));
    verify(transactions).outboxFailed(event.getEventId());
  }

  private boolean twoDltWaitersPresent() {
    Integer count =
        jdbc.queryForObject(
            """
            select count(*)
              from pg_stat_activity
             where datname = current_database()
               and pid <> pg_backend_pid()
               and wait_event_type = 'Lock'
               and query like '%dossier_sanitized_dead_letter%'
            """,
            Integer.class);
    return count != null && count >= 2;
  }

  private static void await(String description, BooleanSupplier condition) {
    long deadline = System.nanoTime() + java.time.Duration.ofSeconds(10).toNanos();
    while (System.nanoTime() < deadline) {
      if (condition.getAsBoolean()) return;
      try {
        Thread.sleep(50);
      } catch (InterruptedException exception) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException("Interrupted while waiting for " + description, exception);
      }
    }
    throw new AssertionError("Timed out waiting for " + description);
  }

  private static void awaitLatch(CountDownLatch latch, String description) {
    try {
      if (!latch.await(20, TimeUnit.SECONDS)) {
        throw new IllegalStateException("Timed out waiting for " + description);
      }
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Interrupted while waiting for " + description, exception);
    }
  }

  private static DossierOutboxEvent outbox(long version) {
    String payload = "{}";
    return DossierOutboxEvent.pending(
        UUID.nameUUIDFromBytes(("outbox-" + version).getBytes(StandardCharsets.UTF_8)),
        CABIN_ID,
        version,
        UUID.nameUUIDFromBytes(("source-" + version).getBytes(StandardCharsets.UTF_8)),
        DossierEventHash.sha256(payload),
        payload,
        NOW.plusSeconds(version));
  }

  private static DossierSanitizedDeadLetter deadLetter() {
    return DossierSanitizedDeadLetter.pending(
        UUID.fromString("30000000-0000-0000-0000-000000000001"),
        UUID.fromString("40000000-0000-0000-0000-000000000001"),
        "rwms.asset.rental-item.v1",
        2,
        19,
        "b".repeat(64),
        "c".repeat(64),
        DossierDltFailureCode.INVALID_PAYLOAD,
        null,
        null,
        NOW);
  }
}
