package dev.buhanzaz.rwms.logistics.contractor.share;

import static dev.buhanzaz.rwms.logistics.contractor.share.ContractorRouteShareApiModels.*;

import dev.buhanzaz.rwms.logistics.contractor.share.domain.ContractorRouteShare;
import dev.buhanzaz.rwms.logistics.contractor.share.domain.ContractorRouteShare.TaskBinding;
import dev.buhanzaz.rwms.logistics.contractor.share.domain.ContractorRouteShareTask;
import dev.buhanzaz.rwms.logistics.domain.LogisticsDocument;
import dev.buhanzaz.rwms.logistics.driver.domain.DriverLogisticsTask;
import dev.buhanzaz.rwms.logistics.driver.domain.DriverTaskAudienceMode;
import dev.buhanzaz.rwms.logistics.driver.domain.DriverTaskSourceType;
import dev.buhanzaz.rwms.logistics.driver.domain.DriverTaskWorkerContent;
import dev.buhanzaz.rwms.logistics.driver.repository.DriverLogisticsTaskRepository;
import dev.buhanzaz.rwms.logistics.driver.service.DriverTaskWorkerContentCodec;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyException;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway;
import dev.buhanzaz.rwms.logistics.order.domain.RentalOrder;
import dev.buhanzaz.rwms.logistics.order.repository.RentalOrderRepository;
import dev.buhanzaz.rwms.logistics.repository.LogisticsDocumentRepository;
import dev.buhanzaz.rwms.logistics.security.LogisticsAuthorizer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;

/**
 * Creates and resolves revocable contractor capabilities while keeping execution truth live in
 * task-board. Remote calls are outside local transactions; only immutable identities and link
 * lifecycle are persisted by logistics-service.
 */
@Service
@RequiredArgsConstructor
public class ContractorRouteShareService {
  private static final Duration MINIMUM_LIFETIME = Duration.ofMinutes(1);
  private static final Duration MAXIMUM_LIFETIME = Duration.ofDays(30);
  private static final int MAXIMUM_JPEG_BYTES = 15_728_640;
  private static final int MAXIMUM_WEBP_BYTES = 1_048_576;
  private static final Set<String> MEDIA_VARIANTS = Set.of("SMALL", "MEDIUM", "LARGE");
  private static final Set<String> TERMINAL_ROUTE_ENTRY_STATUSES = Set.of("DONE", "CANCELLED");
  private static final Pattern SHA256 = Pattern.compile("^[0-9a-f]{64}$");
  private static final String PUBLIC_PREFIX = "/contractor-routes/";
  private static final String PUBLIC_API_PREFIX =
      "/api/logistics/public/v1/contractor-route-shares/";

  private final ContractorRouteShareStore store;
  private final ContractorRouteShareTokenService tokens;
  private final LogisticsDependencyGateway dependencies;
  private final LogisticsAuthorizer access;
  private final DriverLogisticsTaskRepository driverTasks;
  private final LogisticsDocumentRepository documents;
  private final RentalOrderRepository orders;
  private final DriverTaskWorkerContentCodec workerContent;

  /** Creates one explicit route share or returns the exact creator-scoped idempotent replay. */
  public CreationResult create(
      Jwt jwt, UUID warehouseId, UUID idempotencyKey, CreateContractorRouteShareRequest request) {
    if (warehouseId == null || idempotencyKey == null || request == null) {
      throw new IllegalArgumentException("Contractor route share request is invalid");
    }
    access.requireEdit(jwt, warehouseId);
    UUID subjectId = access.subjectId(jwt);
    OffsetDateTime timestamp = now();
    OffsetDateTime expiresAt = canonicalExpiry(request.expiresAt());
    if (request.contractorWorkerId() == null) {
      throw new IllegalArgumentException("contractorWorkerId is required");
    }
    requireUniqueTasks(request.externalTaskIds());
    String requestSha256 = requestHash(warehouseId, request, expiresAt);

    ContractorRouteShare replay = store.findReplay(subjectId, idempotencyKey).orElse(null);
    if (replay != null) return replay(replay, requestSha256);
    validateExpiry(expiresAt, timestamp);

    List<TaskBinding> bindings =
        request.externalTaskIds().stream()
            .map(
                externalTaskId ->
                    validateBinding(
                        warehouseId, request.contractorWorkerId(), externalTaskId, null))
            .map(ValidatedTask::binding)
            .toList();
    ContractorRouteShare candidate =
        ContractorRouteShare.create(
            warehouseId,
            request.contractorWorkerId(),
            subjectId,
            idempotencyKey,
            requestSha256,
            expiresAt,
            bindings,
            timestamp);
    try {
      return new CreationResult(response(store.insert(candidate)), false);
    } catch (DataIntegrityViolationException exception) {
      ContractorRouteShare winner = store.findReplay(subjectId, idempotencyKey).orElse(null);
      if (winner == null) throw exception;
      return replay(winner, requestSha256);
    }
  }

