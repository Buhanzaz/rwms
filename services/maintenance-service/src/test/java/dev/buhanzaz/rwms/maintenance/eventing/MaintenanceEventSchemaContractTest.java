package dev.buhanzaz.rwms.maintenance.eventing;

import static org.assertj.core.api.Assertions.assertThat;

import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import dev.buhanzaz.rwms.maintenance.domain.EstimateState;
import dev.buhanzaz.rwms.maintenance.domain.MaintenanceEventType;
import dev.buhanzaz.rwms.maintenance.eventing.MaintenanceEventContractFixtures.FactCase;
import dev.buhanzaz.rwms.maintenance.eventing.MaintenanceEventPayloads.CompletionKind;
import dev.buhanzaz.rwms.platform.contracts.CorrelationContext;
import dev.buhanzaz.rwms.platform.contracts.DomainEventEnvelopeV2;
import dev.buhanzaz.rwms.platform.contracts.OpaqueActorReference;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

class MaintenanceEventSchemaContractTest {
  private static final ObjectMapper WIRE_MAPPER = JsonMapper.builder().findAndAddModules().build();
  private static final com.fasterxml.jackson.databind.ObjectMapper SCHEMA_MAPPER =
      new com.fasterxml.jackson.databind.ObjectMapper();
  private static final JsonSchemaFactory SCHEMA_FACTORY =
      JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);
  private static final Instant RECORDED_AT = Instant.parse("2026-07-17T09:30:00Z");

  @ParameterizedTest
  @EnumSource(MaintenanceEventType.class)
  void everyExactEventTypeSerializesAsARealValidDomainEventEnvelopeV2(
      MaintenanceEventType eventType) throws Exception {
    FactCase fact = MaintenanceEventContractFixtures.fact(eventType);
    var envelope = new DomainEventEnvelopeV2<>(
        2,
        UUID.randomUUID(),
        eventType.value(),
        1,
        null,
        RECORDED_AT,
        "maintenance-service",
        fact.aggregateType().name(),
        fact.aggregateId().toString(),
        7,
        new CorrelationContext(UUID.randomUUID(), null),
        new OpaqueActorReference(UUID.randomUUID().toString(), "USER", null),
        fact.payload());

    var wire = SCHEMA_MAPPER.readTree(WIRE_MAPPER.writeValueAsString(envelope));

    assertThat(schema().validate(wire)).as(eventType.value()).isEmpty();
    assertThat(wire.required("eventType").textValue()).isEqualTo(eventType.value());
    assertThat(wire.required("aggregateId").textValue()).isEqualTo(fact.aggregateId().toString());
    assertThat(wire.required("occurredAt").isNull()).isTrue();
  }

  @Test
  void requiredNullableFieldsMustBePresentAndUnknownFieldsAreRejected() throws Exception {
    var emptyEstimate = MaintenanceEventContractFixtures.estimate(
        EstimateState.COMPLETED, CompletionKind.EMPTY, 0, null);
    var envelope = new DomainEventEnvelopeV2<>(
        2,
        UUID.randomUUID(),
        MaintenanceEventType.ESTIMATE_COMPLETED.value(),
        1,
        null,
        RECORDED_AT,
        "maintenance-service",
        "ESTIMATE",
        emptyEstimate.estimateId().toString(),
        1,
        new CorrelationContext(UUID.randomUUID(), null),
        null,
        emptyEstimate);
    var valid = SCHEMA_MAPPER.readTree(WIRE_MAPPER.writeValueAsString(envelope));

    assertThat(valid.required("actorRef").isNull()).isTrue();
    assertThat(valid.required("payload").required("repairId").isNull()).isTrue();
    assertThat(valid.required("payload").required("forceCapitalRepair").booleanValue()).isFalse();
    assertThat(schema().validate(valid)).isEmpty();

    var missingNullable = valid.deepCopy();
    ((com.fasterxml.jackson.databind.node.ObjectNode) missingNullable.required("payload"))
        .remove("repairId");
    assertThat(schema().validate(missingNullable)).isNotEmpty();

    var missingCapitalChoice = valid.deepCopy();
    ((com.fasterxml.jackson.databind.node.ObjectNode) missingCapitalChoice.required("payload"))
        .remove("forceCapitalRepair");
    assertThat(schema().validate(missingCapitalChoice)).isEmpty();

    var nullCapitalChoice = valid.deepCopy();
    ((com.fasterxml.jackson.databind.node.ObjectNode) nullCapitalChoice.required("payload"))
        .putNull("forceCapitalRepair");
    assertThat(schema().validate(nullCapitalChoice)).isNotEmpty();

    var missingActor = valid.deepCopy();
    ((com.fasterxml.jackson.databind.node.ObjectNode) missingActor).remove("actorRef");
    assertThat(schema().validate(missingActor)).isNotEmpty();

    var unknown = valid.deepCopy();
    ((com.fasterxml.jackson.databind.node.ObjectNode) unknown.required("payload"))
        .put("comment", "must stay local");
    assertThat(schema().validate(unknown)).isNotEmpty();
  }

  @Test
  void eventFamilyCannotBePublishedWithAnotherAggregateTypeOrPayloadShape() throws Exception {
    FactCase catalog = MaintenanceEventContractFixtures.fact(MaintenanceEventType.CATALOG_ACTIVATED);
    var envelope = new DomainEventEnvelopeV2<>(
        2,
        UUID.randomUUID(),
        MaintenanceEventType.CATALOG_ACTIVATED.value(),
        1,
        RECORDED_AT,
        RECORDED_AT,
        "maintenance-service",
        catalog.aggregateType().name(),
        catalog.aggregateId().toString(),
        2,
        new CorrelationContext(UUID.randomUUID(), UUID.randomUUID()),
        null,
        catalog.payload());
    var mismatched = SCHEMA_MAPPER.readTree(WIRE_MAPPER.writeValueAsString(envelope));
    ((com.fasterxml.jackson.databind.node.ObjectNode) mismatched).put("aggregateType", "ESTIMATE");

    assertThat(schema().validate(mismatched)).isNotEmpty();
  }

  @Test
  void repairPriorityIsRequiredAndRestrictedToOneThroughFive()
      throws Exception {
    FactCase repair = MaintenanceEventContractFixtures.fact(MaintenanceEventType.REPAIR_QUEUED);
    var envelope = new DomainEventEnvelopeV2<>(
        2,
        UUID.randomUUID(),
        MaintenanceEventType.REPAIR_QUEUED.value(),
        1,
        RECORDED_AT,
        RECORDED_AT,
        "maintenance-service",
        repair.aggregateType().name(),
        repair.aggregateId().toString(),
        1,
        new CorrelationContext(UUID.randomUUID(), null),
        null,
        repair.payload());
    var valid = SCHEMA_MAPPER.readTree(WIRE_MAPPER.writeValueAsString(envelope));

    assertThat(valid.required("payload").required("priority").intValue()).isEqualTo(3);
    assertThat(schema().validate(valid)).isEmpty();

    var invalid = valid.deepCopy();
    ((com.fasterxml.jackson.databind.node.ObjectNode) invalid.required("payload"))
        .put("priority", 6);
    assertThat(schema().validate(invalid)).isNotEmpty();

    var missing = valid.deepCopy();
    ((com.fasterxml.jackson.databind.node.ObjectNode) missing.required("payload"))
        .remove("priority");
    assertThat(schema().validate(missing)).isNotEmpty();
  }

  @Test
  void externallyExecutedCapitalRepairPublishesNotRequiredTaskGenerationTruth()
      throws Exception {
    FactCase repair =
        MaintenanceEventContractFixtures.fact(MaintenanceEventType.REPAIR_PLAN_CHANGED);
    var envelope =
        new DomainEventEnvelopeV2<>(
            2,
            UUID.randomUUID(),
            MaintenanceEventType.REPAIR_PLAN_CHANGED.value(),
            1,
            RECORDED_AT,
            RECORDED_AT,
            "maintenance-service",
            repair.aggregateType().name(),
            repair.aggregateId().toString(),
            2,
            new CorrelationContext(UUID.randomUUID(), null),
            null,
            repair.payload());
    var wire = SCHEMA_MAPPER.readTree(WIRE_MAPPER.writeValueAsString(envelope));
    var taskSync =
        (com.fasterxml.jackson.databind.node.ObjectNode)
            wire.required("payload").required("stages").required(0).required("taskSync");
    taskSync.put("generationState", "NOT_REQUIRED");

    assertThat(schema().validate(wire)).isEmpty();
  }

  @Test
  void asyncApiBindsEachAggregateFamilyChannelToItsExactEventTypes() throws Exception {
    String asyncApi = Files.readString(
        Path.of(System.getProperty("rwms.contracts.dir"), "events/maintenance-events.yaml"));

    assertThat(asyncApi)
        .contains(
            "rwms.maintenance.catalog-version.v1",
            "rwms.maintenance.estimate.v1",
            "rwms.maintenance.repair.v1",
            "CatalogVersionFactV1",
            "EstimateFactV1",
            "RepairFactV1",
            "BoardTaskFactV1",
            "QueueEntryFactV1",
            "x-rwms-actionable-event-types",
            "task-board.board-task.completed.v1",
            "task-board.queue-entry.cancelled.v1",
            MaintenanceEventType.CATALOG_IMPORTED.value(),
            MaintenanceEventType.ESTIMATE_COMPLETED.value(),
            MaintenanceEventType.REPAIR_WRITTEN_OFF.value())
        .doesNotContain("rwms.domain.v1", "protocol: amqp");
  }

  private JsonSchema schema() throws Exception {
    Path path = Path.of(
        System.getProperty("rwms.contracts.dir"),
        "events/maintenance/maintenance-events-v1.schema.json");
    return SCHEMA_FACTORY.getSchema(SCHEMA_MAPPER.readTree(Files.readString(path)));
  }
}
