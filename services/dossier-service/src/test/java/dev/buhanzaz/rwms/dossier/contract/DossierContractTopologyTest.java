package dev.buhanzaz.rwms.dossier.contract;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

class DossierContractTopologyTest {
  private static final Path CONTRACTS = Path.of(System.getProperty("rwms.contracts.dir"));
  private static final Path OPENAPI = CONTRACTS.resolve("openapi/dossier-service.yaml");
  private static final Path ASYNCAPI = CONTRACTS.resolve("events/dossier-consumers.yaml");

  private static final Set<String> INPUT_TOPICS =
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

  private static final Set<String> SOURCE_TYPES =
      Set.of("ASSET", "MAINTENANCE", "INVENTORY", "MEDIA", "LOGISTICS", "TASK_BOARD");

  private static final Set<String> ACTIVITY_CODES =
      Set.of(
          "CABIN_CREATED",
          "CABIN_PASSPORT_CHANGED",
          "CABIN_STATUS_CHANGED",
          "CABIN_WAREHOUSE_CHANGED",
          "CABIN_LOGISTICS_EFFECT_APPLIED",
          "CABIN_COMMENT_REVISION_CHANGED",
          "CABIN_MANUAL_NOTE_ADDED",
          "ESTIMATE_CREATED",
          "ESTIMATE_DRAFT_CHANGED",
          "ESTIMATE_COMPLETED",
          "ESTIMATE_AMENDED",
          "REPAIR_CREATED",
          "REPAIR_PLAN_CHANGED",
          "REPAIR_QUEUED",
          "REPAIR_STAGE_COMPLETED",
          "REPAIR_PENDING_ACCEPTANCE",
          "REPAIR_REWORK_CREATED",
          "REPAIR_TRANSFER_PREPARED",
          "REPAIR_TRANSFERRED",
          "REPAIR_ACCEPTED",
          "REPAIR_WRITTEN_OFF",
          "INVENTORY_FINDING_ADDED",
          "INVENTORY_INSPECTION_SAVED",
          "INVENTORY_PUBLICATION_READY",
          "INVENTORY_PUBLICATION_REQUESTED",
          "INVENTORY_PUBLICATION_SUCCEEDED",
          "INVENTORY_PUBLICATION_TRANSIENT_FAILED",
          "INVENTORY_PUBLICATION_BLOCKED",
          "INVENTORY_PUBLICATION_CLOSED_BLOCKED",
          "MEDIA_READY",
          "MEDIA_FAILED",
          "MEDIA_ROTATED",
          "MEDIA_DELETED");

  @Test
  void openApiExposesExactlyOneReadOnlySurfaceWithApprovedFiltersAndErrors() throws Exception {
    Map<String, Object> root = yaml(OPENAPI);
    Map<String, Object> paths = map(root, "paths");
    assertThat(paths.keySet()).containsExactly("/api/dossier/v1/cabins/{cabinId}");

    Map<String, Object> route = map(paths, "/api/dossier/v1/cabins/{cabinId}");
    assertThat(route.keySet()).containsExactly("get");
    Map<String, Object> get = map(route, "get");
    assertThat(get.get("operationId")).isEqualTo("getCabinDossier");

    List<Map<String, Object>> parameters = maps(get, "parameters");
    assertThat(
            parameters.stream()
                .map(parameter -> String.valueOf(parameter.get("$ref")))
                .map(reference -> reference.substring(reference.lastIndexOf('/') + 1))
                .toList())
        .containsExactly(
            "CabinId",
            "Limit",
            "After",
            "OccurredFrom",
            "OccurredBefore",
            "ActivityCodes",
            "SourceTypes",
            "ActorSubjectId");
    assertThat(map(get, "responses").keySet())
        .containsExactlyInAnyOrder("200", "400", "401", "403", "404");

    Map<String, Object> parameterDefinitions = map(map(root, "components"), "parameters");
    assertThat(map(map(parameterDefinitions, "Limit"), "schema"))
        .containsEntry("minimum", 1)
        .containsEntry("maximum", 100)
        .containsEntry("default", 50);
    assertThat(map(parameterDefinitions, "OccurredFrom").get("description").toString())
        .contains("Inclusive RFC 3339");
    assertThat(map(parameterDefinitions, "OccurredBefore").get("description").toString())
        .contains("Exclusive RFC 3339")
        .contains("later than occurredFrom");
    assertThat(map(parameterDefinitions, "After").get("description").toString())
        .contains("tamper-detected")
        .contains("bound to")
        .contains("normalized filters");
  }

