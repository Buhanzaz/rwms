package dev.buhanzaz.rwms.taskboard;
import static dev.buhanzaz.rwms.taskboard.QueueFixtureModels.*;

import static dev.buhanzaz.rwms.taskboard.api.ApiModels.CreateBoardTaskRequest;
import static dev.buhanzaz.rwms.taskboard.api.ApiModels.QueueBindingRequest;
import static dev.buhanzaz.rwms.taskboard.api.ApiModels.QueueDefinitionRequest;
import static dev.buhanzaz.rwms.taskboard.api.ApiModels.RegisterExternalTaskRequest;
import static dev.buhanzaz.rwms.taskboard.api.ApiModels.RouteStepRequest;
import static dev.buhanzaz.rwms.taskboard.api.ApiModels.TaskSourceReferenceDto;
import static dev.buhanzaz.rwms.taskboard.api.ApiModels.WorkQueueDto;
import static org.assertj.core.api.Assertions.assertThat;

import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import dev.buhanzaz.rwms.taskboard.domain.QueueType;
import dev.buhanzaz.rwms.taskboard.domain.TaskLane;
import dev.buhanzaz.rwms.taskboard.domain.TaskSourceType;
import dev.buhanzaz.rwms.taskboard.eventing.TaskBoardAggregateType;
import dev.buhanzaz.rwms.taskboard.eventing.TaskBoardEventTypes;
import dev.buhanzaz.rwms.taskboard.service.RegistryService;
import dev.buhanzaz.rwms.taskboard.service.TaskBoardService;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.yaml.snakeyaml.Yaml;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@ActiveProfiles("test")
class TaskBoardContractIntegrationTest extends PostgresIntegrationTestSupport {
  private static final UUID WAREHOUSE =
      UUID.fromString("00000000-0000-0000-0000-000000000401");
  private static final com.fasterxml.jackson.databind.ObjectMapper SCHEMA_OBJECT_MAPPER =
      new com.fasterxml.jackson.databind.ObjectMapper();
  private static final JsonSchemaFactory JSON_SCHEMA_FACTORY =
      JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);

  @Autowired RegistryService registry;
  @Autowired TaskBoardService board;
  @Autowired JdbcTemplate jdbc;
  @Autowired ObjectMapper objectMapper;

  @BeforeEach
  void clean() {
    cleanTaskBoardFixtures(jdbc);
  }

  @Test
  void emittedCreatedEnvelopeUsesTheCanonicalKafkaContractOnly() throws Exception {
    var queue = createQueue("Contract");
    board.createTask(
        WAREHOUSE,
        new CreateBoardTaskRequest(
            UUID.randomUUID(),
            "Contract task",
            null,
            null,
            null,
            null,
            List.of(new RouteStepRequest(queue.definitionId(), null, null))));

    Map<String, Object> event =
        jdbc.queryForMap(
            "select topic,envelope_body::text body,aggregate_version from outbox_event where event_type=?",
            TaskBoardEventTypes.BOARD_TASK_CREATED);
    var root = objectMapper.readTree(String.valueOf(event.get("body")));
    assertThat(event.get("topic")).isEqualTo(TaskBoardAggregateType.BOARD_TASK.topic());
    assertThat(root.required("envelopeVersion").intValue()).isEqualTo(2);
    assertThat(root.required("eventType").textValue())
        .isEqualTo(TaskBoardEventTypes.BOARD_TASK_CREATED);
    assertThat(root.required("eventVersion").intValue()).isEqualTo(1);
    assertThat(root.required("producer").textValue()).isEqualTo("task-board-service");
    assertThat(root.required("aggregateType").textValue()).isEqualTo("BOARD_TASK");
    assertThat(root.required("aggregateVersion").longValue())
        .isEqualTo(((Number) event.get("aggregate_version")).longValue());
    assertThat(root.required("payload").required("warehouseId").textValue())
        .isEqualTo(WAREHOUSE.toString());
    assertThat(jdbc.queryForObject("select to_regclass('public.task_board_outbox')", String.class))
        .isEqualTo("task_board_outbox");
    assertThat(jdbc.queryForObject("select to_regclass('public.task_board_inbox')", String.class))
        .isEqualTo("task_board_inbox");
    assertThat(jdbc.queryForObject("select count(*) from task_board_outbox", Integer.class))
        .isZero();
    assertThat(jdbc.queryForObject("select count(*) from task_board_inbox", Integer.class))
        .isZero();
  }

  @Test
  void canonicalKafkaSchemaDeclaresAllAggregateFamilyTopicsAndFacts() throws Exception {
    var schema =
        objectMapper.readTree(
            Files.readString(
                Path.of(
                    System.getProperty("rwms.contracts.dir"),
                    "events/task-board/task-board-events-v1.schema.json")));

    assertThat(schema.required("$schema").textValue())
        .isEqualTo("https://json-schema.org/draft/2020-12/schema");
    assertThat(schema.required("x-rwms-topics").toString())
        .contains(TaskBoardAggregateType.BOARD_TASK.topic());
    assertThat(schema.required("properties").required("eventType").required("enum").toString())
        .contains(
            TaskBoardEventTypes.BOARD_TASK_CREATED,
            TaskBoardEventTypes.BOARD_TASK_CANCELLED);
    var queueEntryFact = schema.required("$defs").required("queueEntryFact");
    assertThat(queueEntryFact.required("properties").has("externalTaskId")).isFalse();
    assertThat(queueEntryFact.required("required").toString()).contains("taskId", "routeIndex");
    var boardTaskFact = schema.required("$defs").required("boardTaskFact");
    assertThat(boardTaskFact.required("properties").toString())
        .contains("driverAudience", "plannedDriverWorkerId")
        .doesNotContain("plannedDriverName", "workerName");
    var workQueueFact = schema.required("$defs").required("workQueueFact");
    assertThat(workQueueFact.required("properties").toString())
        .contains("availableTaskLimit", "workerFeedEnabled");
    assertThat(workQueueFact.required("required").toString())
        .doesNotContain("availableTaskLimit", "workerFeedEnabled");
  }

  @Test
  void uuidOnlyQueueEntryV1WithoutExternalTaskIdValidates() throws Exception {
    var queueEntryFact =
        SCHEMA_OBJECT_MAPPER.readTree(
            """
            {
              "envelopeVersion": 2,
              "eventId": "69302adf-83d6-41bb-9bc0-d31a66c9c552",
              "eventType": "task-board.queue-entry.created.v1",
              "eventVersion": 1,
              "occurredAt": null,
              "recordedAt": "2026-07-13T12:00:00Z",
              "producer": "task-board-service",
              "aggregateType": "QUEUE_ENTRY",
              "aggregateId": "846599f0-64ef-4381-aaf5-da9362dafb43",
              "aggregateVersion": 0,
              "correlation": {
                "correlationId": "fdb5dc99-d430-4d9d-a025-622aab912aa2",
                "causationId": null
              },
              "actorRef": null,
              "payload": {
                "queueEntryId": "846599f0-64ef-4381-aaf5-da9362dafb43",
                "taskId": "bb5f3641-a284-4d46-9200-108c879d411d",
                "queueId": null,
                "routeIndex": 0,
                "queuePosition": 0,
                "entryType": "REAL",
                "status": "WAITING",
                "plannedDurationMinutes": null,
                "activeStartedAt": null,
                "pausedAt": null,
                "doneAt": null,
                "activeWorkSeconds": 0,
                "originalBudgetSeconds": null,
                "currentBudgetSeconds": null,
                "pauseOrigin": null,
                "assignments": [],
                "timeEvents": [],
                "interruptions": [],
                "deleted": false
              }
            }
            """);

    JsonSchema schema = canonicalEventSchema();
    assertThat(schema.validate(queueEntryFact)).isEmpty();

    var incompatibleFact = queueEntryFact.deepCopy();
    ((com.fasterxml.jackson.databind.node.ObjectNode) incompatibleFact.required("payload"))
        .put("externalTaskId", UUID.randomUUID().toString());
    assertThat(schema.validate(incompatibleFact)).isNotEmpty();
  }

  @Test
  void groupKpiDayFactMatchesTheFrozenAnalyticsEnvelope() throws Exception {
    var fact =
        SCHEMA_OBJECT_MAPPER.readTree(
            """
            {
              "envelopeVersion": 2,
              "eventId": "8bb18b75-9e00-4f41-8317-dba208139c58",
              "eventType": "task-board.group-kpi-day.changed.v1",
              "eventVersion": 1,
              "occurredAt": "2026-07-30T09:00:00Z",
              "recordedAt": "2026-07-30T09:00:01Z",
              "producer": "task-board-service",
              "aggregateType": "GROUP_KPI_DAY",
              "aggregateId": "a15c043a-3e50-4caf-b41a-4057233e357b",
              "aggregateVersion": 4,
              "correlation": {
                "correlationId": "63ed5468-55ae-4587-9c58-56f66f82ccbd",
                "causationId": null
              },
              "actorRef": null,
              "payload": {
                "evidenceId": "a15c043a-3e50-4caf-b41a-4057233e357b",
                "warehouseId": "00000000-0000-0000-0000-000000000401",
                "workerGroupId": "6f5a44b7-ce3a-453b-9a55-e1170c861b82",
                "localDate": "2026-07-30",
                "dataAvailableFrom": "2026-07-01",
                "formulaVersion": "kpi-v1",
                "completedBudgetSeconds": 7200,
                "earnedRemainingSeconds": 1800,
                "activeSeconds": 5400,
                "penalizedIdleSeconds": 900,
                "completedTaskCount": 3,
                "openState": "IDLE_GRACE",
                "openStateStartedAt": "2026-07-30T08:55:00Z",
                "penaltyStartsAt": "2026-07-30T09:05:00Z",
                "nextTransitionAt": "2026-07-30T09:05:00Z",
                "asOf": "2026-07-30T09:00:00Z"
              }
            }
            """);

    JsonSchema schema = canonicalEventSchema();
    assertThat(schema.validate(fact)).isEmpty();

    var incompatibleFact = fact.deepCopy();
    ((com.fasterxml.jackson.databind.node.ObjectNode) incompatibleFact.required("payload"))
        .put("displayName", "PII must stay local");
    assertThat(schema.validate(incompatibleFact)).isNotEmpty();
  }

  @Test
  void workerOwnerProofAndEvidenceFactsValidateWithoutHumanReadableIdentityData()
      throws Exception {
    JsonSchema schema = canonicalEventSchema();
    var ownerProof =
        SCHEMA_OBJECT_MAPPER.readTree(
            """
            {
              "envelopeVersion": 2,
              "eventId": "cce3f05c-6211-4e49-b6d6-c44977caf860",
              "eventType": "task-board.entry-owner-proof.changed.v1",
              "eventVersion": 1,
              "occurredAt": "2026-07-25T10:00:00Z",
              "recordedAt": "2026-07-25T10:00:01Z",
              "producer": "task-board-service",
              "aggregateType": "TASK_BOARD_ENTRY_OWNER_PROOF",
              "aggregateId": "7bc37f79-b2fd-4244-b2ad-bc74774c8a7f",
              "aggregateVersion": 3,
              "correlation": {
                "correlationId": "626bb492-f007-4b5c-b161-6c0b3506413c",
                "causationId": null
              },
              "actorRef": null,
              "payload": {
                "ownerType": "TASK_BOARD_ENTRY",
                "ownerId": "7bc37f79-b2fd-4244-b2ad-bc74774c8a7f",
                "warehouseId": "00000000-0000-0000-0000-000000000401",
                "routeIndex": 1,
                "active": true,
                "allowedWorkerIds": ["844cc6a6-47a7-4c6d-926e-6ba12dd3e943"],
                "readerWorkerIds": ["46fd2ccb-cb75-4530-a904-8df638a2ed09"],
                "sourceMediaReferences": [{
                  "mediaId": "ee62854e-1b87-42bd-82a2-bf28f86db6e4",
                  "generation": 2
                }]
              }
            }
            """);
    assertThat(schema.validate(ownerProof)).isEmpty();

    var evidence =
        SCHEMA_OBJECT_MAPPER.readTree(
            """
            {
              "envelopeVersion": 2,
              "eventId": "e17674be-498d-4721-a6aa-d617c7cf2fb8",
              "eventType": "task-board.task-evidence.ready.v1",
              "eventVersion": 1,
              "occurredAt": "2026-07-25T09:59:00Z",
              "recordedAt": "2026-07-25T10:01:00Z",
              "producer": "task-board-service",
              "aggregateType": "TASK_EVIDENCE",
              "aggregateId": "f31bbafb-d838-41ab-9323-67d9ab22b3a9",
              "aggregateVersion": 1,
              "correlation": {
                "correlationId": "626bb492-f007-4b5c-b161-6c0b3506413c",
                "causationId": "cce3f05c-6211-4e49-b6d6-c44977caf860"
              },
              "actorRef": {
                "subjectId": "844cc6a6-47a7-4c6d-926e-6ba12dd3e943",
                "principalType": "WORKER",
                "profileRevision": null
              },
              "payload": {
                "evidenceId": "f31bbafb-d838-41ab-9323-67d9ab22b3a9",
                "entryId": "7bc37f79-b2fd-4244-b2ad-bc74774c8a7f",
                "taskId": "988d4922-53e7-436e-ad4a-27117bf7b005",
                "routeIndex": 1,
                "warehouseId": "00000000-0000-0000-0000-000000000401",
                "workerId": "844cc6a6-47a7-4c6d-926e-6ba12dd3e943",
                "workerGroupId": null,
                "mediaId": "ee62854e-1b87-42bd-82a2-bf28f86db6e4",
                "mediaGeneration": 2,
                "capturedAt": "2026-07-25T09:59:00Z",
                "recordedAt": "2026-07-25T10:01:00Z",
                "state": "READY",
                "sourceType": null,
                "sourceId": null
              }
            }
            """);
    assertThat(schema.validate(evidence)).isEmpty();
    assertThat(evidence.toString())
        .doesNotContain("workerName", "groupName", "displayName", "comment");
  }

  @Test
  void sourceOwnedMaintenanceFactsCorrelateNonNullExternalTaskIdAndRouteIndexWithoutChangingQueueV1()
      throws Exception {
    var repair = createQueue("Maintenance repair");
    var verification = createQueue("Maintenance verification");
    UUID externalTaskId = UUID.randomUUID();
    UUID repairId = UUID.randomUUID();
    var source = new TaskSourceReferenceDto(TaskSourceType.MAINTENANCE_REPAIR, repairId);
    var registration = board.registerExternalTask(
        "maintenance-service",
        new RegisterExternalTaskRequest(
            WAREHOUSE,
            externalTaskId,
            "Maintenance task",
            null,
            null,
            30,
            null,
            List.of(
                new RouteStepRequest(repair.definitionId(), null, 20),
                new RouteStepRequest(verification.definitionId(), null, 10)),
            null,
            null,
            source,
            TaskLane.SCHEDULED));

    assertThat(
            board.snapshot(WAREHOUSE).columns().stream()
                .flatMap(column -> column.entries().stream())
                .filter(entry -> registration.taskId().equals(entry.taskId()))
                .map(entry -> entry.source())
                .toList())
        .containsOnly(source);

    JsonSchema schema = canonicalEventSchema();
    String boardFactBody =
        jdbc.queryForObject(
            "select envelope_body::text from outbox_event where event_type=? "
                + "and envelope_body->'payload'->>'externalTaskId'=?",
            String.class,
            TaskBoardEventTypes.BOARD_TASK_CREATED,
            externalTaskId.toString());
    var boardFact = SCHEMA_OBJECT_MAPPER.readTree(boardFactBody);
    assertThat(schema.validate(boardFact)).isEmpty();
    assertThat(boardFact.required("payload").required("externalTaskId").textValue())
        .isEqualTo(externalTaskId.toString());
    String taskId = boardFact.required("payload").required("boardTaskId").textValue();

    List<String> queueFacts =
        jdbc.queryForList(
            "select envelope_body::text from outbox_event where event_type=? "
                + "and envelope_body->'payload'->>'taskId'=? "
                + "order by (envelope_body->'payload'->>'routeIndex')::integer",
            String.class,
            TaskBoardEventTypes.QUEUE_ENTRY_CREATED,
            taskId);
    assertThat(queueFacts).hasSize(2);
    assertThat(
            queueFacts.stream()
                .map(
                    body -> {
                      try {
                        var fact = SCHEMA_OBJECT_MAPPER.readTree(body);
                        assertThat(schema.validate(fact)).isEmpty();
                        assertThat(fact.required("eventType").textValue())
                            .isEqualTo(TaskBoardEventTypes.QUEUE_ENTRY_CREATED);
                        assertThat(fact.required("eventVersion").intValue()).isOne();
                        assertThat(fact.required("payload").has("externalTaskId")).isFalse();
                        assertThat(fact.required("payload").required("taskId").textValue())
                            .isEqualTo(taskId);
                        return fact.required("payload").required("routeIndex").intValue();
                      } catch (Exception exception) {
                        throw new AssertionError("Invalid queue-entry contract fact", exception);
                      }
                    })
                .toList())
        .containsExactly(0, 1);
  }

  @Test
  void canonicalAsyncApiDeclaresKafkaV2BoardTaskAndQueueEntryFacts() throws Exception {
    Map<String, Object> contract = yaml("events/task-board-events.yaml");
    assertThat(contract.get("asyncapi")).isEqualTo("3.1.0");
    assertThat(contract.toString())
        .contains(
            "rwms.task-board.board-task.v1",
            "rwms.task-board.queue-entry.v1",
            "rwms.task-board.entry-owner-proof.v1",
            "rwms.task-board.task-evidence.v1",
            TaskBoardEventTypes.BOARD_TASK_COMPLETED,
            TaskBoardEventTypes.QUEUE_ENTRY_COMPLETED,
            "task-board.entry-owner-proof.changed.v1",
            "task-board.task-evidence.ready.v1",
            "task-board-events-v1.schema.json")
        .doesNotContain("rwms.domain.v1", "protocol=amqp", "displayName");
    assertAllLocalReferencesResolve(contract, contract);
  }

  @Test
  void canonicalOpenApiDeclaresWorkerAndDriverMobileSurfaces() throws Exception {
    Map<String, Object> contract = yaml("openapi/task-board-service.yaml");
    Map<String, Object> paths = child(contract, "paths");
    assertThat(paths)
        .containsKeys(
            "/worker/v1/context",
            "/worker/v1/feed",
            "/worker/v1/entries/{entryId}",
            "/worker/v1/events",
            "/worker/v1/entries/{entryId}/actions",
            "/worker/v1/entries/{entryId}/evidence-reservations",
            "/worker/v1/devices/{installationId}",
            "/driver/v1/context",
            "/driver/v1/feed",
            "/driver/v1/entries/{entryId}",
            "/driver/v1/events",
            "/driver/v1/entries/{entryId}/actions",
            "/driver/v1/entries/{entryId}/evidence-reservations",
            "/driver/v1/devices/{installationId}");

    Map<String, Object> schemas = child(child(contract, "components"), "schemas");
    assertThat(schemas)
        .containsKeys(
            "AudienceSelector",
            "WorkerCategory",
            "WorkerContext",
            "WorkerFeed",
            "WorkerTaskDetail",
            "WorkerKpiPalette",
            "WorkerKpiPaletteRange",
            "WorkerActionRequest",
            "DriverActionRequest",
            "WorkerActionAppliedResult",
            "WorkerActionConflictProblem",
            "EvidenceReservationRequest",
            "TaskEvidence",
            "WorkerInvalidationEvent",
            "WorkerDeviceRegistrationRequest");
    assertThat(child(schemas, "WorkerCategory").get("required"))
        .isEqualTo(
            List.of(
                "queueId",
                "name",
                "type",
                "queuePurpose",
                "sortOrder",
                "audienceModes",
                "groupIds",
                "resultPhotoMinCount"));
    assertThat(
            ((List<?>) child(schemas, "WorkerFeedEntry").get("required")).stream()
                .map(String::valueOf)
                .toList())
        .contains(
            "routeStepIndex", "routeStepCount", "entryType", "pinned", "driverAudience");
    assertThat(child(child(schemas, "WorkerFeedEntry"), "properties"))
        .containsKeys(
            "routeStepIndex", "routeStepCount", "entryType", "pinned", "driverAudience");
    assertThat(
            child(
                child(child(schemas, "WorkerFeedEntry"), "properties"),
                "routeStepIndex"))
        .containsEntry("minimum", 0);
    assertThat(
            child(
                child(child(schemas, "WorkerFeedEntry"), "properties"),
                "routeStepCount"))
        .containsEntry("minimum", 1);
    Map<String, Object> evidenceReservation = child(schemas, "EvidenceReservationRequest");
    assertThat(
            child(child(evidenceReservation, "properties"), "contentType").get("enum"))
        .isEqualTo(List.of("image/jpeg", "image/webp"));
    assertThat(evidenceReservation.get("oneOf").toString())
        .contains("image/jpeg", "15728640", "image/webp", "1048576");
    assertThat(child(child(evidenceReservation, "properties"), "sha256").get("description"))
        .asString()
        .contains("manifest checksum");
    assertThat(child(schemas, "WorkerContext").get("required"))
        .asList()
        .contains("kpiPalette");
    Map<String, Object> workerTaskDetail = child(schemas, "WorkerTaskDetail");
    assertThat(workerTaskDetail.get("required"))
        .asList()
        .contains("source", "routeStepIndex");
    assertThat(child(workerTaskDetail, "properties")).containsKey("routeStepIndex");
    assertThat(child(child(workerTaskDetail, "properties"), "source").get("oneOf").toString())
        .contains("#/components/schemas/TaskSourceReference", "type=null");
    assertThat(
            child(
                    child(child(schemas, "WorkerInvalidationEvent"), "properties"),
                    "type")
                .get("enum"))
        .asList()
        .contains("NEW_TASK", "URGENT_TASK", "TASK_JOIN_AVAILABLE");
    assertThat(child(schemas, "WorkerActionRequest").get("required"))
        .isEqualTo(
            List.of(
                "operationId",
                "action",
                "expectedVersion",
                "workerGroupId",
                "occurredAt",
                "offlineLeaseId",
                "evidenceId"));
    assertThat(child(child(schemas, "WorkerActionRequest"), "properties"))
        .doesNotContainKeys("workerId", "warehouseId");
    assertThat(
            child(
                    child(child(schemas, "DriverActionRequest"), "properties"),
                    "action")
                .get("enum"))
        .isEqualTo(List.of("TAKE", "PAUSE", "RESUME", "COMPLETE"));
    assertThat(
            child(
                    child(child(schemas, "WorkerDeviceRegistrationRequest"), "properties"),
                    "targetKind")
                .get("enum"))
        .isEqualTo(List.of("TOKEN", "FID"));
    assertThat(child(schemas, "AudienceSelector").get("required"))
        .isEqualTo(
            List.of(
                "kind",
                "id",
                "mode",
                "interruptOnTake",
                "notifyOnPrimaryTake"));
    assertAllLocalReferencesResolve(contract, contract);
  }

  @Test
  void canonicalOpenApiContainsExternalIdentityLifecycle() throws Exception {
    Map<String, Object> contract = yaml("openapi/task-board-service.yaml");
    assertThat(contract.get("openapi")).isEqualTo("3.1.0");
    Map<String, Object> paths = child(contract, "paths");
    assertThat(paths)
        .containsKeys(
            "/warehouses/{warehouseId}/task-board/tasks",
            "/warehouses/{warehouseId}/task-board/tasks/by-external-id/{externalTaskId}",
            "/warehouses/{warehouseId}/task-board/tasks/by-external-id/{externalTaskId}/cancel",
            "/internal/task-board/v1/tasks",
            "/internal/task-board/v1/tasks/{externalTaskId}",
            "/internal/task-board/v1/tasks/{externalTaskId}/cancel",
            "/internal/task-board/v1/tasks/{externalTaskId}/cancel-if-pre-start",
            "/internal/task-board/v1/logistics/equipment-movement-tasks",
            "/internal/task-board/v1/logistics/equipment-movement-tasks/{externalTaskId}",
            "/internal/task-board/v1/logistics/equipment-movement-tasks/{externalTaskId}/cancel");
    assertThat(paths)
        .doesNotContainKeys(
            "/internal/task-board/v1/logistics/preparation-tasks",
            "/internal/task-board/v1/logistics/preparation-tasks/{externalTaskId}",
            "/internal/task-board/v1/logistics/preparation-tasks/{externalTaskId}/complete",
            "/internal/task-board/v1/logistics/preparation-tasks/{externalTaskId}/cancel");
    Map<String, Object> schemas = child(child(contract, "components"), "schemas");
    Map<String, Object> movementRequest =
        child(schemas, "RegisterLogisticsEquipmentMovementTaskRequest");
    assertThat(movementRequest.get("required"))
        .isEqualTo(List.of("warehouseId", "externalTaskId", "deadlineAt", "operations"));
    assertThat(child(movementRequest, "properties")).containsKeys("deadlineAt", "operations");
    assertThat(child(schemas, "EquipmentMovementOperation").get("required"))
        .isEqualTo(List.of("direction", "equipmentId", "equipmentName", "quantity"));
    Map<String, Object> createTaskProperties =
        child(child(schemas, "CreateBoardTaskRequest"), "properties");
    Map<String, Object> externalTaskProperties =
        child(child(schemas, "RegisterExternalTaskRequest"), "properties");
    assertThat(createTaskProperties).doesNotContainKey("dailyCapacity");
    assertThat(externalTaskProperties)
        .containsKeys("source", "driverAudience")
        .doesNotContainKey("dailyCapacity");
    assertThat(schemas)
        .containsKeys("DriverTaskAudienceMode", "DriverTaskAudience");
    assertThat(child(schemas, "DriverTaskAudienceMode").get("enum"))
        .isEqualTo(List.of("UNASSIGNED", "ASSIGNED_DRIVER", "WAREHOUSE_DRIVERS"));
    assertThat(child(schemas, "DriverTaskAudience").toString())
        .contains("WAREHOUSE_DRIVERS", "workerId={type=null}", "workerName={type=null}");
    assertThat(child(child(schemas, "MoveExternalLogisticsTaskRequest"), "properties"))
        .containsKey("targetDriverAudience");
    assertThat(child(schemas, "PreStartCancellationOutcome").get("enum"))
        .isEqualTo(List.of("CANCELLED", "ALREADY_CANCELLED", "STARTED", "VERSION_CONFLICT"));
    assertThat(child(schemas, "PreStartCancellationResult").get("required"))
        .isEqualTo(
            List.of(
                "outcome",
                "taskId",
                "externalTaskId",
                "taskVersion",
                "status",
                "cancelledAt"));
    assertThat(
            ((List<?>) child(schemas, "BoardEntry").get("required")).stream()
                .map(String::valueOf)
                .toList())
        .contains("source");
    assertThat(child(schemas, "TaskSourceReference").get("required"))
        .isEqualTo(List.of("type", "sourceId"));
    assertAllLocalReferencesResolve(contract, contract);
  }

  @Test
  void canonicalOpenApiExposesOnlyTheAggregateOrdinaryBoard() throws Exception {
    Map<String, Object> contract = yaml("openapi/task-board-service.yaml");
    Map<String, Object> paths = child(contract, "paths");
    assertThat(paths)
        .containsKeys(
            "/warehouses/{warehouseId}/work-queues/{queueId}/worker-plan",
            "/warehouses/{warehouseId}/task-board/entries/{entryId}/future-availability",
            "/warehouses/{warehouseId}/task-board/entries/{entryId}/reorder")
        .doesNotContainKeys(
            "/warehouses/{warehouseId}/task-board/dates/swap",
            "/warehouses/{warehouseId}/task-board/entries/{entryId}/move");

    Map<String, Object> schemas = child(child(contract, "components"), "schemas");
    assertThat(schemas)
        .containsKeys(
            "WorkerQueuePlanRequest",
            "SetFutureTaskEntryAvailabilityRequest",
            "ReorderBoardEntryRequest")
        .doesNotContainKeys(
            "MoveEntryRequest", "SwapTaskBoardDatesRequest", "TaskBoardDateEntryExpectation");
    assertThat(child(schemas, "TaskBoardSnapshot").get("required"))
        .isEqualTo(List.of("warehouseId", "columns"));
    assertThat(child(schemas, "BoardColumn").get("required"))
        .asList()
        .contains("queueVersion", "availableTaskLimit", "workerFeedEnabled");
    assertThat(child(schemas, "ReorderBoardEntryRequest").get("required"))
        .asList()
        .contains("expectedEntryVersion", "expectedQueueVersion", "targetEntryId", "targetIndex");
    assertThat(child(schemas, "SetFutureTaskEntryAvailabilityRequest").get("required"))
        .isEqualTo(List.of("expectedEntryVersion", "available"));
    assertAllLocalReferencesResolve(contract, contract);
  }

  @SuppressWarnings("unchecked")
  private void assertAllLocalReferencesResolve(Object node, Map<String, Object> root) {
    if (node instanceof Map<?, ?> map) {
      Object reference = map.get("$ref");
      if (reference instanceof String path && path.startsWith("#/")) {
        Object resolved = root;
        for (String segment : path.substring(2).split("/")) {
          assertThat(resolved).as("parent for %s", path).isInstanceOf(Map.class);
          resolved =
              ((Map<String, Object>) resolved)
                  .get(segment.replace("~1", "/").replace("~0", "~"));
          assertThat(resolved).as("resolved %s", path).isNotNull();
        }
      }
      map.values().forEach(value -> assertAllLocalReferencesResolve(value, root));
    } else if (node instanceof Collection<?> collection) {
      collection.forEach(value -> assertAllLocalReferencesResolve(value, root));
    }
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> yaml(String relativePath) throws Exception {
    Path path = Path.of(System.getProperty("rwms.contracts.dir"), relativePath);
    try (InputStream input = Files.newInputStream(path)) {
      return new Yaml().load(input);
    }
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> child(Map<String, Object> value, String key) {
    return (Map<String, Object>) value.get(key);
  }

  private JsonSchema canonicalEventSchema() throws Exception {
    Path path =
        Path.of(
            System.getProperty("rwms.contracts.dir"),
            "events/task-board/task-board-events-v1.schema.json");
    return JSON_SCHEMA_FACTORY.getSchema(SCHEMA_OBJECT_MAPPER.readTree(Files.readString(path)));
  }

  private WorkQueueDto createQueue(String name) {
    var definition =
        registry.createQueueDefinition(
            QueueRegistryTestFixtures.globalDefinition(0L, name, null, QueueType.REPAIR));
    return QueueRegistryTestFixtures.create(registry, jdbc,
        WAREHOUSE,
        new QueueFixtureRequest(
            0L,
            definition.id(),
            true,
            false,
            false,
            null,
            null,
            false,
            null,
            List.<QueueBindingRequest>of()));
  }
}
