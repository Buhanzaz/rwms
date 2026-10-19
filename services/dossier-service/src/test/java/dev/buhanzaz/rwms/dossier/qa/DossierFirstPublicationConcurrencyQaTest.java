package dev.buhanzaz.rwms.dossier.qa;

import static org.assertj.core.api.Assertions.assertThat;

import dev.buhanzaz.rwms.dossier.domain.DossierActiveGeneration;
import dev.buhanzaz.rwms.dossier.domain.DossierGenerationState;
import dev.buhanzaz.rwms.dossier.domain.DossierInboxDecision;
import dev.buhanzaz.rwms.dossier.eventing.DossierEnvelopeValidator;
import dev.buhanzaz.rwms.dossier.eventing.DossierValidatedEvent;
import dev.buhanzaz.rwms.dossier.repository.DossierActiveGenerationRepository;
import dev.buhanzaz.rwms.dossier.repository.DossierCabinPublicationHeadRepository;
import dev.buhanzaz.rwms.dossier.repository.DossierInboxRepository;
import dev.buhanzaz.rwms.dossier.repository.DossierOutboxEventRepository;
import dev.buhanzaz.rwms.dossier.repository.DossierProjectionGenerationRepository;
import dev.buhanzaz.rwms.dossier.service.DossierInboxProcessor;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** PostgreSQL proof that concurrent first cabin publications preserve the JPA/CAS fences. */
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
class DossierFirstPublicationConcurrencyQaTest {
  private static final UUID CABIN_ID =
      UUID.fromString("71000000-0000-0000-0000-000000000001");
  private static final UUID WAREHOUSE_ID =
      UUID.fromString("72000000-0000-0000-0000-000000000001");

  @Container
  static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer("postgres:17-alpine")
          .withDatabaseName("dossier_stage9_first_publication");

  @DynamicPropertySource
  static void database(DynamicPropertyRegistry registry) {
    registry.add("DOSSIER_DB_URL", POSTGRES::getJdbcUrl);
    registry.add("DOSSIER_DB_USERNAME", POSTGRES::getUsername);
    registry.add("DOSSIER_DB_PASSWORD", POSTGRES::getPassword);
    registry.add(
        "DOSSIER_CURSOR_SECRET", () -> "stage-9-concurrency-cursor-secret-at-least-32-bytes");
  }

  @Autowired DossierEnvelopeValidator validator;
  @Autowired DossierInboxProcessor processor;
  @Autowired DossierCabinPublicationHeadRepository publicationHeads;
  @Autowired DossierOutboxEventRepository outbox;
  @Autowired DossierInboxRepository inboxes;
  @Autowired DossierActiveGenerationRepository activeGeneration;
  @Autowired DossierProjectionGenerationRepository generations;
  @Autowired JdbcTemplate jdbc;
  @Autowired PlatformTransactionManager transactionManager;

  @Test
  void concurrentFirstPublicationsKeepEveryFactAndExactlyOneActiveGeneration() throws Exception {
    UUID estimateId = UUID.randomUUID();
    UUID repairId = UUID.randomUUID();
    DossierValidatedEvent estimate = estimate(estimateId, CABIN_ID, 0, 0);
    DossierValidatedEvent repair = repair(repairId, CABIN_ID, 0);
    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch start = new CountDownLatch(1);

    try (var workers = Executors.newFixedThreadPool(2)) {
      Future<DossierInboxProcessor.Outcome> first =
          workers.submit(() -> processAtOnce(estimate, ready, start));
      Future<DossierInboxProcessor.Outcome> second =
          workers.submit(() -> processAtOnce(repair, ready, start));
      assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
      start.countDown();

      assertThat(List.of(first.get(30, TimeUnit.SECONDS), second.get(30, TimeUnit.SECONDS)))
          .containsOnly(DossierInboxProcessor.Outcome.PROCESSED);
    }

    assertThat(inboxes.findAll())
        .hasSize(2)
        .allSatisfy(value -> assertThat(value.getDecision()).isEqualTo(DossierInboxDecision.PROCESSED));
    assertThat(publicationHeads.findByCabinId(CABIN_ID)).get()
        .satisfies(head -> assertThat(head.getPublishedVersion()).isEqualTo(1));
    assertThat(outbox.findAll())
        .hasSize(2)
        .extracting(value -> value.getAggregateVersion())
        .containsExactlyInAnyOrder(0L, 1L);
    UUID activeId =
        activeGeneration
            .findByPointerName(DossierActiveGeneration.POINTER_NAME)
            .orElseThrow()
            .getGenerationId();
    assertThat(generations.findAll())
        .filteredOn(value -> value.getState() == DossierGenerationState.ACTIVE)
        .singleElement()
        .satisfies(value -> assertThat(value.getId()).isEqualTo(activeId));
  }