  @Test
  void openApiVocabularyAndSemanticReferencesAreExactAndPiiFree() throws Exception {
    Map<String, Object> schemas = map(map(yaml(OPENAPI), "components"), "schemas");
    assertThat(strings(map(schemas, "SourceType"), "enum"))
        .containsExactlyInAnyOrderElementsOf(SOURCE_TYPES);
    assertThat(strings(map(schemas, "ActivityCode"), "enum"))
        .containsExactlyInAnyOrderElementsOf(ACTIVITY_CODES);

    Map<String, Object> sourceReference = map(schemas, "SourceReference");
    assertThat(strings(sourceReference, "required"))
        .containsExactly("producer", "aggregateType", "aggregateId");
    assertThat(map(sourceReference, "properties").keySet())
        .containsExactlyInAnyOrder("producer", "aggregateType", "aggregateId", "secondaryId");
    assertThat(strings(map(map(sourceReference, "properties"), "producer"), "enum"))
        .containsExactlyInAnyOrder(
            "asset-service", "maintenance-service", "inventory-service", "media-service");

    Map<String, Object> mediaProjection = map(schemas, "MediaProjection");
    assertThat(strings(mediaProjection, "required"))
        .containsExactly("mediaId", "folderId", "findingId", "generation", "state");
    assertThat(map(mediaProjection, "properties").keySet())
        .containsExactlyInAnyOrder("mediaId", "folderId", "findingId", "generation", "state");

    String raw = Files.readString(OPENAPI);
    assertThat(raw)
        .doesNotContain("sourceEventId")
        .doesNotContain("href:")
        .doesNotContain("displayName")
        .doesNotContain("email:")
        .doesNotContain("login:")
        .doesNotContain("Idempotency-Key")
        .doesNotContain("ETag");
  }

  @Test
  void asyncApiHasExactInputDltAndOutboundTopology() throws Exception {
    Map<String, Object> root = yaml(ASYNCAPI);
    Map<String, Object> channels = map(root, "channels");
    Set<String> addresses = new LinkedHashSet<>();
    channels.values().forEach(value -> addresses.add(String.valueOf(map(value).get("address"))));

    Set<String> expected = new LinkedHashSet<>(INPUT_TOPICS);
    INPUT_TOPICS.forEach(topic -> expected.add(topic + ".dossier-projection-v1.dlt"));
    expected.add("rwms.dossier.cabin-activity.v1");
    assertThat(addresses).containsExactlyInAnyOrderElementsOf(expected);
    assertThat(channels).hasSize(23);

    Map<String, Object> operations = map(root, "operations");
    assertThat(operations).hasSize(23);
    long receives =
        operations.values().stream()
            .map(DossierContractTopologyTest::map)
            .filter(operation -> "receive".equals(operation.get("action")))
            .count();
    long sends = operations.size() - receives;
    assertThat(receives).isEqualTo(11);
    assertThat(sends).isEqualTo(12);

    assertThat(root)
        .containsEntry("x-rwms-consumer-group", "dossier-projection-v1")
        .containsEntry("x-rwms-consumer-start-offset", "earliest");
    Map<String, Object> delivery = map(root, "x-rwms-delivery");
    assertThat(delivery.get("transientRetriesSeconds")).isEqualTo(List.of(1, 2, 4));
    assertThat(delivery)
        .containsEntry("permanentValidationFailure", "SANITIZED_DLT_NO_RETRY")
        .containsEntry("offsetCommit", "AFTER_LOCAL_TRANSACTION")
        .containsEntry("aggregateGap", "QUARANTINE_AGGREGATE");

    Map<String, Object> origins = map(root, "x-rwms-aggregate-version-origin");
    assertThat(map(origins, "media-service"))
        .containsEntry("initialAppliedVersion", 0)
        .containsEntry("firstExpectedVersion", 1);
    assertThat(map(origins, "otherAcceptedProducers"))
        .containsEntry("initialAppliedVersion", -1)
        .containsEntry("firstExpectedVersion", 0);
  }

  @Test
  void inventorySharedTopicExplicitlyAcceptsSessionAndFindingWithoutInventingSubject() throws Exception {
    Map<String, Object> root = yaml(ASYNCAPI);
    Map<String, Object> channel = map(map(root, "channels"), "inventoryFindingFacts");
    assertThat(channel.get("address")).isEqualTo("rwms.inventory.session.v1");
    assertThat(map(channel, "messages").keySet())
        .containsExactlyInAnyOrder("inventorySessionFactV1", "inventoryFindingFactV1");
    assertThat(channel.get("description").toString())
        .contains("SESSION and FINDING")
        .contains("SESSION is journaled as SUBJECT_NOT_PROVIDED")
        .contains("payload.assetId");

    Map<String, Object> messages = map(map(root, "components"), "messages");
    assertThat(map(messages, "InventorySessionFactV1"))
        .containsEntry("x-rwms-unlinked-reason", "SUBJECT_NOT_PROVIDED");
    Map<String, Object> findingMap =
        map(map(messages, "InventoryFindingFactV1"), "x-rwms-activity-code-map");
    assertThat(findingMap)
        .containsEntry("inventory.finding.added.v1", "INVENTORY_FINDING_ADDED")
        .containsEntry("inventory.finding.inspection-saved.v1", "INVENTORY_INSPECTION_SAVED")
        .containsEntry("inventory.finding.owner-proof.v1", null);
  }

