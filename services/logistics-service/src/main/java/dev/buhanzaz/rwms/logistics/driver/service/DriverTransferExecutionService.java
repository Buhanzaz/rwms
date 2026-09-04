package dev.buhanzaz.rwms.logistics.driver.service;

import dev.buhanzaz.rwms.logistics.api.LogisticsApiModels.ArriveTransferLineRequest;
import dev.buhanzaz.rwms.logistics.api.LogisticsApiModels.MediaReferenceInput;
import dev.buhanzaz.rwms.logistics.api.LogisticsApiModels.TransferArrivalPreflightView;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyException;
import dev.buhanzaz.rwms.logistics.service.LogisticsDocumentService;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Executes logistics-owned transfer lifecycle commands selected by the durable driver-task store.
 * Task-board remains the execution signal and media evidence owner; this service only derives
 * stable command identities and invokes the existing version-fenced transfer aggregate boundary.
 */
@Service
@RequiredArgsConstructor
class DriverTransferExecutionService {
  private final LogisticsDocumentService documents;

  /** Starts one whole-transfer or cabin-line departure with a retry-stable command identity. */
  void depart(DriverTaskWorkflowStore.TransferDepartureWork work) {
    requireActor(work.actorId());
    UUID idempotencyKey = commandKey("depart", work.taskId(), work.documentId(), work.lineId());
    UUID correlationId = correlationKey(work.taskId(), work.documentId());
    if (work.lineId() == null) {
      documents.departTransfer(
          work.actorId(),
          idempotencyKey,
          correlationId,
          work.documentId(),
          work.expectedDocumentVersion());
      return;
    }
    documents.departTransferLine(
        work.actorId(),
        idempotencyKey,
        correlationId,
        work.documentId(),
        work.lineId(),
        work.expectedDocumentVersion(),
        work.expectedLineVersion());
  }

  /**
   * Records one transfer arrival from the already selected task-board completion evidence. Active
   * repair continuation uses the task's persisted priority only when the owner preflight requires
   * it; ordinary arrivals never fabricate that field.
   */
  void arrive(DriverTaskWorkflowStore.TransferArrivalWork work) {
    requireActor(work.actorId());
    UUID idempotencyKey = commandKey("arrive", work.taskId(), work.documentId(), work.lineId());
    UUID correlationId = correlationKey(work.taskId(), work.documentId());
    if (work.lineId() == null) {
      documents.arriveTransfer(
          work.actorId(),
          idempotencyKey,
          correlationId,
          work.documentId(),
          work.expectedDocumentVersion());
      return;
    }
    TransferArrivalPreflightView preflight =
        documents.transferArrivalPreflight(
            work.documentId(),
            work.lineId(),
            work.expectedDocumentVersion(),
            work.expectedLineVersion());
    if (!work.documentId().equals(preflight.transferId())
        || !work.lineId().equals(preflight.lineId())) {
      throw permanent(
          "Проверка прибытия вернула данные другого межскладского перемещения");
    }
    if (!preflight.missingQueueDefinitionIds().isEmpty()) {
      throw permanent(
          "Нельзя завершить перемещение: для ремонта не настроена обязательная очередь");
    }
    Integer priority = preflight.priorityRequired() ? work.priority() : null;
    documents.arriveTransferLine(
        work.actorId(),
        idempotencyKey,
        correlationId,
        work.documentId(),
        work.lineId(),
        work.expectedDocumentVersion(),
        work.expectedLineVersion(),
        new ArriveTransferLineRequest(
            List.of(new MediaReferenceInput(work.mediaId(), work.mediaGeneration())), priority));
  }

  private static void requireActor(UUID actorId) {
    if (actorId == null) {
      throw permanent("Для выполнения межскладского перемещения не назначен водитель");
    }
  }

  private static UUID commandKey(
      String operation, UUID taskId, UUID documentId, UUID lineId) {
    return nameUuid(
        "driver-task:transfer:"
            + operation
            + ":"
            + taskId
            + ":"
            + documentId
            + ":"
            + (lineId == null ? "document" : lineId));
  }

  private static UUID correlationKey(UUID taskId, UUID documentId) {
    return nameUuid("driver-task:transfer:correlation:" + taskId + ":" + documentId);
  }

  private static UUID nameUuid(String value) {
    return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8));
  }

  private static LogisticsDependencyException permanent(String message) {
    return new LogisticsDependencyException(
        LogisticsDependencyException.FailureKind.PERMANENT_REJECTION, message);
  }
}
