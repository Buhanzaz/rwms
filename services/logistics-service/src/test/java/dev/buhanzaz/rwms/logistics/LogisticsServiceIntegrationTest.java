package dev.buhanzaz.rwms.logistics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.buhanzaz.rwms.logistics.api.LogisticsApiModels.CreateReturnRequest;
import dev.buhanzaz.rwms.logistics.api.LogisticsApiModels.ReturnLineRequest;
import dev.buhanzaz.rwms.logistics.api.LogisticsApiModels.CreateShipmentRequest;
import dev.buhanzaz.rwms.logistics.api.LogisticsApiModels.ShipmentLineRequest;
import dev.buhanzaz.rwms.logistics.service.LogisticsConflictException;
import dev.buhanzaz.rwms.logistics.service.LogisticsDocumentService;
import dev.buhanzaz.rwms.logistics.service.LogisticsWarehouseLifecycle.AdmissionEvidence;
import dev.buhanzaz.rwms.logistics.service.LogisticsWarehouseOperationMarkStore;
import dev.buhanzaz.rwms.logistics.service.LogisticsWarehouseLifecycleStore;
import dev.buhanzaz.rwms.logistics.service.LogisticsWarehouseLifecycleStore.AdmissionRequirement;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway.WarehouseOperationDirection;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest
@ActiveProfiles({"dev", "test"})
@AutoConfigureMockMvc
class LogisticsServiceIntegrationTest {
  private static final UUID WAREHOUSE = UUID.fromString("00000000-0000-0000-0000-000000000301");
  private static final UUID SUBJECT = UUID.fromString("00000000-0000-0000-0000-000000000302");
  private static final UUID CORRELATION = UUID.fromString("00000000-0000-0000-0000-000000000303");
  private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

  static {
    POSTGRES.start();
  }

  @Autowired LogisticsDocumentService service;
  @Autowired LogisticsWarehouseOperationMarkStore warehouseOperationMarks;
  @Autowired LogisticsWarehouseLifecycleStore warehouseLifecycleStore;
  @Autowired PlatformTransactionManager transactionManager;
  @Autowired JdbcTemplate jdbc;

  @DynamicPropertySource
  static void database(DynamicPropertyRegistry properties) {
    properties.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    properties.add("spring.datasource.username", POSTGRES::getUsername);
    properties.add("spring.datasource.password", POSTGRES::getPassword);
    properties.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    properties.add("rwms.platform.kafka.enabled", () -> "false");
    properties.add("rwms.cors.allowed-origins", () -> "http://localhost");
    properties.add(
        "spring.security.oauth2.resourceserver.jwt.jwk-set-uri",
        () -> "http://127.0.0.1:65535/jwks");
  }

  @BeforeEach
  void cleanFixtures() {
    jdbc.update("delete from logistics_warehouse_readiness_fence");
    jdbc.update("delete from logistics_warehouse_admission_intent");
    jdbc.execute("truncate table warehouse_operation_mark_recovery_audit");
    jdbc.update("delete from warehouse_operation_mark_outbox");
    jdbc.update("delete from logistics_idempotency_record");
    jdbc.update("delete from logistics_external_attempt");
    jdbc.update("delete from logistics_document_line");
    jdbc.update("delete from logistics_document");
    jdbc.update("delete from rental_order");
    jdbc.update("delete from order_client");
  }

  @Test
  void expiredAdmissionCannotCommitAndIsRemovedBeforeReadiness() {
    UUID warehouseId = UUID.randomUUID();
    UUID operationId = UUID.randomUUID();
    List<AdmissionRequirement> requirements =
        List.of(new AdmissionRequirement(warehouseId, WarehouseOperationDirection.OUTGOING));
    assertThat(warehouseLifecycleStore.reserve(operationId, requirements)).isFalse();
    warehouseLifecycleStore.admit(operationId, requirements, List.of(7L));
    jdbc.update(
        "update logistics_warehouse_admission_intent set expires_at=clock_timestamp()-interval '1 second' where operation_id=?",
        operationId);

    assertThatThrownBy(
            () ->
                new TransactionTemplate(transactionManager)
                    .executeWithoutResult(
                        ignored ->
                            warehouseLifecycleStore.consume(
                                operationId,
                                requirements,
                                List.of(
                                    new AdmissionEvidence(
                                        warehouseId,
                                        WarehouseOperationDirection.OUTGOING,
                                        7)),
                                false)))
        .isInstanceOf(LogisticsConflictException.class)
        .hasMessageContaining("expired");

    var readiness = warehouseLifecycleStore.beginReadiness(warehouseId, 8);
    assertThat(readiness.shouldConfirm()).isTrue();
    assertThat(readiness.sealed()).isFalse();
    assertThat(
            jdbc.queryForObject(
                "select count(*) from logistics_warehouse_admission_intent where operation_id=?",
                Long.class,
                operationId))
        .isZero();
    assertThatThrownBy(
            () ->
                jdbc.queryForObject(
                    "select logistics_assert_warehouse_lifecycle_open(?)",
                    Object.class,
                    warehouseId))
        .hasMessageContaining("fenced for readiness");
  }

