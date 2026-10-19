package dev.buhanzaz.rwms.inventory.eventing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.buhanzaz.rwms.inventory.InventoryServiceApplication;
import dev.buhanzaz.rwms.inventory.domain.InventorySession;
import dev.buhanzaz.rwms.inventory.integration.InventoryDependencyGateway;
import dev.buhanzaz.rwms.inventory.repository.InventoryReturnInspectionImportRepository;
import dev.buhanzaz.rwms.inventory.repository.InventorySessionRepository;
import dev.buhanzaz.rwms.inventory.service.InventoryLogisticsReturnInboxProcessor;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

@SpringBootTest(
    classes = InventoryServiceApplication.class,
    properties = {
      "spring.jpa.hibernate.ddl-auto=validate",
      "rwms.platform.kafka.enabled=false",
      "rwms.inventory.dependencies.enabled=false"
    })
@ActiveProfiles("test")
class InventoryNormalReturnIngestionIntegrationTest {
  private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

  static {
    POSTGRES.start();
  }

  @Autowired InventoryLogisticsReturnInboxProcessor processor;
  @Autowired InventorySessionRepository sessions;
  @Autowired InventoryReturnInspectionImportRepository imports;
  @Autowired JdbcTemplate jdbc;
  @Autowired ObjectMapper mapper;
  @MockitoBean InventoryDependencyGateway dependencies;

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

  @BeforeEach
  void clearRows() {
    jdbc.execute(
        """
        truncate table inventory_return_inspection_import,inventory_session,domain_event,
          event_stream_head,aggregate_snapshot,projection_checkpoint,outbox_event,inbox_message,
          consumer_aggregate_checkpoint,version_gap_quarantine,sanitized_dead_letter,
          inventory_media_fact_projection restart identity cascade
        """);
  }

  @Test
  void acceptedReturnImportsEveryLineOnceIntoTheActiveInventory() {
    UUID warehouseId = UUID.randomUUID();
    UUID returnId = UUID.randomUUID();
    UUID lineId = UUID.randomUUID();
    UUID assetId = UUID.randomUUID();
    UUID secondLineId = UUID.randomUUID();
    UUID secondAssetId = UUID.randomUUID();
    UUID eventId = UUID.randomUUID();
    InventorySession session = activeSession(warehouseId);
    OffsetDateTime arrivedAt = OffsetDateTime.now(ZoneOffset.UTC).plusSeconds(1);
    OffsetDateTime completedAt = arrivedAt.plusSeconds(1);
    var line = line(lineId, assetId, 7, "FREE", completedAt);
    var secondLine = line(secondLineId, secondAssetId, 3, "FREE", completedAt);
    var proof =
        new InventoryDependencyGateway.NormalReturnInspection(
            returnId,
            4,
            warehouseId,
            arrivedAt,
            completedAt,
            "ACCEPTED",
            List.of(line, secondLine));
    when(dependencies.normalReturnInspection(returnId)).thenReturn(proof);
    when(dependencies.currentAsset(assetId))
        .thenReturn(Optional.of(asset(assetId, warehouseId, 8, "FREE")));
    when(dependencies.currentAsset(secondAssetId))
        .thenReturn(Optional.of(asset(secondAssetId, warehouseId, 4, "FREE")));
    byte[] event =
        returnEvent(
            eventId,
            returnId,
            4,
            warehouseId,
            "logistics.return.accepted.v1",
            "ACCEPTED",
            2,
            completedAt.plusSeconds(1));

    processor.initial(InventoryLogisticsReturnInboxStore.Source.LOGISTICS_RETURN, event, key(returnId));
    processor.initial(InventoryLogisticsReturnInboxStore.Source.LOGISTICS_RETURN, event, key(returnId));

    assertThat(imports.count()).isEqualTo(2);
    assertThat(imports.findById(new dev.buhanzaz.rwms.inventory.domain.InventoryReturnInspectionImport.Key(returnId, lineId, 4)))
        .hasValueSatisfying(
            receipt -> {
              assertThat(receipt.getInventoryId()).isEqualTo(session.getId());
              assertThat(receipt.getEstimateId()).isNull();
              assertThat(receipt.getWarehouseId()).isEqualTo(warehouseId);
            });
    assertThat(
            jdbc.queryForObject(
                "select count(*) from inventory_finding where inventory_id=? and inspection_source='LOGISTICS_RETURN'",
                Integer.class,
                session.getId()))
        .isEqualTo(2);
    assertThat(processed(eventId)).isTrue();
  }

