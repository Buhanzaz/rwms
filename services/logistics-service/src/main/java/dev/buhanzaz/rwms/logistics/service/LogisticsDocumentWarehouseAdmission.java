package dev.buhanzaz.rwms.logistics.service;

import dev.buhanzaz.rwms.logistics.domain.LogisticsDocument;
import dev.buhanzaz.rwms.logistics.service.LogisticsWarehouseLifecycle.AdmissionTicket;
import dev.buhanzaz.rwms.logistics.service.LogisticsWarehouseLifecycleStore.AdmissionRequirement;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Applies the warehouse admission protocol around a document command: consume exact admission
 * evidence before mutation, then persist its permanent recovery mark with the document.
 */
@Service
@RequiredArgsConstructor
class LogisticsDocumentWarehouseAdmission {
  private final LogisticsWarehouseLifecycle warehouseLifecycle;
  private final LogisticsWarehouseOperationMarkStore warehouseOperationMarks;

  /** Allows the retained no-ticket facade overloads only inside the explicit test harness. */
  void requireLegacyAdmissionDisabled() {
    warehouseLifecycle.requireTestOnlyBypass();
  }

  /** Creates the test-profile ticket used by retained direct service fixtures. */
  AdmissionTicket testTicket(
      UUID subjectId,
      String operationName,
      UUID idempotencyKey,
      List<AdmissionRequirement> requirements) {
    return warehouseLifecycle.disabledTicket(
        subjectId, operationName, idempotencyKey, requirements);
  }

  /** Validates and consumes the exact ticket inside the owning document transaction. */
  void requireAdmission(
      AdmissionTicket ticket, List<AdmissionRequirement> expectedRequirements) {
    if (ticket == null) {
      throw new IllegalArgumentException("Warehouse admission ticket is required");
    }
    List<AdmissionRequirement> expected =
        expectedRequirements.stream()
            .sorted(Comparator.comparing(AdmissionRequirement::warehouseId))
            .toList();
    if (!expected.equals(ticket.requirements())) {
      throw new LogisticsConflictException(
          "Warehouse admission ticket does not match the logistics command");
    }
    warehouseLifecycle.consume(ticket);
  }

  /** Persists the permanent warehouse operation-mark recovery item with the document commit. */
  void enqueue(LogisticsDocument document, UUID warehouseId, AdmissionTicket ticket) {
    warehouseOperationMarks.enqueue(
        warehouseId,
        document.getId(),
        ticket.occurredAt(),
        ticket.evidenceFor(warehouseId).orElse(null));
  }
}
