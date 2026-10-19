package dev.buhanzaz.rwms.analytics.contract;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

class AnalyticsContractTest {
  private static final Path CONTRACTS = Path.of(System.getProperty("rwms.contracts.dir"));
  private static final ObjectMapper JSON = new ObjectMapper();

  @Test
  void canonicalKpiSnapshotAndSanitizedDltValidate() throws Exception {
    JsonSchema input = schema("analytics/group-kpi-day-v1.schema.json");
    JsonNode snapshot =
        JSON.readTree(
            """
            {
              "envelopeVersion":2,
              "eventId":"10000000-0000-0000-0000-000000000001",
              "eventType":"task-board.group-kpi-day.changed.v1",
              "eventVersion":1,
              "occurredAt":"2026-07-30T08:00:00Z",
              "recordedAt":"2026-07-30T08:00:01Z",
              "producer":"task-board-service",
              "aggregateType":"GROUP_KPI_DAY",
              "aggregateId":"20000000-0000-0000-0000-000000000001",
              "aggregateVersion":0,
              "correlation":{"correlationId":"30000000-0000-0000-0000-000000000001","causationId":null},
              "actorRef":null,
              "payload":{
                "evidenceId":"20000000-0000-0000-0000-000000000001",
                "warehouseId":"40000000-0000-0000-0000-000000000001",
                "workerGroupId":"50000000-0000-0000-0000-000000000001",
                "localDate":"2026-07-30",
                "dataAvailableFrom":"2026-07-01",
                "formulaVersion":"kpi-v1",
                "completedBudgetSeconds":3600,
                "earnedRemainingSeconds":1800,
                "activeSeconds":1200,
                "penalizedIdleSeconds":600,
                "completedTaskCount":1,
                "openState":null,
                "openStateStartedAt":null,
                "penaltyStartsAt":null,
                "nextTransitionAt":null,
                "asOf":"2026-07-30T08:00:00Z"
              }
            }
            """);
    assertThat(input.validate(snapshot)).isEmpty();

    JsonSchema dlt = schema("analytics/analytics-sanitized-dlt-v1.schema.json");
    JsonNode failure =
        JSON.readTree(
            """
            {
              "failureId":"60000000-0000-0000-0000-000000000001",
              "failureCode":"MISSING_AGGREGATE_VERSION",
              "sourceTopic":"rwms.task-board.group-kpi-day.v1",
              "sourcePartition":0,
              "sourceOffset":7,
              "recordKeySha256":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
              "messageSha256":"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
              "eventId":"10000000-0000-0000-0000-000000000001",
              "recordedAt":"2026-07-30T08:00:01Z"
            }
            """);
    assertThat(dlt.validate(failure)).isEmpty();
  }

  @Test
  void topologyAndPublicApiAreExact() throws Exception {
    Map<String, Object> async =
        yaml(CONTRACTS.resolve("events/analytics-consumers.yaml"));
    Map<String, Object> channels = map(async, "channels");
    assertThat(channels).hasSize(2);
    assertThat(map(channels, "groupKpiDayFacts"))
        .containsEntry("address", "rwms.task-board.group-kpi-day.v1");
    assertThat(map(channels, "groupKpiDayDlt"))
        .containsEntry(
            "address", "rwms.task-board.group-kpi-day.v1.analytics-projection-v1.dlt");
    assertThat(map(async, "operations").keySet())
        .containsExactlyInAnyOrder("consumeGroupKpiDayFacts", "publishGroupKpiDayDlt");

    Map<String, Object> openapi =
        yaml(CONTRACTS.resolve("openapi/analytics-service.yaml"));
    Map<String, Object> paths = map(openapi, "paths");
    assertThat(paths.keySet())
        .containsExactly("/api/v1/warehouses/{warehouseId}/group-kpi");
    Map<String, Object> get =
        map(map(paths, "/api/v1/warehouses/{warehouseId}/group-kpi"), "get");
    assertThat(map(get, "responses").keySet())
        .containsExactlyInAnyOrder("200", "400", "401", "403");
    Map<String, Object> schemas = map(map(openapi, "components"), "schemas");
    assertThat(list(map(schemas, "PeriodType"), "enum"))
        .containsExactly("DAY", "MONTH", "QUARTER", "YEAR");
    assertThat(list(map(schemas, "CoverageStatus"), "enum"))
        .containsExactly("PROVISIONAL", "PARTIAL", "COMPLETE", "NO_DATA");
  }

  private static JsonSchema schema(String relative) throws Exception {
    return JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
        .getSchema(
            JSON.readTree(CONTRACTS.resolve("events").resolve(relative).toFile()));
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> yaml(Path path) throws Exception {
    try (var stream = Files.newInputStream(path)) {
      return (Map<String, Object>) new Yaml().load(stream);
    }
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> map(Map<String, Object> source, String key) {
    return (Map<String, Object>) source.get(key);
  }

  @SuppressWarnings("unchecked")
  private static List<String> list(Map<String, Object> source, String key) {
    return ((List<Object>) source.get(key)).stream().map(String::valueOf).toList();
  }
}
