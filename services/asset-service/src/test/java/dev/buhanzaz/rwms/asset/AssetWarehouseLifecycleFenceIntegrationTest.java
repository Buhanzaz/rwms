package dev.buhanzaz.rwms.asset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import dev.buhanzaz.rwms.asset.service.AssetWarehouseLifecycleStore;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Regression coverage for P-08's local half of the warehouse readiness protocol. */
@SpringBootTest(
    properties = {
      "spring.jpa.hibernate.ddl-auto=validate",
      "rwms.platform.kafka.enabled=false",
      "rwms.asset.warehouse-registry.enabled=false",
      "spring.cloud.function.definition=",
      "spring.task.scheduling.enabled=false"
    })
@ActiveProfiles("test")
class AssetWarehouseLifecycleFenceIntegrationTest {
  private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

  static {
    POSTGRES.start();
  }

  @Autowired AssetWarehouseLifecycleStore lifecycle;
  @Autowired JdbcTemplate jdbc;
  @Autowired DataSource dataSource;

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
    registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> "http://issuer.invalid");
    registry.add("rwms.cors.allowed-origins", () -> "http://localhost:5173");
  }

  @AfterAll
  static void stopDatabase() {
    POSTGRES.stop();
  }

  @Test
  void readinessWaitsForAnAlreadyWritingTransactionAndObservesItsCommittedBlocker()
      throws Exception {
    UUID warehouseId = UUID.randomUUID();
    ExecutorService executor = Executors.newSingleThreadExecutor();
    try (Connection writer = dataSource.getConnection()) {
      writer.setAutoCommit(false);
      insertRentalItem(writer, warehouseId, UUID.randomUUID());

      Future<AssetWarehouseLifecycleStore.ReadinessAttempt> readiness =
          executor.submit(() -> lifecycle.beginReadiness(warehouseId, 7L));
      awaitAdvisoryWait();
      writer.commit();

      assertThat(readiness.get(10, TimeUnit.SECONDS).shouldConfirm()).isFalse();
      assertThat(fenceCount(warehouseId)).isZero();
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  void aTransactionStartedBeforeTheFenceCannotCommitANewBlockerAndExistingWorkCanDrain()
      throws Exception {
    UUID fencedWarehouseId = UUID.randomUUID();
    try (Connection writer = dataSource.getConnection()) {
      writer.setAutoCommit(false);
      try (var statement = writer.createStatement()) {
        statement.execute("select 1");
      }

      AssetWarehouseLifecycleStore.ReadinessAttempt attempt =
          lifecycle.beginReadiness(fencedWarehouseId, 8L);
      assertThat(attempt.shouldConfirm()).isTrue();

      SQLException rejected =
          catchThrowableOfType(
              () -> insertRentalItem(writer, fencedWarehouseId, UUID.randomUUID()), SQLException.class);
      assertThat((Throwable) rejected).isNotNull();
      assertThat(rejected.getSQLState()).isEqualTo("23514");
      writer.rollback();
    }

    UUID drainingWarehouseId = UUID.randomUUID();
    UUID rentalItemId = UUID.randomUUID();
    insertRentalItem(drainingWarehouseId, rentalItemId);
    jdbc.update(
        """
        insert into asset_warehouse_readiness_fence(
          warehouse_id,warehouse_version,state,created_at,updated_at)
        values (?,9,'CONFIRMING',clock_timestamp(),clock_timestamp())
        """,
        drainingWarehouseId);

    assertThat(
            jdbc.update(
                """
                update rental_item
                   set status='WRITTEN_OFF',version=version+1,updated_at=clock_timestamp()
                 where id=?
                """,
                rentalItemId))
        .isOne();
    assertThat(
            jdbc.queryForObject("select status from rental_item where id=?", String.class, rentalItemId))
        .isEqualTo("WRITTEN_OFF");
  }

  @Test
  void aSealedFenceRemainsReplaySafeAtItsOriginalWarehouseVersion() {
    UUID warehouseId = UUID.randomUUID();

    AssetWarehouseLifecycleStore.ReadinessAttempt started = lifecycle.beginReadiness(warehouseId, 11L);
    lifecycle.sealReadiness(warehouseId, 11L);
    AssetWarehouseLifecycleStore.ReadinessAttempt replay = lifecycle.beginReadiness(warehouseId, 12L);

    assertThat(started).isEqualTo(
        new AssetWarehouseLifecycleStore.ReadinessAttempt(warehouseId, 11L, false, true));
    assertThat(replay).isEqualTo(
        new AssetWarehouseLifecycleStore.ReadinessAttempt(warehouseId, 11L, true, true));
  }

  private long fenceCount(UUID warehouseId) {
    Long count =
        jdbc.queryForObject(
            "select count(*) from asset_warehouse_readiness_fence where warehouse_id=?",
            Long.class,
            warehouseId);
    return count == null ? 0L : count;
  }

  private void awaitAdvisoryWait() throws InterruptedException {
    for (int attempt = 0; attempt < 200; attempt++) {
      Integer waiters =
          jdbc.queryForObject(
              "select count(*) from pg_locks where locktype='advisory' and not granted",
              Integer.class);
      if (waiters != null && waiters > 0) {
        return;
      }
      Thread.sleep(25);
    }
    throw new AssertionError("Readiness did not reach the warehouse lifecycle lock wait");
  }

  private void insertRentalItem(UUID warehouseId, UUID rentalItemId) throws SQLException {
    try (Connection connection = dataSource.getConnection()) {
      insertRentalItem(connection, warehouseId, rentalItemId);
    }
  }

  private static void insertRentalItem(Connection connection, UUID warehouseId, UUID rentalItemId)
      throws SQLException {
    String number = "FENCE-" + rentalItemId;
    String identity = "FENCE" + rentalItemId.toString().replace("-", "");
    try (PreparedStatement statement =
        connection.prepareStatement(
            """
            insert into rental_item(
              id,version,company_id,warehouse_id,display_canonical_number,identity_match_key,status,
              passport_json,tags_json,created_at,updated_at)
            values (?,0,?,?,?,?,'FREE','{}','[]',clock_timestamp(),clock_timestamp())
            """)) {
      statement.setObject(1, rentalItemId);
      statement.setObject(
          2,
          dev.buhanzaz.rwms.asset.domain.AssetCompanyDefaults.INITIAL_COMPANY_ID);
      statement.setObject(3, warehouseId);
      statement.setString(4, number);
      statement.setString(5, identity);
      statement.executeUpdate();
    }
  }
}
