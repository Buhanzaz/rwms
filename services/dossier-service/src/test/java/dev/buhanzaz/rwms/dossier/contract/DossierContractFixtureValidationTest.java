package dev.buhanzaz.rwms.dossier.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import dev.buhanzaz.rwms.dossier.eventing.DossierEnvelopeValidator;
import dev.buhanzaz.rwms.dossier.eventing.DossierProducerSchemaValidator;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class DossierContractFixtureValidationTest {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final Path EVENTS =
      Path.of(System.getProperty("rwms.contracts.dir")).resolve("events");
  private static final String EVENT_ID = "10000000-0000-0000-0000-000000000001";
  private static final String AGGREGATE_ID = "20000000-0000-0000-0000-000000000001";
  private static final String CABIN_ID = "30000000-0000-0000-0000-000000000001";
  private static final String WAREHOUSE_ID = "40000000-0000-0000-0000-000000000001";
  private static final String SECONDARY_ID = "60000000-0000-0000-0000-000000000001";
  private static final Set<String> SOURCE_TOPICS =
      Set.of(
          "rwms.asset.rental-item.v1",
          "rwms.maintenance.estimate.v1",
          "rwms.maintenance.repair.v1",
          "rwms.inventory.session.v1",
          "rwms.inventory.publication.v1",
          "rwms.media.media.v1",
          "rwms.logistics.return.v1",
          "rwms.logistics.shipment.v1",
          "rwms.logistics.transfer.v1",
          "rwms.task-board.board-task.v1",
          "rwms.task-board.queue-entry.v1");

  private final DossierProducerSchemaValidator producerSchemas =
      new DossierProducerSchemaValidator();
  private final DossierEnvelopeValidator envelopeValidator =
      new DossierEnvelopeValidator(new tools.jackson.databind.ObjectMapper(), producerSchemas);

  @Test
  void canonicalVisibleAndUnlinkedInputFixturesPassFullValidationForEveryAcceptedTopic() {
    Map<String, String> fixtures =
        Map.ofEntries(
            Map.entry(
                "rwms.asset.rental-item.v1",
                envelope(
                    "asset.rental-item.created.v1",
                    "asset-service",
                    "RENTAL_ITEM",
                    0,
                    """
                    {"rentalItemId":"%s","warehouseId":"%s","status":"AVAILABLE","numberSha256":"%s"}
                    """
                        .formatted(AGGREGATE_ID, WAREHOUSE_ID, "a".repeat(64)))),
            Map.entry(
                "rwms.maintenance.estimate.v1",
                envelope(
                    "maintenance.estimate.created.v1",
                    "maintenance-service",
                    "ESTIMATE",
                    0,
                    """
                    {"estimateId":"%s","warehouseId":"%s","rentalItemId":"%s","lifecycle":"DRAFT","revision":1,"dispatchDate":"2026-07-18","lineCount":0,"completionKind":"NOT_COMPLETED","repairId":null}
                    """
                        .formatted(AGGREGATE_ID, WAREHOUSE_ID, CABIN_ID))),
            Map.entry(
                "rwms.maintenance.repair.v1",
                envelope(
                    "maintenance.repair.created.v1",
                    "maintenance-service",
                    "REPAIR",
                    0,
                    """
                    {"repairId":"%s","rootRepairId":"%s","sourceRepairId":null,"estimateId":null,"warehouseId":"%s","rentalItemId":"%s","origin":"DIRECT_REPAIR","kind":"PRIMARY","executionState":"DRAFT","acceptanceState":"NOT_READY","dispatchDate":"2026-07-18","priority":3,"stages":[]}
                    """
                        .formatted(AGGREGATE_ID, AGGREGATE_ID, WAREHOUSE_ID, CABIN_ID))),
            Map.entry(
                "rwms.inventory.session.v1",
                envelope(
                    "inventory.finding.added.v1",
                    "inventory-service",
                    "FINDING",
                    0,
                    findingPayload(CABIN_ID))),
            Map.entry(
                "rwms.inventory.publication.v1",
                envelope(
                    "inventory.publication.ready.v1",
                    "inventory-service",
                    "PUBLICATION",
                    0,
                    """
                    {"inventoryId":"%s","findingId":"%s","publicationIntentId":"%s","warehouseId":"%s","publicationRevision":0,"state":"READY","attemptCount":0,"maintenanceRepairId":null,"failureCode":null,"sourceReference":{"inventoryId":"%s","findingId":"%s","sourceRevision":1,"requestSha256":"%s"}}
                    """
                        .formatted(
                            SECONDARY_ID,
                            SECONDARY_ID,
                            AGGREGATE_ID,
                            WAREHOUSE_ID,
                            SECONDARY_ID,
                            SECONDARY_ID,
                            "c".repeat(64)))),
            Map.entry(
                "rwms.media.media.v1",
                envelope(
                    "media.media.ready.v1",
                    "media-service",
                    "MEDIA",
                    1,
                    """
                    {"mediaId":"%s","ownerType":"INVENTORY_FINDING","ownerId":"%s","warehouseId":"%s","kind":"IMAGE","status":"READY","generation":1,"rotationDegrees":0}
                    """
                        .formatted(AGGREGATE_ID, SECONDARY_ID, WAREHOUSE_ID))),
            Map.entry(
                "rwms.logistics.return.v1",
                envelope(
                    "logistics.return.created.v1",
                    "logistics-service",
                    "RETURN",
                    0,
                    logisticsPayload("RETURN"))),
            Map.entry(
                "rwms.logistics.shipment.v1",
                envelope(
                    "logistics.shipment.created.v1",
                    "logistics-service",
                    "SHIPMENT",
                    0,
                    logisticsPayload("SHIPMENT"))),
            Map.entry(
                "rwms.logistics.transfer.v1",
                envelope(
                    "logistics.transfer.created.v1",
                    "logistics-service",
                    "TRANSFER",
                    0,
                    logisticsPayload("TRANSFER"))),
            Map.entry(
                "rwms.task-board.board-task.v1",
                envelope(
                    "task-board.board-task.created.v1",
                    "task-board-service",
                    "BOARD_TASK",
                    0,
                    """
                    {"boardTaskId":"%s","warehouseId":"%s","externalTaskId":null,"status":"ACTIVE","scheduledDate":"2026-07-18","lane":"SCHEDULED","priority":3,"pinned":false,"plannedDurationMinutes":null,"deadlineAt":null,"doneAt":null,"deleted":false}
                    """
                        .formatted(AGGREGATE_ID, WAREHOUSE_ID))),
            Map.entry(
                "rwms.task-board.queue-entry.v1",
                envelope(
                    "task-board.queue-entry.created.v1",
                    "task-board-service",
                    "QUEUE_ENTRY",
                    0,
                    """
                    {"queueEntryId":"%s","taskId":"%s","queueId":null,"routeIndex":0,"queuePosition":0,"entryType":"REAL","status":"WAITING","plannedDurationMinutes":null,"activeStartedAt":null,"pausedAt":null,"doneAt":null,"activeWorkSeconds":0,"originalBudgetSeconds":null,"currentBudgetSeconds":null,"pauseOrigin":null,"assignments":[],"timeEvents":[],"interruptions":[],"deleted":false}
                    """
                        .formatted(AGGREGATE_ID, SECONDARY_ID))));

    assertThat(fixtures).hasSize(11);
    fixtures.forEach(
        (topic, fixture) ->
            assertThatCode(
                    () ->
                        envelopeValidator.validate(
                            topic,
                            0,
                            1,
                            AGGREGATE_ID,
                            fixture.getBytes(StandardCharsets.UTF_8)))
                .as("canonical fixture for %s", topic)
                .doesNotThrowAnyException());
  }

  @Test
  void sharedInventoryTopicValidatesSessionAndFindingFamiliesIncludingNullSubject() {
    String session =
        envelope(
            "inventory.session.started.v1",
            "inventory-service",
            "SESSION",
            0,
            """
            {"inventoryId":"%s","warehouseId":"%s","sessionRevision":0,"lifecycle":"ACTIVE","businessDate":"2026-07-18","expectedCount":0,"findingCount":0,"terminalAt":null,"statistics":null}
            """
                .formatted(AGGREGATE_ID, WAREHOUSE_ID));
    String findingWithCabin =
        envelope(
            "inventory.finding.added.v1",
            "inventory-service",
            "FINDING",
            0,
            findingPayload(CABIN_ID));
    String findingWithoutCabin =
        envelope(
            "inventory.finding.added.v1",
            "inventory-service",
            "FINDING",
            0,
            findingPayload(null));

    envelopeValidator.validate(
        "rwms.inventory.session.v1",
        0,
        1,
        AGGREGATE_ID,
        session.getBytes(StandardCharsets.UTF_8));
    envelopeValidator.validate(
        "rwms.inventory.session.v1",
        0,
        2,
        AGGREGATE_ID,
        findingWithCabin.getBytes(StandardCharsets.UTF_8));
    envelopeValidator.validate(
        "rwms.inventory.session.v1",
        0,
        3,
        AGGREGATE_ID,
        findingWithoutCabin.getBytes(StandardCharsets.UTF_8));
  }

  @Test
  void outboundFixtureValidatesAndSemanticReferenceCannotLeakPrivateIdentityOrNavigation() throws Exception {
    JsonSchema schema = schema("dossier/dossier-cabin-activity-v1.schema.json");
    JsonNode valid =
        JSON.readTree(
            """
            {
              "envelopeVersion":2,
              "eventId":"70000000-0000-0000-0000-000000000001",
              "eventType":"dossier.cabin-activity.projected.v1",
              "eventVersion":1,
              "occurredAt":null,
              "recordedAt":"2026-07-18T00:00:00Z",
              "producer":"dossier-service",
              "aggregateType":"CABIN",
              "aggregateId":"30000000-0000-0000-0000-000000000001",
              "aggregateVersion":0,
              "correlation":{"correlationId":"50000000-0000-0000-0000-000000000001","causationId":null},
              "actorRef":{"subjectId":"80000000-0000-0000-0000-000000000001","principalType":"USER","profileRevision":null},
              "payload":{
                "activityId":"90000000-0000-0000-0000-000000000001",
                "cabinId":"30000000-0000-0000-0000-000000000001",
                "warehouseId":"40000000-0000-0000-0000-000000000001",
                "activityCode":"CABIN_CREATED",
                "sourceRef":{"producer":"asset-service","aggregateType":"RENTAL_ITEM","aggregateId":"20000000-0000-0000-0000-000000000001"}
              }
            }
            """);
    assertThat(schema.validate(valid)).isEmpty();

    Map<String, String> visibleSourceFamilies =
        Map.of(
            "asset-service", "RENTAL_ITEM",
            "maintenance-service", "REPAIR",
            "inventory-service", "FINDING",
            "media-service", "MEDIA");
    visibleSourceFamilies.forEach(
        (producer, aggregateType) -> {
          JsonNode family = valid.deepCopy();
          var sourceRef =
              (com.fasterxml.jackson.databind.node.ObjectNode) family.at("/payload/sourceRef");
          sourceRef.put("producer", producer);
          sourceRef.put("aggregateType", aggregateType);
          assertThat(schema.validate(family)).as("visible outbound family %s", producer).isEmpty();
        });

    for (String forbidden : new String[] {"sourceEventId", "href", "displayName", "email", "login"}) {
      JsonNode leaking = valid.deepCopy();
      ((com.fasterxml.jackson.databind.node.ObjectNode) leaking.at("/payload/sourceRef"))
          .put(forbidden, "forbidden");
      assertThat(schema.validate(leaking)).as("forbidden outbound field %s", forbidden).isNotEmpty();
    }
  }

  @Test
  void sanitizedDltFixtureValidatesAndRejectsRawSourceOrSensitiveValues() throws Exception {
    JsonSchema schema = schema("dossier/dossier-sanitized-dlt-v1.schema.json");
    JsonNode valid =
        JSON.readTree(
            """
            {
              "failureId":"70000000-0000-0000-0000-000000000001",
              "failureCode":"INVALID_PAYLOAD",
              "sourceTopic":"rwms.asset.rental-item.v1",
              "sourcePartition":0,
              "sourceOffset":1,
              "recordKeySha256":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
              "messageSha256":"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
              "eventId":null,
              "recordedAt":"2026-07-18T00:00:00Z"
            }
            """);
    assertThat(schema.validate(valid)).isEmpty();

    for (String sourceTopic : SOURCE_TOPICS) {
      JsonNode family = valid.deepCopy();
      ((com.fasterxml.jackson.databind.node.ObjectNode) family).put("sourceTopic", sourceTopic);
      assertThat(schema.validate(family)).as("sanitized DLT family %s", sourceTopic).isEmpty();
    }

    for (String forbidden :
        new String[] {"rawBody", "payload", "recordKey", "actorRef", "warehouseId", "rawError", "token"}) {
      JsonNode leaking = valid.deepCopy();
      ((com.fasterxml.jackson.databind.node.ObjectNode) leaking).put(forbidden, "forbidden");
      assertThat(schema.validate(leaking)).as("forbidden DLT field %s", forbidden).isNotEmpty();
    }
  }

  private static String findingPayload(String assetId) {
    String asset = assetId == null ? "null" : "\"" + assetId + "\"";
    return """
        {"inventoryId":"%s","findingId":"%s","warehouseId":"%s","sessionRevision":0,"findingRevision":0,"origin":"EXPECTED","inspection":"NOT_INSPECTED","reconciliation":"MATCHED","assetId":%s,"sourceAttached":false,"mediaCount":0,"planFingerprintSha256":null}
        """
        .formatted(SECONDARY_ID, AGGREGATE_ID, WAREHOUSE_ID, asset);
  }

  private static String logisticsPayload(String type) {
    return """
        {"documentId":"%s","documentType":"%s","state":"CREATED","warehouseId":"%s","destinationWarehouseId":null,"lineCount":1,"resultCode":null}
        """
        .formatted(AGGREGATE_ID, type, WAREHOUSE_ID);
  }

  private static String envelope(
      String eventType, String producer, String aggregateType, long aggregateVersion, String payload) {
    return """
        {"envelopeVersion":2,"eventId":"%s","eventType":"%s","eventVersion":1,"occurredAt":"2026-07-18T00:00:00Z","recordedAt":"2026-07-18T00:00:00Z","producer":"%s","aggregateType":"%s","aggregateId":"%s","aggregateVersion":%d,"correlation":{"correlationId":"50000000-0000-0000-0000-000000000001","causationId":null},"actorRef":null,"payload":%s}
        """
        .formatted(EVENT_ID, eventType, producer, aggregateType, AGGREGATE_ID, aggregateVersion, payload.strip());
  }

  private static JsonSchema schema(String relative) throws Exception {
    return JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
        .getSchema(JSON.readTree(EVENTS.resolve(relative).toFile()));
  }
}
