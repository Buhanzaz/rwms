package dev.buhanzaz.rwms.inventory.service;

import dev.buhanzaz.rwms.inventory.domain.InventoryFinding;
import dev.buhanzaz.rwms.inventory.domain.InventoryReturnInspectionImport;
import dev.buhanzaz.rwms.inventory.domain.InventorySession;
import dev.buhanzaz.rwms.inventory.domain.SessionLifecycle;
import dev.buhanzaz.rwms.inventory.eventing.InventoryDeadLetterStore;
import dev.buhanzaz.rwms.inventory.eventing.InventoryEventChecksum;
import dev.buhanzaz.rwms.inventory.eventing.InventoryLogisticsReturnInboxStore;
import dev.buhanzaz.rwms.inventory.eventing.InventoryLogisticsReturnInboxStore.Source;
import dev.buhanzaz.rwms.inventory.integration.InventoryDependencyGateway;
import dev.buhanzaz.rwms.inventory.persistence.InventoryPostgresJsonbCanonicalizer;
import dev.buhanzaz.rwms.inventory.repository.InventoryReturnInspectionImportRepository;
import dev.buhanzaz.rwms.inventory.repository.InventorySessionRepository;
import dev.buhanzaz.rwms.platform.contracts.OpaqueActorReference;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Validates return facts, performs remote reads outside SQL transactions and imports exact proof. */
@Service
public class InventoryLogisticsReturnInboxProcessor {
  private static final Set<String> ROOT_FIELDS =
      Set.of(
          "envelopeVersion",
          "eventId",
          "eventType",
          "eventVersion",
          "occurredAt",
          "recordedAt",
          "producer",
          "aggregateType",
          "aggregateId",
          "aggregateVersion",
          "correlation",
          "actorRef",
          "payload");
  private static final Set<String> CORRELATION_FIELDS =
      Set.of("correlationId", "causationId");
  private static final Set<String> ACTOR_FIELDS =
      Set.of("subjectId", "principalType", "profileRevision");
  private static final Set<String> RETURN_PAYLOAD_FIELDS =
      Set.of(
          "documentId",
          "documentType",
          "state",
          "warehouseId",
          "destinationWarehouseId",
          "lineCount",
          "resultCode");
  private static final Set<String> ESTIMATE_PAYLOAD_FIELDS =
      Set.of(
          "estimateId",
          "warehouseId",
          "rentalItemId",
          "lifecycle",
          "revision",
          "dispatchDate",
          "lineCount",
          "completionKind",
          "repairId");
  private static final Map<String, String> RETURN_EVENT_STATES =
      Map.ofEntries(
          Map.entry("logistics.return.created.v1", "DRAFT"),
          Map.entry("logistics.return.registration-started.v1", "REGISTERING"),
          Map.entry("logistics.return.inspection-required.v1", "INSPECTION_REQUIRED"),
          Map.entry("logistics.return.acceptance-started.v1", "ACCEPTING"),
          Map.entry("logistics.return.accepted.v1", "ACCEPTED"),
          Map.entry("logistics.return.estimate-started.v1", "ESTIMATE_PENDING"),
          Map.entry("logistics.return.estimate-requested.v1", "ESTIMATE_REQUESTED"),
          Map.entry("logistics.return.conflicted.v1", "CONFLICT"),
          Map.entry("logistics.return.conflict.v1", "CONFLICT"),
          Map.entry("logistics.return.reconciliation-required.v1", "RECONCILIATION_REQUIRED"));
  private static final Set<String> ESTIMATE_EVENTS =
      Set.of(
          "maintenance.estimate.created.v1",
          "maintenance.estimate.draft-changed.v1",
          "maintenance.estimate.completed.v1",
          "maintenance.estimate.amended.v1");
  private static final String RETURN_ACCEPTED = "logistics.return.accepted.v1";
  private static final String ESTIMATE_COMPLETED = "maintenance.estimate.completed.v1";
  private static final OpaqueActorReference SERVICE_ACTOR =
      new OpaqueActorReference(
          UUID.nameUUIDFromBytes(
                  "rwms:inventory-service:normal-return-import"
                      .getBytes(StandardCharsets.UTF_8))
              .toString(),
          "SERVICE",
          null);