  @Test
  void cabinHeadContentionDoesNotBlockAnIndependentCabin() throws Exception {
    UUID blockedCabin = UUID.fromString("74000000-0000-0000-0000-000000000001");
    UUID freeCabin = UUID.fromString("74000000-0000-0000-0000-000000000002");
    UUID blockedEstimate = UUID.randomUUID();
    UUID freeRepair = UUID.randomUUID();
    assertThat(processor.process(estimate(blockedEstimate, blockedCabin, 0, 10)))
        .isEqualTo(DossierInboxProcessor.Outcome.PROCESSED);

    CountDownLatch headLocked = new CountDownLatch(1);
    CountDownLatch releaseHead = new CountDownLatch(1);
    try (var workers = Executors.newFixedThreadPool(3)) {
      Future<?> locker =
          workers.submit(
              () ->
                  new TransactionTemplate(transactionManager)
                      .executeWithoutResult(
                          ignored -> {
                            publicationHeads.findForUpdateByCabinId(blockedCabin).orElseThrow();
                            headLocked.countDown();
                            awaitLatch(releaseHead, "release blocked cabin head");
                          }));
      assertThat(headLocked.await(10, TimeUnit.SECONDS)).isTrue();

      Future<DossierInboxProcessor.Outcome> blocked =
          workers.submit(
              () -> processor.process(estimate(blockedEstimate, blockedCabin, 1, 11)));
      await(
          "blocked cabin waits only on its publication head",
          this::publicationHeadWaiterPresent);

      Future<DossierInboxProcessor.Outcome> independent =
          workers.submit(() -> processor.process(repair(freeRepair, freeCabin, 12)));
      try {
        assertThat(independent.get(5, TimeUnit.SECONDS))
            .isEqualTo(DossierInboxProcessor.Outcome.PROCESSED);
      } finally {
        releaseHead.countDown();
      }

      locker.get(10, TimeUnit.SECONDS);
      assertThat(blocked.get(10, TimeUnit.SECONDS))
          .isEqualTo(DossierInboxProcessor.Outcome.PROCESSED);
    }

    assertThat(publicationHeads.findByCabinId(blockedCabin)).get()
        .satisfies(head -> assertThat(head.getPublishedVersion()).isEqualTo(1));
    assertThat(publicationHeads.findByCabinId(freeCabin)).get()
        .satisfies(head -> assertThat(head.getPublishedVersion()).isZero());
    assertThat(generations.findAll())
        .filteredOn(value -> value.getState() == DossierGenerationState.ACTIVE)
        .hasSize(1);
  }

  private DossierInboxProcessor.Outcome processAtOnce(
      DossierValidatedEvent event, CountDownLatch ready, CountDownLatch start) throws Exception {
    ready.countDown();
    if (!start.await(10, TimeUnit.SECONDS)) {
      throw new IllegalStateException("Timed out starting concurrent projection");
    }
    return processor.process(event);
  }

  private DossierValidatedEvent estimate(
      UUID estimateId, UUID cabinId, long aggregateVersion, long offset) {
    String payload =
        """
        {"estimateId":"%s","warehouseId":"%s","rentalItemId":"%s","lifecycle":"DRAFT","revision":1,"dispatchDate":"2026-07-18","lineCount":0,"completionKind":"NOT_COMPLETED","repairId":null}
        """
            .formatted(estimateId, WAREHOUSE_ID, cabinId);
    return validate(
        "rwms.maintenance.estimate.v1",
        offset,
        estimateId,
        envelope(
            aggregateVersion == 0
                ? "maintenance.estimate.created.v1"
                : "maintenance.estimate.draft-changed.v1",
            "ESTIMATE",
            estimateId,
            aggregateVersion,
            payload));
  }

  private DossierValidatedEvent repair(UUID repairId, UUID cabinId, long offset) {
    String payload =
        """
        {"repairId":"%s","rootRepairId":"%s","sourceRepairId":null,"estimateId":null,"warehouseId":"%s","rentalItemId":"%s","origin":"DIRECT_REPAIR","kind":"PRIMARY","executionState":"DRAFT","acceptanceState":"NOT_READY","dispatchDate":"2026-07-18","priority":3,"stages":[]}
        """
            .formatted(repairId, repairId, WAREHOUSE_ID, cabinId);
    return validate(
        "rwms.maintenance.repair.v1",
        offset,
        repairId,
        envelope("maintenance.repair.created.v1", "REPAIR", repairId, 0, payload));
  }

  private DossierValidatedEvent validate(
      String topic, long offset, UUID key, String envelope) {
    return validator.validate(
        topic, 0, offset, key.toString(), envelope.getBytes(StandardCharsets.UTF_8));
  }

  private static String envelope(
      String eventType,
      String aggregateType,
      UUID aggregateId,
      long aggregateVersion,
      String payload) {
    return """
        {"envelopeVersion":2,"eventId":"%s","eventType":"%s","eventVersion":1,"occurredAt":"2026-07-18T10:00:00Z","recordedAt":"2026-07-18T12:00:00Z","producer":"maintenance-service","aggregateType":"%s","aggregateId":"%s","aggregateVersion":%d,"correlation":{"correlationId":"73000000-0000-0000-0000-000000000001","causationId":null},"actorRef":null,"payload":%s}
        """
        .formatted(
            UUID.randomUUID(),
            eventType,
            aggregateType,
            aggregateId,
            aggregateVersion,
            payload.strip());
  }

  private boolean publicationHeadWaiterPresent() {
    Integer count =
        jdbc.queryForObject(
            """
            select count(*)
              from pg_stat_activity
             where datname = current_database()
               and wait_event_type = 'Lock'
               and query like '%dossier_cabin_publication_head%'
            """,
            Integer.class);
    return count != null && count > 0;
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
}
