package dev.buhanzaz.rwms.taskboard;

import static dev.buhanzaz.rwms.taskboard.api.ApiModels.CreateBoardTaskRequest;
import static dev.buhanzaz.rwms.taskboard.api.ApiModels.QueueBindingRequest;
import static dev.buhanzaz.rwms.taskboard.api.ApiModels.RegisterExternalTaskRequest;
import static dev.buhanzaz.rwms.taskboard.api.ApiModels.RouteStepRequest;
import static dev.buhanzaz.rwms.taskboard.api.ApiModels.WorkQueueDto;
import static dev.buhanzaz.rwms.taskboard.api.ApiModels.WorkQueueRequest;
import static org.assertj.core.api.Assertions.assertThat;

import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import dev.buhanzaz.rwms.taskboard.domain.QueueType;
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
    var queue =
        registry.createQueue(
            WAREHOUSE,
            new WorkQueueRequest(
                0L,
                "CONTRACT",
                "Contract",
                null,
                QueueType.REPAIR,
                true,
                false,
                false,
                null,
                null,
                false,
                List.<QueueBindingRequest>of()));
    board.createTask(
        WAREHOUSE,
        new CreateBoardTaskRequest(
            UUID.randomUUID(),
            "Contract task",
            null,
            null,
            null,
            null,
            List.of(new RouteStepRequest(queue.id(), null, null, null))));

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
  }

  @Test
  void historicalQueueEntryV1WithoutExternalTaskIdStillValidates() throws Exception {
    var historicalFact =
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
                "queueCode": "LEGACY",
                "routeIndex": 0,
                "queuePosition": 0,
                "entryType": "REAL",
                "status": "WAITING",
                "plannedDurationMinutes": null,
                "activeStartedAt": null,
                "pausedAt": null,
                "doneAt": null,
                "activeWorkSeconds": 0,
                "pauseOrigin": null,
                "assignments": [],
                "timeEvents": [],
                "interruptions": [],
                "deleted": false
              }
            }
            """);

    JsonSchema schema = canonicalEventSchema();
    assertThat(schema.validate(historicalFact)).isEmpty();

    var incompatibleFact = historicalFact.deepCopy();
    ((com.fasterxml.jackson.databind.node.ObjectNode) incompatibleFact.required("payload"))
        .put("externalTaskId", UUID.randomUUID().toString());
    assertThat(schema.validate(incompatibleFact)).isNotEmpty();
  }

  @Test
  void sourceOwnedMaintenanceFactsCorrelateNonNullExternalTaskIdAndRouteIndexWithoutChangingQueueV1()
      throws Exception {
    var repair = createQueue("MAINTENANCE_REPAIR", "Maintenance repair");
    var verification = createQueue("MAINTENANCE_VERIFY", "Maintenance verification");
    UUID externalTaskId = UUID.randomUUID();
    board.registerExternalTask(
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
                new RouteStepRequest(repair.id(), null, null, 20),
                new RouteStepRequest(verification.id(), null, null, 10))));

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
            TaskBoardEventTypes.BOARD_TASK_COMPLETED,
            TaskBoardEventTypes.QUEUE_ENTRY_COMPLETED,
            "task-board-events-v1.schema.json")
        .doesNotContain("rwms.domain.v1", "protocol=amqp", "displayName");
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
            "/internal/task-board/v1/tasks/{externalTaskId}/cancel");
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

  private WorkQueueDto createQueue(String code, String name) {
    return registry.createQueue(
        WAREHOUSE,
        new WorkQueueRequest(
            0L,
            code,
            name,
            null,
            QueueType.REPAIR,
            true,
            false,
            false,
            null,
            null,
            false,
            List.<QueueBindingRequest>of()));
  }
}