  private final InventoryLogisticsReturnInboxStore inbox;
  private final InventoryPostgresJsonbCanonicalizer jsonb;
  private final ObjectMapper mapper;
  private final InventoryDeadLetterStore deadLetters;
  private final InventoryDependencyGateway dependencies;
  private final InventorySessionRepository sessions;
  private final InventoryReturnInspectionImportRepository imports;
  private final InventoryFindingService findings;
  private final TransactionTemplate transactions;

  public InventoryLogisticsReturnInboxProcessor(
      InventoryLogisticsReturnInboxStore inbox,
      InventoryPostgresJsonbCanonicalizer jsonb,
      ObjectMapper mapper,
      InventoryDeadLetterStore deadLetters,
      InventoryDependencyGateway dependencies,
      InventorySessionRepository sessions,
      InventoryReturnInspectionImportRepository imports,
      InventoryFindingService findings,
      PlatformTransactionManager transactionManager) {
    this.inbox = inbox;
    this.jsonb = jsonb;
    this.mapper = mapper;
    this.deadLetters = deadLetters;
    this.dependencies = dependencies;
    this.sessions = sessions;
    this.imports = imports;
    this.findings = findings;
    transactions = new TransactionTemplate(transactionManager);
  }

  public void initial(Source source, byte[] bytes, byte[] recordKey) {
    String deliveryHash = InventoryEventChecksum.sha256(bytes);
    InboundEvent event;
    try {
      event = validate(source, bytes, recordKey);
    } catch (InvalidInboundEvent exception) {
      deadLetters.record("VALIDATION_REJECTED", deliveryHash, source.topic(), exception.eventId());
      return;
    }
    Optional<String> existing = inbox.payloadSha256(event.eventId());
    if (existing.isPresent()) {
      if (!deliveryHash.equals(existing.get())) {
        deadLetters.record("EVENT_ID_CONFLICT", deliveryHash, source.topic(), event.eventId());
      }
      return;
    }
    String envelope = canonical(new String(bytes, StandardCharsets.UTF_8), "inbound fact");
    PreparedImport prepared = prepare(event).orElse(null);
    transactions.executeWithoutResult(
        ignored -> applyInitial(source, event, deliveryHash, envelope, prepared));
  }

  public void retry(UUID eventId) {
    InventoryLogisticsReturnInboxStore.RetryEnvelope retry =
        inbox.retryEnvelope(eventId).orElse(null);
    if (retry == null) return;
    byte[] bytes = retry.body().getBytes(StandardCharsets.UTF_8);
    InboundEvent event;
    try {
      event =
          validate(
              retry.source(), bytes, retry.recordKey().getBytes(StandardCharsets.UTF_8));
    } catch (InvalidInboundEvent exception) {
      transactions.executeWithoutResult(
          ignored -> {
            if (inbox.lockRetryHash(eventId).isEmpty()) return;
            inbox.markValidationRejected(eventId);
            deadLetters.record(
                "VALIDATION_REJECTED", retry.payloadSha256(), retry.source().topic(), eventId);
          });
      return;
    }
    PreparedImport prepared = prepare(event).orElse(null);
    transactions.executeWithoutResult(
        ignored -> applyRetry(retry.source(), event, retry.payloadSha256(), prepared));
  }

  private Optional<PreparedImport> prepare(InboundEvent event) {
    if (event instanceof ReturnEvent value) {
      return RETURN_ACCEPTED.equals(value.eventType())
          ? Optional.of(prepareAcceptedReturn(value))
          : Optional.empty();
    }
    EstimateEvent value = (EstimateEvent) event;
    if (!ESTIMATE_COMPLETED.equals(value.eventType())) return Optional.empty();
    return dependencies
        .completedReturnEstimate(value.estimateId())
        .map(proof -> prepareEstimate(value, proof));
  }

