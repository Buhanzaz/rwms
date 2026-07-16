package dev.buhanzaz.rwms.warehouse.eventing;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class WarehouseEventContractTest {
  private final ObjectMapper objectMapper = new ObjectMapper();

  @Test
  void canonicalSchemaMatchesTheSingleWarehouseAggregateFamily() throws Exception {
    JsonNode schema = objectMapper.readTree(Files.readString(schemaPath()));

    assertThat(strings(schema.at("/properties/eventType/enum")))
        .containsExactlyInAnyOrderElementsOf(
            Set.of(
                WarehouseEventType.CREATED.value(),
                WarehouseEventType.CHANGED.value(),
                WarehouseEventType.DEACTIVATED.value()));
    assertThat(strings(schema.get("x-rwms-topics")))
        .containsExactly(WarehouseAggregateType.WAREHOUSE.topic());
    assertThat(schema.at("/properties/producer/const").stringValue()).isEqualTo("warehouse-service");
    assertThat(schema.at("/properties/aggregateType/const").stringValue()).isEqualTo("WAREHOUSE");
    assertThat(schema.at("/properties/envelopeVersion/const").intValue()).isEqualTo(2);
    assertThat(schema.at("/$defs/warehouseFact/additionalProperties").booleanValue()).isFalse();
    assertThat(strings(schema.at("/$defs/warehouseFact/required")))
        .containsExactlyInAnyOrder("warehouseId", "code", "timeZone", "active", "sortOrder");
    assertThat(fieldNames(schema.at("/$defs/warehouseFact/properties")))
        .containsExactlyInAnyOrder("warehouseId", "code", "timeZone", "active", "sortOrder");
  }

  private Set<String> strings(JsonNode node) {
    Set<String> values = new HashSet<>();
    node.forEach(value -> values.add(value.stringValue()));
    return values;
  }

  private Set<String> fieldNames(JsonNode node) {
    return Set.copyOf(node.propertyNames());
  }

  private Path schemaPath() {
    return Path.of(
        System.getProperty("rwms.contracts.dir"), "events/warehouse/warehouse-events-v1.schema.json");
  }
}