  @Test
  void estimateRequestedWaitsForCompletionThenImportsOnlyTheProvenLine() {
    UUID warehouseId = UUID.randomUUID();
    UUID returnId = UUID.randomUUID();
    UUID lineId = UUID.randomUUID();
    UUID assetId = UUID.randomUUID();
    UUID estimateId = UUID.randomUUID();
    InventorySession session = activeSession(warehouseId);
    OffsetDateTime arrivedAt = OffsetDateTime.now(ZoneOffset.UTC).plusSeconds(1);
    OffsetDateTime logisticsCompletedAt = arrivedAt.plusSeconds(1);
    var line = line(lineId, assetId, 11, "WAITING_ESTIMATE_CONFIRMATION", logisticsCompletedAt);

    UUID requestedEventId = UUID.randomUUID();
    processor.initial(
        InventoryLogisticsReturnInboxStore.Source.LOGISTICS_RETURN,
        returnEvent(
            requestedEventId,
            returnId,
            6,
            warehouseId,
            "logistics.return.estimate-requested.v1",
            "ESTIMATE_REQUESTED",
            1,
            logisticsCompletedAt.plusSeconds(1)),
        key(returnId));

    assertThat(imports.count()).isZero();
    verify(dependencies, never()).normalReturnInspection(returnId);
    assertThat(processed(requestedEventId)).isTrue();

    OffsetDateTime estimateCompletedAt = logisticsCompletedAt.plusSeconds(2);
    var logisticsProof =
        new InventoryDependencyGateway.NormalReturnInspection(
            returnId,
            6,
            warehouseId,
            arrivedAt,
            logisticsCompletedAt,
            "ESTIMATE_REQUESTED",
            List.of(line));
    var estimateProof =
        new InventoryDependencyGateway.CompletedReturnEstimateProof(
            estimateId,
            3,
            2,
            returnId,
            lineId,
            warehouseId,
            assetId,
            10,
            arrivedAt,
            estimateCompletedAt,
            "NON_EMPTY",
            UUID.randomUUID());
    when(dependencies.completedReturnEstimate(estimateId)).thenReturn(Optional.of(estimateProof));
    when(dependencies.normalReturnInspection(returnId)).thenReturn(logisticsProof);
    when(dependencies.currentAsset(assetId))
        .thenReturn(
            Optional.of(
                asset(assetId, warehouseId, 12, "WAITING_ESTIMATE_CONFIRMATION")));
    UUID completionEventId = UUID.randomUUID();

    processor.initial(
        InventoryLogisticsReturnInboxStore.Source.MAINTENANCE_ESTIMATE,
        estimateEvent(
            completionEventId,
            estimateId,
            3,
            warehouseId,
            assetId,
            2,
            "maintenance.estimate.completed.v1",
            "COMPLETED",
            "NON_EMPTY",
            1,
            estimateProof.repairId(),
            estimateCompletedAt.plusSeconds(1)),
        key(estimateId));

    assertThat(imports.findByEstimateIdAndWarehouseId(estimateId, warehouseId))
        .hasValueSatisfying(
            receipt -> {
              assertThat(receipt.getInventoryId()).isEqualTo(session.getId());
              assertThat(receipt.getLineId()).isEqualTo(lineId);
              assertThat(receipt.getImportedAt()).isNotNull();
            });
    assertThat(processed(completionEventId)).isTrue();
  }