  /** Revokes one share after warehouse authorization and an optimistic aggregate fence. */
  public ContractorRouteShareResponse revoke(
      Jwt jwt, UUID warehouseId, UUID shareId, RevokeContractorRouteShareRequest request) {
    if (warehouseId == null || shareId == null || request == null) {
      throw new IllegalArgumentException("Contractor route share revocation is invalid");
    }
    access.requireEdit(jwt, warehouseId);
    access.subjectId(jwt);
    return response(store.revoke(shareId, warehouseId, request.expectedVersion(), now()));
  }

  /** Resolves a valid public capability and re-proves every exact worker/task binding live. */
  public PublicContractorRouteShareResponse publicRoute(String token) {
    ResolvedShare resolved = resolve(token);
    List<PublicContractorRouteTask> tasks = new ArrayList<>(resolved.share().getTasks().size());
    for (ContractorRouteShareTask membership : resolved.share().getTasks()) {
      tasks.add(
          publicTask(
              validateBinding(
                  resolved.share().getWarehouseId(),
                  resolved.share().getContractorWorkerId(),
                  membership.getExternalTaskId(),
                  membership),
              token.trim()));
    }
    return new PublicContractorRouteShareResponse(
        resolved.share().getId(), resolved.share().getExpiresAt(), List.copyOf(tasks));
  }

  /** Applies one exact public START/COMPLETE and returns the refreshed no-store task projection. */
  public PublicContractorRouteTaskActionResponse applyAction(
      String token,
      UUID externalTaskId,
      UUID entryId,
      UUID idempotencyKey,
      ApplyContractorRouteTaskActionRequest request) {
    if (externalTaskId == null || entryId == null || idempotencyKey == null || request == null) {
      throw new IllegalArgumentException("Contractor route task action is invalid");
    }
    String action = request.action().trim().toUpperCase(java.util.Locale.ROOT);
    if (!("START".equals(action) || "COMPLETE".equals(action))
        || ("START".equals(action) && request.evidenceId() != null)
        || ("COMPLETE".equals(action) && request.evidenceId() == null)) {
      throw new IllegalArgumentException("Contractor route task action is invalid");
    }
    ResolvedShare resolved = resolve(token);
    ContractorRouteShareTask membership =
        resolved.share().getTasks().stream()
            .filter(item -> externalTaskId.equals(item.getExternalTaskId()))
            .findFirst()
            .orElseThrow(ContractorRouteShareProblem::notFound);
    ValidatedTask current =
        "START".equals(action)
            ? validateOrderedStart(resolved.share(), externalTaskId, entryId)
            : validateBinding(
                resolved.share().getWarehouseId(),
                resolved.share().getContractorWorkerId(),
                externalTaskId,
                membership);
    LogisticsDependencyGateway.ContractorTaskRouteEntry currentEntry =
        current.execution().route().stream()
            .filter(route -> entryId.equals(route.entryId()))
            .findFirst()
            .orElseThrow(ContractorRouteShareProblem::notFound);
    if ("COMPLETE".equals(action)
        && currentEntry.evidence().stream()
            .noneMatch(
                evidence ->
                    request.evidenceId().equals(evidence.evidenceId())
                        && "READY".equals(evidence.state())
                        && evidence.mediaId() != null
                        && evidence.mediaGeneration() != null)) {
      throw ContractorRouteShareProblem.conflict(
          "CONTRACTOR_ROUTE_EVIDENCE_NOT_READY",
          "Нельзя завершить этап: выберите готовую фотографию результата");
    }
    try {
      LogisticsDependencyGateway.ContractorTaskActionResult result =
          dependencies.applyContractorTaskAction(
              resolved.share().getContractorWorkerId(),
              externalTaskId,
              entryId,
              idempotencyKey,
              action,
              request.expectedVersion(),
              request.evidenceId());
      ValidatedTask refreshed = current.withExecution(result.task());
      validateRemoteIdentity(refreshed, result.task());
      return new PublicContractorRouteTaskActionResponse(
          result.currentVersion(), publicTask(refreshed, token.trim()));
    } catch (LogisticsDependencyException exception) {
      if (exception.kind() != LogisticsDependencyException.FailureKind.PERMANENT_REJECTION) {
        throw ContractorRouteShareProblem.unavailable();
      }
      if (!remoteAssignmentStillExists(resolved.share().getContractorWorkerId(), externalTaskId)) {
        throw ContractorRouteShareProblem.notFound();
      }
      String message =
          "COMPLETE".equals(action)
              ? "Нельзя завершить этап: обновите маршрут и проверьте обязательные фотографии"
              : "Нельзя начать этап: маршрут изменился или этап уже был обработан";
      throw ContractorRouteShareProblem.conflict("CONTRACTOR_ROUTE_ACTION_CONFLICT", message);
    }
  }

