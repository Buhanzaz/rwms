package dev.buhanzaz.rwms.logistics.service;

import dev.buhanzaz.rwms.logistics.api.LogisticsApiModels.AcceptReturnRequest;
import dev.buhanzaz.rwms.logistics.api.LogisticsApiModels.CreateReturnRequest;
import dev.buhanzaz.rwms.logistics.api.LogisticsApiModels.ReturnPickupRequest;
import dev.buhanzaz.rwms.logistics.api.LogisticsApiModels.StartReturnEstimatesRequest;
import dev.buhanzaz.rwms.logistics.domain.LogisticsDocument;
import dev.buhanzaz.rwms.logistics.domain.LogisticsDocumentLine;
import dev.buhanzaz.rwms.logistics.domain.LogisticsDocumentState;
import dev.buhanzaz.rwms.logistics.domain.LogisticsDocumentType;
import dev.buhanzaz.rwms.logistics.domain.LogisticsGuard;
import dev.buhanzaz.rwms.logistics.domain.LogisticsGuardState;
import dev.buhanzaz.rwms.logistics.domain.LogisticsMediaPurpose;
import dev.buhanzaz.rwms.logistics.domain.LogisticsMediaReference;
import dev.buhanzaz.rwms.logistics.domain.LogisticsReturnShortageSnapshot;
import dev.buhanzaz.rwms.logistics.domain.LogisticsTargetService;
import dev.buhanzaz.rwms.logistics.eventing.LogisticsEventStore;
import dev.buhanzaz.rwms.logistics.eventing.LogisticsEventType;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway.WarehouseOperationDirection;
import dev.buhanzaz.rwms.logistics.order.domain.RentalOrder;
import dev.buhanzaz.rwms.logistics.repository.LogisticsDocumentLineRepository;
import dev.buhanzaz.rwms.logistics.repository.LogisticsDocumentRepository;
import dev.buhanzaz.rwms.logistics.repository.LogisticsGuardRepository;
import dev.buhanzaz.rwms.logistics.repository.LogisticsMediaReferenceRepository;
import dev.buhanzaz.rwms.logistics.repository.LogisticsReturnShortageSnapshotRepository;
import dev.buhanzaz.rwms.logistics.service.LogisticsWarehouseLifecycle.AdmissionTicket;
import dev.buhanzaz.rwms.logistics.service.LogisticsWarehouseLifecycleStore.AdmissionRequirement;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Owns the return-document command state machine from creation through registration, undamaged
 * acceptance, and maintenance estimate initiation. All durable effects are recorded before their
 * relays make private service calls.
 */
@Service
@RequiredArgsConstructor
class LogisticsReturnDocumentCoordinator {
  private static final String CREATE_RETURN = "CREATE_RETURN";
  private static final String REGISTER_RETURN = "REGISTER_RETURN";
  private static final String ACCEPT_RETURN = "ACCEPT_RETURN";
  private static final String START_RETURN_ESTIMATES = "START_RETURN_ESTIMATES";

  private final LogisticsDocumentRepository documentRepository;
  private final LogisticsDocumentLineRepository lineRepository;
  private final LogisticsGuardRepository guardRepository;
  private final LogisticsMediaReferenceRepository mediaReferenceRepository;
  private final LogisticsReturnShortageSnapshotRepository shortageSnapshotRepository;
  private final LogisticsEventStore eventStore;
  private final LogisticsDocumentWarehouseAdmission warehouseAdmission;
  private final LogisticsDocumentIdempotency idempotency;
  private final LogisticsDocumentReadProjection readProjection;
  private final LogisticsDocumentAttemptWriter attemptWriter;
  private final LogisticsRentalOrderBindingPolicy rentalOrderBinding;

  LogisticsDocumentCommandResult createReturn(
      UUID subjectId, UUID idempotencyKey, UUID correlationId, CreateReturnRequest request) {
    requireRequest(request);
    warehouseAdmission.requireLegacyAdmissionDisabled();
    return createReturn(
        subjectId,
        idempotencyKey,
        correlationId,
        request,
        warehouseAdmission.testTicket(
            subjectId,
            CREATE_RETURN,
            idempotencyKey,
            List.of(
                new AdmissionRequirement(
                    request.warehouseId(), WarehouseOperationDirection.INCOMING))));
  }