  @Test
  void unrelatedCompletedEstimateAndMalformedFactAreAcknowledgedAndRejectedPrecisely()
      throws Exception {
    UUID warehouseId = UUID.randomUUID();
    activeSession(warehouseId);
    UUID estimateId = UUID.randomUUID();
    UUID eventId = UUID.randomUUID();
    when(dependencies.completedReturnEstimate(estimateId)).thenReturn(Optional.empty());
    byte[] event =
        estimateEvent(
            eventId,
            estimateId,
            1,
            warehouseId,
            UUID.randomUUID(),
            1,
            "maintenance.estimate.completed.v1",
            "COMPLETED",
            "EMPTY",
            0,
            null,
            OffsetDateTime.now(ZoneOffset.UTC).plusSeconds(2));

    processor.initial(
        InventoryLogisticsReturnInboxStore.Source.MAINTENANCE_ESTIMATE, event, key(estimateId));

    assertThat(processed(eventId)).isTrue();
    assertThat(imports.count()).isZero();

    UUID malformedId = UUID.randomUUID();
    byte[] malformed =
        returnEvent(
            malformedId,
            UUID.randomUUID(),
            1,
            warehouseId,
            "logistics.return.accepted.v1",
            "ACCEPTED",
            1,
            OffsetDateTime.now(ZoneOffset.UTC).plusSeconds(2));
    processor.initial(
        InventoryLogisticsReturnInboxStore.Source.LOGISTICS_RETURN,
        malformed,
        key(UUID.randomUUID()));
    assertThat(
            jdbc.queryForObject(
                "select count(*) from sanitized_dead_letter where source_event_id=? and failure_code='VALIDATION_REJECTED'",
                Integer.class,
                malformedId))
        .isOne();

    UUID stringVersionId = UUID.randomUUID();
    UUID stringVersionReturnId = UUID.randomUUID();
    ObjectNode stringVersion =
        (ObjectNode)
            mapper.readTree(
                returnEvent(
                    stringVersionId,
                    stringVersionReturnId,
                    1,
                    warehouseId,
                    "logistics.return.accepted.v1",
                    "ACCEPTED",
                    1,
                    OffsetDateTime.now(ZoneOffset.UTC).plusSeconds(2)));
    stringVersion.put("aggregateVersion", "1");
    processor.initial(
        InventoryLogisticsReturnInboxStore.Source.LOGISTICS_RETURN,
        stringVersion.toString().getBytes(StandardCharsets.UTF_8),
        key(stringVersionReturnId));
    assertThat(
            jdbc.queryForObject(
                "select count(*) from sanitized_dead_letter where source_event_id=? and failure_code='VALIDATION_REJECTED'",
                Integer.class,
                stringVersionId))
        .isOne();
  }