  @Test
  void recoversAQuarantinedWarehouseMarkOnceWithImmutableReviewAudit() {
    UUID warehouseId = UUID.randomUUID();
    UUID operationId = UUID.randomUUID();
    UUID reviewer = UUID.randomUUID();
    jdbc.update(
        """
        insert into warehouse_operation_mark_outbox(
          operation_id,warehouse_id,occurred_at,state,attempt_count,next_attempt_at,
          last_error_code,created_at,updated_at)
        values (?,?,clock_timestamp(),'QUARANTINED',8,clock_timestamp(),
                'WAREHOUSE_UNAVAILABLE',clock_timestamp(),clock_timestamp())
        """,
        operationId,
        warehouseId);

    var recovered =
        warehouseOperationMarks.recoverQuarantined(
            warehouseId, operationId, 0, reviewer, "Проверено администратором");
    var replayed =
        warehouseOperationMarks.recoverQuarantined(
            warehouseId, operationId, 0, reviewer, "Проверено администратором");

    assertThat(recovered.replayed()).isFalse();
    assertThat(recovered.state()).isEqualTo("PENDING");
    assertThat(recovered.recoveryVersion()).isOne();
    assertThat(replayed.replayed()).isTrue();
    assertThat(
            jdbc.queryForObject(
                "select count(*) from warehouse_operation_mark_recovery_audit where warehouse_id=? and operation_id=?",
                Long.class,
                warehouseId,
                operationId))
        .isOne();
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "delete from warehouse_operation_mark_recovery_audit where warehouse_id=? and operation_id=?",
                    warehouseId,
                    operationId))
        .hasMessageContaining("immutable");
    assertThatThrownBy(
            () ->
                warehouseOperationMarks.recoverQuarantined(
                    warehouseId, operationId, 0, reviewer, "Другая причина"))
        .isInstanceOf(LogisticsConflictException.class)
        .hasMessageContaining("another reviewed command");
  }

  @Test
  void firstWarehouseBoundRentalOrderAtomicallyMarksTheWarehouseAsOperated() {
    UUID warehouseId = UUID.randomUUID();
    UUID clientId = UUID.randomUUID();
    UUID orderId = UUID.randomUUID();
    jdbc.update(
        """
        insert into order_client(
          id,version,client_type,display_name,normalized_name,phone,normalized_phone,
          contact_person,responsible_manager_id,responsible_manager_display_name,created_by_subject_id,
          creation_idempotency_key,creation_request_sha256,created_at,updated_at)
        values (?,0,'LEGAL_ENTITY','Клиент','клиент','+79990000000','+79990000000',
                'Представитель',?,'Управляющий',?,?,?,clock_timestamp(),clock_timestamp())
        """,
        clientId,
        SUBJECT,
        SUBJECT,
        UUID.randomUUID(),
        "a".repeat(64));
    jdbc.update(
        """
        insert into rental_order(
          id,version,order_number,status,client_id,manager_id,manager_display_name,
          created_by_subject_id,created_by_display_name,created_by_role,warehouse_id,
          creation_idempotency_key,creation_request_sha256,created_at,updated_at)
        values (?,0,'ORD-900001','DRAFT',?,?,'Управляющий',?,'Управляющий',
                'WAREHOUSE_MANAGER',?,?,?,clock_timestamp(),clock_timestamp())
        """,
        orderId,
        clientId,
        SUBJECT,
        SUBJECT,
        warehouseId,
        UUID.randomUUID(),
        "b".repeat(64));

    assertThat(
            jdbc.queryForObject(
                "select count(*) from warehouse_operation_mark_outbox where warehouse_id=?",
                Long.class,
                warehouseId))
        .isOne();
    assertThat(
            jdbc.queryForObject(
                "select state from warehouse_operation_mark_outbox where warehouse_id=?",
                String.class,
                warehouseId))
        .isEqualTo("PENDING");
  }

  @Test
  void createsAndReplaysAnImmutableReturnDraftThroughJpaAndMapStruct() {
    UUID key = UUID.randomUUID();
    CreateReturnRequest request =
        new CreateReturnRequest(
            WAREHOUSE,
            List.of(
                new ReturnLineRequest(
                    UUID.fromString("00000000-0000-0000-0000-000000000304"), 7, " Tenant A ")));

    LogisticsDocumentService.CreateResult created = service.createReturn(SUBJECT, key, CORRELATION, request);
    LogisticsDocumentService.CreateResult replayed = service.createReturn(SUBJECT, key, CORRELATION, request);

    assertThat(created.replayed()).isFalse();
    assertThat(replayed.replayed()).isTrue();
    assertThat(replayed.response()).isEqualTo(created.response());
    assertThat(created.response().partySnapshot()).isNull();
    assertThat(created.response().lines()).singleElement().satisfies(line -> {
      assertThat(line.assetVersion()).isEqualTo(7);
      assertThat(line.tenantSnapshot()).isEqualTo("Tenant A");
    });
    assertThat(
            jdbc.queryForObject(
                "select count(*) from logistics_document where id=?",
                Long.class,
                created.response().id()))
        .isOne();
    assertThat(
            jdbc.queryForObject(
                "select count(*) from domain_event where aggregate_id=? and event_type='logistics.return.created.v1'",
                Long.class,
                created.response().id().toString()))
        .isOne();
    assertThat(
            jdbc.queryForObject(
                "select count(*) from outbox_event where aggregate_id=? and topic='rwms.logistics.return.v1'",
                Long.class,
                created.response().id().toString()))
        .isOne();

    CreateReturnRequest changed =
        new CreateReturnRequest(
            WAREHOUSE,
            List.of(
                new ReturnLineRequest(
                    UUID.fromString("00000000-0000-0000-0000-000000000304"), 8, "Tenant A")));
    assertThatThrownBy(() -> service.createReturn(SUBJECT, key, CORRELATION, changed))
        .isInstanceOf(LogisticsConflictException.class)
        .hasMessageContaining("Idempotency key");
  }

  @Test
  void shipmentDraftKeepsPartyAndDriverSnapshotsLocal() {
    LogisticsDocumentService.CreateResult created =
        service.createShipment(
            SUBJECT,
            UUID.randomUUID(),
            CORRELATION,
            new CreateShipmentRequest(
                WAREHOUSE,
                "  Party A  ",
                "  Driver A  ",
                List.of(
                    new ShipmentLineRequest(
                        UUID.fromString("00000000-0000-0000-0000-000000000305"), 2))));

    assertThat(created.response().partySnapshot()).isEqualTo("Party A");
    assertThat(created.response().driverSnapshot()).isEqualTo("Driver A");
    assertThat(created.response().lines()).hasSize(1);
  }

  @Test
  void returnDraftKeepsTheConfiguredDriverSnapshot() {
    LogisticsDocumentService.CreateResult created =
        service.createReturn(
            SUBJECT,
            UUID.randomUUID(),
            CORRELATION,
            new CreateReturnRequest(
                WAREHOUSE,
                null,
                "  Водитель возврата  ",
                List.of(
                    new ReturnLineRequest(
                        UUID.fromString("00000000-0000-0000-0000-000000000307"),
                        3,
                        "Tenant C"))));

    assertThat(created.response().driverSnapshot()).isEqualTo("Водитель возврата");
  }

  @Test
  void concurrentCreateRetriesWithTheSameKeyProduceOneDocumentAndOneEvent() throws Exception {
    UUID key = UUID.randomUUID();
    CreateReturnRequest request =
        new CreateReturnRequest(
            WAREHOUSE,
            List.of(
                new ReturnLineRequest(
                    UUID.fromString("00000000-0000-0000-0000-000000000306"), 3, "Tenant B")));
    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch start = new CountDownLatch(1);
    ExecutorService workers = Executors.newFixedThreadPool(2);
    try {
      Future<LogisticsDocumentService.CreateResult> first =
          workers.submit(() -> createWhenReleased(ready, start, key, request));
      Future<LogisticsDocumentService.CreateResult> second =
          workers.submit(() -> createWhenReleased(ready, start, key, request));
      assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
      start.countDown();

      LogisticsDocumentService.CreateResult firstResult = first.get(30, TimeUnit.SECONDS);
      LogisticsDocumentService.CreateResult secondResult = second.get(30, TimeUnit.SECONDS);

      assertThat(firstResult.response().id()).isEqualTo(secondResult.response().id());
      assertThat(List.of(firstResult.replayed(), secondResult.replayed())).containsExactlyInAnyOrder(false, true);
      assertThat(
              jdbc.queryForObject(
                  "select count(*) from logistics_document where warehouse_id=?", Long.class, WAREHOUSE))
          .isOne();
      assertThat(
              jdbc.queryForObject(
                  "select count(*) from domain_event where aggregate_id=?", Long.class,
                  firstResult.response().id().toString()))
          .isOne();
    } finally {
      workers.shutdownNow();
    }
  }

  private LogisticsDocumentService.CreateResult createWhenReleased(
      CountDownLatch ready, CountDownLatch start, UUID key, CreateReturnRequest request) throws Exception {
    ready.countDown();
    if (!start.await(10, TimeUnit.SECONDS)) {
      throw new IllegalStateException("Concurrent create test did not start");
    }
    return service.createReturn(SUBJECT, key, CORRELATION, request);
  }

  @AfterAll
  static void stopDatabase() {
    POSTGRES.stop();
  }
}
