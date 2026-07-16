package dev.buhanzaz.rwms.taskboard;

import static dev.buhanzaz.rwms.taskboard.api.ApiModels.CreateBoardTaskRequest;
import static dev.buhanzaz.rwms.taskboard.api.ApiModels.QueueBindingRequest;
import static dev.buhanzaz.rwms.taskboard.api.ApiModels.RouteStepRequest;
import static dev.buhanzaz.rwms.taskboard.api.ApiModels.WorkQueueRequest;
import static org.assertj.core.api.Assertions.assertThat;

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
            "/warehouses/{warehouseId}/task-board/tasks/by-external-id/{externalTaskId}/cancel");
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
}