  @Test
  void asyncActivityMapsAndOutboundSchemaUseTheExactOpenApiVocabulary() throws Exception {
    Map<String, Object> asyncSchemas = map(map(yaml(ASYNCAPI), "components"), "messages");
    Set<String> mappedCodes = new LinkedHashSet<>();
    asyncSchemas.values().stream()
        .map(DossierContractTopologyTest::map)
        .filter(message -> message.containsKey("x-rwms-activity-code-map"))
        .map(message -> map(message, "x-rwms-activity-code-map"))
        .flatMap(mapping -> mapping.values().stream())
        .filter(java.util.Objects::nonNull)
        .map(String::valueOf)
        .forEach(mappedCodes::add);
    assertThat(mappedCodes).containsExactlyInAnyOrderElementsOf(ACTIVITY_CODES);

    Map<String, Object> outputSchema =
        json(CONTRACTS.resolve("events/dossier/dossier-cabin-activity-v1.schema.json"));
    Map<String, Object> definitions = map(outputSchema, "$defs");
    Map<String, Object> activityFact = map(definitions, "activityFact");
    Map<String, Object> activityProperties = map(activityFact, "properties");
    assertThat(strings(map(activityProperties, "activityCode"), "enum"))
        .containsExactlyInAnyOrderElementsOf(ACTIVITY_CODES);
    assertThat(map(map(definitions, "sourceReference"), "properties").keySet())
        .containsExactlyInAnyOrder("producer", "aggregateType", "aggregateId", "secondaryId");
  }

  @Test
  void asyncDiscriminatorsMatchTheCurrentProducerOwnedEventFamiliesExactly() throws Exception {
    Map<String, Object> root = yaml(ASYNCAPI);
    Map<String, Object> components = map(root, "components");
    Map<String, Object> schemas = map(components, "schemas");
    assertDiscriminator(
        schemas,
        "AssetRentalItemDiscriminator",
        "asset/asset-events-v1.schema.json",
        "asset.rental-item.");
    assertDiscriminator(
        schemas,
        "MaintenanceEstimateDiscriminator",
        "maintenance/maintenance-events-v1.schema.json",
        "maintenance.estimate.");
    assertDiscriminator(
        schemas,
        "MaintenanceRepairDiscriminator",
        "maintenance/maintenance-events-v1.schema.json",
        "maintenance.repair.");
    assertDiscriminator(
        schemas,
        "InventorySessionDiscriminator",
        "inventory/inventory-events-v1.schema.json",
        "inventory.session.");
    assertDiscriminator(
        schemas,
        "InventoryFindingDiscriminator",
        "inventory/inventory-events-v1.schema.json",
        "inventory.finding.");
    assertDiscriminator(
        schemas,
        "InventoryPublicationDiscriminator",
        "inventory/inventory-events-v1.schema.json",
        "inventory.publication.");
    assertDiscriminator(
        schemas,
        "LogisticsReturnDiscriminator",
        "logistics/logistics-events-v1.schema.json",
        "logistics.return.");
    assertDiscriminator(
        schemas,
        "LogisticsShipmentDiscriminator",
        "logistics/logistics-events-v1.schema.json",
        "logistics.shipment.");
    assertDiscriminator(
        schemas,
        "LogisticsTransferDiscriminator",
        "logistics/logistics-events-v1.schema.json",
        "logistics.transfer.");
    assertDiscriminator(
        schemas,
        "TaskBoardBoardTaskDiscriminator",
        "task-board/task-board-events-v1.schema.json",
        "task-board.board-task.");
    assertDiscriminator(
        schemas,
        "TaskBoardQueueEntryDiscriminator",
        "task-board/task-board-events-v1.schema.json",
        "task-board.queue-entry.");

    Map<String, Object> messages = map(components, "messages");
    assertThat(map(map(messages, "MediaFactV1"), "x-rwms-activity-code-map").keySet())
        .containsExactlyInAnyOrderElementsOf(
            producerEventTypes("media/media-facts-v1.schema.json", "media.media."));
    for (String message :
        List.of(
            "AssetRentalItemFactV1",
            "MaintenanceEstimateFactV1",
            "MaintenanceRepairFactV1",
            "InventorySessionFactV1",
            "InventoryFindingFactV1",
            "InventoryPublicationFactV1",
            "MediaFactV1",
            "LogisticsReturnFactV1",
            "LogisticsShipmentFactV1",
            "LogisticsTransferFactV1",
            "TaskBoardBoardTaskFactV1",
            "TaskBoardQueueEntryFactV1",
            "CabinActivityProjectedV1")) {
      assertThat(map(messages, message)).containsEntry("x-rwms-record-key", "aggregateId");
    }
  }

