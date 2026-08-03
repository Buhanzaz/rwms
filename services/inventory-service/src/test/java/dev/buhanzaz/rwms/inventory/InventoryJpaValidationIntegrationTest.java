package dev.buhanzaz.rwms.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.buhanzaz.rwms.inventory.domain.InventoryFinding;
import dev.buhanzaz.rwms.inventory.repository.InventoryFindingRepository;
import jakarta.persistence.EntityManagerFactory;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest(
    properties = {
      "spring.jpa.hibernate.ddl-auto=validate",
      "rwms.platform.kafka.enabled=false",
      "rwms.inventory.dependencies.enabled=false"
    })
@ActiveProfiles("test")
class InventoryJpaValidationIntegrationTest {
  private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

  static {
    POSTGRES.start();
  }

  @Autowired EntityManagerFactory entityManagerFactory;
  @Autowired JdbcTemplate jdbc;
  @Autowired DataSource dataSource;
  @Autowired InventoryFindingRepository findings;
  @Autowired PlatformTransactionManager transactionManager;

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
    registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    registry.add(
        "spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> "http://issuer.invalid");
    registry.add("rwms.cors.allowed-origins", () -> "http://localhost:5173");
  }

  @AfterAll
  static void stopDatabase() {
    POSTGRES.stop();
  }

  @Test
  void flywayMigrationsPassHibernateValidationForIndependentAggregateRevisions() {
    assertThat(entityManagerFactory.isOpen()).isTrue();
    assertThat(dataSource).isNotNull();
        assertThat(
            jdbc.queryForObject(
                "select count(*) from flyway_schema_history where success", Integer.class))
        .isEqualTo(12);
    assertThat(entityManagerFactory.getMetamodel().getEntities())
        .extracting(value -> value.getJavaType().getSimpleName())
        .contains(
            "InventorySession",
            "InventoryFinding",
            "InventoryMembershipMovement",
            "InventoryPublicationIntent");
  }

  @Test
  void cancelStyleOwnerTransitionLocksStableOrderAndRejectsStaleFindingRace() {
    UUID inventoryId = UUID.randomUUID();
    UUID firstId = UUID.fromString("00000000-0000-0000-0000-000000000011");
    UUID secondId = UUID.fromString("00000000-0000-0000-0000-000000000012");
    seedSession(inventoryId);
    seedFinding(inventoryId, secondId, "B2");
    seedFinding(inventoryId, firstId, "A1");
    TransactionTemplate transactions = new TransactionTemplate(transactionManager);

    InventoryFinding stale =
        transactions.execute(status -> findings.findById(firstId).orElseThrow());
    List<UUID> lockedOrder =
        transactions.execute(
            status -> {
              List<InventoryFinding> locked =
                  findings.findAllByInventoryIdForUpdateOrderById(inventoryId);
              locked.getFirst().transitionOwnerProof(false);
              findings.saveAllAndFlush(locked);
              return locked.stream().map(InventoryFinding::getId).toList();
            });

    assertThat(lockedOrder).containsExactly(firstId, secondId);
    InventoryFinding cancelled = findings.findById(firstId).orElseThrow();
    assertThat(cancelled.isOwnerProofActive()).isFalse();
    assertThat(cancelled.getOwnerProofRevision()).isOne();

    stale.transitionOwnerProof(false);
    assertThatThrownBy(
            () ->
                transactions.execute(
                    status -> {
                      findings.saveAndFlush(stale);
                      return null;
                    }))
        .isInstanceOf(ObjectOptimisticLockingFailureException.class);
  }

  private void seedSession(UUID inventoryId) {
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    UUID operationId = UUID.randomUUID();
    jdbc.update(
        """
        insert into inventory_session(
          id,session_revision,warehouse_id,warehouse_version_snapshot,warehouse_time_zone,
          business_date,lifecycle,start_operation_id,start_idempotency_key,start_request_sha256,
          expected_population_count,expected_population_sha256,started_by_subject_id,
          started_by_display_name,started_actor_ref,started_at,created_at,updated_at)
        values (?,0,?,0,'Europe/Moscow',current_date,'ACTIVE',?,?,?,0,?,?,'Inventory operator',?::jsonb,?,?,?)
        """,
        inventoryId,
        UUID.randomUUID(),
        operationId,
        operationId,
        "0".repeat(64),
        "1".repeat(64),
        UUID.randomUUID(),
        actor(),
        now,
        now,
        now);
  }

  private void seedFinding(UUID inventoryId, UUID findingId, String matchKey) {
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    jdbc.update(
        """
        insert into inventory_finding(
          id,inventory_id,finding_revision,origin,inspection,reconciliation,
          display_canonical_number,identity_match_key,passport_observation_state,
          equipment_observation_state,mutation_state,actor_ref,created_at,updated_at)
        values (?,?,0,'UNEXPECTED_EXISTING','NOT_INSPECTED','CONFLICT',?,?,'ABSENT',
          'ABSENT','IDLE',?::jsonb,?,?)
        """,
        findingId,
        inventoryId,
        matchKey,
        matchKey,
        actor(),
        now,
        now);
  }

  private String actor() {
    return "{\"subjectId\":\"00000000-0000-0000-0000-000000000701\","
        + "\"principalType\":\"USER\",\"profileRevision\":null}";
  }
}
