package dev.buhanzaz.rwms.inventory.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.lang.reflect.RecordComponent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

class ReturnEstimateInspectionContractTest {
  @Test
  void openApiMatchesTheExactStatusResponseAndPublicReadRoute() throws Exception {
    Map<String, Object> document;
    Path contract =
        Path.of(System.getProperty("rwms.contracts.dir"), "openapi/inventory-service.yaml");
    try (InputStream input = Files.newInputStream(contract)) {
      document = map(new Yaml().load(input));
    }

    Map<String, Object> path =
        child(document, "paths", "/api/inventory/v1/return-estimates/{estimateId}/inspection");
    Map<String, Object> get = map(path.get("get"));
    assertThat(get.get("operationId")).isEqualTo("getReturnEstimateInspection");
    assertThat(child(get, "responses").keySet())
        .containsExactlyInAnyOrder("200", "401", "403", "404", "409", "503");

    Map<String, Object> schema = child(document, "components", "schemas", "ReturnEstimateInspection");
    Set<String> javaFields =
        Arrays.stream(ReturnEstimateInspectionResponse.class.getRecordComponents())
            .map(RecordComponent::getName)
            .collect(Collectors.toSet());
    assertThat(child(schema, "properties").keySet()).containsExactlyInAnyOrderElementsOf(javaFields);
    assertThat(schema.get("required")).isEqualTo(List.of("state", "inventoryId", "findingId", "cabinNumber"));
    assertThat(child(child(schema, "properties"), "state").get("enum"))
        .isEqualTo(List.of("CONFIRMED", "PENDING", "NOT_REQUIRED"));
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> map(Object value) {
    return (Map<String, Object>) value;
  }

  private static Map<String, Object> child(Map<String, Object> root, String... keys) {
    Map<String, Object> current = root;
    for (String key : keys) {
      current = map(current.get(key));
    }
    return current;
  }
}