  private PreparedImport prepareAcceptedReturn(ReturnEvent event) {
    InventoryDependencyGateway.NormalReturnInspection proof =
        dependencies.normalReturnInspection(event.returnId());
    if (!event.returnId().equals(proof.returnId())
        || event.aggregateVersion() != proof.documentVersion()
        || !event.warehouseId().equals(proof.warehouseId())
        || !event.state().equals(proof.terminalState())
        || event.lineCount() != proof.lines().size()) {
      throw InventoryException.dependency(
          "Logistics-service returned mismatched normal-return inspection proof");
    }
    return prepareAssets(
        proof,
        proof.lines(),
        null,
        proof.arrivedAt(),
        proof.completedAt(),
        proof.terminalState(),
        canonicalHash(proof));
  }

  private PreparedImport prepareEstimate(
      EstimateEvent event, InventoryDependencyGateway.CompletedReturnEstimateProof estimate) {
    if (!event.estimateId().equals(estimate.estimateId())
        || event.aggregateVersion() > estimate.estimateVersion()
        || event.revision() > estimate.estimateRevision()
        || !event.warehouseId().equals(estimate.warehouseId())
        || !event.assetId().equals(estimate.assetId())
        || event.aggregateVersion() == estimate.estimateVersion()
            && (!event.completionKind().equals(estimate.completionKind())
                || !java.util.Objects.equals(event.repairId(), estimate.repairId()))) {
      throw InventoryException.dependency(
          "Maintenance-service returned mismatched completed return estimate proof");
    }
    InventoryDependencyGateway.NormalReturnInspection proof =
        dependencies.normalReturnInspection(estimate.returnId());
    InventoryDependencyGateway.NormalReturnInspectionLine line =
        proof.lines().stream()
            .filter(candidate -> estimate.lineId().equals(candidate.lineId()))
            .findFirst()
            .orElseThrow(
                () ->
                    InventoryException.dependency(
                        "Logistics return proof does not contain the estimated line"));
    if (!"ESTIMATE_REQUESTED".equals(proof.terminalState())
        || !estimate.warehouseId().equals(proof.warehouseId())
        || !estimate.assetId().equals(line.assetId())
        || !estimate.arrivedAt().equals(proof.arrivedAt())
        || estimate.completedAt().isBefore(proof.arrivedAt())) {
      throw InventoryException.dependency(
          "Maintenance completion does not match logistics return inspection proof");
    }
    return prepareAssets(
        proof,
        List.of(line),
        estimate.estimateId(),
        proof.arrivedAt(),
        estimate.completedAt(),
        proof.terminalState(),
        canonicalHash(new CombinedProof(proof, estimate)));
  }

  private PreparedImport prepareAssets(
      InventoryDependencyGateway.NormalReturnInspection proof,
      List<InventoryDependencyGateway.NormalReturnInspectionLine> lines,
      UUID estimateId,
      OffsetDateTime arrivedAt,
      OffsetDateTime completedAt,
      String terminalState,
      String proofHash) {
    Map<UUID, InventoryDependencyGateway.LiveAssetSnapshot> currentAssets = new LinkedHashMap<>();
    lines.stream()
        .sorted(Comparator.comparing(InventoryDependencyGateway.NormalReturnInspectionLine::lineId))
        .forEach(
            line -> {
              InventoryDependencyGateway.LiveAssetSnapshot current =
                  dependencies
                      .currentAsset(line.assetId())
                      .orElseThrow(
                          () ->
                              InventoryException.dependency(
                                  "Return inspection asset is absent from asset-service"));
              if (!line.assetId().equals(current.assetId())
                  || current.version() < line.assetVersion()) {
                throw InventoryException.dependency(
                    "Asset-service returned stale normal-return asset truth");
              }
              currentAssets.put(line.assetId(), current);
            });
    return new PreparedImport(
        proof,
        List.copyOf(lines),
        Map.copyOf(currentAssets),
        estimateId,
        arrivedAt,
        completedAt,
        terminalState,
        proofHash);
  }