  LogisticsDocumentCommandResult createReturn(
      UUID subjectId,
      UUID idempotencyKey,
      UUID correlationId,
      CreateReturnRequest request,
      AdmissionTicket admission) {
    requireRequest(request);
    String checksum =
        LogisticsCommandChecksum.sha256(CREATE_RETURN, returnFingerprintValues(request));
    idempotency.acquireLock(subjectId, CREATE_RETURN, idempotencyKey);
    LogisticsDocument replay = idempotency.replay(subjectId, idempotencyKey, CREATE_RETURN, checksum);
    if (replay != null) return result(replay, true);

    warehouseAdmission.requireAdmission(
        admission,
        List.of(
            new AdmissionRequirement(
                request.warehouseId(), WarehouseOperationDirection.INCOMING)));

    RentalOrder returnOrder = rentalOrderBinding.validateReturnBinding(request);
    LogisticsDocument document =
        documentRepository.saveAndFlush(
            LogisticsDocument.createReturn(
                request.warehouseId(),
                request.clientId(),
                returnOrder == null ? null : returnOrder.getClient().getDisplayName(),
                request.driverSnapshot(),
                subjectId,
                correlationId));
    List<LogisticsDocumentLine> lines =
        lineRepository.saveAllAndFlush(returnLines(document, request.lines()));
    OffsetDateTime proofCreatedAt = now();
    for (LogisticsDocumentLine line : lines) {
      attemptWriter.createLineAttempt(
          document,
          line,
          LogisticsTargetService.MEDIA,
          LogisticsDocumentEffectOperations.RETURN_MEDIA_OWNER_PROOF_REGISTER,
          attemptWriter.ownerProofDigest(
              LogisticsDocumentEffectOperations.RETURN_MEDIA_OWNER_PROOF_REGISTER,
              document,
              line,
              document.getWarehouseId(),
              0,
              0,
              true),
          proofCreatedAt);
    }
    eventStore.initialize(document, lines.size(), correlationId, subjectId);
    warehouseAdmission.enqueue(document, document.getWarehouseId(), admission);
    idempotency.remember(subjectId, idempotencyKey, CREATE_RETURN, checksum, document);
    return result(document, false);
  }

  LogisticsDocumentCommandResult registerReturn(
      UUID subjectId,
      UUID idempotencyKey,
      UUID correlationId,
      UUID documentId,
      long expectedDocumentVersion,
      ReturnPickupRequest request) {
    requireReturnCommand(documentId, correlationId, expectedDocumentVersion, request);
    String checksum =
        LogisticsCommandChecksum.sha256(
            REGISTER_RETURN,
            List.of(
                documentId.toString(),
                Long.toString(expectedDocumentVersion),
                request.driverSnapshot().trim(),
                request.scheduledDate().toString()));
    idempotency.acquireLock(subjectId, REGISTER_RETURN, idempotencyKey);
    LogisticsDocument replay = idempotency.replay(subjectId, idempotencyKey, REGISTER_RETURN, checksum);
    if (replay != null) return result(replay, true);

    LogisticsDocument document = readProjection.document(documentId, LogisticsDocumentType.RETURN);
    if (document.getVersion() != expectedDocumentVersion) {
      throw new LogisticsConflictException("Return document version changed concurrently");
    }
    List<LogisticsDocumentLine> lines = readProjection.linesRequired(documentId);

    document.scheduleReturn(request.driverSnapshot(), request.scheduledDate());
    document.beginReturnRegistration();
    documentRepository.saveAndFlush(document);
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    attemptWriter.createDocumentAttempt(
        document,
        LogisticsTargetService.WAREHOUSE,
        LogisticsDocumentEffectOperations.RETURN_WAREHOUSE_IDENTITY,
        LogisticsCommandChecksum.sha256(
            LogisticsDocumentEffectOperations.RETURN_WAREHOUSE_IDENTITY,
            List.of(document.getWarehouseId().toString())),
        now);
    eventStore.append(
        document,
        lines.size(),
        correlationId,
        subjectId,
        LogisticsEventType.RETURN_REGISTRATION_STARTED,
        null);
    idempotency.remember(subjectId, idempotencyKey, REGISTER_RETURN, checksum, document);
    return result(document, false);
  }