  /**
   * Reserves one exact result-evidence identity and then forwards its already-validated bytes to
   * media-service. A failed media call leaves the task-board reservation retryable under the same
   * evidence ID; this method opens no local database transaction around either remote effect.
   */
  public PublicContractorEvidenceUploadResponse uploadEvidence(
      String token,
      UUID externalTaskId,
      UUID entryId,
      UUID evidenceId,
      UUID idempotencyKey,
      OffsetDateTime capturedAt,
      String contentType,
      String contentSha256,
      byte[] bytes) {
    ValidatedTask current = resolveTask(token, externalTaskId);
    LogisticsDependencyGateway.ContractorTaskRouteEntry entry = requiredEntry(current, entryId);
    validateEvidenceBytes(
        evidenceId, idempotencyKey, capturedAt, contentType, contentSha256, bytes);

    LogisticsDependencyGateway.ContractorEvidenceReservation reservation;
    try {
      reservation =
          dependencies.reserveContractorTaskEvidence(
              current.execution().workerId(),
              externalTaskId,
              entry.entryId(),
              evidenceId,
              capturedAt,
              contentType,
              bytes.length,
              contentSha256);
    } catch (LogisticsDependencyException exception) {
      throw contractorEvidenceEffectProblem(
          current.execution().workerId(), externalTaskId, exception);
    }
    if (!current.execution().warehouseId().equals(reservation.warehouseId())
        || !Set.of("RESERVED", "UPLOADING", "READY").contains(reservation.state())) {
      throw ContractorRouteShareProblem.conflict(
          "CONTRACTOR_ROUTE_EVIDENCE_STATE_CONFLICT",
          "Фотография уже отклонена или больше не может быть загружена");
    }

    LogisticsDependencyGateway.ContractorEvidenceMediaReceipt receipt;
    try {
      receipt =
          dependencies.uploadContractorTaskEvidence(
              current.execution().warehouseId(),
              current.execution().workerId(),
              entry.entryId(),
              evidenceId,
              contentType,
              contentSha256,
              bytes);
    } catch (LogisticsDependencyException exception) {
      throw contractorEvidenceEffectProblem(
          current.execution().workerId(), externalTaskId, exception);
    }
    if ("FAILED".equals(receipt.status())) {
      throw ContractorRouteShareProblem.conflict(
          "CONTRACTOR_ROUTE_EVIDENCE_PROCESSING_FAILED",
          "Фотографию не удалось обработать; сделайте новый снимок");
    }
    String state = "READY".equals(receipt.status()) ? "READY" : "UPLOADING";
    Long generation = receipt.generation() > 0 ? receipt.generation() : null;
    String canonicalToken = token.trim();
    return new PublicContractorEvidenceUploadResponse(
        evidenceId,
        reservation.version(),
        state,
        receipt.mediaId(),
        generation,
        contentType,
        generation == null
            ? null
            : mediaPath(
                canonicalToken,
                externalTaskId,
                entry.entryId(),
                receipt.mediaId(),
                generation,
                "LARGE"),
        generation == null
            ? null
            : mediaPath(
                canonicalToken,
                externalTaskId,
                entry.entryId(),
                receipt.mediaId(),
                generation,
                "SMALL"));
  }