  private void applyInitial(
      Source source,
      InboundEvent event,
      String deliveryHash,
      String envelope,
      PreparedImport prepared) {
    if (inbox.insertReceived(
            source,
            event.eventId(),
            event.aggregateId(),
            event.aggregateVersion(),
            event.eventType(),
            deliveryHash,
            envelope)
        == 0) {
      recordConflictIfChanged(source, event.eventId(), deliveryHash);
      return;
    }
    if (prepared != null) applyPrepared(event, prepared);
    inbox.markProcessed(event.eventId());
  }

  private void applyRetry(
      Source source, InboundEvent event, String deliveryHash, PreparedImport prepared) {
    Optional<String> storedHash = inbox.lockRetryHash(event.eventId());
    if (storedHash.isEmpty()) return;
    if (!deliveryHash.equals(storedHash.get())) {
      inbox.markValidationRejected(event.eventId());
      deadLetters.record("EVENT_ID_CONFLICT", deliveryHash, source.topic(), event.eventId());
      return;
    }
    if (prepared != null) applyPrepared(event, prepared);
    inbox.markProcessed(event.eventId());
  }

  private void applyPrepared(InboundEvent event, PreparedImport prepared) {
    InventoryDependencyGateway.NormalReturnInspection proof = prepared.proof();
    InventorySession session =
        sessions
            .findByWarehouseIdAndLifecycleForUpdate(proof.warehouseId(), SessionLifecycle.ACTIVE)
            .orElse(null);
    if (session == null
        || session.getStartedAt() == null
        || session.getStartedAt().isAfter(prepared.completedAt())) {
      return;
    }
    NormalReturnImportContext context =
        new NormalReturnImportContext(
            proof.returnId(),
            proof.documentVersion(),
            prepared.estimateId(),
            event.eventId(),
            event.correlationId(),
            event.actor() == null ? SERVICE_ACTOR : event.actor(),
            event.occurredAt(),
            prepared.arrivedAt(),
            prepared.completedAt(),
            prepared.terminalState(),
            prepared.proofHash());
    OffsetDateTime importedAt = OffsetDateTime.now(ZoneOffset.UTC);
    for (InventoryDependencyGateway.NormalReturnInspectionLine line :
        prepared.lines().stream()
            .sorted(
                Comparator.comparing(
                    InventoryDependencyGateway.NormalReturnInspectionLine::lineId))
            .toList()) {
      InventoryReturnInspectionImport.Key key =
          new InventoryReturnInspectionImport.Key(
              proof.returnId(), line.lineId(), proof.documentVersion());
      InventoryReturnInspectionImport existing = imports.findById(key).orElse(null);
      if (existing != null) {
        if (!prepared.proofHash().equals(existing.getProofSha256())) {
          throw new IllegalStateException("Return inspection semantic key changed proof");
        }
        continue;
      }
      InventoryDependencyGateway.LiveAssetSnapshot current =
          prepared.currentAssets().get(line.assetId());
      InventoryFinding finding =
          findings.importNormalReturnInspection(session, line, current, context);
      imports.saveAndFlush(
          InventoryReturnInspectionImport.imported(
              proof.returnId(),
              line.lineId(),
              proof.documentVersion(),
              context.estimateId(),
              event.eventId(),
              event.occurredAt(),
              session.getId(),
              finding.getId(),
              proof.warehouseId(),
              prepared.arrivedAt(),
              prepared.completedAt(),
              prepared.terminalState(),
              line.assetId(),
              line.assetVersion(),
              line.status(),
              canonical(json(line.media()), "normal-return media evidence"),
              prepared.proofHash(),
              importedAt));
    }
  }