  LogisticsDocumentCommandResult acceptUndamagedReturn(
      UUID subjectId,
      UUID idempotencyKey,
      UUID correlationId,
      UUID documentId,
      long expectedDocumentVersion,
      AcceptReturnRequest request) {
    requireReturnCommand(documentId, correlationId, expectedDocumentVersion, request);
    String checksum =
        LogisticsCommandChecksum.sha256(
            ACCEPT_RETURN, acceptanceFingerprintValues(documentId, expectedDocumentVersion, request));
    idempotency.acquireLock(subjectId, ACCEPT_RETURN, idempotencyKey);
    LogisticsDocument replay = idempotency.replay(subjectId, idempotencyKey, ACCEPT_RETURN, checksum);
    if (replay != null) return result(replay, true);

    LogisticsDocument document = readProjection.document(documentId, LogisticsDocumentType.RETURN);
    requireExpectedVersion(
        document, expectedDocumentVersion, "Return document version changed concurrently");
    if (document.getState() != LogisticsDocumentState.INSPECTION_REQUIRED) {
      throw new LogisticsConflictException("Return cannot be accepted in its current lifecycle state");
    }
    List<LogisticsDocumentLine> lines = readProjection.linesRequired(documentId);
    Map<UUID, LogisticsDocumentLine> byId = linesById(lines);
    validateAcceptanceLines(request, byId.keySet());

    OffsetDateTime now = now();
    List<LogisticsMediaReference> references = new ArrayList<>();
    for (var input : request.lines()) {
      LogisticsDocumentLine line = byId.get(input.lineId());
      line.captureReturnAdditionalContents(additionalContentsSnapshot(input));
      for (var reference : input.references()) {
        references.add(
            LogisticsMediaReference.pending(
                document,
                line,
                reference.mediaId(),
                reference.generation(),
                LogisticsMediaPurpose.RETURN_INSPECTION,
                now));
      }
    }
    document.beginReturnAcceptance();
    documentRepository.saveAndFlush(document);
    mediaReferenceRepository.saveAllAndFlush(references);
    for (LogisticsDocumentLine line : lines) {
      List<LogisticsMediaReference> lineReferences =
          references.stream().filter(reference -> line.equals(reference.getLine())).toList();
      attemptWriter.createLineAttempt(
          document,
          line,
          LogisticsTargetService.MEDIA,
          LogisticsDocumentEffectOperations.RETURN_MEDIA_VALIDATE,
          mediaAttemptDigest(document, line, lineReferences),
          now);
    }
    eventStore.append(
        document,
        lines.size(),
        correlationId,
        subjectId,
        LogisticsEventType.RETURN_ACCEPTANCE_STARTED,
        "EQUIPMENT_COMPLETENESS_CONFIRMED");
    idempotency.remember(subjectId, idempotencyKey, ACCEPT_RETURN, checksum, document);
    return result(document, false);
  }

  LogisticsDocumentCommandResult startReturnEstimates(
      UUID subjectId,
      UUID idempotencyKey,
      UUID correlationId,
      UUID documentId,
      long expectedDocumentVersion,
      StartReturnEstimatesRequest request) {
    requireReturnCommand(documentId, correlationId, expectedDocumentVersion, request);
    String checksum =
        LogisticsCommandChecksum.sha256(
            START_RETURN_ESTIMATES, estimateFingerprintValues(documentId, expectedDocumentVersion, request));
    idempotency.acquireLock(subjectId, START_RETURN_ESTIMATES, idempotencyKey);
    LogisticsDocument replay =
        idempotency.replay(subjectId, idempotencyKey, START_RETURN_ESTIMATES, checksum);
    if (replay != null) return result(replay, true);

    LogisticsDocument document = readProjection.document(documentId, LogisticsDocumentType.RETURN);
    requireExpectedVersion(
        document, expectedDocumentVersion, "Return document version changed concurrently");
    if (document.getState() != LogisticsDocumentState.INSPECTION_REQUIRED) {
      throw new LogisticsConflictException(
          "Return estimates cannot be started in its current lifecycle state");
    }
    List<LogisticsDocumentLine> lines = readProjection.linesRequired(documentId);
    Map<UUID, LogisticsDocumentLine> byId = linesById(lines);
    validateReturnEstimateLines(request, byId.keySet());

    OffsetDateTime now = now();
    List<LogisticsReturnShortageSnapshot> snapshots = new ArrayList<>();
    List<LogisticsMediaReference> references = new ArrayList<>();
    for (var input : request.lines()) {
      LogisticsDocumentLine line = byId.get(input.lineId());
      LogisticsGuard guard = activeGuard(line);
      ObjectNode sourceSnapshot = returnEstimateSourceSnapshot();
      snapshots.add(
          LogisticsReturnShortageSnapshot.create(
              document,
              line,
              document.getWarehouseId(),
              line.getAssetId(),
              guard.getObservedAssetVersion(),
              sourceSnapshot,
              returnEstimateSourceSnapshotDigest(document, line, guard, input),
              now));
      for (var reference : input.references()) {
        references.add(
            LogisticsMediaReference.pending(
                document,
                line,
                reference.mediaId(),
                reference.generation(),
                LogisticsMediaPurpose.RETURN_INSPECTION,
                now));
      }
    }
    document.beginReturnEstimate();
    documentRepository.saveAndFlush(document);
    shortageSnapshotRepository.saveAllAndFlush(snapshots);
    mediaReferenceRepository.saveAllAndFlush(references);
    for (LogisticsReturnShortageSnapshot snapshot : snapshots) {
      LogisticsDocumentLine line = snapshot.getLine();
      List<LogisticsMediaReference> lineReferences =
          references.stream().filter(reference -> line.equals(reference.getLine())).toList();
      attemptWriter.createLineAttempt(
          document,
          line,
          LogisticsTargetService.MEDIA,
          LogisticsDocumentEffectOperations.RETURN_MEDIA_VALIDATE,
          mediaAttemptDigest(document, line, lineReferences),
          now);
    }
    eventStore.append(
        document,
        lines.size(),
        correlationId,
        subjectId,
        LogisticsEventType.RETURN_ESTIMATE_STARTED,
        "INSPECTION_MEDIA_SUBMITTED");
    idempotency.remember(subjectId, idempotencyKey, START_RETURN_ESTIMATES, checksum, document);
    return result(document, false);
  }