  /** Reads one exact proven source or READY evidence image without exposing its private locator. */
  public LogisticsDependencyGateway.MediaContent media(
      String token,
      UUID externalTaskId,
      UUID entryId,
      UUID mediaId,
      long generation,
      String variant) {
    ValidatedTask current = resolveTask(token, externalTaskId);
    LogisticsDependencyGateway.ContractorTaskRouteEntry entry = requiredEntry(current, entryId);
    if (mediaId == null
        || generation < 1
        || variant == null
        || !MEDIA_VARIANTS.contains(variant)
        || !isProvenMedia(current, entry, mediaId, generation)) {
      throw ContractorRouteShareProblem.notFound();
    }
    try {
      return dependencies.readContractorTaskMedia(
          current.execution().warehouseId(),
          current.execution().workerId(),
          entry.entryId(),
          mediaId,
          generation,
          variant);
    } catch (LogisticsDependencyException exception) {
      if (exception.kind() == LogisticsDependencyException.FailureKind.PERMANENT_REJECTION) {
        throw ContractorRouteShareProblem.notFound();
      }
      throw ContractorRouteShareProblem.unavailable();
    }
  }

  private ValidatedTask resolveTask(String token, UUID externalTaskId) {
    if (externalTaskId == null) throw ContractorRouteShareProblem.notFound();
    ResolvedShare resolved = resolve(token);
    ContractorRouteShareTask membership =
        resolved.share().getTasks().stream()
            .filter(item -> externalTaskId.equals(item.getExternalTaskId()))
            .findFirst()
            .orElseThrow(ContractorRouteShareProblem::notFound);
    return validateBinding(
        resolved.share().getWarehouseId(),
        resolved.share().getContractorWorkerId(),
        externalTaskId,
        membership);
  }

  /**
   * Re-proves every persisted task binding and permits START only for the first unfinished route
   * entry across the complete shared route. Task-board still owns and fences the final transition.
   */
  private ValidatedTask validateOrderedStart(
      ContractorRouteShare share, UUID externalTaskId, UUID entryId) {
    List<ValidatedTask> currentTasks = new ArrayList<>(share.getTasks().size());
    for (ContractorRouteShareTask membership : share.getTasks()) {
      currentTasks.add(
          validateBinding(
              share.getWarehouseId(),
              share.getContractorWorkerId(),
              membership.getExternalTaskId(),
              membership));
    }
    ValidatedTask target =
        currentTasks.stream()
            .filter(candidate -> externalTaskId.equals(candidate.execution().externalTaskId()))
            .findFirst()
            .orElseThrow(ContractorRouteShareProblem::notFound);
    for (ValidatedTask candidate : currentTasks) {
      LogisticsDependencyGateway.ContractorTaskRouteEntry firstUnfinished =
          candidate.execution().route().stream()
              .filter(entry -> !TERMINAL_ROUTE_ENTRY_STATUSES.contains(entry.status()))
              .findFirst()
              .orElse(null);
      if (firstUnfinished == null) continue;
      if (externalTaskId.equals(candidate.execution().externalTaskId())
          && entryId.equals(firstUnfinished.entryId())) {
        return target;
      }
      throw ContractorRouteShareProblem.conflict(
          "CONTRACTOR_ROUTE_ACTION_ORDER_CONFLICT",
          "Нельзя начать этап: сначала завершите предыдущий этап маршрута");
    }
    throw ContractorRouteShareProblem.conflict(
        "CONTRACTOR_ROUTE_ACTION_ORDER_CONFLICT", "Нельзя начать этап: маршрут уже завершён");
  }

  private static LogisticsDependencyGateway.ContractorTaskRouteEntry requiredEntry(
      ValidatedTask current, UUID entryId) {
    if (entryId == null) throw ContractorRouteShareProblem.notFound();
    return current.execution().route().stream()
        .filter(candidate -> entryId.equals(candidate.entryId()))
        .findFirst()
        .orElseThrow(ContractorRouteShareProblem::notFound);
  }

  private ResolvedShare resolve(String token) {
    ContractorRouteShareTokenService.TokenIdentity identity;
    try {
      identity = tokens.verify(token);
    } catch (ContractorRouteShareTokenService.InvalidContractorRouteShareTokenException exception) {
      throw ContractorRouteShareProblem.notFound();
    }
    ContractorRouteShare share =
        store.findById(identity.shareId()).orElseThrow(ContractorRouteShareProblem::notFound);
    if (!share.isAvailable(identity.tokenRevision(), now())) {
      throw ContractorRouteShareProblem.notFound();
    }
    return new ResolvedShare(share);
  }