  private void recordConflictIfChanged(Source source, UUID eventId, String deliveryHash) {
    String existing = inbox.payloadSha256(eventId).orElse(null);
    if (!deliveryHash.equals(existing)) {
      deadLetters.record("EVENT_ID_CONFLICT", deliveryHash, source.topic(), eventId);
    }
  }

  private InboundEvent validate(Source source, byte[] bytes, byte[] recordKey) {
    return switch (source) {
      case LOGISTICS_RETURN -> validateReturn(bytes, recordKey);
      case MAINTENANCE_ESTIMATE -> validateEstimate(bytes, recordKey);
    };
  }

  private ReturnEvent validateReturn(byte[] bytes, byte[] recordKey) {
    JsonNode root;
    UUID eventId = null;
    try {
      root = mapper.readTree(bytes);
      eventId = eventId(root);
      exact(root, ROOT_FIELDS);
      String eventType = root.path("eventType").asText();
      String expectedState = RETURN_EVENT_STATES.get(eventType);
      commonEnvelope(root, recordKey, "logistics-service", "RETURN", expectedState != null);
      UUID returnId = UUID.fromString(root.path("aggregateId").asText());
      JsonNode payload = root.path("payload");
      exact(payload, RETURN_PAYLOAD_FIELDS);
      if (!returnId.equals(UUID.fromString(payload.path("documentId").asText()))
          || !"RETURN".equals(payload.path("documentType").asText())
          || !expectedState.equals(payload.path("state").asText())
          || !payload.path("destinationWarehouseId").isNull()) {
        throw invalid(eventId);
      }
      int lineCount = integralInt(payload, "lineCount", 1);
      JsonNode resultCode = payload.get("resultCode");
      if (lineCount < 1
          || lineCount > 100
          || resultCode == null
          || (!resultCode.isNull()
              && (!resultCode.isTextual()
                  || !resultCode.asText().matches("^[A-Z][A-Z0-9_]{0,63}$")))) {
        throw invalid(eventId);
      }
      return new ReturnEvent(
          eventId,
          eventType,
          returnId,
          integralLong(root, "aggregateVersion", 0),
          UUID.fromString(payload.path("warehouseId").asText()),
          expectedState,
          lineCount,
          correlationId(root),
          actor(root.path("actorRef")),
          auditOccurredAt(root));
    } catch (InvalidInboundEvent exception) {
      throw exception.eventId() == null && eventId != null ? invalid(eventId) : exception;
    } catch (RuntimeException exception) {
      throw invalid(eventId);
    }
  }