  private LogisticsDocumentCommandResult result(LogisticsDocument document, boolean replayed) {
    return new LogisticsDocumentCommandResult(readProjection.view(document), replayed);
  }

  private LogisticsGuard activeGuard(LogisticsDocumentLine line) {
    LogisticsGuard guard =
        guardRepository
            .findByLine_Id(line.getId())
            .orElseThrow(() -> new LogisticsConflictException("Return line has no active asset lease"));
    if (guard.getGuardState() != LogisticsGuardState.ACTIVE
        || guard.getLeaseId() == null
        || guard.getLeaseVersion() == null
        || guard.getFenceToken() == null
        || guard.getObservedAssetVersion() == null) {
      throw new LogisticsConflictException("Return line does not have an active asset lease");
    }
    return guard;
  }

  private static void requireReturnCommand(
      UUID documentId, UUID correlationId, long expectedDocumentVersion, Object request) {
    if (documentId == null || correlationId == null || expectedDocumentVersion < 0) {
      throw new IllegalArgumentException("Return command identifiers and version are required");
    }
    requireRequest(request);
  }

  private static void requireExpectedVersion(
      LogisticsDocument document, long expectedVersion, String message) {
    if (document.getVersion() != expectedVersion) {
      throw new LogisticsConflictException(message);
    }
  }

  private static Map<UUID, LogisticsDocumentLine> linesById(List<LogisticsDocumentLine> lines) {
    return lines.stream()
        .collect(
            java.util.stream.Collectors.toUnmodifiableMap(LogisticsDocumentLine::getId, line -> line));
  }

  private static void validateAcceptanceLines(
      AcceptReturnRequest request, Set<UUID> requiredLineIds) {
    if (request.lines() == null || request.lines().size() != requiredLineIds.size()) {
      throw new LogisticsConflictException(
          "Return acceptance must contain media for every return line");
    }
    HashSet<UUID> lineIds = new HashSet<>();
    HashSet<UUID> mediaIds = new HashSet<>();
    for (var input : request.lines()) {
      if (input == null
          || input.lineId() == null
          || input.references() == null
          || !Boolean.TRUE.equals(input.equipmentConfirmed())
          || input.additionalEquipment() == null
          || input.references().isEmpty()
          || input.references().size() > 20
          || !lineIds.add(input.lineId())
          || !requiredLineIds.contains(input.lineId())) {
        throw new LogisticsConflictException("Return acceptance lines are invalid");
      }
      HashSet<UUID> lineMediaIds = new HashSet<>();
      for (var reference : input.references()) {
        if (reference == null
            || reference.mediaId() == null
            || reference.generation() < 1
            || !lineMediaIds.add(reference.mediaId())
            || !mediaIds.add(reference.mediaId())) {
          throw new LogisticsConflictException("Return acceptance media references are invalid");
        }
      }
      if (input.additionalEquipment().size() > 100) {
        throw new LogisticsConflictException("Return additional equipment is invalid");
      }
      HashSet<UUID> additionalEquipmentIds = new HashSet<>();
      for (var additional : input.additionalEquipment()) {
        if (additional == null
            || additional.equipmentId() == null
            || additional.quantity() == null
            || additional.quantity() < 1
            || !additionalEquipmentIds.add(additional.equipmentId())) {
          throw new LogisticsConflictException("Return additional equipment is invalid");
        }
      }
    }
    if (!lineIds.equals(requiredLineIds)) {
      throw new LogisticsConflictException("Return acceptance must contain exactly the return lines");
    }
  }