  private ValidatedTask validateBinding(
      UUID warehouseId,
      UUID contractorWorkerId,
      UUID externalTaskId,
      ContractorRouteShareTask expectedMembership) {
    DriverLogisticsTask task =
        driverTasks
            .findByExternalTaskId(externalTaskId)
            .orElseThrow(ContractorRouteShareProblem::notFound);
    if (task.getId() == null
        || task.getSourceType() != DriverTaskSourceType.LOGISTICS_DOCUMENT
        || task.getSourceId() == null
        || !warehouseId.equals(task.getWarehouseId())
        || task.getDriverAudienceMode() != DriverTaskAudienceMode.ASSIGNED_DRIVER
        || !contractorWorkerId.equals(task.getPlannedDriverWorkerId())
        || expectedMembership != null
            && (!task.getId().equals(expectedMembership.getDriverTaskId())
                || !task.getSourceId().equals(expectedMembership.getDocumentId()))) {
      throw ContractorRouteShareProblem.notFound();
    }
    LogisticsDocument document =
        documents.findById(task.getSourceId()).orElseThrow(ContractorRouteShareProblem::notFound);
    if (!warehouseId.equals(document.getWarehouseId())
        || !contractorWorkerId.equals(document.getDriverWorkerId())
        || document.getRentalOrderId() == null
        || expectedMembership != null
            && (!document.getId().equals(expectedMembership.getDocumentId())
                || !Objects.equals(
                    document.getRentalOrderId(), expectedMembership.getRentalOrderId()))) {
      throw ContractorRouteShareProblem.notFound();
    }
    RentalOrder order =
        orders
            .findPlanningCandidateById(document.getRentalOrderId())
            .orElseThrow(ContractorRouteShareProblem::notFound);
    if (!warehouseId.equals(order.getWarehouseId())) {
      throw ContractorRouteShareProblem.notFound();
    }
    LogisticsDependencyGateway.ContractorTaskExecution execution =
        readExecution(contractorWorkerId, externalTaskId);
    ValidatedTask validated = new ValidatedTask(task, document, order, execution);
    validateRemoteIdentity(validated, execution);
    return validated;
  }

  private LogisticsDependencyGateway.ContractorTaskExecution readExecution(
      UUID contractorWorkerId, UUID externalTaskId) {
    try {
      return dependencies.readContractorTaskExecution(contractorWorkerId, externalTaskId);
    } catch (LogisticsDependencyException exception) {
      if (exception.kind() == LogisticsDependencyException.FailureKind.PERMANENT_REJECTION) {
        throw ContractorRouteShareProblem.notFound();
      }
      throw ContractorRouteShareProblem.unavailable();
    }
  }

  private static void validateRemoteIdentity(
      ValidatedTask local, LogisticsDependencyGateway.ContractorTaskExecution execution) {
    if (execution == null
        || !local.task().getPlannedDriverWorkerId().equals(execution.workerId())
        || !local.task().getExternalTaskId().equals(execution.externalTaskId())
        || !local.task().getWarehouseId().equals(execution.warehouseId())
        || execution.source() == null
        || !"LOGISTICS_DRIVER_TASK".equals(execution.source().type())
        || !local.task().getId().equals(execution.source().sourceId())) {
      throw ContractorRouteShareProblem.notFound();
    }
  }

  private boolean remoteAssignmentStillExists(UUID contractorWorkerId, UUID externalTaskId) {
    try {
      dependencies.readContractorTaskExecution(contractorWorkerId, externalTaskId);
      return true;
    } catch (LogisticsDependencyException exception) {
      if (exception.kind() == LogisticsDependencyException.FailureKind.PERMANENT_REJECTION) {
        return false;
      }
      throw ContractorRouteShareProblem.unavailable();
    }
  }

