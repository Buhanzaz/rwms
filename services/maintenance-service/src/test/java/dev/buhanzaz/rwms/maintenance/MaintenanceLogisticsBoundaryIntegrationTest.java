package dev.buhanzaz.rwms.maintenance;

import static dev.buhanzaz.rwms.maintenance.api.MaintenanceApiModels.LogisticsEquipmentShortage;
import static dev.buhanzaz.rwms.maintenance.api.MaintenanceApiModels.MediaReferenceInput;
import static dev.buhanzaz.rwms.maintenance.api.MaintenanceApiModels.UpsertLogisticsReturnShortageRequest;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.buhanzaz.rwms.maintenance.domain.CatalogVersion;
import dev.buhanzaz.rwms.maintenance.repository.CatalogVersionRepository;
import dev.buhanzaz.rwms.maintenance.security.MaintenanceAuthorizer;
import dev.buhanzaz.rwms.maintenance.service.LogisticsReturnShortageService;
import dev.buhanzaz.rwms.maintenance.service.MaintenanceConflictException;
import dev.buhanzaz.rwms.maintenance.service.MaintenanceValidationException;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest(properties = {
    "spring.jpa.hibernate.ddl-auto=validate",
    "rwms.platform.kafka.enabled=false",
    "rwms.maintenance.task-reconciliation.initial-delay=1h",
    "rwms.maintenance.task-reconciliation.delay=1h",
    "AUTH_ISSUER=http://auth.test",
    "PANEL_ORIGIN=http://panel.test"
})
@ActiveProfiles("test")
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class MaintenanceLogisticsBoundaryIntegrationTest {
  @Container
  @ServiceConnection
  static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

  @Autowired LogisticsReturnShortageService logistics;
  @Autowired MaintenanceAuthorizer authorizer;
  @Autowired CatalogVersionRepository catalogs;
  @Autowired JdbcTemplate jdbc;

  @BeforeEach
  void reset() {
    jdbc.execute(
        """
        truncate table
          logistics_return_shortage,
          maintenance_media_reference,
          estimate_revision,
          estimate_line,
          estimate_plan_stage,
          maintenance_estimate,
          integration_reconciliation,
          outbox_event,
          aggregate_snapshot,
          projection_checkpoint,
          domain_event,
          event_stream_head,
          catalog_version
        cascade
        """);
    CatalogVersion catalog =
        CatalogVersion.draft(UUID.randomUUID(), "1".repeat(64), 0, 0, "{}");
    catalog.activate();
    catalogs.saveAndFlush(catalog);
  }

  @Test
  void storesOneImmutableReturnLineSourceAndReplaysOnlyTheCanonicalSnapshot() {
    UUID returnId = UUID.randomUUID();
    UUID lineId = UUID.randomUUID();
    UUID warehouseId = UUID.randomUUID();
    UUID rentalItemId = UUID.randomUUID();
    UUID firstEquipment = UUID.randomUUID();
    UUID secondEquipment = UUID.randomUUID();
    UUID firstMedia = UUID.randomUUID();
    UUID secondMedia = UUID.randomUUID();
    LocalDate dispatchDate = LocalDate.of(2026, 7, 27);
    UpsertLogisticsReturnShortageRequest request =
        new UpsertLogisticsReturnShortageRequest(
            warehouseId,
            rentalItemId,
            7L,
            dispatchDate,
            List.of(
                new MediaReferenceInput(secondMedia, 3L),
                new MediaReferenceInput(firstMedia, 1L)),
            List.of(
                new LogisticsEquipmentShortage(secondEquipment, 2L),
                new LogisticsEquipmentShortage(firstEquipment, 1L)));

    LogisticsReturnShortageService.UpsertResult first =
        logistics.upsert(returnId, lineId, request);
    LogisticsReturnShortageService.UpsertResult replay =
        logistics.upsert(
            returnId,
            lineId,
            new UpsertLogisticsReturnShortageRequest(
                warehouseId,
                rentalItemId,
                7L,
                dispatchDate,
                List.of(
                    new MediaReferenceInput(firstMedia, 1L),
                    new MediaReferenceInput(secondMedia, 3L)),
                List.of(
                    new LogisticsEquipmentShortage(firstEquipment, 1L),
                    new LogisticsEquipmentShortage(secondEquipment, 2L))));

    assertThat(first.replayed()).isFalse();
    assertThat(replay.replayed()).isTrue();
    assertThat(replay.response()).isEqualTo(first.response());
    assertThat(first.response().shortages())
        .extracting(LogisticsEquipmentShortage::equipmentId)
        .containsExactlyElementsOf(
            List.of(firstEquipment, secondEquipment).stream()
                .sorted(java.util.Comparator.comparing(UUID::toString))
                .toList());
    assertThat(first.response().snapshotSha256()).matches("[0-9a-f]{64}");
    assertThat(first.response().estimateId()).isNotNull();
    assertThat(jdbc.queryForObject(
        "select count(*) from logistics_return_shortage", Integer.class)).isOne();
    assertThat(jdbc.queryForObject(
        "select estimate_id from logistics_return_shortage where return_id=? and line_id=?",
        UUID.class,
        returnId,
        lineId))
        .isEqualTo(first.response().estimateId());
    assertThat(jdbc.queryForObject(
        "select count(*) from maintenance_estimate", Integer.class)).isOne();
    assertThat(jdbc.queryForObject(
        "select state from maintenance_estimate where id=?",
        String.class,
        first.response().estimateId()))
        .isEqualTo("DRAFT");
    assertThat(jdbc.queryForObject(
        "select source_party from maintenance_estimate where id=?",
        String.class,
        first.response().estimateId()))
        .isEqualTo("Возврат из аренды");
    assertThat(jdbc.queryForObject(
        "select dispatch_date from estimate_revision where estimate_id=?",
        LocalDate.class,
        first.response().estimateId()))
        .isEqualTo(dispatchDate);
    assertThat(jdbc.queryForObject(
        "select count(*) from estimate_line where estimate_id=?",
        Integer.class,
        first.response().estimateId()))
        .isZero();
    assertThat(jdbc.queryForObject(
        "select count(*) from estimate_plan_stage where estimate_id=?",
        Integer.class,
        first.response().estimateId()))
        .isZero();
    assertThat(jdbc.queryForObject(
        "select count(*) from maintenance_media_reference where aggregate_type='ESTIMATE' and aggregate_id=?",
        Integer.class,
        first.response().estimateId()))
        .isEqualTo(2);
    assertThat(jdbc.queryForObject(
        "select count(*) from maintenance_repair", Integer.class)).isZero();
    assertThat(jdbc.queryForObject(
        "select count(*) from domain_event where aggregate_type='ESTIMATE'", Integer.class)).isOne();
    assertThat(jdbc.queryForObject(
        """
        select count(*) from integration_reconciliation
        where dependency_type='MEDIA'
          and operation_type='UPSERT_MEDIA_OWNER_PROOF'
          and media_owner_type='MAINTENANCE_ESTIMATE'
          and media_owner_id=?
        """,
        Integer.class,
        first.response().estimateId()))
        .isOne();
    assertThat(logistics.get(returnId, lineId)).isEqualTo(first.response());

    assertThatThrownBy(
            () ->
                logistics.upsert(
                    returnId,
                    lineId,
                    new UpsertLogisticsReturnShortageRequest(
                        warehouseId,
                        rentalItemId,
                        7L,
                        dispatchDate,
                        List.of(
                            new MediaReferenceInput(firstMedia, 1L),
                            new MediaReferenceInput(secondMedia, 3L)),
                        List.of(new LogisticsEquipmentShortage(firstEquipment, 2L)))))
        .isInstanceOf(MaintenanceConflictException.class)
        .hasMessageContaining("different immutable shortage snapshot");
  }

  @Test
  void concurrentFirstUseCreatesOneSourceAndAChangedPayloadIsRejected() throws Exception {
    UUID returnId = UUID.randomUUID();
    UUID lineId = UUID.randomUUID();
    UpsertLogisticsReturnShortageRequest request =
        new UpsertLogisticsReturnShortageRequest(
            UUID.randomUUID(),
            UUID.randomUUID(),
            0L,
            LocalDate.of(2026, 7, 27),
            List.of(new MediaReferenceInput(UUID.randomUUID(), 1L)),
            List.of(new LogisticsEquipmentShortage(UUID.randomUUID(), 1L)));
    CountDownLatch start = new CountDownLatch(1);
    var executor = Executors.newFixedThreadPool(2);
    try {
      List<Callable<Boolean>> calls = java.util.stream.IntStream.range(0, 2)
          .mapToObj(
              ignored ->
                  (Callable<Boolean>)
                      () -> {
                        start.await();
                        return logistics.upsert(returnId, lineId, request).replayed();
                      })
          .toList();
      List<Future<Boolean>> futures = calls.stream().map(executor::submit).toList();
      start.countDown();

      List<Boolean> results = List.of(futures.getFirst().get(), futures.get(1).get());
      assertThat(results).containsExactlyInAnyOrder(false, true);
      assertThat(jdbc.queryForObject(
          "select count(*) from logistics_return_shortage", Integer.class)).isOne();
      assertThat(jdbc.queryForObject(
          "select count(*) from maintenance_estimate", Integer.class)).isOne();
      assertThat(jdbc.queryForObject(
          "select count(*) from domain_event where aggregate_type='ESTIMATE'",
          Integer.class))
          .isOne();
    } finally {
      executor.shutdownNow();
    }

    UUID duplicateEquipment = UUID.randomUUID();
    assertThatThrownBy(
            () ->
                logistics.upsert(
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    new UpsertLogisticsReturnShortageRequest(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        0L,
                        LocalDate.of(2026, 7, 27),
                        List.of(new MediaReferenceInput(UUID.randomUUID(), 1L)),
                        List.of(
                            new LogisticsEquipmentShortage(duplicateEquipment, 1L),
                            new LogisticsEquipmentShortage(duplicateEquipment, 2L)))))
        .isInstanceOf(MaintenanceValidationException.class)
        .hasMessageContaining("duplicate equipment ID");

    UUID duplicateMedia = UUID.randomUUID();
    assertThatThrownBy(
            () ->
                logistics.upsert(
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    new UpsertLogisticsReturnShortageRequest(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        0L,
                        LocalDate.of(2026, 7, 27),
                        List.of(
                            new MediaReferenceInput(duplicateMedia, 1L),
                            new MediaReferenceInput(duplicateMedia, 1L)),
                        List.of(
                            new LogisticsEquipmentShortage(UUID.randomUUID(), 1L)))))
        .isInstanceOf(MaintenanceValidationException.class)
        .hasMessageContaining("duplicate media ID");
  }

  @Test
  void acceptsOnlyTheExactLogisticsServiceCredential() {
    authorizer.requireLogisticsService(
        serviceJwt(
            "logistics-service",
            "logistics-service",
            List.of("maintenance.logistics")));

    for (Jwt invalid : List.of(
        serviceJwt("other-service", "logistics-service", List.of("maintenance.logistics")),
        serviceJwt("logistics-service", "other-service", List.of("maintenance.logistics")),
        serviceJwt(
            "logistics-service",
            "logistics-service",
            List.of("maintenance.logistics", "rwms.write")),
        userJwt())) {
      assertThatThrownBy(() -> authorizer.requireLogisticsService(invalid))
          .isInstanceOf(AccessDeniedException.class);
    }
  }

  private static Jwt serviceJwt(String subject, String clientId, List<String> scopes) {
    return Jwt.withTokenValue("service-token")
        .header("alg", "none")
        .subject(subject)
        .audience(List.of("rwms-services"))
        .claim("principal_type", "SERVICE")
        .claim("client_id", clientId)
        .claim("scope", scopes)
        .build();
  }

  private static Jwt userJwt() {
    return Jwt.withTokenValue("user-token")
        .header("alg", "none")
        .subject(UUID.randomUUID().toString())
        .audience(List.of("rwms-services"))
        .claim("principal_type", "USER")
        .claim("scope", List.of("maintenance.logistics"))
        .build();
  }
}