  private static void validateReturnEstimateLines(
      StartReturnEstimatesRequest request, Set<UUID> requiredLineIds) {
    if (request.lines() == null || request.lines().size() != requiredLineIds.size()) {
      throw new LogisticsConflictException(
          "Return estimates must contain photos for every return line");
    }
    HashSet<UUID> lineIds = new HashSet<>();
    HashSet<UUID> mediaIds = new HashSet<>();
    for (var input : request.lines()) {
      if (input == null
          || input.lineId() == null
          || input.references() == null
          || input.references().isEmpty()
          || input.references().size() > 20
          || !lineIds.add(input.lineId())
          || !requiredLineIds.contains(input.lineId())) {
        throw new LogisticsConflictException("Return estimate lines are invalid");
      }
      HashSet<UUID> lineMediaIds = new HashSet<>();
      for (var reference : input.references()) {
        if (reference == null
            || reference.mediaId() == null
            || reference.generation() < 1
            || !lineMediaIds.add(reference.mediaId())
            || !mediaIds.add(reference.mediaId())) {
          throw new LogisticsConflictException("Return estimate media references are invalid");
        }
      }
    }
    if (!lineIds.equals(requiredLineIds)) {
      throw new LogisticsConflictException("Return estimate must contain exactly the return lines");
    }
  }

  private static String mediaAttemptDigest(
      LogisticsDocument document,
      LogisticsDocumentLine line,
      List<LogisticsMediaReference> references) {
    List<String> values = new ArrayList<>();
    values.add(document.getId().toString());
    values.add(line.getId().toString());
    values.add(document.getWarehouseId().toString());
    references.stream()
        .sorted(Comparator.comparing(LogisticsMediaReference::getMediaId))
        .forEach(
            reference -> {
              values.add(reference.getMediaId().toString());
              values.add(Long.toString(reference.getGeneration()));
            });
    return LogisticsCommandChecksum.sha256(
        LogisticsDocumentEffectOperations.RETURN_MEDIA_VALIDATE, values);
  }

  private static ObjectNode returnEstimateSourceSnapshot() {
    return JsonNodeFactory.instance.objectNode();
  }

  private static ObjectNode additionalContentsSnapshot(
      dev.buhanzaz.rwms.logistics.api.LogisticsApiModels.ReturnMediaLineRequest input) {
    ObjectNode root = JsonNodeFactory.instance.objectNode();
    root.put("equipmentConfirmed", Boolean.TRUE.equals(input.equipmentConfirmed()));
    ArrayNode values = root.putArray("additionalEquipment");
    input.additionalEquipment().stream()
        .sorted(Comparator.comparing(value -> value.equipmentId().toString()))
        .forEach(
            value -> {
              ObjectNode additional = values.addObject();
              additional.put("equipmentId", value.equipmentId().toString());
              additional.put("quantity", value.quantity());
            });
    return root;
  }

  private static String returnEstimateSourceSnapshotDigest(
      LogisticsDocument document,
      LogisticsDocumentLine line,
      LogisticsGuard guard,
      dev.buhanzaz.rwms.logistics.api.LogisticsApiModels.ReturnEstimateLineRequest input) {
    List<String> values = new ArrayList<>();
    values.add(document.getId().toString());
    values.add(line.getId().toString());
    values.add(document.getWarehouseId().toString());
    values.add(line.getAssetId().toString());
    values.add(Long.toString(guard.getObservedAssetVersion()));
    input.references().stream()
        .sorted(Comparator.comparing(value -> value.mediaId().toString()))
        .forEach(
            value -> {
              values.add(value.mediaId().toString());
              values.add(Long.toString(value.generation()));
            });
    return LogisticsCommandChecksum.sha256("RETURN_ESTIMATE_SOURCE_SNAPSHOT", values);
  }

