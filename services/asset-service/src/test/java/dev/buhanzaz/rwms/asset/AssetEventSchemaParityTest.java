package dev.buhanzaz.rwms.asset;

import static org.assertj.core.api.Assertions.assertThat;

import dev.buhanzaz.rwms.asset.domain.AssetAggregateType;
import dev.buhanzaz.rwms.asset.domain.AssetEventType;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class AssetEventSchemaParityTest {
  private final ObjectMapper mapper = new ObjectMapper();

  @Test
  void eventContractListsEveryPublishedEventAndAggregateFamilyTopic() throws Exception {
    Path contract = Path.of(
        System.getProperty("rwms.contracts.dir"), "events/asset/asset-events-v1.schema.json");
    JsonNode document = mapper.readTree(contract.toFile());

    Set<String> schemaEventTypes = strings(document.path("properties").path("eventType").path("enum"));
    Set<String> sourceEventTypes = new LinkedHashSet<>();
    for (AssetEventType type : AssetEventType.values()) sourceEventTypes.add(type.value());
    assertThat(schemaEventTypes).containsExactlyInAnyOrderElementsOf(sourceEventTypes);

    Set<String> topics = strings(document.path("x-rwms-topics"));
    for (AssetAggregateType aggregate : AssetAggregateType.values()) {
      assertThat(topics).contains(aggregate.topic());
    }
    assertThat(document.path("description").stringValue())
        .contains("Comment text", "manual-note text", "tenant references", "media URLs", "PII");
  }

  private static Set<String> strings(JsonNode values) {
    Set<String> result = new LinkedHashSet<>();
    values.forEach(value -> result.add(value.stringValue()));
    return result;
  }
}