  private PublicContractorRouteTask publicTask(ValidatedTask validated, String token) {
    DriverTaskWorkerContent content;
    try {
      content = workerContent.decode(validated.task().getWorkerContentJson());
    } catch (RuntimeException exception) {
      throw ContractorRouteShareProblem.unavailable();
    }
    List<PublicContractorCargoItem> cargo = new ArrayList<>();
    content
        .works()
        .forEach(
            work ->
                cargo.add(
                    new PublicContractorCargoItem(
                        "WORK", work.name(), work.quantity(), work.unit(), work.comment())));
    content
        .materials()
        .forEach(
            material ->
                cargo.add(
                    new PublicContractorCargoItem(
                        "MATERIAL", material.name(), material.quantity(), material.unit(), null)));
    LogisticsDependencyGateway.ContractorTaskExecution execution = validated.execution();
    List<PublicContractorSourceMedia> sourceMedia =
        content.sourceMedia().stream()
            .map(
                media -> {
                  LogisticsDependencyGateway.ContractorTaskRouteEntry ownerEntry =
                      mediaEntry(execution, media.mediaId(), media.generation());
                  return publicSourceMedia(
                      token,
                      execution.externalTaskId(),
                      ownerEntry.entryId(),
                      media.mediaId(),
                      media.generation(),
                      media.contentType(),
                      media.capturedAt(),
                      media.recordedAt());
                })
            .toList();
    List<PublicContractorRouteEntry> route =
        execution.route().stream()
            .map(
                entry ->
                    new PublicContractorRouteEntry(
                        entry.entryId(),
                        entry.version(),
                        entry.routeIndex(),
                        entry.routeStepIndex(),
                        entry.routeStepCount(),
                        entry.queueName(),
                        entry.taskText(),
                        entry.status(),
                        entry.plannedDurationMinutes(),
                        entry.works().stream()
                            .map(
                                work ->
                                    new PublicContractorWork(
                                        work.id(),
                                        work.name(),
                                        work.quantity(),
                                        work.unit(),
                                        work.durationMinutes(),
                                        work.comment(),
                                        work.sourceMediaIds()))
                            .toList(),
                        entry.materials().stream()
                            .map(
                                material ->
                                    new PublicContractorMaterial(
                                        material.id(),
                                        material.name(),
                                        material.quantity(),
                                        material.unit()))
                            .toList(),
                        entry.comments().stream()
                            .map(
                                comment ->
                                    new PublicContractorComment(
                                        comment.id(),
                                        comment.text(),
                                        comment.authorDisplayName(),
                                        comment.createdAt()))
                            .toList(),
                        entry.sourceMedia().stream()
                            .map(
                                media ->
                                    publicSourceMedia(
                                        token,
                                        execution.externalTaskId(),
                                        entry.entryId(),
                                        media.mediaId(),
                                        media.generation(),
                                        media.contentType(),
                                        media.capturedAt(),
                                        media.recordedAt()))
                            .toList(),
                        entry.resultPhotoMinCount(),
                        entry.evidence().stream()
                            .map(
                                evidence ->
                                    new PublicContractorEvidence(
                                        evidence.evidenceId(),
                                        evidence.version(),
                                        evidence.capturedAt(),
                                        evidence.recordedAt(),
                                        evidence.state(),
                                        evidence.mediaId(),
                                        evidence.mediaGeneration(),
                                        evidence.contentType(),
                                        readyMediaPath(
                                            token,
                                            execution.externalTaskId(),
                                            entry.entryId(),
                                            evidence,
                                            "LARGE"),
                                        readyMediaPath(
                                            token,
                                            execution.externalTaskId(),
                                            entry.entryId(),
                                            evidence,
                                            "SMALL")))
                            .toList(),
                        entry.completionAllowed()))
            .toList();
    RentalOrder order = validated.order();
    return new PublicContractorRouteTask(
        execution.externalTaskId(),
        execution.taskId(),
        execution.taskVersion(),
        execution.title(),
        execution.description(),
        execution.unitNumber(),
        execution.scheduledDate(),
        execution.deadlineAt(),
        execution.priority(),
        execution.status(),
        order.getDeliveryAddress(),
        order.getLatitude(),
        order.getLongitude(),
        order.getContactPhone(),
        order.getComment(),
        List.copyOf(cargo),
        sourceMedia,
        route);
  }

  private ContractorRouteShareProblem contractorEvidenceEffectProblem(
      UUID workerId, UUID externalTaskId, LogisticsDependencyException exception) {
    if (exception.kind() != LogisticsDependencyException.FailureKind.PERMANENT_REJECTION) {
      return ContractorRouteShareProblem.unavailable();
    }
    if (!remoteAssignmentStillExists(workerId, externalTaskId)) {
      return ContractorRouteShareProblem.notFound();
    }
    return ContractorRouteShareProblem.conflict(
        "CONTRACTOR_ROUTE_EVIDENCE_CONFLICT",
        "Не удалось принять фотографию: обновите маршрут и повторите попытку");
  }

