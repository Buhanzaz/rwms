package dev.buhanzaz.rwms.logistics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.buhanzaz.rwms.logistics.api.LogisticsApiModels.CreateReturnRequest;
import dev.buhanzaz.rwms.logistics.api.LogisticsApiModels.ReturnLineRequest;
import dev.buhanzaz.rwms.logistics.api.LogisticsApiModels.ReturnPickupRequest;
import dev.buhanzaz.rwms.logistics.domain.LogisticsDocumentState;
import dev.buhanzaz.rwms.logistics.domain.LogisticsDocumentType;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway;
import dev.buhanzaz.rwms.logistics.service.LogisticsDocumentService;
import dev.buhanzaz.rwms.logistics.service.ReturnRegistrationProcessor;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest(
    properties = {
      "spring.jpa.hibernate.ddl-auto=validate",
      "rwms.platform.kafka.enabled=false",
      "rwms.logistics.return-registration.relay-enabled=false",
      "AUTH_ISSUER=http://auth.test",
      "PANEL_ORIGIN=http://panel.test"
    })
@ActiveProfiles("test")
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ReturnRegistrationSagaIntegrationTest {
  private static final UUID WAREHOUSE = UUID.fromString("00000000-0000-0000-0000-000000000401");
  private static final UUID SUBJECT = UUID.fromString("00000000-0000-0000-0000-000000000402");
  private static final UUID CORRELATION = UUID.fromString("00000000-0000-0000-0000-000000000403");
  private static final UUID ASSET = UUID.fromString("00000000-0000-0000-0000-000000000404");

  @Container
  @ServiceConnection
  static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

  @Autowired LogisticsDocumentService documents;
  @Autowired ReturnRegistrationProcessor processor;
  @Autowired JdbcTemplate jdbc;

  @MockitoBean LogisticsDependencyGateway dependencies;

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
    org.mockito.Mockito.reset(dependencies);
  }

  @Test
  void registersReturnThroughDurableWarehouseSnapshotLeaseAndFencedIntakeSteps() {
    LogisticsDocumentService.CreateResult created =
        documents.createReturn(
            SUBJECT,
            UUID.randomUUID(),
            CORRELATION,
            new CreateReturnRequest(
                WAREHOUSE, List.of(new ReturnLineRequest(ASSET, 7, " Tenant A "))));
    UUID documentId = created.response().id();
    UUID lineId = created.response().lines().getFirst().id();

    when(dependencies.readWarehouseIdentity(WAREHOUSE))
        .thenReturn(new LogisticsDependencyGateway.WarehouseIdentity(WAREHOUSE, 5, true, "Europe/Moscow"));
    when(dependencies.readRentalItemSnapshot(ASSET))
        .thenReturn(
            new LogisticsDependencyGateway.RentalItemSnapshot(
                ASSET,
                7,
                WAREHOUSE,
                "RENTED",
                List.of(new LogisticsDependencyGateway.EquipmentContent(UUID.randomUUID(), 2))));
    when(dependencies.acquireReturnLease(any(), eq(ASSET), eq(7L), eq(documentId), eq(lineId)))
        .thenReturn(
            new LogisticsDependencyGateway.OperationLease(
                UUID.randomUUID(),
                3,
                ASSET,
                11,
                "ACTIVE",
                OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(5)));
    when(dependencies.applyReturnIntake(any(), eq(ASSET), eq(7L), any(), eq(11L), eq(documentId), eq(lineId)))
        .thenReturn(
            new LogisticsDependencyGateway.RentalItemSnapshot(
                ASSET,
                8,
                WAREHOUSE,
                "AFTER_RENT",
                List.of(new LogisticsDependencyGateway.EquipmentContent(UUID.randomUUID(), 2))));

    UUID registrationKey = UUID.randomUUID();
    LogisticsDocumentService.CreateResult started =
        documents.registerReturn(
            SUBJECT,
            registrationKey,
            CORRELATION,
            documentId,
            0,
            new ReturnPickupRequest(
                "Driver snapshot", LocalDate.parse("2026-07-01")));
    LogisticsDocumentService.CreateResult replayed =
        documents.registerReturn(
            SUBJECT,
            registrationKey,
            CORRELATION,
            documentId,
            0,
            new ReturnPickupRequest(
                "Driver snapshot", LocalDate.parse("2026-07-01")));

    assertThat(started.replayed()).isFalse();
    assertThat(replayed.replayed()).isTrue();
    assertThat(started.response().state()).isEqualTo(LogisticsDocumentState.REGISTERING);

    processor.processUntilIdle(documentId);

    assertThat(documents.get(documentId, LogisticsDocumentType.RETURN).state())
        .isEqualTo(LogisticsDocumentState.INSPECTION_REQUIRED);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from logistics_external_attempt where result='CONFIRMED'",
                Long.class))
        .isEqualTo(4);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from logistics_guard where guard_state='ACTIVE'",
                Long.class))
        .isOne();
    assertThat(
            jdbc.queryForObject(
                "select count(*) from domain_event where aggregate_id=?",
                Long.class,
                documentId.toString()))
        .isEqualTo(3);
    assertThat(
            jdbc.queryForObject(
                "select expected_contents_snapshot is not null and factual_contents_snapshot is not null "
                    + "from logistics_document_line where id=?",
                Boolean.class,
                lineId))
        .isTrue();

    verify(dependencies).readWarehouseIdentity(WAREHOUSE);
    verify(dependencies).readRentalItemSnapshot(ASSET);
    verify(dependencies).acquireReturnLease(any(), eq(ASSET), eq(7L), eq(documentId), eq(lineId));
    verify(dependencies).applyReturnIntake(any(), eq(ASSET), eq(7L), any(), eq(11L), eq(documentId), eq(lineId));
  }

  @Test
  void rejectsAStaleOrNonRentedAssetSnapshotWithoutAcquiringACanonicalLease() {
    LogisticsDocumentService.CreateResult created =
        documents.createReturn(
            SUBJECT,
            UUID.randomUUID(),
            CORRELATION,
            new CreateReturnRequest(
                WAREHOUSE, List.of(new ReturnLineRequest(ASSET, 7, "Tenant A"))));
    UUID documentId = created.response().id();

    when(dependencies.readWarehouseIdentity(WAREHOUSE))
        .thenReturn(new LogisticsDependencyGateway.WarehouseIdentity(WAREHOUSE, 5, true, "Europe/Moscow"));
    when(dependencies.readRentalItemSnapshot(ASSET))
        .thenReturn(
            new LogisticsDependencyGateway.RentalItemSnapshot(
                ASSET, 6, WAREHOUSE, "FREE", List.of()));

    documents.registerReturn(
        SUBJECT,
        UUID.randomUUID(),
        CORRELATION,
        documentId,
        0,
        new ReturnPickupRequest(
            "Driver snapshot", LocalDate.parse("2026-07-01")));
    processor.processUntilIdle(documentId);

    assertThat(documents.get(documentId, LogisticsDocumentType.RETURN).state())
        .isEqualTo(LogisticsDocumentState.CONFLICT);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from logistics_external_attempt where result='PERMANENT_REJECTION'",
                Long.class))
        .isOne();
    verify(dependencies, never()).acquireReturnLease(any(), any(), anyLong(), any(), any());
    verify(dependencies, never())
        .applyReturnIntake(any(), any(), anyLong(), any(), anyLong(), any(), any());
  }

  @Test
  void retriesAnUncertainFencedEffectWithTheSameDurableIdempotencyKey() {
    LogisticsDocumentService.CreateResult created =
        documents.createReturn(
            SUBJECT,
            UUID.randomUUID(),
            CORRELATION,
            new CreateReturnRequest(
                WAREHOUSE, List.of(new ReturnLineRequest(ASSET, 7, "Tenant A"))));
    UUID documentId = created.response().id();
    UUID lineId = created.response().lines().getFirst().id();
    UUID leaseId = UUID.randomUUID();

    when(dependencies.readWarehouseIdentity(WAREHOUSE))
        .thenReturn(new LogisticsDependencyGateway.WarehouseIdentity(WAREHOUSE, 5, true, "Europe/Moscow"));
    when(dependencies.readRentalItemSnapshot(ASSET))
        .thenReturn(
            new LogisticsDependencyGateway.RentalItemSnapshot(
                ASSET, 7, WAREHOUSE, "RENTED", List.of()));
    when(dependencies.acquireReturnLease(any(), eq(ASSET), eq(7L), eq(documentId), eq(lineId)))
        .thenReturn(
            new LogisticsDependencyGateway.OperationLease(
                leaseId,
                3,
                ASSET,
                11,
                "ACTIVE",
                OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(5)));
    when(dependencies.applyReturnIntake(any(), eq(ASSET), eq(7L), eq(leaseId), eq(11L), eq(documentId), eq(lineId)))
        .thenThrow(
            new dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyException(
                dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyException.FailureKind.TRANSIENT,
                "timeout"))
        .thenReturn(
            new LogisticsDependencyGateway.RentalItemSnapshot(
                ASSET, 8, WAREHOUSE, "AFTER_RENT", List.of()));

    documents.registerReturn(
        SUBJECT,
        UUID.randomUUID(),
        CORRELATION,
        documentId,
        0,
        new ReturnPickupRequest(
            "Driver snapshot", LocalDate.parse("2026-07-01")));
    processor.processUntilIdle(documentId);
    UUID persistedKey =
        jdbc.queryForObject(
            "select operation_id from logistics_external_attempt where operation_type='RETURN_ASSET_INTAKE'",
            UUID.class);
    jdbc.update(
        "update logistics_external_attempt set next_attempt_at=clock_timestamp() where operation_id=?",
        persistedKey);

    processor.processUntilIdle(documentId);

    ArgumentCaptor<UUID> keys = ArgumentCaptor.forClass(UUID.class);
    verify(dependencies, org.mockito.Mockito.times(2))
        .applyReturnIntake(
            keys.capture(), eq(ASSET), eq(7L), eq(leaseId), eq(11L), eq(documentId), eq(lineId));
    assertThat(keys.getAllValues()).containsOnly(persistedKey);
    assertThat(documents.get(documentId, LogisticsDocumentType.RETURN).state())
        .isEqualTo(LogisticsDocumentState.INSPECTION_REQUIRED);
  }
}