  private EstimateEvent validateEstimate(byte[] bytes, byte[] recordKey) {
    JsonNode root;
    UUID eventId = null;
    try {
      root = mapper.readTree(bytes);
      eventId = eventId(root);
      exact(root, ROOT_FIELDS);
      String eventType = root.path("eventType").asText();
      commonEnvelope(
          root, recordKey, "maintenance-service", "ESTIMATE", ESTIMATE_EVENTS.contains(eventType));
      UUID estimateId = UUID.fromString(root.path("aggregateId").asText());
      JsonNode payload = root.path("payload");
      exactEstimatePayload(payload);
      if (!estimateId.equals(UUID.fromString(payload.path("estimateId").asText()))) {
        throw invalid(eventId);
      }
      String lifecycle = payload.path("lifecycle").asText();
      String completionKind = payload.path("completionKind").asText();
      int revision = integralInt(payload, "revision", 1);
      int lineCount = integralInt(payload, "lineCount", 0);
      UUID repairId = nullableUuid(payload.path("repairId"));
      LocalDate.parse(payload.path("dispatchDate").asText());
      boolean completedLifecycleEvent =
          ESTIMATE_COMPLETED.equals(eventType)
              || "maintenance.estimate.amended.v1".equals(eventType);
      if (revision < 1
          || lineCount < 0
          || !(payload.path("forceCapitalRepair").isMissingNode()
              || payload.path("forceCapitalRepair").isBoolean())
          || !("DRAFT".equals(lifecycle) || "COMPLETED".equals(lifecycle))
          || completedLifecycleEvent != "COMPLETED".equals(lifecycle)
          || completedLifecycleEvent == "NOT_COMPLETED".equals(completionKind)
          || !("NOT_COMPLETED".equals(completionKind)
              || "EMPTY".equals(completionKind)
              || "NON_EMPTY".equals(completionKind))
          || ("EMPTY".equals(completionKind) && (lineCount != 0 || repairId != null))
          || ("NON_EMPTY".equals(completionKind) && (lineCount == 0 || repairId == null))
          || ("NOT_COMPLETED".equals(completionKind) && repairId != null)) {
        throw invalid(eventId);
      }
      return new EstimateEvent(
          eventId,
          eventType,
          estimateId,
          integralLong(root, "aggregateVersion", 0),
          UUID.fromString(payload.path("warehouseId").asText()),
          UUID.fromString(payload.path("rentalItemId").asText()),
          revision,
          completionKind,
          repairId,
          correlationId(root),
          actor(root.path("actorRef")),
          auditOccurredAt(root));
    } catch (InvalidInboundEvent exception) {
      throw exception.eventId() == null && eventId != null ? invalid(eventId) : exception;
    } catch (RuntimeException exception) {
      throw invalid(eventId);
    }
  }

  private void commonEnvelope(
      JsonNode root,
      byte[] recordKey,
      String producer,
      String aggregateType,
      boolean knownEvent) {
    long aggregateVersion = integralLong(root, "aggregateVersion", 0);
    UUID aggregateId = UUID.fromString(root.path("aggregateId").asText());
    String key = recordKey == null ? null : new String(recordKey, StandardCharsets.UTF_8);
    if (integralInt(root, "envelopeVersion", 0) != 2
        || integralInt(root, "eventVersion", 0) != 1
        || !producer.equals(root.path("producer").asText())
        || !aggregateType.equals(root.path("aggregateType").asText())
        || !knownEvent
        || !aggregateId.toString().equals(key)) {
      throw invalid(eventId(root));
    }
    auditOccurredAt(root);
    correlationId(root);
    actor(root.path("actorRef"));
  }

  private UUID eventId(JsonNode root) {
    if (root == null || !root.isObject() || !root.hasNonNull("eventId")) return null;
    return UUID.fromString(root.path("eventId").asText());
  }

  private UUID correlationId(JsonNode root) {
    JsonNode correlation = root.path("correlation");
    exact(correlation, CORRELATION_FIELDS);
    UUID correlationId = UUID.fromString(correlation.path("correlationId").asText());
    if (!correlation.path("causationId").isNull()) {
      UUID.fromString(correlation.path("causationId").asText());
    }
    return correlationId;
  }

  private UUID nullableUuid(JsonNode value) {
    return value.isNull() ? null : UUID.fromString(value.asText());
  }

  private OffsetDateTime auditOccurredAt(JsonNode root) {
    OffsetDateTime recordedAt = OffsetDateTime.parse(root.path("recordedAt").asText());
    JsonNode occurredAt = root.get("occurredAt");
    return occurredAt == null || occurredAt.isNull()
        ? recordedAt
        : OffsetDateTime.parse(occurredAt.asText());
  }

  private int integralInt(JsonNode parent, String field, int minimum) {
    JsonNode value = parent == null ? null : parent.get(field);
    if (value == null || !value.isIntegralNumber() || !value.canConvertToInt()) {
      throw invalid(null);
    }
    int result = value.intValue();
    if (result < minimum) throw invalid(null);
    return result;
  }