  @Test
  void delayedCompletionUsesTheNewerAmendedProofAndAcceptsNullableOccurredAt() throws Exception {
    UUID warehouseId = UUID.randomUUID();
    UUID returnId = UUID.randomUUID();
    UUID lineId = UUID.randomUUID();
    UUID assetId = UUID.randomUUID();
    UUID estimateId = UUID.randomUUID();
    OffsetDateTime arrivedAt = OffsetDateTime.now(ZoneOffset.UTC).minusSeconds(5);
    activeSession(warehouseId);
    OffsetDateTime logisticsCompletedAt = arrivedAt.plusSeconds(1);
    OffsetDateTime estimateCompletedAt = OffsetDateTime.now(ZoneOffset.UTC).plusSeconds(1);
    var line = line(lineId, assetId, 5, "WAITING_ESTIMATE_CONFIRMATION", logisticsCompletedAt);
    when(dependencies.normalReturnInspection(returnId))
        .thenReturn(
            new InventoryDependencyGateway.NormalReturnInspection(
                returnId,
                8,
                warehouseId,
                arrivedAt,
                logisticsCompletedAt,
                "ESTIMATE_REQUESTED",
                List.of(line)));
    UUID currentRepairId = UUID.randomUUID();
    when(dependencies.completedReturnEstimate(estimateId))
        .thenReturn(
            Optional.of(
                new InventoryDependencyGateway.CompletedReturnEstimateProof(
                    estimateId,
                    4,
                    2,
                    returnId,
                    lineId,
                    warehouseId,
                    assetId,
                    5,
                    arrivedAt,
                    estimateCompletedAt,
                    "NON_EMPTY",
                    currentRepairId)));
    when(dependencies.currentAsset(assetId))
        .thenReturn(
            Optional.of(
                asset(assetId, warehouseId, 6, "WAITING_ESTIMATE_CONFIRMATION")));
    UUID eventId = UUID.randomUUID();
    byte[] originallyEmptyCompletion =
        estimateEvent(
            eventId,
            estimateId,
            3,
            warehouseId,
            assetId,
            1,
            "maintenance.estimate.completed.v1",
            "COMPLETED",
            "EMPTY",
            0,
            null,
            estimateCompletedAt.plusSeconds(1));
    ObjectNode nullableOccurredAt = (ObjectNode) mapper.readTree(originallyEmptyCompletion);
    nullableOccurredAt.putNull("occurredAt");

    processor.initial(
        InventoryLogisticsReturnInboxStore.Source.MAINTENANCE_ESTIMATE,
        nullableOccurredAt.toString().getBytes(StandardCharsets.UTF_8),
        key(estimateId));

    assertThat(imports.findByEstimateIdAndWarehouseId(estimateId, warehouseId)).isPresent();
    assertThat(processed(eventId)).isTrue();
    assertThat(
            jdbc.queryForObject(
                "select count(*) from inventory_membership_movement where inventory_id=(select inventory_id from inventory_return_inspection_import where estimate_id=?)",
                Integer.class,
                estimateId))
        .isZero();
  }

  @Test
  void acceptedReturnWithoutAnActiveInventoryIsAcknowledgedWithoutImport() {
    UUID warehouseId = UUID.randomUUID();
    UUID returnId = UUID.randomUUID();
    UUID lineId = UUID.randomUUID();
    UUID assetId = UUID.randomUUID();
    UUID eventId = UUID.randomUUID();
    OffsetDateTime arrivedAt = OffsetDateTime.now(ZoneOffset.UTC).minusSeconds(2);
    OffsetDateTime completedAt = arrivedAt.plusSeconds(1);
    var line = line(lineId, assetId, 2, "FREE", completedAt);
    when(dependencies.normalReturnInspection(returnId))
        .thenReturn(
            new InventoryDependencyGateway.NormalReturnInspection(
                returnId,
                1,
                warehouseId,
                arrivedAt,
                completedAt,
                "ACCEPTED",
                List.of(line)));
    when(dependencies.currentAsset(assetId))
        .thenReturn(Optional.of(asset(assetId, warehouseId, 2, "FREE")));

    processor.initial(
        InventoryLogisticsReturnInboxStore.Source.LOGISTICS_RETURN,
        returnEvent(
            eventId,
            returnId,
            1,
            warehouseId,
            "logistics.return.accepted.v1",
            "ACCEPTED",
            1,
            completedAt.plusSeconds(1)),
        key(returnId));

    assertThat(imports.count()).isZero();
    assertThat(processed(eventId)).isTrue();
  }

