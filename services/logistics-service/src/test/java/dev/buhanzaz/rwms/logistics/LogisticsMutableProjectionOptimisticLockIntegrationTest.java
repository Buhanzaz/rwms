package dev.buhanzaz.rwms.logistics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.buhanzaz.rwms.logistics.api.LogisticsApiModels.CreateReturnRequest;
import dev.buhanzaz.rwms.logistics.api.LogisticsApiModels.ReturnLineRequest;
import dev.buhanzaz.rwms.logistics.domain.LogisticsExternalAttempt;
import dev.buhanzaz.rwms.logistics.domain.LogisticsExternalAttemptResult;
import dev.buhanzaz.rwms.logistics.domain.LogisticsGuard;
import dev.buhanzaz.rwms.logistics.domain.LogisticsGuardState;
import dev.buhanzaz.rwms.logistics.domain.LogisticsMediaPurpose;
import dev.buhanzaz.rwms.logistics.domain.LogisticsMediaReadiness;
import dev.buhanzaz.rwms.logistics.domain.LogisticsMediaReference;
import dev.buhanzaz.rwms.logistics.domain.LogisticsTargetService;
import dev.buhanzaz.rwms.logistics.repository.LogisticsDocumentLineRepository;
import dev.buhanzaz.rwms.logistics.repository.LogisticsDocumentRepository;
import dev.buhanzaz.rwms.logistics.repository.LogisticsExternalAttemptRepository;
import dev.buhanzaz.rwms.logistics.repository.LogisticsGuardRepository;
import dev.buhanzaz.rwms.logistics.repository.LogisticsMediaReferenceRepository;
import dev.buhanzaz.rwms.logistics.service.LogisticsDocumentService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.OptimisticLockException;
import jakarta.persistence.RollbackException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import org.hibernate.StaleObjectStateException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest(
    properties = {
      "spring.jpa.hibernate.ddl-auto=validate",
      "rwms.platform.kafka.enabled=false",
      "rwms.logistics.return-registration.relay-enabled=false",
      "rwms.logistics.return-completion.relay-enabled=false",
      "rwms.logistics.shipment.relay-enabled=false",
      "rwms.logistics.transfer.relay-enabled=false",
      "AUTH_ISSUER=http://issuer.invalid",
      "PANEL_ORIGIN=http://localhost:5173"
    })
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class LogisticsMutableProjectionOptimisticLockIntegrationTest {
  private static final UUID WAREHOUSE =
      UUID.fromString("00000000-0000-0000-0000-000000000921");
  private static final UUID SUBJECT =
      UUID.fromString("00000000-0000-0000-0000-000000000922");
  private static final UUID CORRELATION =
      UUID.fromString("00000000-0000-0000-0000-000000000923");
  private static final String REQUEST_HASH = "1".repeat(64);
  private static final String RESPONSE_HASH = "2".repeat(64);

  @Container @ServiceConnection
  static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

  @Autowired LogisticsDocumentService documents;
  @Autowired LogisticsDocumentRepository documentRepository;
  @Autowired LogisticsDocumentLineRepository lineRepository;
  @Autowired LogisticsExternalAttemptRepository attemptRepository;
  @Autowired LogisticsGuardRepository guardRepository;
  @Autowired LogisticsMediaReferenceRepository mediaRepository;
  @Autowired EntityManagerFactory entityManagerFactory;
  @Autowired TransactionTemplate transactions;
  @Autowired JdbcTemplate jdbc;

  @BeforeEach
  void reset() {
    jdbc.execute(
        """
        truncate table
          logistics_document,
          event_stream_head,
          domain_event,
          aggregate_snapshot,
          projection_checkpoint,
          outbox_event
        cascade
        """);
  }

  @Test
  void staleConcurrentUpdatesAreRejectedForEveryMutableSagaProjection() {
    Seed seed = transactions.execute(status -> seedMutableProjections());
    assertThat(seed).isNotNull();
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

    assertStaleWriteRejected(
        LogisticsExternalAttempt.class,
        seed.attemptId(),
        attempt -> attempt.retry(now.plusMinutes(1)),
        attempt -> attempt.reject(RESPONSE_HASH, now));
    assertStaleWriteRejected(
        LogisticsGuard.class,
        seed.guardId(),
        guard -> guard.recordObservedAssetVersion(8),
        LogisticsGuard::conflict);
    assertStaleWriteRejected(
        LogisticsMediaReference.class,
        seed.mediaReferenceId(),
        media -> media.ready(now),
        LogisticsMediaReference::reject);

    transactions.executeWithoutResult(
        status -> {
          LogisticsExternalAttempt attempt =
              attemptRepository.findById(seed.attemptId()).orElseThrow();
          LogisticsGuard guard = guardRepository.findById(seed.guardId()).orElseThrow();
          LogisticsMediaReference media =
              mediaRepository.findById(seed.mediaReferenceId()).orElseThrow();
          assertThat(attempt.getResult()).isEqualTo(LogisticsExternalAttemptResult.RETRY);
          assertThat(attempt.getRowVersion()).isOne();
          assertThat(guard.getGuardState()).isEqualTo(LogisticsGuardState.ACTIVE);
          assertThat(guard.getObservedAssetVersion()).isEqualTo(8);
          assertThat(guard.getRowVersion()).isOne();
          assertThat(media.getReadiness()).isEqualTo(LogisticsMediaReadiness.READY);
          assertThat(media.getRowVersion()).isOne();
        });
  }

  private Seed seedMutableProjections() {
    var created =
        documents.createReturn(
            SUBJECT,
            UUID.randomUUID(),
            CORRELATION,
            new CreateReturnRequest(
                WAREHOUSE,
                List.of(
                    new ReturnLineRequest(
                        UUID.randomUUID(), 7, "Optimistic lock tenant snapshot"))));
    var document = documentRepository.findById(created.response().id()).orElseThrow();
    var line = lineRepository.findById(created.response().lines().getFirst().id()).orElseThrow();
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    LogisticsExternalAttempt attempt =
        attemptRepository.saveAndFlush(
            LogisticsExternalAttempt.create(
                document,
                line,
                LogisticsTargetService.ASSET,
                "OPTIMISTIC_LOCK_TEST",
                REQUEST_HASH,
                CORRELATION,
                null,
                now));
    LogisticsGuard guard =
        guardRepository.saveAndFlush(
            LogisticsGuard.active(document, line, UUID.randomUUID(), 0, 1, 7, now));
    LogisticsMediaReference media =
        mediaRepository.saveAndFlush(
            LogisticsMediaReference.pending(
                document,
                line,
                UUID.randomUUID(),
                1,
                LogisticsMediaPurpose.RETURN_INSPECTION,
                now));
    return new Seed(attempt.getId(), guard.getId(), media.getId());
  }

  private <T> void assertStaleWriteRejected(
      Class<T> entityType, UUID id, Consumer<T> winner, Consumer<T> stale) {
    EntityManager first = entityManagerFactory.createEntityManager();
    EntityManager second = entityManagerFactory.createEntityManager();
    try {
      first.getTransaction().begin();
      second.getTransaction().begin();
      T winningState = first.find(entityType, id);
      T staleState = second.find(entityType, id);
      assertThat(winningState).isNotNull();
      assertThat(staleState).isNotNull();

      winner.accept(winningState);
      first.getTransaction().commit();
      stale.accept(staleState);

      assertThatThrownBy(second.getTransaction()::commit)
          .isInstanceOfAny(RollbackException.class, OptimisticLockException.class)
          .satisfies(
              failure ->
                  assertThat(hasCause(failure, StaleObjectStateException.class)).isTrue());
    } finally {
      if (first.getTransaction().isActive()) {
        first.getTransaction().rollback();
      }
      if (second.getTransaction().isActive()) {
        second.getTransaction().rollback();
      }
      first.close();
      second.close();
    }
  }

  private static boolean hasCause(Throwable failure, Class<? extends Throwable> expected) {
    Throwable current = failure;
    while (current != null) {
      if (expected.isInstance(current)) {
        return true;
      }
      current = current.getCause();
    }
    return false;
  }

  private record Seed(UUID attemptId, UUID guardId, UUID mediaReferenceId) {}
}