  private static void validateEvidenceBytes(
      UUID evidenceId,
      UUID idempotencyKey,
      OffsetDateTime capturedAt,
      String contentType,
      String contentSha256,
      byte[] bytes) {
    if (evidenceId == null || idempotencyKey == null || !evidenceId.equals(idempotencyKey)) {
      throw ContractorRouteShareProblem.badRequest(
          "CONTRACTOR_ROUTE_EVIDENCE_IDEMPOTENCY_MISMATCH",
          "Ключ повторной отправки должен совпадать с идентификатором фотографии");
    }
    if (capturedAt == null) {
      throw ContractorRouteShareProblem.badRequest(
          "CONTRACTOR_ROUTE_EVIDENCE_CAPTURED_AT_REQUIRED", "Не указано время создания фотографии");
    }
    if (!("image/jpeg".equals(contentType) || "image/webp".equals(contentType))) {
      throw ContractorRouteShareProblem.unsupportedMedia();
    }
    int maximum = "image/jpeg".equals(contentType) ? MAXIMUM_JPEG_BYTES : MAXIMUM_WEBP_BYTES;
    if (bytes == null || bytes.length == 0 || bytes.length > maximum) {
      throw ContractorRouteShareProblem.badRequest(
          "CONTRACTOR_ROUTE_EVIDENCE_SIZE_INVALID",
          "Размер фотографии недопустим для выбранного формата");
    }
    if (contentSha256 == null || !SHA256.matcher(contentSha256).matches()) {
      throw ContractorRouteShareProblem.badRequest(
          "CONTRACTOR_ROUTE_EVIDENCE_SHA256_INVALID",
          "Контрольная сумма фотографии имеет неверный формат");
    }
    byte[] declared = HexFormat.of().parseHex(contentSha256);
    byte[] actual;
    try {
      actual = MessageDigest.getInstance("SHA-256").digest(bytes);
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is required", exception);
    }
    if (!MessageDigest.isEqual(declared, actual)) {
      throw ContractorRouteShareProblem.badRequest(
          "CONTRACTOR_ROUTE_EVIDENCE_SHA256_MISMATCH",
          "Контрольная сумма не соответствует фотографии");
    }
  }

  private boolean isProvenMedia(
      ValidatedTask validated,
      LogisticsDependencyGateway.ContractorTaskRouteEntry entry,
      UUID mediaId,
      long generation) {
    DriverTaskWorkerContent content;
    try {
      content = workerContent.decode(validated.task().getWorkerContentJson());
    } catch (RuntimeException exception) {
      throw ContractorRouteShareProblem.unavailable();
    }
    boolean logisticsSource =
        content.sourceMedia().stream()
            .anyMatch(media -> mediaId.equals(media.mediaId()) && generation == media.generation());
    boolean entrySource =
        entry.sourceMedia().stream()
            .anyMatch(media -> mediaId.equals(media.mediaId()) && generation == media.generation());
    boolean readyEvidence =
        entry.evidence().stream()
            .anyMatch(
                evidence ->
                    "READY".equals(evidence.state())
                        && mediaId.equals(evidence.mediaId())
                        && Objects.equals(generation, evidence.mediaGeneration()));
    return logisticsSource || entrySource || readyEvidence;
  }

  private static LogisticsDependencyGateway.ContractorTaskRouteEntry mediaEntry(
      LogisticsDependencyGateway.ContractorTaskExecution execution, UUID mediaId, long generation) {
    return execution.route().stream()
        .filter(
            entry ->
                entry.sourceMedia().stream()
                    .anyMatch(
                        media ->
                            mediaId.equals(media.mediaId()) && generation == media.generation()))
        .findFirst()
        .orElseGet(execution.route()::getFirst);
  }

  private static PublicContractorSourceMedia publicSourceMedia(
      String token,
      UUID externalTaskId,
      UUID entryId,
      UUID mediaId,
      long generation,
      String contentType,
      OffsetDateTime capturedAt,
      OffsetDateTime recordedAt) {
    return new PublicContractorSourceMedia(
        mediaId,
        generation,
        contentType,
        capturedAt,
        recordedAt,
        mediaPath(token, externalTaskId, entryId, mediaId, generation, "LARGE"),
        mediaPath(token, externalTaskId, entryId, mediaId, generation, "SMALL"));
  }

  private static String readyMediaPath(
      String token,
      UUID externalTaskId,
      UUID entryId,
      LogisticsDependencyGateway.ContractorTaskEvidence evidence,
      String variant) {
    return "READY".equals(evidence.state())
            && evidence.mediaId() != null
            && evidence.mediaGeneration() != null
            && evidence.mediaGeneration() > 0
        ? mediaPath(
            token, externalTaskId, entryId, evidence.mediaId(), evidence.mediaGeneration(), variant)
        : null;
  }