  private InventorySession activeSession(UUID warehouseId) {
    return sessions.saveAndFlush(
        InventorySession.start(
            warehouseId,
            1,
            "Europe/Moscow",
            LocalDate.now(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            "1".repeat(64),
            0,
            "2".repeat(64),
            UUID.randomUUID(),
            "Inventory operator",
            actor().toString()));
  }

  private InventoryDependencyGateway.NormalReturnInspectionLine line(
      UUID lineId,
      UUID assetId,
      long assetVersion,
      String status,
      OffsetDateTime completedAt) {
    return new InventoryDependencyGateway.NormalReturnInspectionLine(
        lineId,
        assetId,
        assetVersion,
        status,
        List.of(
            new InventoryDependencyGateway.NormalReturnInspectionMedia(
                UUID.randomUUID(), 1, "LOGISTICS_RETURN", completedAt.minusNanos(1))));
  }

  private InventoryDependencyGateway.LiveAssetSnapshot asset(
      UUID assetId, UUID warehouseId, long version, String status) {
    String number = "CAB-" + assetId.toString().substring(0, 8);
    return new InventoryDependencyGateway.LiveAssetSnapshot(
        assetId,
        version,
        warehouseId,
        status,
        number,
        number.toLowerCase(java.util.Locale.ROOT),
        "TENANT",
        mapper.createObjectNode().put("number", number),
        mapper.createArrayNode());
  }

  private byte[] returnEvent(
      UUID eventId,
      UUID returnId,
      long version,
      UUID warehouseId,
      String eventType,
      String state,
      int lineCount,
      OffsetDateTime occurredAt) {
    ObjectNode root = envelope(eventId, eventType, "logistics-service", "RETURN", returnId, version, occurredAt);
    root.putObject("payload")
        .put("documentId", returnId.toString())
        .put("documentType", "RETURN")
        .put("state", state)
        .put("warehouseId", warehouseId.toString())
        .putNull("destinationWarehouseId")
        .put("lineCount", lineCount)
        .put("resultCode", state);
    return root.toString().getBytes(StandardCharsets.UTF_8);
  }

  private byte[] estimateEvent(
      UUID eventId,
      UUID estimateId,
      long version,
      UUID warehouseId,
      UUID assetId,
      int revision,
      String eventType,
      String lifecycle,
      String completionKind,
      int lineCount,
      UUID repairId,
      OffsetDateTime occurredAt) {
    ObjectNode root = envelope(eventId, eventType, "maintenance-service", "ESTIMATE", estimateId, version, occurredAt);
    ObjectNode payload =
        root.putObject("payload")
            .put("estimateId", estimateId.toString())
            .put("warehouseId", warehouseId.toString())
            .put("rentalItemId", assetId.toString())
            .put("lifecycle", lifecycle)
            .put("revision", revision)
            .put("dispatchDate", LocalDate.now().toString())
            .put("lineCount", lineCount)
            .put("completionKind", completionKind)
            .put("forceCapitalRepair", false);
    if (repairId == null) payload.putNull("repairId");
    else payload.put("repairId", repairId.toString());
    return root.toString().getBytes(StandardCharsets.UTF_8);
  }

  private ObjectNode envelope(
      UUID eventId,
      String eventType,
      String producer,
      String aggregateType,
      UUID aggregateId,
      long version,
      OffsetDateTime occurredAt) {
    ObjectNode root = mapper.createObjectNode();
    root.put("envelopeVersion", 2);
    root.put("eventId", eventId.toString());
    root.put("eventType", eventType);
    root.put("eventVersion", 1);
    root.put("occurredAt", occurredAt.toString());
    root.put("recordedAt", occurredAt.plusNanos(1).toString());
    root.put("producer", producer);
    root.put("aggregateType", aggregateType);
    root.put("aggregateId", aggregateId.toString());
    root.put("aggregateVersion", version);
    root.putObject("correlation")
        .put("correlationId", UUID.randomUUID().toString())
        .putNull("causationId");
    root.putNull("actorRef");
    return root;
  }

  private ObjectNode actor() {
    return mapper.createObjectNode()
        .put("subjectId", UUID.randomUUID().toString())
        .put("principalType", "USER")
        .putNull("profileRevision");
  }

  private byte[] key(UUID aggregateId) {
    return aggregateId.toString().getBytes(StandardCharsets.UTF_8);
  }

  private boolean processed(UUID eventId) {
    return Boolean.TRUE.equals(
        jdbc.queryForObject(
            "select status='PROCESSED' from inbox_message where event_id=?", Boolean.class, eventId));
  }
}