  @Test
  void sanitizedDltSchemaIsCoordinatesAndHashesOnly() throws Exception {
    Map<String, Object> dlt =
        json(CONTRACTS.resolve("events/dossier/dossier-sanitized-dlt-v1.schema.json"));
    assertThat(map(dlt, "properties").keySet())
        .containsExactlyInAnyOrder(
            "failureId",
            "failureCode",
            "sourceTopic",
            "sourcePartition",
            "sourceOffset",
            "recordKeySha256",
            "messageSha256",
            "eventId",
            "recordedAt");
    assertThat(strings(dlt, "required"))
        .containsExactlyInAnyOrder(
            "failureId",
            "failureCode",
            "sourceTopic",
            "sourcePartition",
            "sourceOffset",
            "recordKeySha256",
            "messageSha256",
            "eventId",
            "recordedAt");
  }

  @Test
  void everyContractReferenceResolvesToAnInternalNodeOrExistingCanonicalFile() throws Exception {
    assertReferencesResolve(OPENAPI, yaml(OPENAPI));
    assertReferencesResolve(ASYNCAPI, yaml(ASYNCAPI));
  }

  private static void assertReferencesResolve(Path contract, Map<String, Object> root) {
    List<String> references = new ArrayList<>();
    collectReferences(root, references);
    assertThat(references).isNotEmpty();
    references.forEach(
        reference -> {
          if (reference.startsWith("#/")) {
            assertThat(resolve(root, reference))
                .as("internal reference %s in %s", reference, contract)
                .isNotNull();
          } else {
            String file = reference.contains("#") ? reference.substring(0, reference.indexOf('#')) : reference;
            assertThat(contract.getParent().resolve(file).normalize())
                .as("external reference %s in %s", reference, contract)
                .exists();
          }
        });
  }

  private static Object resolve(Map<String, Object> root, String reference) {
    Object node = root;
    for (String segment : reference.substring(2).split("/")) {
      if (!(node instanceof Map<?, ?> current)) return null;
      node = current.get(segment.replace("~1", "/").replace("~0", "~"));
    }
    return node;
  }

  private static void assertDiscriminator(
      Map<String, Object> schemas, String discriminator, String producerSchema, String prefix)
      throws Exception {
    Set<String> expected = producerEventTypes(producerSchema, prefix);
    Set<String> actual =
        new LinkedHashSet<>(
            strings(map(map(map(schemas, discriminator), "properties"), "eventType"), "enum"));
    assertThat(actual)
        .as("%s eventType parity", discriminator)
        .containsExactlyInAnyOrderElementsOf(expected);
    assertThat(map(map(map(schemas, discriminator), "properties"), "eventVersion"))
        .containsEntry("const", 1);
  }

  private static Set<String> producerEventTypes(String relative, String prefix) throws Exception {
    Map<String, Object> producer = json(CONTRACTS.resolve("events").resolve(relative));
    return strings(map(map(producer, "properties"), "eventType"), "enum").stream()
        .filter(value -> value.startsWith(prefix))
        .collect(java.util.stream.Collectors.toUnmodifiableSet());
  }

  private static void collectReferences(Object node, List<String> references) {
    if (node instanceof Map<?, ?> values) {
      values.forEach(
          (key, value) -> {
            if ("$ref".equals(key)) references.add(String.valueOf(value));
            collectReferences(value, references);
          });
    } else if (node instanceof Iterable<?> values) {
      values.forEach(value -> collectReferences(value, references));
    }
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> yaml(Path path) throws Exception {
    try (InputStream input = Files.newInputStream(path)) {
      return new Yaml().load(input);
    }
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> json(Path path) throws Exception {
    return new tools.jackson.databind.ObjectMapper().readValue(path.toFile(), Map.class);
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> map(Map<String, Object> parent, String key) {
    return (Map<String, Object>) parent.get(key);
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> map(Object value) {
    return (Map<String, Object>) value;
  }

  @SuppressWarnings("unchecked")
  private static List<Map<String, Object>> maps(Map<String, Object> parent, String key) {
    return (List<Map<String, Object>>) parent.get(key);
  }

  @SuppressWarnings("unchecked")
  private static List<String> strings(Map<String, Object> parent, String key) {
    return (List<String>) parent.get(key);
  }
}