  private static String mediaPath(
      String token,
      UUID externalTaskId,
      UUID entryId,
      UUID mediaId,
      long generation,
      String variant) {
    return PUBLIC_API_PREFIX
        + token
        + "/tasks/"
        + externalTaskId
        + "/entries/"
        + entryId
        + "/media/"
        + mediaId
        + "/generations/"
        + generation
        + "/variants/"
        + variant
        + "/content";
  }

  private CreationResult replay(ContractorRouteShare share, String requestSha256) {
    if (!share.matchesRequest(requestSha256)) {
      throw ContractorRouteShareProblem.conflict(
          "CONTRACTOR_ROUTE_SHARE_IDEMPOTENCY_REUSED",
          "Idempotency-Key уже использован для другой ссылки на маршрут");
    }
    return new CreationResult(response(share), true);
  }

  private ContractorRouteShareResponse response(ContractorRouteShare share) {
    String publicPath =
        share.getRevokedAt() == null
            ? PUBLIC_PREFIX + tokens.issue(share.getId(), share.getTokenRevision())
            : null;
    return new ContractorRouteShareResponse(
        share.getId(),
        share.getVersion(),
        share.getWarehouseId(),
        share.getContractorWorkerId(),
        share.getExpiresAt(),
        share.getRevokedAt(),
        share.getCreatedAt(),
        publicPath,
        share.getTasks().stream().map(ContractorRouteShareTask::getExternalTaskId).toList());
  }

  private static void validateExpiry(OffsetDateTime expiresAt, OffsetDateTime timestamp) {
    if (expiresAt == null) throw new IllegalArgumentException("expiresAt is required");
    Duration lifetime = Duration.between(timestamp.toInstant(), expiresAt.toInstant());
    if (lifetime.compareTo(MINIMUM_LIFETIME) < 0 || lifetime.compareTo(MAXIMUM_LIFETIME) > 0) {
      throw new IllegalArgumentException(
          "Contractor route share lifetime must be between one minute and thirty days");
    }
  }

  private static void requireUniqueTasks(List<UUID> externalTaskIds) {
    if (externalTaskIds == null
        || externalTaskIds.isEmpty()
        || externalTaskIds.size() > ContractorRouteShare.MAXIMUM_TASKS
        || externalTaskIds.stream().anyMatch(Objects::isNull)
        || Set.copyOf(externalTaskIds).size() != externalTaskIds.size()) {
      throw new IllegalArgumentException("Contractor route share tasks are invalid");
    }
  }

  private static String requestHash(
      UUID warehouseId,
      CreateContractorRouteShareRequest request,
      OffsetDateTime canonicalExpiresAt) {
    StringBuilder canonical =
        new StringBuilder("CONTRACTOR_ROUTE_SHARE\n")
            .append(warehouseId)
            .append('\n')
            .append(request.contractorWorkerId())
            .append('\n')
            .append(canonicalExpiresAt.toInstant());
    request.externalTaskIds().forEach(taskId -> canonical.append('\n').append(taskId));
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256")
                  .digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is required", exception);
    }
  }

  private static OffsetDateTime now() {
    return OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MILLIS);
  }

  /** Uses database-safe precision and offset so first response and a post-restart replay match. */
  private static OffsetDateTime canonicalExpiry(OffsetDateTime expiresAt) {
    if (expiresAt == null) throw new IllegalArgumentException("expiresAt is required");
    return expiresAt.toInstant().truncatedTo(ChronoUnit.MILLIS).atOffset(ZoneOffset.UTC);
  }

  /**
   * Creation outcome used by the HTTP boundary to expose replay semantics without response drift.
   */
  public record CreationResult(ContractorRouteShareResponse response, boolean replayed) {}

  /** Local and live remote facts proven to describe one exact contractor assignment. */
  private record ValidatedTask(
      DriverLogisticsTask task,
      LogisticsDocument document,
      RentalOrder order,
      LogisticsDependencyGateway.ContractorTaskExecution execution) {
    TaskBinding binding() {
      return new TaskBinding(
          task.getExternalTaskId(), task.getId(), document.getId(), order.getId());
    }

    ValidatedTask withExecution(LogisticsDependencyGateway.ContractorTaskExecution refreshed) {
      return new ValidatedTask(task, document, order, refreshed);
    }
  }

  /** Resolved local capability after signature, expiry, revocation and revision checks. */
  private record ResolvedShare(ContractorRouteShare share) {}
}
