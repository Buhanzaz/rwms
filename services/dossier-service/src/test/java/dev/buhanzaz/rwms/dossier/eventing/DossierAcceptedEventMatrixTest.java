package dev.buhanzaz.rwms.dossier.eventing;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class DossierAcceptedEventMatrixTest {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final Path CONTRACTS = Path.of(System.getProperty("rwms.contracts.dir"));

  @Test
  void acceptedTypesMatchCanonicalProducerSchemasAndDossierAsyncApi() throws Exception {
    assertTopic(
        "rwms.asset.rental-item.v1",
        events("events/asset/asset-events-v1.schema.json", "asset.rental-item."));
    assertTopic(
        "rwms.maintenance.estimate.v1",
        events("events/maintenance/maintenance-events-v1.schema.json", "maintenance.estimate."));
    assertTopic(
        "rwms.maintenance.repair.v1",
        events("events/maintenance/maintenance-events-v1.schema.json", "maintenance.repair."));
    assertTopic(
        "rwms.inventory.session.v1",
        events("events/inventory/inventory-events-v1.schema.json", "inventory.session.", "inventory.finding."));
    assertTopic(
        "rwms.inventory.publication.v1",
        events("events/inventory/inventory-events-v1.schema.json", "inventory.publication."));
    assertTopic(
        "rwms.media.media.v1", events("events/media/media-facts-v1.schema.json", "media.media."));
    assertTopic("rwms.media.cabin-photo.v1", Set.of("media.cabin.cover-changed.v1"));
    for (String family : Set.of("return", "shipment", "transfer")) {
      assertTopic(
          "rwms.logistics." + family + ".v1",
          events("events/logistics/logistics-events-v1.schema.json", "logistics." + family + "."));
    }
    assertTopic(
        "rwms.task-board.board-task.v1",
        events("events/task-board/task-board-events-v1.schema.json", "task-board.board-task."));
    assertTopic(
        "rwms.task-board.queue-entry.v1",
        events("events/task-board/task-board-events-v1.schema.json", "task-board.queue-entry."));
  }

  private static void assertTopic(String topic, Set<String> expected) throws Exception {
    Set<String> accepted = DossierEnvelopeValidator.acceptedEventTypes(topic);
    assertThat(accepted).containsExactlyInAnyOrderElementsOf(expected);
    String dossierAsyncApi = Files.readString(CONTRACTS.resolve("events/dossier-consumers.yaml"));
    assertThat(dossierAsyncApi).contains(topic);
    accepted.forEach(eventType -> assertThat(dossierAsyncApi).contains(eventType));
  }

  private static Set<String> events(String relative, String... prefixes) throws Exception {
    JsonNode values = JSON.readTree(CONTRACTS.resolve(relative).toFile()).required("properties").required("eventType").required("enum");
    return StreamSupport.stream(values.spliterator(), false)
        .map(JsonNode::stringValue)
        .filter(value -> java.util.Arrays.stream(prefixes).anyMatch(value::startsWith))
        .collect(Collectors.toUnmodifiableSet());
  }
}