  private static List<String> acceptanceFingerprintValues(
      UUID documentId, long expectedVersion, AcceptReturnRequest request) {
    List<String> values = new ArrayList<>();
    values.add(documentId.toString());
    values.add(Long.toString(expectedVersion));
    request.lines().stream()
        .sorted(Comparator.comparing(value -> value.lineId().toString()))
        .forEach(
            line -> {
              values.add(line.lineId().toString());
              values.add(Boolean.toString(line.equipmentConfirmed()));
              line.references().stream()
                  .sorted(Comparator.comparing(value -> value.mediaId().toString()))
                  .forEach(
                      reference -> {
                        values.add(reference.mediaId().toString());
                        values.add(Long.toString(reference.generation()));
                      });
              line.additionalEquipment().stream()
                  .sorted(Comparator.comparing(value -> value.equipmentId().toString()))
                  .forEach(
                      additional -> {
                        values.add(additional.equipmentId().toString());
                        values.add(Long.toString(additional.quantity()));
                      });
            });
    return values;
  }

  private static List<String> estimateFingerprintValues(
      UUID documentId, long expectedVersion, StartReturnEstimatesRequest request) {
    List<String> values = new ArrayList<>();
    values.add(documentId.toString());
    values.add(Long.toString(expectedVersion));
    request.lines().stream()
        .sorted(Comparator.comparing(value -> value.lineId().toString()))
        .forEach(
            line -> {
              values.add(line.lineId().toString());
              line.references().stream()
                  .sorted(Comparator.comparing(value -> value.mediaId().toString()))
                  .forEach(
                      reference -> {
                        values.add(reference.mediaId().toString());
                        values.add(Long.toString(reference.generation()));
                      });
            });
    return values;
  }

  private static List<LogisticsDocumentLine> returnLines(
      LogisticsDocument document,
      List<dev.buhanzaz.rwms.logistics.api.LogisticsApiModels.ReturnLineRequest> inputs) {
    validateReturnLineInputs(inputs);
    List<LogisticsDocumentLine> lines = new ArrayList<>(inputs.size());
    for (int index = 0; index < inputs.size(); index++) {
      var input = inputs.get(index);
      if (input.tenantSnapshot() == null || input.tenantSnapshot().isBlank()) {
        throw new IllegalArgumentException("tenantSnapshot is required for a return line");
      }
      lines.add(
          LogisticsDocumentLine.create(
              document,
              index + 1,
              input.assetId(),
              input.assetVersion(),
              input.tenantSnapshot(),
              input.rentalOrderId()));
    }
    return lines;
  }

  private static void validateReturnLineInputs(
      List<dev.buhanzaz.rwms.logistics.api.LogisticsApiModels.ReturnLineRequest> inputs) {
    if (inputs == null || inputs.isEmpty() || inputs.size() > 100) {
      throw new IllegalArgumentException("Document must contain 1 to 100 lines");
    }
    HashSet<UUID> assetIds = new HashSet<>();
    for (var input : inputs) {
      UUID assetId = input.assetId();
      if (assetId == null || !assetIds.add(assetId)) {
        throw new IllegalArgumentException("Document line asset IDs must be distinct");
      }
    }
  }

  private static List<String> returnFingerprintValues(CreateReturnRequest request) {
    List<String> values = new ArrayList<>();
    values.add(request.warehouseId().toString());
    values.add(request.clientId() == null ? null : request.clientId().toString());
    values.add(optionalTrimSnapshot(request.driverSnapshot()));
    for (var line : request.lines()) {
      values.add(line.assetId().toString());
      values.add(Long.toString(line.assetVersion()));
      values.add(line.tenantSnapshot());
      values.add(line.rentalOrderId() == null ? null : line.rentalOrderId().toString());
    }
    return values;
  }

  private static String optionalTrimSnapshot(String value) {
    if (value == null) return null;
    String normalized = value.trim();
    return normalized.isEmpty() ? null : normalized;
  }

  private static OffsetDateTime now() {
    return OffsetDateTime.now(ZoneOffset.UTC);
  }

  private static void requireRequest(Object request) {
    if (request == null) throw new IllegalArgumentException("Request is required");
  }
}