  private long integralLong(JsonNode parent, String field, long minimum) {
    JsonNode value = parent == null ? null : parent.get(field);
    if (value == null || !value.isIntegralNumber() || !value.canConvertToLong()) {
      throw invalid(null);
    }
    long result = value.longValue();
    if (result < minimum) throw invalid(null);
    return result;
  }

  private OpaqueActorReference actor(JsonNode value) throws JacksonException {
    if (value.isNull()) return null;
    exact(value, ACTOR_FIELDS);
    return mapper.treeToValue(value, OpaqueActorReference.class);
  }

  private void exactEstimatePayload(JsonNode value) {
    if (value == null || !value.isObject()) throw invalid(null);
    if (value.size() == ESTIMATE_PAYLOAD_FIELDS.size()
        || value.size() == ESTIMATE_PAYLOAD_FIELDS.size() + 1 && value.has("forceCapitalRepair")) {
      for (String field : ESTIMATE_PAYLOAD_FIELDS) {
        if (!value.has(field)) throw invalid(null);
      }
      return;
    }
    throw invalid(null);
  }

  private void exact(JsonNode value, Set<String> fields) {
    if (value == null || !value.isObject() || value.size() != fields.size()) throw invalid(null);
    for (String field : fields) {
      if (!value.has(field)) throw invalid(null);
    }
  }

  private String canonicalHash(Object proof) {
    return InventoryEventChecksum.sha256(canonical(json(proof), "normal-return proof"));
  }

  private String json(Object value) {
    try {
      return mapper.writeValueAsString(value);
    } catch (JacksonException exception) {
      throw new IllegalArgumentException("Return inspection proof is not serializable", exception);
    }
  }

  private String canonical(String raw, String name) {
    String value = jsonb.canonicalize(raw);
    if (value == null) throw new IllegalStateException("PostgreSQL did not canonicalize " + name);
    return value;
  }

  private InvalidInboundEvent invalid(UUID eventId) {
    return new InvalidInboundEvent(eventId);
  }

  private sealed interface InboundEvent permits ReturnEvent, EstimateEvent {
    UUID eventId();

    String eventType();

    UUID aggregateId();

    long aggregateVersion();

    UUID correlationId();

    OpaqueActorReference actor();

    OffsetDateTime occurredAt();
  }

  private record ReturnEvent(
      UUID eventId,
      String eventType,
      UUID aggregateId,
      long aggregateVersion,
      UUID warehouseId,
      String state,
      int lineCount,
      UUID correlationId,
      OpaqueActorReference actor,
      OffsetDateTime occurredAt)
      implements InboundEvent {
    UUID returnId() {
      return aggregateId;
    }
  }

  private record EstimateEvent(
      UUID eventId,
      String eventType,
      UUID aggregateId,
      long aggregateVersion,
      UUID warehouseId,
      UUID assetId,
      int revision,
      String completionKind,
      UUID repairId,
      UUID correlationId,
      OpaqueActorReference actor,
      OffsetDateTime occurredAt)
      implements InboundEvent {
    UUID estimateId() {
      return aggregateId;
    }
  }

  private record PreparedImport(
      InventoryDependencyGateway.NormalReturnInspection proof,
      List<InventoryDependencyGateway.NormalReturnInspectionLine> lines,
      Map<UUID, InventoryDependencyGateway.LiveAssetSnapshot> currentAssets,
      UUID estimateId,
      OffsetDateTime arrivedAt,
      OffsetDateTime completedAt,
      String terminalState,
      String proofHash) {}

  private record CombinedProof(
      InventoryDependencyGateway.NormalReturnInspection logistics,
      InventoryDependencyGateway.CompletedReturnEstimateProof maintenance) {}

  private static final class InvalidInboundEvent extends RuntimeException {
    private final UUID eventId;

    private InvalidInboundEvent(UUID eventId) {
      this.eventId = eventId;
    }

    private UUID eventId() {
      return eventId;
    }
  }
}
