package dev.buhanzaz.rwms.logistics;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class LogisticsContractFoundationTest {
  private final ObjectMapper objectMapper = new ObjectMapper();

  @Test
  void publicDraftCreationAndReadEndpointsAreAlreadyCanonicalized() throws Exception {
    Map<String, Object> document = openApi();
    Map<String, Object> paths = child(document, "paths");

    assertThat(paths)
        .containsKeys(
            "/api/logistics/v1/returns",
            "/api/logistics/v1/returns/{documentId}",
            "/api/logistics/v1/returns/{documentId}/register",
            "/api/logistics/v1/shipments",
            "/api/logistics/v1/shipments/{documentId}",
            "/api/logistics/v1/transfers",
            "/api/logistics/v1/transfers/{documentId}",
            "/api/logistics/v1/{documentType}/{documentId}/reconcile");
    assertThat(child(child(document, "components"), "schemas"))
        .containsKeys(
            "CreateReturnRequest",
            "CreateShipmentRequest",
            "CreateTransferRequest",
            "LogisticsDocument",
            "LogisticsLine",
            "ReconcileRequest");
    Map<String, Object> idempotencyKey =
        child(child(document, "components"), "parameters");
    assertThat(idempotencyKey.get("IdempotencyKey"))
        .isInstanceOfSatisfying(
            Map.class,
            parameter -> {
              assertThat(parameter.get("in")).isEqualTo("header");
              assertThat(parameter.get("required")).isEqualTo(true);
            });
  }

  @Test
  void returnRegistrationIsAnAsynchronousVersionedCommand() throws Exception {
    Map<String, Object> paths = child(openApi(), "paths");
    Map<String, Object> register = child(paths, "/api/logistics/v1/returns/{documentId}/register");
    Map<String, Object> post = child(register, "post");
    Map<String, Object> responses = child(post, "responses");

    assertThat(responses).containsKey("202");
    assertThat(register.get("parameters"))
        .isInstanceOfSatisfying(
            java.util.List.class,
            parameters ->
                assertThat(parameters)
                    .anySatisfy(
                        value ->
                            assertThat(value)
                                .isInstanceOfSatisfying(
                                    Map.class,
                                    parameter ->
                                        assertThat(parameter.get("$ref"))
                                            .isEqualTo("#/components/parameters/ExpectedVersion"))));
  }

  @Test
  void eventSchemaIsSanitizedAndUsesOnlyAggregateFamilyTopics() throws Exception {
    JsonNode schema = objectMapper.readTree(Files.readString(eventSchemaPath()));

    assertThat(strings(schema.get("x-rwms-topics")))
        .containsExactlyInAnyOrder(
            "rwms.logistics.return.v1",
            "rwms.logistics.shipment.v1",
            "rwms.logistics.transfer.v1");
    assertThat(schema.at("/properties/producer/const").stringValue()).isEqualTo("logistics-service");
    assertThat(strings(schema.at("/properties/aggregateType/enum")))
        .containsExactlyInAnyOrder("RETURN", "SHIPMENT", "TRANSFER");
    assertThat(fieldNames(schema.at("/$defs/logisticsFact/properties")))
        .containsExactlyInAnyOrder(
            "documentId",
            "documentType",
            "state",
            "warehouseId",
            "destinationWarehouseId",
            "lineCount",
            "resultCode")
        .doesNotContain("partySnapshot", "tenantSnapshot", "driverSnapshot", "mediaId", "passport");
  }

  @Test
  void inboundContractAllowsOnlyDeclaredSourceFactsAndAHarmlessDlt() throws Exception {
    Map<String, Object> document = eventContract();
    Map<String, Object> channels = child(document, "channels");
    assertThat(channels)
        .containsKeys(
            "assetRentalItemFacts",
            "assetOperationLeaseFacts",
            "assetEquipmentAllocationHoldFacts",
            "taskBoardFacts",
            "maintenanceEstimateFacts",
            "mediaFacts",
            "inboundSanitizedDlt");

    Map<String, Object> operations = child(document, "operations");
    assertThat(operations)
        .containsKeys(
            "consumeRentalItemFacts",
            "consumeOperationLeaseFacts",
            "consumeEquipmentAllocationHoldFacts",
            "consumeBoardTaskFacts",
            "consumeMaintenanceEstimateFacts",
            "consumeMediaFacts",
            "publishInboundSanitizedConsumerFailure");

    Map<String, Object> messages = child(child(document, "components"), "messages");
    Map<String, Object> dlt = child(messages, "InboundSanitizedDltV1");
    Map<String, Object> payload = child(dlt, "payload");
    assertThat(child(payload, "properties"))
        .containsOnlyKeys(
            "failureCode", "messageSha256", "sourceTopic", "sourceEventId", "recordedAt");
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> openApi() throws Exception {
    Path contract = Path.of(System.getProperty("rwms.contracts.dir"), "openapi/logistics-service.yaml");
    try (InputStream input = Files.newInputStream(contract)) {
      return new Yaml().load(input);
    }
  }

  private Path eventSchemaPath() {
    return Path.of(
        System.getProperty("rwms.contracts.dir"), "events/logistics/logistics-events-v1.schema.json");
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> eventContract() throws Exception {
    Path contract = Path.of(System.getProperty("rwms.contracts.dir"), "events/logistics-events.yaml");
    try (InputStream input = Files.newInputStream(contract)) {
      return new Yaml().load(input);
    }
  }

  private static Set<String> strings(JsonNode node) {
    java.util.HashSet<String> values = new java.util.HashSet<>();
    node.forEach(value -> values.add(value.stringValue()));
    return values;
  }

  private static Set<String> fieldNames(JsonNode node) {
    return Set.copyOf(node.propertyNames());
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> child(Map<String, Object> map, String name) {
    return (Map<String, Object>) map.get(name);
  }

}
